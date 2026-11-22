import { ChildProcessWithoutNullStreams, execFileSync, spawn } from "child_process";
import fs from "fs";
import os from "os";
import path from "path";
import { EventHub } from "./events";
import { t } from "./i18n";
import { commandLine, killTree } from "./proc";

/**
 * Drives the Antigravity agent through its Agent Client Protocol server (`agy acp`).
 *
 * ACP is JSON-RPC 2.0 over newline-delimited stdio. The bridge is the ACP client: it opens a
 * session in the active workspace, forwards prompts, streams agent output to the phone, and
 * answers the agent's `session/request_permission` calls with the phone's Approve / Reject.
 */

export type AgentState = "offline" | "starting" | "idle" | "working" | "awaiting_permission" | "error";

interface PermissionOption {
  optionId: string;
  name: string;
  kind: string;
}

interface PendingPermission {
  rpcId: string | number;
  toolCallId: string;
  title: string;
  kind: string;
  detail: string;
  options: PermissionOption[];
}

interface RpcWaiter {
  resolve: (value: any) => void;
  reject: (err: Error) => void;
}

const MAX_TOOL_OUTPUT = 4000;

export function resolveAgentCommand(configured?: string): string | null {
  if (configured && fs.existsSync(configured)) return configured;
  const candidates = [
    path.join(os.homedir(), ".gemini", "bin", "agy.exe"),
    path.join(os.homedir(), ".gemini", "bin", "agy"),
  ];
  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) return candidate;
  }
  try {
    const finder = process.platform === "win32" ? "where" : "which";
    const out = execFileSync(finder, ["agy"], { encoding: "utf-8", stdio: ["ignore", "pipe", "ignore"] });
    const first = out.split(/\r?\n/).find((line) => line.trim().length > 0);
    if (first && fs.existsSync(first.trim())) return first.trim();
  } catch {
    // agy is not on PATH
  }
  return null;
}

export class AgentSession {
  private proc: ChildProcessWithoutNullStreams | null = null;
  private stdoutBuffer = "";
  private nextRpcId = 1;
  private waiters = new Map<string | number, RpcWaiter>();
  private sessionId: string | null = null;
  private sessionCwd: string | null = null;
  private procCwd: string | null = null;
  private starting: Promise<void> | null = null;
  private queue: string[] = [];
  private pendingPermission: PendingPermission | null = null;
  private toolTitles = new Map<string, string>();
  private rejectedTools = new Set<string>();
  private messageStreamId: string | null = null;
  private thoughtStreamId: string | null = null;
  private userStreamId: string | null = null;
  private streamCounter = 0;
  private lastPrompt: string | null = null;

  state: AgentState = "offline";
  title: string | null = null;
  lastError: string | null = null;
  commands: string[] = [];

  constructor(
    private readonly hub: EventHub,
    private readonly getWorkspace: () => string,
    private readonly command: string | null,
    private readonly onStateChange: () => void
  ) {}

  get available(): boolean {
    return this.command !== null;
  }

  get commandPath(): string | null {
    return this.command;
  }

  get queuedPrompts(): number {
    return this.queue.length;
  }

  get permission(): Omit<PendingPermission, "rpcId" | "toolCallId"> | null {
    if (!this.pendingPermission) return null;
    const { title, kind, detail, options } = this.pendingPermission;
    return { title, kind, detail, options };
  }

  /** Queue a prompt; it is sent as soon as the agent is idle. */
  async prompt(text: string): Promise<"sent" | "queued"> {
    const trimmed = text.trim();
    if (!trimmed) throw new Error(t("Message is empty"));
    if (!this.command) {
      throw new Error(t("Antigravity CLI (agy) was not found on the PC. Install Antigravity and run agy once."));
    }
    this.lastPrompt = trimmed;
    this.hub.log("agent", "user", trimmed);
    if (this.state === "working" || this.state === "awaiting_permission" || this.state === "starting") {
      this.queue.push(trimmed);
      this.hub.log("agent", "system", t("Waiting ({0}). It will be sent when the agent finishes.", this.queue.length));
      this.onStateChange();
      return "queued";
    }
    this.queue.push(trimmed);
    void this.drainQueue();
    return "sent";
  }

  approve(): string {
    return this.answerPermission(["allow_once", "allow_always"], "Approved");
  }

  reject(): string {
    return this.answerPermission(["reject_once", "reject_always"], "Rejected");
  }

  stop(): string {
    const dropped = this.queue.length;
    this.queue = [];
    if (this.pendingPermission) {
      this.respond(this.pendingPermission.rpcId, { outcome: { outcome: "cancelled" } });
      this.clearPermission();
    }
    if (this.sessionId && (this.state === "working" || this.state === "awaiting_permission")) {
      this.notify("session/cancel", { sessionId: this.sessionId });
      this.hub.log("agent", "action", dropped > 0 ? t("Stopping (removed {0} waiting messages)", dropped) : t("Stopping"));
      return "Cancelling the current agent turn";
    }
    this.hub.log("agent", "action", t("Nothing is running"));
    return "Agent is not running a turn";
  }

  /** Throw away the current conversation and start a fresh agent session. */
  async restart(): Promise<string> {
    this.queue = [];
    this.shutdown("restart");
    this.hub.log("agent", "action", t("New conversation"));
    if (!this.command) return "Antigravity CLI not found";
    await this.ensureSession().catch(() => undefined);
    return "New agent session started";
  }

  /** Re-send the last prompt in a new session. */
  async retryLast(): Promise<string> {
    const last = this.lastPrompt;
    await this.restart();
    if (last) {
      await this.prompt(last);
      return "Re-sent the last prompt in a new session";
    }
    return "New agent session started";
  }

  /** Called when the active workspace changes: the agent must run in the new folder. */
  async moveTo(workspace: string): Promise<void> {
    if (this.sessionCwd && path.resolve(this.sessionCwd) === path.resolve(workspace)) return;
    if (this.proc) {
      this.shutdown("workspace changed");
      this.hub.log("agent", "system", t("Now working in {0}", path.basename(workspace)));
    }
  }

  shutdown(reason: string): void {
    const proc = this.proc;
    this.proc = null;
    this.sessionId = null;
    this.sessionCwd = null;
    this.procCwd = null;
    this.starting = null;
    this.title = null;
    this.clearPermission();
    this.endStreams();
    for (const waiter of this.waiters.values()) waiter.reject(new Error(`Agent stopped (${reason})`));
    this.waiters.clear();
    if (proc) {
      proc.removeAllListeners("exit");
      killTree(proc.pid);
    }
    this.setState("offline");
  }

  private async drainQueue(): Promise<void> {
    while (this.queue.length > 0) {
      if (this.state === "working" || this.state === "awaiting_permission") return;
      const next = this.queue.shift() as string;
      try {
        await this.ensureSession();
      } catch (err) {
        this.queue = [];
        this.lastError = (err as Error).message;
        this.hub.log("agent", "error", t("Could not start the agent: {0}", this.lastError));
        this.setState("error");
        return;
      }
      this.setState("working");
      this.endStreams();
      try {
        const result = await this.request("session/prompt", {
          sessionId: this.sessionId,
          prompt: [{ type: "text", text: next }],
        });
        this.endStreams();
        const reason = String(result?.stopReason ?? "end_turn");
        if (reason === "cancelled") this.hub.log("agent", "system", t("Stopped"));
        else if (reason !== "end_turn") this.hub.log("agent", "system", t("Turn ended: {0}", reason));
      } catch (err) {
        this.endStreams();
        if (this.proc) this.hub.log("agent", "error", t("The message failed: {0}", (err as Error).message));
      }
      if (this.proc) this.setState("idle");
    }
  }

  private ensureSession(): Promise<void> {
    const workspace = this.getWorkspace();
    if (this.proc && this.sessionId && this.sessionCwd === workspace) return Promise.resolve();
    if (this.starting) return this.starting;
    if (this.proc && this.procCwd !== workspace) this.shutdown("workspace changed");
    this.starting = this.startSession(workspace).finally(() => {
      this.starting = null;
    });
    return this.starting;
  }

  private async startSession(workspace: string): Promise<void> {
    this.setState("starting");
    await this.ensureProcess(workspace);
    let session: any;
    try {
      session = await this.request("session/new", { cwd: workspace, mcpServers: [] });
    } catch (err) {
      const message = (err as Error).message;
      throw new Error(/auth/i.test(message) ? t("{0}. Run agy once on the PC to sign in.", message) : message);
    }
    this.sessionId = String(session?.sessionId ?? "");
    if (!this.sessionId) throw new Error("Agent did not return a session id");
    this.sessionCwd = workspace;
    this.title = null;
    this.lastError = null;
    this.setState("idle");
  }

  /** Starts `agy acp` in the workspace and performs the ACP handshake, reusing a running process. */
  private async ensureProcess(workspace: string): Promise<void> {
    if (this.proc && this.procCwd === workspace) return;
    if (this.proc) this.shutdown("workspace changed");
    if (!this.command) throw new Error("Antigravity CLI (agy) not found");

    const launch = commandLine(this.command, ["acp"]);
    const proc = spawn(launch.file, launch.args, {
      ...launch.options,
      cwd: workspace,
      windowsHide: true,
      env: { ...process.env, NO_COLOR: "1" },
    }) as ChildProcessWithoutNullStreams;
    this.proc = proc;
    this.stdoutBuffer = "";

    proc.stdout.setEncoding("utf-8");
    proc.stdout.on("data", (chunk: string) => this.onStdout(chunk));
    proc.stderr.setEncoding("utf-8");
    proc.stderr.on("data", (chunk: string) => {
      const text = chunk.trim();
      if (text) console.error(`[agy] ${text}`);
    });
    proc.on("error", (err) => {
      if (this.proc !== proc) return;
      this.lastError = err.message;
      this.hub.log("agent", "error", t("The agent stopped unexpectedly ({0})", err.message));
      this.shutdown("process error");
      this.setState("error");
    });
    proc.on("exit", (code) => {
      if (this.proc !== proc) return;
      this.hub.log("agent", "error", t("The agent stopped unexpectedly ({0})", code ?? "?"));
      this.shutdown("process exited");
    });

    await this.request("initialize", {
      protocolVersion: 1,
      clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false },
      clientInfo: { name: "antigravity-remote-bridge", version: "1.3.1" },
    });
    this.procCwd = workspace;
  }

  private onStdout(chunk: string): void {
    this.stdoutBuffer += chunk;
    let newline: number;
    while ((newline = this.stdoutBuffer.indexOf("\n")) >= 0) {
      const line = this.stdoutBuffer.slice(0, newline).trim();
      this.stdoutBuffer = this.stdoutBuffer.slice(newline + 1);
      if (!line) continue;
      let message: any;
      try {
        message = JSON.parse(line);
      } catch {
        console.error(`[agy] non-JSON output: ${line.slice(0, 200)}`);
        continue;
      }
      this.onRpc(message);
    }
  }

  private onRpc(message: any): void {
    const hasId = message.id !== undefined && message.id !== null;
    if (hasId && !message.method) {
      const waiter = this.waiters.get(message.id);
      if (!waiter) return;
      this.waiters.delete(message.id);
      if (message.error) waiter.reject(new Error(String(message.error.message ?? "Agent error")));
      else waiter.resolve(message.result);
      return;
    }
    if (message.method === "session/update") {
      this.onSessionUpdate(message.params?.update ?? {});
      return;
    }
    if (message.method === "session/request_permission" && hasId) {
      this.onPermissionRequest(message.id, message.params ?? {});
      return;
    }
    if (hasId) {
      this.send({ jsonrpc: "2.0", id: message.id, error: { code: -32601, message: `Method not supported: ${message.method}` } });
    }
  }

  private onSessionUpdate(update: any): void {
    switch (update.sessionUpdate) {
      case "agent_message_chunk": {
        const text = contentText(update.content);
        if (!text) return;
        this.thoughtStreamId = null;
        this.userStreamId = null;
        if (!this.messageStreamId) this.messageStreamId = this.newStreamId("msg");
        this.hub.log("agent", "agent", text, this.messageStreamId);
        return;
      }
      case "user_message_chunk": {
        const text = contentText(update.content);
        if (!text) return;
        this.messageStreamId = null;
        this.thoughtStreamId = null;
        if (!this.userStreamId) this.userStreamId = this.newStreamId("user");
        this.hub.log("agent", "user", text, this.userStreamId);
        return;
      }
      case "agent_thought_chunk": {
        const text = contentText(update.content);
        if (!text) return;
        this.messageStreamId = null;
        if (!this.thoughtStreamId) this.thoughtStreamId = this.newStreamId("thought");
        this.hub.log("agent", "thought", text, this.thoughtStreamId);
        return;
      }
      case "tool_call": {
        this.endStreams();
        const id = String(update.toolCallId ?? "");
        const title = String(update.title ?? update.name ?? "tool");
        if (id) this.toolTitles.set(id, title);
        this.hub.log("agent", "tool", `> ${kindLabel(update.kind)} ${shorten(title)}`);
        return;
      }
      case "tool_call_update": {
        const status = String(update.status ?? "");
        if (status !== "completed" && status !== "failed") return;
        this.endStreams();
        const id = String(update.toolCallId ?? "");
        const title = this.toolTitles.get(id) ?? String(update.title ?? "tool");
        this.toolTitles.delete(id);
        if (this.rejectedTools.delete(id)) return;
        this.hub.log("agent", status === "failed" ? "error" : "tool", `${status === "failed" ? "failed" : "ok"} ${shorten(title)}`);
        if (update.kind === "execute") {
          const output = commandOutput(update.rawOutput);
          if (output) this.hub.log("agent", "output", output);
        }
        return;
      }
      case "plan": {
        const entries: any[] = Array.isArray(update.entries) ? update.entries : [];
        if (entries.length === 0) return;
        this.endStreams();
        const lines = entries.map((entry) => {
          const mark = entry.status === "completed" ? "[x]" : entry.status === "in_progress" ? "[>]" : "[ ]";
          return `${mark} ${entry.content ?? ""}`;
        });
        this.hub.log("agent", "system", `${t("Plan")}\n${lines.join("\n")}`);
        return;
      }
      case "session_info_update": {
        if (typeof update.title === "string" && update.title.trim()) {
          this.title = update.title.trim();
          this.onStateChange();
        }
        return;
      }
      case "available_commands_update": {
        const list: any[] = Array.isArray(update.availableCommands) ? update.availableCommands : [];
        this.commands = list.map((cmd) => String(cmd.name ?? "")).filter(Boolean);
        this.onStateChange();
        return;
      }
      default:
        return;
    }
  }

  private onPermissionRequest(rpcId: string | number, params: any): void {
    this.endStreams();
    const toolCall = params.toolCall ?? {};
    const rawInput = toolCall.rawInput ?? toolCall.args ?? {};
    const detail =
      typeof rawInput.CommandLine === "string"
        ? rawInput.CommandLine
        : typeof rawInput.Path === "string"
          ? rawInput.Path
          : JSON.stringify(rawInput).slice(0, 500);
    const options: PermissionOption[] = (Array.isArray(params.options) ? params.options : []).map((opt: any) => ({
      optionId: String(opt.optionId),
      name: String(opt.name ?? opt.optionId),
      kind: String(opt.kind ?? ""),
    }));
    this.pendingPermission = {
      rpcId,
      toolCallId: String(toolCall.toolCallId ?? ""),
      title: String(toolCall.title ?? toolCall.name ?? "Agent action"),
      kind: String(toolCall.kind ?? "other"),
      detail,
      options,
    };
    this.hub.log("agent", "action", t("Approval needed: {0}", shorten(this.pendingPermission.title)));
    this.hub.emit({ type: "permission", channel: "agent", data: this.permission as unknown as Record<string, unknown> });
    this.setState("awaiting_permission");
  }

  private answerPermission(preferredKinds: string[], verb: string): string {
    const pending = this.pendingPermission;
    if (!pending) {
      this.hub.log("agent", "action", t("Nothing is waiting for approval"));
      return "Nothing is waiting for approval";
    }
    const option =
      preferredKinds.map((kind) => pending.options.find((opt) => opt.kind === kind)).find(Boolean) ??
      (verb === "Approved" ? pending.options[0] : pending.options[pending.options.length - 1]);
    if (!option) {
      this.respond(pending.rpcId, { outcome: { outcome: "cancelled" } });
    } else {
      this.respond(pending.rpcId, { outcome: { outcome: "selected", optionId: option.optionId } });
    }
    this.clearPermission();
    if (verb === "Rejected" && pending.toolCallId) this.rejectedTools.add(pending.toolCallId);
    this.hub.log("agent", "action", verb === "Approved" ? t("Approved: {0}", shorten(pending.title)) : t("Rejected: {0}", shorten(pending.title)));
    this.setState("working");
    return `${verb} ${shorten(pending.title)}`;
  }

  private clearPermission(): void {
    if (!this.pendingPermission) return;
    this.pendingPermission = null;
    this.hub.emit({ type: "permission_resolved", channel: "agent" });
  }

  private endStreams(): void {
    this.messageStreamId = null;
    this.thoughtStreamId = null;
    this.userStreamId = null;
  }

  private newStreamId(prefix: string): string {
    this.streamCounter += 1;
    return `${prefix}-${Date.now().toString(36)}-${this.streamCounter}`;
  }

  private setState(state: AgentState): void {
    if (this.state === state) return;
    this.state = state;
    this.onStateChange();
  }

  private request(method: string, params: unknown): Promise<any> {
    const id = this.nextRpcId++;
    return new Promise((resolve, reject) => {
      if (!this.proc) {
        reject(new Error("Agent is not running"));
        return;
      }
      this.waiters.set(id, { resolve, reject });
      this.send({ jsonrpc: "2.0", id, method, params });
    });
  }

  private notify(method: string, params: unknown): void {
    this.send({ jsonrpc: "2.0", method, params });
  }

  private respond(id: string | number, result: unknown): void {
    this.send({ jsonrpc: "2.0", id, result });
  }

  private send(message: unknown): void {
    if (!this.proc || !this.proc.stdin.writable) return;
    this.proc.stdin.write(JSON.stringify(message) + "\n");
  }
}

function shorten(text: string, max = 72): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > max ? `${flat.slice(0, max - 3)}...` : flat;
}

function contentText(content: any): string {
  if (!content) return "";
  if (typeof content.text === "string") return content.text;
  if (Array.isArray(content)) return content.map(contentText).join("");
  return "";
}

function kindLabel(kind: unknown): string {
  switch (kind) {
    case "execute":
      return "run";
    case "edit":
      return "edit";
    case "read":
      return "read";
    case "search":
      return "search";
    case "delete":
      return "delete";
    case "fetch":
      return "fetch";
    default:
      return "tool";
  }
}

function commandOutput(rawOutput: any): string {
  const text = typeof rawOutput?.output === "string" ? rawOutput.output : "";
  if (!text) return "";
  const marker = text.indexOf("Command output:");
  if (marker < 0) return "";
  const body = text.slice(marker + "Command output:".length).replace(/\r/g, "").trim();
  if (!body) return "";
  return body.length > MAX_TOOL_OUTPUT ? `${body.slice(0, MAX_TOOL_OUTPUT)}\n... (truncated)` : body;
}

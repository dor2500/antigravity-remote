import { spawn } from "child_process";
import fs from "fs";
import os from "os";
import path from "path";
import { AgentState } from "./agent";
import { t } from "./i18n";
import { EventHub } from "./events";
import { commandLine } from "./proc";

/**
 * Talks to the local Antigravity hub (`agy --hub`), the process that runs the agent for
 * Antigravity IDE. Through it the phone can list the IDE's conversations, follow one live,
 * send messages into it, stop it and answer its approval requests.
 *
 * The hub speaks the Connect protocol (JSON over HTTP) on localhost and requires the CSRF
 * token it writes to ~/.gemini/antigravity/daemon/hub_<port>.json. This interface is not a
 * documented public API, so every call is defensive and failures are reported, not thrown away.
 */

const SERVICE = "exa.language_server_pb.LanguageServerService";
const POLL_BUSY_MS = 1000;
const POLL_IDLE_MS = 3000;
const MAX_OUTPUT = 4000;

interface HubInfo {
  pid: number;
  httpPort: number;
  csrfToken: string;
}

export interface IdeConversation {
  id: string;
  title: string;
  status: string;
  updatedAt: string;
  steps: number;
}

interface WaitingStep {
  index: number;
  type: string;
  step: any;
  title: string;
  kind: string;
  detail: string;
}

function daemonDir(): string {
  return path.join(os.homedir(), ".gemini", "antigravity", "daemon");
}

function pidAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (err) {
    return (err as NodeJS.ErrnoException).code === "EPERM";
  }
}

/** The hub file of the running hub process, newest first. */
export function findHub(): HubInfo | null {
  let files: string[];
  try {
    files = fs.readdirSync(daemonDir()).filter((f) => /^hub_\d+\.json$/.test(f));
  } catch {
    return null;
  }
  const candidates = files
    .map((f) => {
      const full = path.join(daemonDir(), f);
      try {
        return { info: JSON.parse(fs.readFileSync(full, "utf-8")) as HubInfo, mtime: fs.statSync(full).mtimeMs };
      } catch {
        return null;
      }
    })
    .filter((c): c is { info: HubInfo; mtime: number } => c !== null && !!c.info.httpPort && !!c.info.csrfToken)
    .sort((a, b) => b.mtime - a.mtime);
  return candidates.find((c) => pidAlive(c.info.pid))?.info ?? null;
}

export class IdeHubSession {
  private hubInfo: HubInfo | null = null;
  private activeId: string | null = null;
  private trajectoryId: string | null = null;
  private emitted = new Map<string, string>();
  private pollTimer: NodeJS.Timeout | null = null;
  private polling = false;
  private waiting: WaitingStep | null = null;
  private runStatus = "";

  state: AgentState = "offline";
  title: string | null = null;
  lastError: string | null = null;

  constructor(
    private readonly hub: EventHub,
    private readonly agentCommand: string | null,
    private readonly onStateChange: () => void
  ) {}

  /** True when a running hub was found. Rechecked on every call. */
  get available(): boolean {
    return this.connect() !== null;
  }

  get active(): boolean {
    return this.activeId !== null;
  }

  get conversationId(): string | null {
    return this.activeId;
  }

  get permission(): { title: string; kind: string; detail: string } | null {
    if (!this.waiting) return null;
    return { title: this.waiting.title, kind: this.waiting.kind, detail: this.waiting.detail };
  }

  async list(): Promise<IdeConversation[]> {
    const result = await this.call("GetAllCascadeTrajectories", {});
    const summaries: Record<string, any> = result?.trajectorySummaries ?? {};
    return Object.entries(summaries)
      .map(([id, s]) => ({
        id,
        title: String(s?.annotations?.title ?? s?.summary ?? "Untitled conversation"),
        status: String(s?.status ?? "").replace("CASCADE_RUN_STATUS_", "").toLowerCase(),
        updatedAt: String(s?.lastModifiedTime ?? ""),
        steps: Number(s?.stepCount ?? 0),
      }))
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  }

  /** Follow an IDE conversation: replay its history, then stream new steps. */
  async open(id: string): Promise<string> {
    if (!id) throw new Error("Conversation id is required");
    const conversations = await this.list();
    const match = conversations.find((c) => c.id === id);
    if (!match) throw new Error(t("That conversation was not found in Antigravity"));
    this.close();
    this.activeId = id;
    this.title = match.title;
    this.lastError = null;
    this.hub.emit({ type: "clear", channel: "agent" });
    this.hub.log("agent", "system", t("Following the IDE conversation: {0}", match.title));
    await this.poll(true);
    this.schedulePoll();
    return match.title;
  }

  /** Start a new conversation in the IDE with this prompt, then follow it. */
  async create(prompt: string): Promise<string> {
    const hubInfo = this.connect();
    if (!hubInfo) throw new Error(t("Antigravity IDE is not running on the PC"));
    if (!this.agentCommand) throw new Error(t("Antigravity CLI (agy) was not found on the PC. Install Antigravity and run agy once."));
    const output = await runAgentApi(this.agentCommand, hubInfo, ["new-conversation", prompt]);
    let id = "";
    try {
      id = String(JSON.parse(output)?.response?.newConversation?.conversationId ?? "");
    } catch {
      id = "";
    }
    if (!id) throw new Error(`${t("Antigravity did not create the conversation")}: ${output.slice(0, 200)}`);
    return this.open(id);
  }

  close(): void {
    if (this.pollTimer) clearTimeout(this.pollTimer);
    this.pollTimer = null;
    const wasActive = this.activeId !== null;
    this.activeId = null;
    this.trajectoryId = null;
    this.emitted.clear();
    this.title = null;
    this.setWaiting(null);
    this.state = "offline";
    if (wasActive) this.onStateChange();
  }

  async prompt(text: string): Promise<"sent"> {
    const id = this.requireActive();
    await this.call("SendUserCascadeMessage", { cascadeId: id, items: [{ text }] });
    this.setState("working");
    this.schedulePoll(200);
    return "sent";
  }

  async stop(): Promise<string> {
    const id = this.requireActive();
    await this.call("CancelCascadeInvocation", { cascadeId: id });
    this.hub.log("agent", "action", t("Stopping"));
    this.schedulePoll(300);
    return "Cancelling the current IDE turn";
  }

  approve(): Promise<string> {
    return this.answer(true);
  }

  reject(): Promise<string> {
    return this.answer(false);
  }

  private async answer(allow: boolean): Promise<string> {
    const id = this.requireActive();
    const waiting = this.waiting;
    const verb = allow ? "Approved" : "Rejected";
    if (!waiting) {
      this.hub.log("agent", "action", t("Nothing is waiting for approval"));
      return "Nothing is waiting for approval";
    }
    const interaction: Record<string, unknown> = { trajectoryId: this.trajectoryId ?? "", stepIndex: waiting.index };
    const step = waiting.step;
    if (step.runCommand) {
      const line = String(step.runCommand.proposedCommandLine ?? step.runCommand.commandLine ?? "");
      interaction.runCommand = { confirm: allow, proposedCommandLine: line, submittedCommandLine: line };
    } else if (step.codeAction || step.filePermission) {
      interaction.filePermission = { allow, absolutePathUri: stepPathUri(step) };
    } else {
      interaction.permission = { allow };
    }
    await this.call("HandleCascadeUserInteraction", { conversationId: id, interaction });
    this.hub.log("agent", "action", allow ? t("Approved: {0}", shorten(waiting.title)) : t("Rejected: {0}", shorten(waiting.title)));
    this.setWaiting(null);
    this.setState("working");
    this.schedulePoll(300);
    return `${verb} ${shorten(waiting.title)}`;
  }

  private requireActive(): string {
    if (!this.activeId) throw new Error(t("No IDE conversation is open"));
    return this.activeId;
  }

  private schedulePoll(delay?: number): void {
    if (!this.activeId) return;
    if (this.pollTimer) clearTimeout(this.pollTimer);
    const wait = delay ?? (this.state === "working" || this.state === "awaiting_permission" ? POLL_BUSY_MS : POLL_IDLE_MS);
    this.pollTimer = setTimeout(() => {
      void this.poll(false).finally(() => this.schedulePoll());
    }, wait);
  }

  private async poll(initial: boolean): Promise<void> {
    const id = this.activeId;
    if (!id || this.polling) return;
    this.polling = true;
    try {
      const [stepsResult, summaries] = await Promise.all([
        this.call("GetCascadeTrajectorySteps", { cascadeId: id }),
        this.call("GetAllCascadeTrajectories", {}),
      ]);
      if (this.activeId !== id) return;
      const summary = summaries?.trajectorySummaries?.[id];
      if (summary?.trajectoryId) this.trajectoryId = String(summary.trajectoryId);
      if (summary?.annotations?.title && summary.annotations.title !== this.title) {
        this.title = String(summary.annotations.title);
        this.onStateChange();
      }
      this.runStatus = String(summary?.status ?? "");
      const steps: any[] = Array.isArray(stepsResult?.steps) ? stepsResult.steps : [];
      steps.forEach((step, index) => this.renderStep(id, index, step));

      let waiting: WaitingStep | null = null;
      steps.forEach((step, index) => {
        if (step?.status === "CORTEX_STEP_STATUS_WAITING") waiting = describeWaiting(index, step);
      });
      this.setWaiting(waiting);
      const busy = /RUNNING|BUSY|CANCELING/.test(this.runStatus);
      this.setState(waiting ? "awaiting_permission" : busy ? "working" : "idle");
      this.lastError = null;
    } catch (err) {
      const message = (err as Error).message;
      if (message !== this.lastError) this.hub.log("agent", "error", t("Lost contact with the IDE: {0}", message));
      this.lastError = message;
      this.setState("error");
    } finally {
      this.polling = false;
    }
  }

  /** Emits the parts of a step the phone has not seen yet. Growing text is sent as a delta. */
  private renderStep(id: string, index: number, step: any): void {
    const parts = stepParts(step);
    parts.forEach((part, n) => {
      const key = `${index}:${n}`;
      const previous = this.emitted.get(key);
      if (previous === part.text) return;
      const streamId = `ide-${id.slice(0, 8)}-${index}-${n}`;
      if (previous !== undefined && part.stream && part.text.startsWith(previous)) {
        this.hub.log("agent", part.level, part.text.slice(previous.length), streamId);
      } else {
        this.hub.log("agent", part.level, part.text, part.stream && previous === undefined ? streamId : undefined);
      }
      this.emitted.set(key, part.text);
    });
  }

  private setWaiting(waiting: WaitingStep | null): void {
    const before = this.waiting ? `${this.waiting.index}` : "";
    const after = waiting ? `${waiting.index}` : "";
    this.waiting = waiting;
    if (before === after) return;
    if (waiting) {
      this.hub.log("agent", "action", t("Approval needed: {0}", shorten(waiting.title)));
      this.hub.emit({ type: "permission", channel: "agent", data: this.permission as unknown as Record<string, unknown> });
    } else {
      this.hub.emit({ type: "permission_resolved", channel: "agent" });
    }
  }

  private setState(state: AgentState): void {
    if (this.state === state) return;
    this.state = state;
    this.onStateChange();
  }

  private connect(): HubInfo | null {
    if (this.hubInfo && pidAlive(this.hubInfo.pid)) return this.hubInfo;
    this.hubInfo = findHub();
    return this.hubInfo;
  }

  private async call(method: string, body: unknown): Promise<any> {
    const info = this.connect();
    if (!info) throw new Error(t("Antigravity IDE is not running on the PC"));
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20000);
    try {
      const res = await fetch(`http://127.0.0.1:${info.httpPort}/${SERVICE}/${method}`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Connect-Protocol-Version": "1",
          "x-antigravity-csrf-token": info.csrfToken,
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      const text = await res.text();
      if (!res.ok) {
        let message = text;
        try {
          message = String(JSON.parse(text)?.message ?? text);
        } catch {
          // keep raw text
        }
        throw new Error(`${method}: ${message.slice(0, 200)}`);
      }
      return text ? JSON.parse(text) : {};
    } catch (err) {
      if ((err as Error).name === "AbortError") throw new Error(`${method}: the IDE did not answer in time`);
      if (/fetch failed|ECONNREFUSED/i.test((err as Error).message)) this.hubInfo = null;
      throw err;
    } finally {
      clearTimeout(timer);
    }
  }
}

type Part = { level: "user" | "agent" | "thought" | "tool" | "output" | "error" | "system"; text: string; stream?: boolean };

/** Turns one hub step into the log entries the phone shows. */
function stepParts(step: any): Part[] {
  const type = String(step?.type ?? "").replace("CORTEX_STEP_TYPE_", "");
  const status = String(step?.status ?? "");
  const done = status === "CORTEX_STEP_STATUS_DONE";
  const failed = status === "CORTEX_STEP_STATUS_ERROR";
  const canceled = status === "CORTEX_STEP_STATUS_CANCELED" || status === "CORTEX_STEP_STATUS_INTERRUPTED";
  switch (type) {
    case "USER_INPUT": {
      const input = step.userInput ?? {};
      const text =
        typeof input.userResponse === "string" && input.userResponse
          ? input.userResponse
          : (Array.isArray(input.items) ? input.items : []).map((i: any) => i?.text ?? "").join("");
      return text ? [{ level: "user", text }] : [];
    }
    case "PLANNER_RESPONSE": {
      const r = step.plannerResponse ?? {};
      const parts: Part[] = [];
      if (typeof r.thinking === "string" && r.thinking.trim()) parts.push({ level: "thought", text: r.thinking, stream: true });
      const reply = typeof r.modifiedResponse === "string" && r.modifiedResponse ? r.modifiedResponse : r.response;
      if (typeof reply === "string" && reply.trim()) parts.push({ level: "agent", text: reply, stream: true });
      return parts;
    }
    case "RUN_COMMAND": {
      const rc = step.runCommand ?? {};
      const line = String(rc.commandLine ?? rc.proposedCommandLine ?? "");
      const parts: Part[] = [{ level: "tool", text: `> run ${shorten(line)}` }];
      if (canceled) {
        parts.push({ level: "error", text: `canceled ${shorten(line, 48)}` });
      } else if (done || failed) {
        const output = commandOutput(String(rc.combinedOutput?.full ?? ""));
        if (output) parts.push({ level: "output", text: output });
        const code = rc.exitCode ?? 0;
        parts.push({ level: code === 0 ? "tool" : "error", text: `${code === 0 ? "ok" : `exit ${code}`} ${shorten(line, 48)}` });
      }
      return parts;
    }
    case "CODE_ACTION": {
      const uri = stepPathUri(step);
      return [{ level: failed ? "error" : "tool", text: `> edit ${uri ? path.basename(decodeURIComponent(uri)) : "file"}` }];
    }
    case "SYSTEM_MESSAGE":
    case "CHECKPOINT":
    case "EPHEMERAL_MESSAGE":
      return [];
    case "ERROR_MESSAGE":
      return [{ level: "error", text: shorten(JSON.stringify(step.errorMessage ?? step), 300) }];
    default: {
      if (step?.systemMessage?.renderInfo?.hidden) return [];
      const tool = step?.metadata?.toolCall;
      let label = "";
      if (tool?.name) {
        let summary = "";
        try {
          summary = String(JSON.parse(tool.argumentsJson ?? "{}")?.toolSummary ?? "");
        } catch {
          summary = "";
        }
        label = `${String(tool.name).replace(/_/g, " ")}${summary ? `: ${summary}` : ""}`;
      } else if (type && type !== "GENERIC" && type !== "UNSPECIFIED") {
        label = type.toLowerCase().replace(/_/g, " ");
      }
      if (!label) return [];
      return [{ level: failed ? "error" : "tool", text: `> ${shorten(label)}${canceled ? " (canceled)" : ""}` }];
    }
  }
}

function describeWaiting(index: number, step: any): WaitingStep {
  const type = String(step?.type ?? "").replace("CORTEX_STEP_TYPE_", "");
  if (step.runCommand) {
    const line = String(step.runCommand.proposedCommandLine ?? step.runCommand.commandLine ?? "");
    return { index, type, step, title: line, kind: "execute", detail: line };
  }
  const uri = stepPathUri(step);
  if (uri) {
    const file = decodeURIComponent(uri.replace(/^file:\/\/\//, ""));
    return { index, type, step, title: `Edit ${path.basename(file)}`, kind: "edit", detail: file };
  }
  const label = type.toLowerCase().replace(/_/g, " ");
  return { index, type, step, title: label, kind: "other", detail: label };
}

function stepPathUri(step: any): string {
  const spec = step?.codeAction?.actionSpec ?? {};
  for (const value of Object.values(spec) as any[]) {
    const uri = value?.path?.absoluteUri ?? value?.absoluteUri;
    if (typeof uri === "string") return uri;
  }
  const fp = step?.filePermission?.absolutePathUri;
  return typeof fp === "string" ? fp : "";
}

function commandOutput(full: string): string {
  const marker = full.indexOf("Command output:");
  if (marker < 0) return "";
  const body = full.slice(marker + "Command output:".length).replace(/\r/g, "").trim();
  return body.length > MAX_OUTPUT ? `${body.slice(0, MAX_OUTPUT)}\n... (truncated)` : body;
}

function shorten(text: string, max = 72): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > max ? `${flat.slice(0, max - 3)}...` : flat;
}

function runAgentApi(command: string, info: HubInfo, args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    const launch = commandLine(command, ["agentapi", ...args]);
    const child = spawn(launch.file, launch.args, {
      ...launch.options,
      windowsHide: true,
      env: {
        ...process.env,
        ANTIGRAVITY_LS_ADDRESS: `127.0.0.1:${info.httpPort}`,
        ANTIGRAVITY_CSRF_TOKEN: info.csrfToken,
      },
    });
    let out = "";
    child.stdout?.on("data", (d: Buffer) => (out += d.toString("utf-8")));
    child.stderr?.on("data", (d: Buffer) => (out += d.toString("utf-8")));
    child.on("error", reject);
    child.on("close", () => resolve(out.trim()));
  });
}

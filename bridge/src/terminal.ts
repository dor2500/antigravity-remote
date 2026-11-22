import { ChildProcessWithoutNullStreams, spawn } from "child_process";
import fs from "fs";
import path from "path";
import { StringDecoder } from "string_decoder";
import { killTree } from "./proc";
import { EventHub, LogLevel } from "./events";

const CD_PATTERN = /^\s*(cd|chdir|set-location|sl)(?:\s+(.+?))?\s*;?\s*$/i;
const FLUSH_DELAY_MS = 120;

/**
 * A lightweight terminal bound to the active workspace. Each command runs in its own
 * PowerShell process; the working directory is tracked across commands so `cd` behaves as
 * expected, and text typed on the phone can be fed to a running command's stdin.
 */
export class TerminalSession {
  private proc: ChildProcessWithoutNullStreams | null = null;
  private cwd: string;
  private commandCounter = 0;
  command: string | null = null;

  constructor(
    private readonly hub: EventHub,
    private readonly shell: string,
    workspace: string,
    private readonly onStateChange: () => void
  ) {
    this.cwd = workspace;
  }

  get running(): boolean {
    return this.proc !== null;
  }

  get workingDirectory(): string {
    return this.cwd;
  }

  resetTo(workspace: string): void {
    this.cwd = workspace;
    this.hub.log("terminal", "system", `cwd: ${workspace}`);
    this.onStateChange();
  }

  run(command: string): void {
    const trimmed = command.trim();
    if (!trimmed) throw new Error("Command is empty");
    if (this.proc) throw new Error(`"${this.command}" is still running. Stop it or send input to it first.`);

    this.hub.log("terminal", "user", `PS ${this.cwd}> ${trimmed}`);

    const cd = CD_PATTERN.exec(trimmed);
    if (cd) {
      this.changeDirectory(cd[2]);
      return;
    }

    this.commandCounter += 1;
    const streamBase = `term-${Date.now().toString(36)}-${this.commandCounter}`;
    // The command travels in an environment variable so PowerShell error positions refer to
    // the user's text rather than to this UTF-8 setup line.
    const script =
      "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $OutputEncoding=[System.Text.Encoding]::UTF8; " +
      "$ProgressPreference='SilentlyContinue'; Invoke-Expression $env:AGR_COMMAND";
    const proc = spawn(this.shell, ["-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script], {
      cwd: this.cwd,
      windowsHide: true,
      env: { ...process.env, AGR_COMMAND: trimmed, FORCE_COLOR: "0", NO_COLOR: "1", TERM: "dumb" },
    });
    this.proc = proc;
    this.command = trimmed;
    this.onStateChange();

    const out = new LineStream(this.hub, "output", `${streamBase}-out`);
    const err = new LineStream(this.hub, "error", `${streamBase}-err`);
    proc.stdout.on("data", (chunk: Buffer) => out.write(chunk));
    proc.stderr.on("data", (chunk: Buffer) => err.write(chunk));

    proc.on("error", (error) => {
      if (this.proc !== proc) return;
      out.end();
      err.end();
      this.finish(`Failed to start ${this.shell}: ${error.message}`, "error");
    });
    proc.on("close", (code) => {
      if (this.proc !== proc) return;
      out.end();
      err.end();
      const exit = code ?? -1;
      this.finish(`exit ${exit}`, exit === 0 ? "system" : "error");
    });
  }

  sendInput(text: string): void {
    if (!this.proc || !this.proc.stdin.writable) throw new Error("No command is running");
    this.proc.stdin.write(text.endsWith("\n") ? text : `${text}\n`);
    this.hub.log("terminal", "user", `< ${text}`);
  }

  stop(): string {
    if (!this.proc) {
      this.hub.log("terminal", "action", "Nothing is running");
      return "No command is running";
    }
    const { pid } = this.proc;
    const command = this.command;
    killTree(pid);
    this.proc = null;
    this.command = null;
    this.hub.log("terminal", "action", `Stopped: ${command}`);
    this.onStateChange();
    return `Stopped ${command}`;
  }

  private changeDirectory(rawTarget: string | undefined): void {
    if (!rawTarget) {
      this.hub.log("terminal", "output", this.cwd);
      return;
    }
    const target = rawTarget.replace(/^['"]|['"]$/g, "").replace(/^~(?=$|[\\/])/, process.env.USERPROFILE ?? "~");
    const resolved = path.resolve(this.cwd, target);
    if (!fs.existsSync(resolved) || !fs.statSync(resolved).isDirectory()) {
      this.hub.log("terminal", "error", `cd: no such directory: ${resolved}`);
      return;
    }
    this.cwd = resolved;
    this.hub.log("terminal", "system", `cwd: ${resolved}`);
    this.onStateChange();
  }

  private finish(message: string, level: LogLevel): void {
    this.proc = null;
    this.command = null;
    this.hub.log("terminal", level, message);
    this.onStateChange();
  }
}

/** Splits a byte stream into complete lines, flushing a trailing partial line after a short pause. */
class LineStream {
  private decoder = new StringDecoder("utf8");
  private pending = "";
  private timer: NodeJS.Timeout | null = null;
  private lineCounter = 0;

  constructor(
    private readonly hub: EventHub,
    private readonly level: LogLevel,
    private readonly streamBase: string
  ) {}

  write(chunk: Buffer): void {
    this.pending += this.decoder.write(chunk);
    this.flushLines();
    this.schedulePartialFlush();
  }

  end(): void {
    this.pending += this.decoder.end();
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.flushLines();
    if (this.pending) this.emitPartial();
    this.pending = "";
  }

  private flushLines(): void {
    let newline: number;
    while ((newline = this.pending.indexOf("\n")) >= 0) {
      const line = this.pending.slice(0, newline).replace(/\r$/, "");
      this.pending = this.pending.slice(newline + 1);
      this.emit(line);
      this.lineCounter += 1;
    }
  }

  private schedulePartialFlush(): void {
    if (this.timer) clearTimeout(this.timer);
    if (!this.pending) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      if (this.pending) this.emitPartial();
    }, FLUSH_DELAY_MS);
  }

  /** Prompts such as "Continue? [y/n]" have no newline; show them without waiting. */
  private emitPartial(): void {
    const text = this.pending.replace(/\r/g, "");
    this.pending = "";
    this.hub.log("terminal", this.level, text, this.currentStreamId());
  }

  /** A line continuing an already-shown partial shares its streamId, so the phone merges them. */
  private emit(line: string): void {
    this.hub.log("terminal", this.level, line, this.currentStreamId());
  }

  private currentStreamId(): string {
    return `${this.streamBase}-${this.lineCounter}`;
  }
}

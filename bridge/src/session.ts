import { execSync, spawn, ChildProcess } from "child_process";
import fs from "fs";
import os from "os";
import path from "path";

export type LogLevel = "system" | "agent" | "terminal" | "error" | "user" | "action";

export interface StreamEvent {
  type: "log" | "status" | "workspace_changed" | "clear" | "terminal_exit";
  level?: LogLevel;
  message?: string;
  timestamp: string;
  data?: Record<string, unknown>;
}

export class SessionManager {
  private activeProcess: ChildProcess | null = null;
  private activeCommand: string | null = null;
  private lastPrompt: string | null = null;
  private logBuffer: StreamEvent[] = [];
  private readonly maxBufferSize = 500;
  private listeners: Set<(event: StreamEvent) => void> = new Set();
  private agyCliPath: string | null = null;

  constructor(private getWorkspace: () => string) {
    this.agyCliPath = this.detectAgyCli();
  }

  private detectAgyCli(): string | null {
    const candidates = [
      path.join(os.homedir(), "AppData", "Local", "Programs", "antigravity", "bin", "agy.cmd"),
      path.join(os.homedir(), "AppData", "Local", "Programs", "Antigravity", "bin", "agy.cmd"),
    ];

    for (const c of candidates) {
      if (fs.existsSync(c)) return c;
    }

    try {
      const out = execSync("where agy", { encoding: "utf-8", stdio: ["pipe", "pipe", "ignore"] }).trim();
      const firstLine = out.split(/\r?\n/)[0];
      if (firstLine && fs.existsSync(firstLine)) return firstLine;
    } catch {
      // agy CLI not on PATH
    }
    return null;
  }

  public isAntigravityRunning(): boolean {
    try {
      const out = execSync('tasklist /FI "IMAGENAME eq Antigravity.exe" /NH', {
        encoding: "utf-8",
        stdio: ["pipe", "pipe", "ignore"],
      });
      return out.toLowerCase().includes("antigravity.exe");
    } catch {
      return false;
    }
  }

  public getAgyCliPath(): string | null {
    return this.agyCliPath;
  }

  public isRunning(): boolean {
    return this.activeProcess !== null;
  }

  public getActiveCommand(): string | null {
    return this.activeCommand;
  }

  public getLogBuffer(): StreamEvent[] {
    return this.logBuffer;
  }

  public subscribe(listener: (event: StreamEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  public emit(event: Omit<StreamEvent, "timestamp">): void {
    const fullEvent: StreamEvent = {
      ...event,
      timestamp: new Date().toISOString(),
    };
    if (event.type === "log") {
      this.logBuffer.push(fullEvent);
      if (this.logBuffer.length > this.maxBufferSize) {
        this.logBuffer.shift();
      }
    }
    for (const listener of this.listeners) {
      try {
        listener(fullEvent);
      } catch {
        // Ignore broken listener
      }
    }
  }

  public clearLogs(): void {
    this.logBuffer = [];
    this.emit({ type: "clear", message: "Logs cleared" });
  }

  public runShellCommand(command: string, level: LogLevel = "terminal"): boolean {
    const trimmed = command.trim();
    if (!trimmed) return false;

    if (this.activeProcess) {
      this.emit({
        type: "log",
        level: "error",
        message: `Another process is already running (${this.activeCommand}). Stop it first.`,
      });
      return false;
    }

    const cwd = this.getWorkspace();
    this.activeCommand = trimmed;
    this.emit({
      type: "log",
      level: "user",
      message: `> ${trimmed}`,
    });
    this.emitStatus();

    const child = spawn("powershell.exe", ["-NoLogo", "-NoProfile", "-Command", trimmed], {
      cwd,
      windowsHide: true,
      env: { ...process.env, FORCE_COLOR: "0" },
    });

    this.activeProcess = child;

    const handleChunk = (chunk: Buffer, isError: boolean) => {
      const text = chunk.toString("utf-8");
      const lines = text.split(/\r?\n/);
      for (const line of lines) {
        if (line.length > 0) {
          this.emit({
            type: "log",
            level: isError ? "error" : level,
            message: line,
          });
        }
      }
    };

    child.stdout?.on("data", (d: Buffer) => handleChunk(d, false));
    child.stderr?.on("data", (d: Buffer) => handleChunk(d, true));

    child.on("close", (code) => {
      this.activeProcess = null;
      this.activeCommand = null;
      this.emit({
        type: "terminal_exit",
        level: code === 0 ? "system" : "error",
        message: `Process exited with code ${code ?? -1}`,
        data: { exitCode: code ?? -1 },
      });
      this.emitStatus();
    });

    child.on("error", (err) => {
      this.activeProcess = null;
      this.activeCommand = null;
      this.emit({
        type: "log",
        level: "error",
        message: `Execution error: ${err.message}`,
      });
      this.emitStatus();
    });

    return true;
  }

  public sendPrompt(prompt: string): { mode: string } {
    const trimmed = prompt.trim();
    if (!trimmed) return { mode: "empty" };

    this.lastPrompt = trimmed;
    this.emit({
      type: "log",
      level: "user",
      message: `[Prompt] ${trimmed}`,
    });

    // Write prompt to workspace inbox file so Antigravity IDE / agents can also inspect it
    try {
      const inboxPath = path.join(this.getWorkspace(), ".antigravity-remote-prompt.md");
      fs.writeFileSync(
        inboxPath,
        `# Remote Prompt (${new Date().toISOString()})\n\n${trimmed}\n`,
        "utf-8"
      );
    } catch {
      // Non-fatal if workspace is read-only
    }

    // If prompt starts with '!' or '/', treat as direct shell command in workspace
    if (trimmed.startsWith("!")) {
      const cmd = trimmed.slice(1).trim();
      this.runShellCommand(cmd, "terminal");
      return { mode: "shell" };
    }

    // If agy CLI is installed, invoke it in the workspace context
    if (this.agyCliPath) {
      const escaped = trimmed.replace(/"/g, '\\"');
      const cmd = `& "${this.agyCliPath}" "${escaped}"`;
      this.runShellCommand(cmd, "agent");
      return { mode: "agy_cli" };
    }

    // Fallback: log receipt and run workspace status check or command if applicable
    this.emit({
      type: "log",
      level: "agent",
      message: `Prompt queued in workspace (${path.basename(this.getWorkspace())}). Prefix with "!" to run a shell command directly.`,
    });
    return { mode: "queued" };
  }

  public performAction(action: string): { ok: boolean; detail: string } {
    switch (action.toLowerCase()) {
      case "approve": {
        if (this.activeProcess?.stdin?.writable) {
          this.activeProcess.stdin.write("y\n");
          this.emit({ type: "log", level: "action", message: "[Action] Approved (sent 'y' to stdin)" });
          return { ok: true, detail: "Sent approval to active process" };
        }
        this.emit({ type: "log", level: "action", message: "[Action] Approve signal recorded" });
        return { ok: true, detail: "Approval signal recorded" };
      }
      case "reject": {
        if (this.activeProcess?.stdin?.writable) {
          this.activeProcess.stdin.write("n\n");
          this.emit({ type: "log", level: "action", message: "[Action] Rejected (sent 'n' to stdin)" });
          return { ok: true, detail: "Sent rejection to active process" };
        }
        this.emit({ type: "log", level: "action", message: "[Action] Reject signal recorded" });
        return { ok: true, detail: "Rejection signal recorded" };
      }
      case "stop": {
        if (this.activeProcess && this.activeProcess.pid) {
          const pid = this.activeProcess.pid;
          try {
            execSync(`taskkill /pid ${pid} /T /F`, { stdio: "ignore" });
          } catch {
            this.activeProcess.kill("SIGKILL");
          }
          this.activeProcess = null;
          this.activeCommand = null;
          this.emit({ type: "log", level: "action", message: `[Action] Stopped process (PID ${pid})` });
          this.emitStatus();
          return { ok: true, detail: `Stopped process ${pid}` };
        }
        this.emit({ type: "log", level: "action", message: "[Action] No active process to stop" });
        return { ok: true, detail: "No active process running" };
      }
      case "restart": {
        const last = this.lastPrompt;
        if (this.activeProcess) {
          this.performAction("stop");
        }
        if (last) {
          this.sendPrompt(last);
          return { ok: true, detail: "Restarted last prompt" };
        }
        this.emit({ type: "log", level: "action", message: "[Action] Session ready" });
        return { ok: true, detail: "Session ready" };
      }
      case "clear": {
        this.clearLogs();
        return { ok: true, detail: "Cleared output stream" };
      }
      default:
        return { ok: false, detail: `Unknown action: ${action}` };
    }
  }

  public emitStatus(): void {
    this.emit({
      type: "status",
      data: {
        running: this.isRunning(),
        activeCommand: this.activeCommand,
        antigravityRunning: this.isAntigravityRunning(),
        agyCliAvailable: this.agyCliPath !== null,
      },
    });
  }
}

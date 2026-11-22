import { execFile, spawn } from "child_process";
import fs from "fs";
import os from "os";
import path from "path";
import { commandLine } from "./proc";

/** Locates the Antigravity IDE install and reports whether it is running. */
export class IdeIntegration {
  readonly cliPath: string | null;
  private running = false;
  private lastCheck = 0;

  constructor(configuredCli?: string) {
    this.cliPath = IdeIntegration.findCli(configuredCli);
    this.refresh();
  }

  get installed(): boolean {
    return this.cliPath !== null;
  }

  get isRunning(): boolean {
    if (Date.now() - this.lastCheck > 5000) this.refresh();
    return this.running;
  }

  /** Opens a folder in the IDE, reusing the current window. */
  openFolder(folder: string): void {
    if (!this.cliPath) throw new Error("Antigravity IDE was not found on this PC");
    const launch = commandLine(this.cliPath, ["--reuse-window", folder]);
    const child = spawn(launch.file, launch.args, {
      ...launch.options,
      detached: true,
      stdio: "ignore",
      windowsHide: true,
    });
    child.unref();
    this.lastCheck = 0;
  }

  private refresh(): void {
    this.lastCheck = Date.now();
    if (process.platform !== "win32") return;
    execFile("tasklist", ["/FO", "CSV", "/NH"], { windowsHide: true, maxBuffer: 8 * 1024 * 1024 }, (err, stdout) => {
      if (err) return;
      const names = stdout.toLowerCase();
      this.running = names.includes('"antigravity ide.exe"') || names.includes('"antigravity.exe"');
    });
  }

  private static findCli(configured?: string): string | null {
    if (configured && fs.existsSync(configured)) return configured;
    const programs = path.join(os.homedir(), "AppData", "Local", "Programs");
    const candidates = [
      path.join(programs, "Antigravity IDE", "bin", "antigravity-ide.cmd"),
      path.join(programs, "Antigravity", "bin", "antigravity.cmd"),
      path.join(programs, "antigravity", "bin", "antigravity.cmd"),
    ];
    return candidates.find((candidate) => fs.existsSync(candidate)) ?? null;
  }
}

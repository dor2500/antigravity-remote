import { execFileSync, SpawnOptions } from "child_process";

/**
 * Returns spawn() arguments that run `command args...` correctly on Windows even when the
 * command is a .cmd/.bat script and paths contain spaces (Node does not quote for shell: true).
 */
export function commandLine(command: string, args: string[]): { file: string; args: string[]; options: SpawnOptions } {
  if (process.platform === "win32" && /\.(cmd|bat)$/i.test(command)) {
    const quoted = [command, ...args].map((part) => `"${part.replace(/"/g, '""')}"`).join(" ");
    return { file: "cmd.exe", args: ["/d", "/s", "/c", `"${quoted}"`], options: { windowsVerbatimArguments: true } };
  }
  return { file: command, args, options: {} };
}

/** Kills a process and all of its children. */
export function killTree(pid: number | undefined): void {
  if (!pid) return;
  try {
    if (process.platform === "win32") {
      execFileSync("taskkill", ["/pid", String(pid), "/T", "/F"], { stdio: "ignore", windowsHide: true });
    } else {
      process.kill(pid, "SIGKILL");
    }
  } catch {
    // Process already gone
  }
}

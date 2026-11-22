import crypto from "crypto";
import fs from "fs";
import os from "os";
import path from "path";

export interface BridgeConfig {
  port: number;
  token: string;
  activeWorkspace: string;
  workspaces: string[];
}

const CONFIG_PATH = path.resolve(__dirname, "..", ".bridge-config.json");

function discoverDefaultWorkspaces(): string[] {
  const candidates: string[] = [];
  const home = os.homedir();
  const possibleRoots = [
    path.join(home, ".gemini", "antigravity", "scratch"),
    path.join(home, "Documents", "Nebula Projects"),
    path.join(home, "Projects"),
    path.join(home, "source", "repos"),
    process.cwd(),
  ];

  for (const root of possibleRoots) {
    if (fs.existsSync(root) && fs.statSync(root).isDirectory()) {
      candidates.push(root);
      try {
        const entries = fs.readdirSync(root, { withFileTypes: true });
        for (const entry of entries) {
          if (entry.isDirectory() && !entry.name.startsWith(".")) {
            candidates.push(path.join(root, entry.name));
          }
        }
      } catch {
        // Ignore unreadable directories
      }
    }
  }

  return Array.from(new Set(candidates)).slice(0, 25);
}

export function loadOrCreateConfig(cliPort?: number): BridgeConfig {
  let config: Partial<BridgeConfig> = {};

  if (fs.existsSync(CONFIG_PATH)) {
    try {
      const raw = fs.readFileSync(CONFIG_PATH, "utf-8");
      config = JSON.parse(raw) as Partial<BridgeConfig>;
    } catch {
      config = {};
    }
  }

  const discovered = discoverDefaultWorkspaces();
  const port = cliPort || Number(process.env.PORT) || config.port || 8765;
  const token = config.token || crypto.randomBytes(24).toString("hex");

  const existingWorkspaces = (config.workspaces || []).filter((w) =>
    fs.existsSync(w)
  );
  const workspaces = Array.from(new Set([...existingWorkspaces, ...discovered]));
  const activeWorkspace =
    config.activeWorkspace && fs.existsSync(config.activeWorkspace)
      ? config.activeWorkspace
      : workspaces[0] || process.cwd();

  const finalConfig: BridgeConfig = {
    port,
    token,
    activeWorkspace,
    workspaces,
  };

  saveConfig(finalConfig);
  return finalConfig;
}

export function saveConfig(config: BridgeConfig): void {
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2), "utf-8");
}

export function getLocalLanIp(): string {
  const interfaces = os.networkInterfaces();
  const scored: { address: string; score: number }[] = [];

  for (const [name, addrs] of Object.entries(interfaces)) {
    if (!addrs) continue;
    const lowerName = name.toLowerCase();
    // Deprioritize virtual adapters (WSL, Hyper-V, VMware, VirtualBox, Docker)
    const isVirtual =
      lowerName.includes("vethernet") ||
      lowerName.includes("wsl") ||
      lowerName.includes("hyper-v") ||
      lowerName.includes("vmware") ||
      lowerName.includes("virtualbox") ||
      lowerName.includes("host-only") ||
      lowerName.includes("docker") ||
      lowerName.includes("loopback");

    for (const addr of addrs) {
      if (addr.family !== "IPv4" || addr.internal) continue;

      let score = 0;
      const isVboxDefault = addr.address.startsWith("192.168.56.");
      if (!isVirtual && !isVboxDefault) score += 100;
      if (addr.address.startsWith("192.168.") && !isVboxDefault) score += 50;
      else if (addr.address.startsWith("10.")) score += 40;
      else if (/^172\.(1[6-9]|2\d|3[01])\./.test(addr.address)) score += 30;
      if (lowerName.includes("wi-fi") || lowerName.includes("wlan") || lowerName.includes("ethernet")) {
        score += 20;
      }

      scored.push({ address: addr.address, score });
    }
  }

  scored.sort((a, b) => b.score - a.score);
  return scored[0]?.address || "127.0.0.1";
}

import crypto from "crypto";
import dgram from "dgram";
import fs from "fs";
import os from "os";
import path from "path";
import { fileURLToPath } from "url";

export interface BridgeConfig {
  port: number;
  token: string;
  activeWorkspace: string;
  workspaces: string[];
  /** Optional override for the Antigravity CLI (agy) path. */
  agentCommand?: string;
  /** Optional override for the Antigravity IDE launcher (antigravity-ide.cmd). */
  ideCli?: string;
  /** Shell used by the terminal tab. */
  shell: string;
  /** Address put in the pairing QR code. Defaults to the detected LAN IP. */
  advertiseHost?: string;
  /** PC name shown in the app. Defaults to the Windows computer name. */
  displayName?: string;
  /** Publish the current routes, encrypted, so a paired phone finds a new tunnel URL by itself. */
  relay?: boolean;
  /** Public https URL that reaches this bridge (a tunnel or reverse proxy), offered to the phone as a fallback route. */
  publicUrl?: string;
}

export interface CliOptions {
  port?: number;
  workspace?: string;
  host?: string;
  name?: string;
  /** Public URL for this run only (start-bridge-remote.bat passes its tunnel address here). */
  url?: string;
  publicUrl?: string;
  noRelay: boolean;
  resetToken: boolean;
}

const CONFIG_DIR = path.join(process.env.APPDATA ?? path.join(os.homedir(), ".config"), "antigravity-remote");
export const CONFIG_PATH = path.join(CONFIG_DIR, "bridge-config.json");
const DEFAULT_PORT = 8765;
const MAX_WORKSPACES = 40;

export function parseCli(argv: string[]): CliOptions {
  const value = (flag: string): string | undefined => {
    const index = argv.indexOf(flag);
    return index !== -1 ? argv[index + 1] : undefined;
  };
  const port = Number(value("--port"));
  return {
    port: Number.isInteger(port) && port > 0 && port < 65536 ? port : undefined,
    workspace: value("--workspace"),
    host: value("--host"),
    name: value("--name"),
    url: value("--url"),
    publicUrl: value("--public-url"),
    noRelay: argv.includes("--no-relay"),
    resetToken: argv.includes("--reset-token"),
  };
}

function isDirectory(candidate: string): boolean {
  try {
    return fs.statSync(candidate).isDirectory();
  } catch {
    return false;
  }
}

function uriToPath(uri: unknown): string | null {
  if (typeof uri !== "string" || !uri.startsWith("file://")) return null;
  try {
    return fileURLToPath(uri);
  } catch {
    return null;
  }
}

/** Folders recently opened in Antigravity IDE, read from its window state. */
function ideRecentFolders(): string[] {
  const appData = process.env.APPDATA ?? path.join(os.homedir(), "AppData", "Roaming");
  const storageFiles = ["Antigravity IDE", "Antigravity"].map((name) =>
    path.join(appData, name, "User", "globalStorage", "storage.json")
  );
  const folders: string[] = [];
  for (const file of storageFiles) {
    let data: any;
    try {
      data = JSON.parse(fs.readFileSync(file, "utf-8"));
    } catch {
      continue;
    }
    const uris: unknown[] = [
      data?.windowsState?.lastActiveWindow?.folder,
      ...(Array.isArray(data?.windowsState?.openedWindows) ? data.windowsState.openedWindows.map((w: any) => w?.folder) : []),
      ...(Array.isArray(data?.backupWorkspaces?.folders) ? data.backupWorkspaces.folders.map((f: any) => f?.folderUri) : []),
      ...Object.keys(data?.profileAssociations?.workspaces ?? {}),
    ];
    for (const uri of uris) {
      const folder = uriToPath(uri);
      if (folder) folders.push(folder);
    }
  }
  const temp = path.resolve(os.tmpdir()).toLowerCase();
  return folders.filter((folder) => isDirectory(folder) && !path.resolve(folder).toLowerCase().startsWith(temp));
}

/** Direct child folders of the usual project roots. */
function projectRootFolders(): string[] {
  const home = os.homedir();
  const roots = [
    path.join(home, ".gemini", "antigravity", "scratch"),
    path.join(home, "source", "repos"),
    path.join(home, "Projects"),
    path.join(home, "projects"),
    path.join(home, "Documents", "GitHub"),
    path.join(home, "Documents", "Projects"),
    path.join(home, "dev"),
    path.join(home, "code"),
  ];
  const folders: string[] = [];
  for (const root of roots) {
    if (!isDirectory(root)) continue;
    try {
      for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
        if (entry.isDirectory() && !entry.name.startsWith(".")) folders.push(path.join(root, entry.name));
      }
    } catch {
      // Unreadable root
    }
  }
  return folders;
}

function unique(paths: string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const candidate of paths) {
    const resolved = path.resolve(candidate);
    const key = resolved.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(resolved);
  }
  return result;
}

export function loadOrCreateConfig(cli: CliOptions): BridgeConfig {
  let stored: Partial<BridgeConfig> = {};
  if (fs.existsSync(CONFIG_PATH)) {
    try {
      stored = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf-8")) as Partial<BridgeConfig>;
    } catch {
      console.warn(`[bridge] ${CONFIG_PATH} is not valid JSON; starting with defaults.`);
    }
  }

  const port = cli.port ?? (Number(process.env.BRIDGE_PORT) || stored.port || DEFAULT_PORT);
  const token =
    !cli.resetToken && typeof stored.token === "string" && stored.token.length >= 32
      ? stored.token
      : crypto.randomBytes(24).toString("hex");

  const cliWorkspace = cli.workspace && isDirectory(cli.workspace) ? path.resolve(cli.workspace) : null;
  const ideFolders = ideRecentFolders();
  const workspaces = unique([
    ...(cliWorkspace ? [cliWorkspace] : []),
    ...(stored.workspaces ?? []).filter(isDirectory),
    ...ideFolders,
    ...projectRootFolders(),
  ]).slice(0, MAX_WORKSPACES);

  const activeWorkspace =
    cliWorkspace ??
    (stored.activeWorkspace && isDirectory(stored.activeWorkspace) ? path.resolve(stored.activeWorkspace) : null) ??
    ideFolders[0] ??
    workspaces[0] ??
    os.homedir();
  if (!workspaces.some((w) => w.toLowerCase() === activeWorkspace.toLowerCase())) workspaces.unshift(activeWorkspace);

  const config: BridgeConfig = {
    port,
    token,
    activeWorkspace,
    workspaces,
    shell: stored.shell || "powershell.exe",
    agentCommand: stored.agentCommand,
    ideCli: stored.ideCli,
    advertiseHost: cli.host ?? stored.advertiseHost,
    displayName: cli.name ?? stored.displayName,
    publicUrl: normalizeUrl(stored.publicUrl),
    relay: stored.relay !== false,
  };
  saveConfig(config);
  return config;
}

export function normalizeUrl(value: string | undefined): string | undefined {
  if (!value || !value.trim()) return undefined;
  const trimmed = value.trim().replace(/\/+$/, "");
  if (!/^https?:\/\//i.test(trimmed)) {
    console.warn(`[bridge] publicUrl must start with https:// - ignoring "${value}"`);
    return undefined;
  }
  return trimmed;
}

/** Tailscale's address range (100.64.0.0/10). */
export function isTailscaleAddress(ip: string): boolean {
  return /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(ip);
}

export function saveConfig(config: BridgeConfig): void {
  fs.mkdirSync(CONFIG_DIR, { recursive: true });
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2), "utf-8");
}

export interface NetworkAddress {
  address: string;
  name: string;
  score: number;
}

/**
 * Local address of the interface that carries the default route. A connected UDP socket picks
 * the outgoing interface without sending anything.
 */
function defaultRouteAddress(): Promise<string | null> {
  return new Promise((resolve) => {
    const socket = dgram.createSocket("udp4");
    const done = (value: string | null): void => {
      socket.close();
      resolve(value);
    };
    socket.on("error", () => done(null));
    socket.connect(53, "8.8.8.8", () => {
      try {
        done(socket.address().address);
      } catch {
        done(null);
      }
    });
  });
}

/** All IPv4 addresses, best LAN candidate first. Tailscale (100.64.0.0/10) is kept but ranked after LAN. */
export async function listAddresses(): Promise<NetworkAddress[]> {
  const primary = await defaultRouteAddress();
  const result: NetworkAddress[] = [];
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    if (!addrs) continue;
    const lower = name.toLowerCase();
    const virtual = /vethernet|wsl|hyper-v|vmware|virtualbox|host-only|docker|loopback|vpn|zerotier/.test(lower);
    for (const addr of addrs) {
      if (addr.family !== "IPv4" || addr.internal) continue;
      const ip = addr.address;
      let score = 0;
      const tailscale = /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(ip) || lower.includes("tailscale");
      if (ip.startsWith("169.254.")) score -= 100;
      if (!virtual && !tailscale) score += 100;
      if (ip.startsWith("192.168.") && !ip.startsWith("192.168.56.")) score += 50;
      else if (ip.startsWith("10.")) score += 40;
      else if (/^172\.(1[6-9]|2\d|3[01])\./.test(ip)) score += 30;
      if (/wi-?fi|wlan|wireless|ethernet/.test(lower)) score += 20;
      if (tailscale) score += 10;
      if (ip === primary) score += 500;
      result.push({ address: ip, name, score });
    }
  }
  return result.sort((a, b) => b.score - a.score);
}

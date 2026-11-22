import crypto from "crypto";
import express, { NextFunction, Request, Response } from "express";
import fs from "fs";
import http from "http";
import os from "os";
import path from "path";
import qrcode from "qrcode-terminal";
import { WebSocket, WebSocketServer } from "ws";
import { AgentSession, resolveAgentCommand } from "./agent";
import { CONFIG_PATH, isTailscaleAddress, listAddresses, loadOrCreateConfig, normalizeUrl, parseCli, saveConfig } from "./config";
import { EventHub, StreamEvent } from "./events";
import { listDirectory, readFileContent } from "./files";
import { IdeIntegration } from "./ide";
import { IdeHubSession } from "./ide-hub";
import { AddressRelay } from "./relay";
import { setLanguage, t } from "./i18n";
import { TerminalSession } from "./terminal";

const VERSION = "1.3.1";

const cli = parseCli(process.argv.slice(2));
const config = loadOrCreateConfig(cli);
// A tunnel URL passed for this run (start-bridge-remote.bat) wins over the one saved in the config.
const publicUrl = normalizeUrl(cli.url ?? cli.publicUrl) ?? config.publicUrl;
let addresses: Awaited<ReturnType<typeof listAddresses>> = [];
let advertisedHost = config.advertiseHost || "127.0.0.1";

const hub = new EventHub();
const ide = new IdeIntegration(config.ideCli);
let statusTimer: NodeJS.Timeout | null = null;
const scheduleStatus = (): void => {
  if (statusTimer) return;
  statusTimer = setTimeout(() => {
    statusTimer = null;
    hub.emit({ type: "status", data: buildStatus() });
  }, 50);
};
const agentCommand = resolveAgentCommand(config.agentCommand);
const agent = new AgentSession(hub, () => config.activeWorkspace, agentCommand, scheduleStatus);
const ideChat = new IdeHubSession(hub, agentCommand, scheduleStatus);
const terminal = new TerminalSession(hub, config.shell, config.activeWorkspace, scheduleStatus);

function buildStatus(): Record<string, unknown> {
  return {
    version: VERSION,
    hostname: config.displayName || os.hostname(),
    ip: advertisedHost,
    port: config.port,
    activeWorkspace: config.activeWorkspace,
    workspaceName: path.basename(config.activeWorkspace) || config.activeWorkspace,
    agent: {
      mode: ideChat.active ? "ide" : "cli",
      available: ideChat.active || agent.available,
      state: ideChat.active ? ideChat.state : agent.state,
      title: ideChat.active ? ideChat.title : agent.title,
      queued: ideChat.active ? 0 : agent.queuedPrompts,
      lastError: ideChat.active ? ideChat.lastError : agent.lastError,
      permission: ideChat.active ? ideChat.permission : agent.permission,
      commands: agent.commands,
      conversationId: ideChat.conversationId,
    },
    terminal: {
      running: terminal.running,
      command: terminal.command,
      cwd: terminal.workingDirectory,
    },
    ide: {
      installed: ide.installed,
      running: ide.isRunning,
      conversations: ideChat.available,
    },
    uptimeSeconds: Math.floor(process.uptime()),
  };
}

function tokenMatches(provided: string): boolean {
  const a = Buffer.from(provided);
  const b = Buffer.from(config.token);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function bodyString(req: Request, key: string): string {
  const value = (req.body as Record<string, unknown> | undefined)?.[key];
  return typeof value === "string" ? value : "";
}

function fail(res: Response, status: number, err: unknown): void {
  res.status(status).json({ error: err instanceof Error ? err.message : String(err) });
}

async function switchWorkspace(target: string): Promise<string> {
  const resolved = path.resolve(target.trim());
  if (!target.trim() || !fs.existsSync(resolved) || !fs.statSync(resolved).isDirectory()) {
    throw new Error(t("Not a folder: {0}", target));
  }
  config.activeWorkspace = resolved;
  config.workspaces = [resolved, ...config.workspaces.filter((w) => w.toLowerCase() !== resolved.toLowerCase())];
  saveConfig(config);
  await agent.moveTo(resolved);
  if (!terminal.running) terminal.resetTo(resolved);
  hub.log("agent", "system", t("Project: {0}", path.basename(resolved)));
  scheduleStatus();
  return resolved;
}

async function performAction(action: string, target: string): Promise<string> {
  switch (action.toLowerCase()) {
    case "approve":
      return ideChat.active ? ideChat.approve() : agent.approve();
    case "reject":
      return ideChat.active ? ideChat.reject() : agent.reject();
    case "stop":
      if (target === "terminal") return terminal.stop();
      return ideChat.active ? ideChat.stop() : agent.stop();
    case "restart":
      if (ideChat.active) {
        ideChat.close();
        hub.emit({ type: "clear", channel: "agent" });
      }
      return agent.restart();
    case "retry":
      return agent.retryLast();
    case "clear":
      hub.emit({ type: "clear", channel: target === "terminal" ? "terminal" : "agent" });
      return "Cleared";
    default:
      throw new Error(`Unknown action: ${action}`);
  }
}

async function handlePrompt(prompt: string): Promise<string> {
  const trimmed = prompt.trim();
  if (!trimmed) throw new Error(t("Message is empty"));
  if (trimmed.startsWith("!")) {
    terminal.run(trimmed.slice(1));
    return "terminal";
  }
  if (ideChat.active) return ideChat.prompt(trimmed);
  return agent.prompt(trimmed);
}

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "1mb" }));

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "antigravity-remote-bridge", version: VERSION });
});

// Locks out an address after repeated wrong tokens, which matters once a public URL is used.
const FAILURE_WINDOW_MS = 10 * 60 * 1000;
const MAX_FAILURES = 10;
const failures = new Map<string, { count: number; since: number }>();

function clientAddress(req: http.IncomingMessage): string {
  const direct = req.socket.remoteAddress || "unknown";
  // Forwarding headers are only trusted from a tunnel running on this PC; anyone else could forge them.
  const loopback = direct === "127.0.0.1" || direct === "::1" || direct === "::ffff:127.0.0.1";
  if (!loopback) return direct;
  const forwarded = String(req.headers["cf-connecting-ip"] ?? req.headers["x-forwarded-for"] ?? "").split(",")[0].trim();
  return forwarded || direct;
}

function lockedOut(address: string): boolean {
  const entry = failures.get(address);
  if (!entry) return false;
  if (Date.now() - entry.since > FAILURE_WINDOW_MS) {
    failures.delete(address);
    return false;
  }
  return entry.count >= MAX_FAILURES;
}

function recordFailure(address: string): void {
  const entry = failures.get(address);
  if (!entry || Date.now() - entry.since > FAILURE_WINDOW_MS) failures.set(address, { count: 1, since: Date.now() });
  else entry.count += 1;
  if (failures.get(address)!.count === MAX_FAILURES) console.warn(`[bridge] ${address} locked out for 10 minutes after repeated bad tokens`);
}

app.use("/api", (req: Request, res: Response, next: NextFunction) => {
  const address = clientAddress(req);
  if (lockedOut(address)) {
    res.status(429).json({ error: "Too many failed attempts. Try again later." });
    return;
  }
  setLanguage(String(req.headers["x-app-language"] ?? ""));
  const header = req.headers.authorization ?? "";
  const provided = header.startsWith("Bearer ") ? header.slice(7).trim() : String(req.headers["x-auth-token"] ?? "");
  if (!provided || !tokenMatches(provided)) {
    recordFailure(address);
    res.status(401).json({ error: "Invalid or missing token" });
    return;
  }
  next();
});

app.get("/api/status", (_req, res) => {
  res.json(buildStatus());
});

app.get("/api/workspaces", (_req, res) => {
  const workspaces = config.workspaces
    .filter((w) => fs.existsSync(w))
    .map((w) => ({ path: w, name: path.basename(w) || w, active: w === config.activeWorkspace }));
  res.json({ activeWorkspace: config.activeWorkspace, workspaces });
});

app.post("/api/workspaces/switch", async (req, res) => {
  try {
    const resolved = await switchWorkspace(bodyString(req, "path"));
    res.json({ ok: true, activeWorkspace: resolved, workspaceName: path.basename(resolved) });
  } catch (err) {
    fail(res, 400, err);
  }
});

app.post("/api/ide/open", (req, res) => {
  try {
    const folder = bodyString(req, "path") || config.activeWorkspace;
    ide.openFolder(folder);
    hub.log("agent", "system", t("Opened {0} in Antigravity IDE", path.basename(folder)));
    res.json({ ok: true });
  } catch (err) {
    fail(res, 400, err);
  }
});

app.get("/api/ide/conversations", async (_req, res) => {
  try {
    res.json({ active: ideChat.conversationId, conversations: await ideChat.list() });
  } catch (err) {
    fail(res, 400, err);
  }
});

app.post("/api/ide/conversations/open", async (req, res) => {
  try {
    const title = await ideChat.open(bodyString(req, "id"));
    res.json({ ok: true, title });
  } catch (err) {
    fail(res, 400, err);
  }
});

app.post("/api/ide/conversations/new", async (req, res) => {
  try {
    const prompt = bodyString(req, "prompt").trim();
    if (!prompt) throw new Error(t("Message is empty"));
    const title = await ideChat.create(prompt);
    res.json({ ok: true, title });
  } catch (err) {
    fail(res, 400, err);
  }
});

app.post("/api/ide/conversations/close", (_req, res) => {
  ideChat.close();
  hub.emit({ type: "clear", channel: "agent" });
  hub.log("agent", "system", t("Back to the agent on the PC"));
  res.json({ ok: true });
});

app.post("/api/prompt", async (req, res) => {
  try {
    const mode = await handlePrompt(bodyString(req, "prompt"));
    res.json({ ok: true, mode });
  } catch (err) {
    fail(res, 400, err);
  }
});

app.post("/api/terminal", (req, res) => {
  try {
    terminal.run(bodyString(req, "command"));
    res.json({ ok: true });
  } catch (err) {
    fail(res, 409, err);
  }
});

app.post("/api/terminal/input", (req, res) => {
  try {
    terminal.sendInput(bodyString(req, "text"));
    res.json({ ok: true });
  } catch (err) {
    fail(res, 409, err);
  }
});

app.post("/api/action", async (req, res) => {
  try {
    const detail = await performAction(bodyString(req, "action"), bodyString(req, "target"));
    res.json({ ok: true, detail });
  } catch (err) {
    fail(res, 400, err);
  }
});

app.get("/api/files", (req, res) => {
  try {
    const relPath = typeof req.query.path === "string" ? req.query.path : "";
    res.json({ path: relPath, entries: listDirectory(config.activeWorkspace, relPath) });
  } catch (err) {
    fail(res, 400, err);
  }
});

app.get("/api/files/read", (req, res) => {
  try {
    const relPath = typeof req.query.path === "string" ? req.query.path : "";
    if (!relPath) throw new Error("Query parameter 'path' is required");
    res.json(readFileContent(config.activeWorkspace, relPath));
  } catch (err) {
    fail(res, 400, err);
  }
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });

const send = (ws: WebSocket, event: StreamEvent): void => {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(event));
};

wss.on("connection", (ws, req) => {
  const url = new URL(req.url ?? "/", "http://localhost");
  const address = clientAddress(req);
  if (lockedOut(address)) {
    ws.close(4029, "Too many failed attempts");
    return;
  }
  if (!tokenMatches(url.searchParams.get("token") ?? "")) {
    recordFailure(address);
    ws.close(4001, "Unauthorized");
    return;
  }
  setLanguage(url.searchParams.get("lang"));
  const remote = req.socket.remoteAddress ?? "unknown";
  console.log(`[bridge] phone connected from ${remote}`);

  send(ws, { type: "clear", channel: "agent", timestamp: new Date().toISOString() });
  send(ws, { type: "clear", channel: "terminal", timestamp: new Date().toISOString() });
  for (const event of hub.getHistory()) send(ws, event);
  send(ws, { type: "status", data: buildStatus(), timestamp: new Date().toISOString() });
  const permission = ideChat.active ? ideChat.permission : agent.permission;
  if (permission) {
    send(ws, { type: "permission", channel: "agent", data: permission as unknown as Record<string, unknown>, timestamp: new Date().toISOString() });
  }

  const unsubscribe = hub.subscribe((event) => send(ws, event));

  ws.on("message", async (raw) => {
    let msg: Record<string, unknown>;
    try {
      msg = JSON.parse(raw.toString("utf-8"));
    } catch {
      return;
    }
    const text = (key: string): string => (typeof msg[key] === "string" ? (msg[key] as string) : "");
    try {
      switch (msg.type) {
        case "prompt":
          await handlePrompt(text("prompt"));
          break;
        case "terminal":
          terminal.run(text("command"));
          break;
        case "terminal_input":
          terminal.sendInput(text("text"));
          break;
        case "action":
          await performAction(text("action"), text("target"));
          break;
        case "ping":
          send(ws, { type: "status", data: buildStatus(), timestamp: new Date().toISOString() });
          break;
        default:
          break;
      }
    } catch (err) {
      const channel = msg.type === "terminal" || msg.type === "terminal_input" ? "terminal" : "agent";
      hub.log(channel, "error", (err as Error).message);
    }
  });

  ws.on("close", () => {
    unsubscribe();
    console.log(`[bridge] phone disconnected (${remote})`);
  });
});

const statusHeartbeat = setInterval(() => {
  if (wss.clients.size > 0) scheduleStatus();
}, 10000);

server.on("error", (err: NodeJS.ErrnoException) => {
  if (err.code === "EADDRINUSE") {
    console.error(`[bridge] Port ${config.port} is already in use. Close the other bridge or run with --port <n>.`);
  } else {
    console.error(`[bridge] Server error: ${err.message}`);
  }
  process.exit(1);
});

/** Every way to reach this PC, best first: LAN address, Tailscale addresses, then the public URL. */
function currentRoutes(): { hosts: string[]; port: number; url?: string } {
  const hosts = [
    advertisedHost,
    ...addresses.filter((a) => isTailscaleAddress(a.address) && a.address !== advertisedHost).map((a) => a.address),
  ];
  return { hosts, port: config.port, ...(publicUrl ? { url: publicUrl } : {}) };
}

const relay = config.relay !== false && !cli.noRelay ? new AddressRelay(config.token, currentRoutes) : null;

async function start(): Promise<void> {
  addresses = await listAddresses();
  advertisedHost = config.advertiseHost || addresses[0]?.address || "127.0.0.1";
  server.listen(config.port, "0.0.0.0", () => {
    printBanner();
    relay?.start();
  });
}

function printBanner(): void {
  const routes = currentRoutes();
  // "ip" stays for older app versions; "hosts" and "url" list every route the app may try.
  const payload = JSON.stringify({ ip: advertisedHost, port: config.port, token: config.token, hosts: routes.hosts, ...(routes.url ? { url: routes.url } : {}) });
  const tailscale = routes.hosts.filter(isTailscaleAddress);
  const line = "=".repeat(64);
  const away = routes.url
    ? "ready (tunnel)"
    : tailscale.length > 0
      ? "ready (Tailscale)"
      : "home network only - start with the tunnel or install Tailscale";
  console.log("");
  console.log(line);
  console.log(`  ANTIGRAVITY REMOTE  v${VERSION}   ${config.displayName || os.hostname()}`);
  console.log(line);
  console.log("  Scan this code once with the Antigravity Remote app:");
  console.log("");
  qrcode.generate(payload, { small: true }, (code) => console.log(code));
  console.log(`  At home       ${advertisedHost}:${config.port}`);
  if (tailscale.length > 0) console.log(`  Tailscale     ${tailscale.join(", ")}`);
  if (routes.url) console.log(`  Tunnel        ${routes.url}`);
  console.log(`  Away access   ${away}`);
  console.log(`  Auto-find     ${relay ? "on - the phone finds new addresses by itself" : "off"}`);
  console.log(`  Agent         ${agent.commandPath ? "Antigravity CLI found" : "Antigravity CLI (agy) not found"}${ide.installed ? ", IDE found" : ""}`);
  console.log(`  Workspace     ${config.activeWorkspace}`);
  console.log(`  Token         ${config.token}`);
  console.log(`  Settings      ${CONFIG_PATH}`);
  console.log(line);
  console.log("  Keep this window open. Press Ctrl+C to stop.");
  console.log(line);
}

void start();

const shutdown = (): void => {
  clearInterval(statusHeartbeat);
  relay?.stop();
  ideChat.close();
  agent.shutdown("bridge stopping");
  terminal.stop();
  server.close();
  process.exit(0);
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

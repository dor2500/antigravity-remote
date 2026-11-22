import crypto from "crypto";
import express, { NextFunction, Request, Response } from "express";
import fs from "fs";
import http from "http";
import os from "os";
import path from "path";
import qrcode from "qrcode-terminal";
import { WebSocket, WebSocketServer } from "ws";
import { AgentSession, resolveAgentCommand } from "./agent";
import { CONFIG_PATH, listAddresses, loadOrCreateConfig, parseCli, saveConfig } from "./config";
import { EventHub, StreamEvent } from "./events";
import { listDirectory, readFileContent } from "./files";
import { IdeIntegration } from "./ide";
import { TerminalSession } from "./terminal";

const VERSION = "1.0.0";

const cli = parseCli(process.argv.slice(2));
const config = loadOrCreateConfig(cli);
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
const agent = new AgentSession(hub, () => config.activeWorkspace, resolveAgentCommand(config.agentCommand), scheduleStatus);
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
      available: agent.available,
      state: agent.state,
      title: agent.title,
      queued: agent.queuedPrompts,
      lastError: agent.lastError,
      permission: agent.permission,
      commands: agent.commands,
    },
    terminal: {
      running: terminal.running,
      command: terminal.command,
      cwd: terminal.workingDirectory,
    },
    ide: {
      installed: ide.installed,
      running: ide.isRunning,
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
    throw new Error(`Not a folder: ${target}`);
  }
  config.activeWorkspace = resolved;
  config.workspaces = [resolved, ...config.workspaces.filter((w) => w.toLowerCase() !== resolved.toLowerCase())];
  saveConfig(config);
  await agent.moveTo(resolved);
  if (!terminal.running) terminal.resetTo(resolved);
  hub.log("agent", "system", `Workspace: ${path.basename(resolved)} (${resolved})`);
  scheduleStatus();
  return resolved;
}

async function performAction(action: string, target: string): Promise<string> {
  switch (action.toLowerCase()) {
    case "approve":
      return agent.approve();
    case "reject":
      return agent.reject();
    case "stop":
      return target === "terminal" ? terminal.stop() : agent.stop();
    case "restart":
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
  if (!trimmed) throw new Error("Prompt is empty");
  if (trimmed.startsWith("!")) {
    terminal.run(trimmed.slice(1));
    return "terminal";
  }
  return agent.prompt(trimmed);
}

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "1mb" }));

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "antigravity-remote-bridge", version: VERSION });
});

app.use("/api", (req: Request, res: Response, next: NextFunction) => {
  const header = req.headers.authorization ?? "";
  const provided = header.startsWith("Bearer ") ? header.slice(7).trim() : String(req.headers["x-auth-token"] ?? "");
  if (!provided || !tokenMatches(provided)) {
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
    hub.log("agent", "system", `Opened ${path.basename(folder)} in Antigravity IDE`);
    res.json({ ok: true });
  } catch (err) {
    fail(res, 400, err);
  }
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
  if (!tokenMatches(url.searchParams.get("token") ?? "")) {
    ws.close(4001, "Unauthorized");
    return;
  }
  const remote = req.socket.remoteAddress ?? "unknown";
  console.log(`[bridge] phone connected from ${remote}`);

  send(ws, { type: "clear", channel: "agent", timestamp: new Date().toISOString() });
  send(ws, { type: "clear", channel: "terminal", timestamp: new Date().toISOString() });
  for (const event of hub.getHistory()) send(ws, event);
  send(ws, { type: "status", data: buildStatus(), timestamp: new Date().toISOString() });
  const permission = agent.permission;
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

async function start(): Promise<void> {
  addresses = await listAddresses();
  advertisedHost = config.advertiseHost || addresses[0]?.address || "127.0.0.1";
  server.listen(config.port, "0.0.0.0", printBanner);
}

function printBanner(): void {
  const publicUrl = cli.url?.trim().replace(/\/+$/, "");
  const payload = JSON.stringify(
    publicUrl
      ? { url: publicUrl, ip: advertisedHost, port: config.port, token: config.token }
      : { ip: advertisedHost, port: config.port, token: config.token }
  );
  const line = "=".repeat(64);
  console.log("");
  console.log(line);
  console.log(`  ANTIGRAVITY REMOTE BRIDGE v${VERSION}`);
  console.log(line);
  console.log(`  Host        ${config.displayName || os.hostname()}`);
  if (publicUrl) {
    console.log(`  Remote URL  ${publicUrl} (HTTPS - works from anywhere)`);
  }
  console.log(`  LAN Address ${advertisedHost}:${config.port}`);
  const others = addresses.filter((a) => a.address !== advertisedHost);
  if (others.length > 0) {
    console.log(`  Also on     ${others.map((a) => `${a.address} (${a.name})`).join(", ")}`);
  }
  console.log(`  Token       ${config.token}`);
  console.log(`  Workspace   ${config.activeWorkspace}`);
  console.log(`  Agent CLI   ${agent.commandPath ?? "not found - prompts need the Antigravity CLI (agy)"}`);
  console.log(`  IDE         ${ide.installed ? (ide.isRunning ? "installed, running" : "installed") : "not found"}`);
  console.log(`  Config      ${CONFIG_PATH}`);
  console.log(line);
  console.log("  Scan this code with the Antigravity Remote app:");
  console.log("");
  qrcode.generate(payload, { small: true }, (code) => console.log(code));
  if (publicUrl) {
    console.log(`  Manual entry: ${publicUrl}  token above`);
  } else {
    console.log(`  Manual entry: ${advertisedHost}  port ${config.port}  token above`);
  }
  console.log("  Press Ctrl+C to stop.");
  console.log(line);
}

void start();

const shutdown = (): void => {
  clearInterval(statusHeartbeat);
  agent.shutdown("bridge stopping");
  terminal.stop();
  server.close();
  process.exit(0);
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

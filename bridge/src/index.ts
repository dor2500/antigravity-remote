import cors from "cors";
import express, { NextFunction, Request, Response } from "express";
import fs from "fs";
import http from "http";
import os from "os";
import path from "path";
import qrcode from "qrcode-terminal";
import { WebSocket, WebSocketServer } from "ws";
import { getLocalLanIp, loadOrCreateConfig, saveConfig } from "./config";
import { listDirectory, readFileContent } from "./files";
import { SessionManager, StreamEvent } from "./session";

function parseCliPort(): number | undefined {
  const idx = process.argv.indexOf("--port");
  if (idx !== -1 && process.argv[idx + 1]) {
    const parsed = Number(process.argv[idx + 1]);
    if (!Number.isNaN(parsed) && parsed > 0) return parsed;
  }
  return undefined;
}

const config = loadOrCreateConfig(parseCliPort());
const lanIp = getLocalLanIp();
const session = new SessionManager(() => config.activeWorkspace);

const app = express();
app.use(cors());
app.use(express.json({ limit: "2mb" }));

// Public health check (no sensitive data)
app.get("/health", (_req: Request, res: Response) => {
  res.json({ ok: true, service: "antigravity-remote-bridge" });
});

// Bearer token authentication for all /api routes
app.use("/api", (req: Request, res: Response, next: NextFunction) => {
  const authHeader = req.headers.authorization || "";
  const tokenHeader = (req.headers["x-auth-token"] as string) || "";
  const bearer = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
  const provided = bearer || tokenHeader;

  if (!provided || provided !== config.token) {
    res.status(401).json({ error: "Unauthorized: invalid or missing token" });
    return;
  }
  next();
});

app.get("/api/status", (_req: Request, res: Response) => {
  res.json({
    connected: true,
    hostname: os.hostname(),
    ip: lanIp,
    port: config.port,
    activeWorkspace: config.activeWorkspace,
    workspaceName: path.basename(config.activeWorkspace),
    running: session.isRunning(),
    activeCommand: session.getActiveCommand(),
    antigravityRunning: session.isAntigravityRunning(),
    agyCliAvailable: session.getAgyCliPath() !== null,
    uptimeSeconds: Math.floor(process.uptime()),
  });
});

app.get("/api/workspaces", (_req: Request, res: Response) => {
  const list = config.workspaces
    .filter((w) => fs.existsSync(w))
    .map((w) => ({
      path: w,
      name: path.basename(w),
      active: w === config.activeWorkspace,
    }));
  res.json({ activeWorkspace: config.activeWorkspace, workspaces: list });
});

app.post("/api/workspaces/switch", (req: Request, res: Response) => {
  const target = String(req.body?.path || "").trim();
  if (!target || !fs.existsSync(target) || !fs.statSync(target).isDirectory()) {
    res.status(400).json({ error: "Invalid workspace directory path" });
    return;
  }
  const resolved = path.resolve(target);
  config.activeWorkspace = resolved;
  if (!config.workspaces.includes(resolved)) {
    config.workspaces.unshift(resolved);
  }
  saveConfig(config);

  session.emit({
    type: "workspace_changed",
    level: "system",
    message: `Switched workspace to ${path.basename(resolved)} (${resolved})`,
    data: {
      activeWorkspace: resolved,
      workspaceName: path.basename(resolved),
    },
  });

  res.json({
    ok: true,
    activeWorkspace: resolved,
    workspaceName: path.basename(resolved),
  });
});

app.post("/api/prompt", (req: Request, res: Response) => {
  const prompt = String(req.body?.prompt || "");
  if (!prompt.trim()) {
    res.status(400).json({ error: "Prompt cannot be empty" });
    return;
  }
  const result = session.sendPrompt(prompt);
  res.json({ ok: true, mode: result.mode });
});

app.post("/api/terminal", (req: Request, res: Response) => {
  const command = String(req.body?.command || "");
  if (!command.trim()) {
    res.status(400).json({ error: "Command cannot be empty" });
    return;
  }
  const started = session.runShellCommand(command, "terminal");
  if (!started) {
    res.status(409).json({ error: "Another command is currently running" });
    return;
  }
  res.json({ ok: true, command });
});

app.post("/api/action", (req: Request, res: Response) => {
  const action = String(req.body?.action || "");
  if (!action) {
    res.status(400).json({ error: "Action is required" });
    return;
  }
  const result = session.performAction(action);
  if (!result.ok) {
    res.status(400).json({ error: result.detail });
    return;
  }
  res.json(result);
});

app.get("/api/files", (req: Request, res: Response) => {
  try {
    const relPath = String(req.query.path || "");
    const entries = listDirectory(config.activeWorkspace, relPath);
    res.json({ path: relPath, entries });
  } catch (err) {
    res.status(400).json({ error: (err as Error).message });
  }
});

app.get("/api/files/read", (req: Request, res: Response) => {
  try {
    const relPath = String(req.query.path || "");
    if (!relPath) {
      res.status(400).json({ error: "Query parameter 'path' is required" });
      return;
    }
    const fileData = readFileContent(config.activeWorkspace, relPath);
    res.json(fileData);
  } catch (err) {
    res.status(400).json({ error: (err as Error).message });
  }
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });

wss.on("connection", (ws: WebSocket, req: http.IncomingMessage) => {
  const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
  const token = url.searchParams.get("token") || "";

  if (token !== config.token) {
    ws.close(4001, "Unauthorized");
    return;
  }

  // Send recent log history on connect
  for (const event of session.getLogBuffer()) {
    ws.send(JSON.stringify(event));
  }

  // Send initial status
  ws.send(
    JSON.stringify({
      type: "status",
      timestamp: new Date().toISOString(),
      data: {
        running: session.isRunning(),
        activeCommand: session.getActiveCommand(),
        antigravityRunning: session.isAntigravityRunning(),
        agyCliAvailable: session.getAgyCliPath() !== null,
        activeWorkspace: config.activeWorkspace,
        workspaceName: path.basename(config.activeWorkspace),
        hostname: os.hostname(),
      },
    } satisfies StreamEvent)
  );

  const unsubscribe = session.subscribe((event) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(event));
    }
  });

  ws.on("message", (raw) => {
    try {
      const msg = JSON.parse(raw.toString("utf-8")) as {
        type?: string;
        command?: string;
        prompt?: string;
        action?: string;
      };
      if (msg.type === "terminal" && msg.command) {
        session.runShellCommand(msg.command, "terminal");
      } else if (msg.type === "prompt" && msg.prompt) {
        session.sendPrompt(msg.prompt);
      } else if (msg.type === "action" && msg.action) {
        session.performAction(msg.action);
      }
    } catch {
      // Ignore malformed WS messages
    }
  });

  ws.on("close", () => {
    unsubscribe();
  });
});

server.listen(config.port, "0.0.0.0", () => {
  const qrPayload = JSON.stringify({
    ip: lanIp,
    port: config.port,
    token: config.token,
  });

  console.log("");
  console.log("============================================================");
  console.log("  ANTIGRAVITY IDE - REMOTE BRIDGE SERVER");
  console.log("============================================================");
  console.log(`  Host:      ${os.hostname()}`);
  console.log(`  LAN IP:    ${lanIp}`);
  console.log(`  Port:      ${config.port}`);
  console.log(`  Token:     ${config.token}`);
  console.log(`  Workspace: ${config.activeWorkspace}`);
  console.log(`  IDE Proc:  ${session.isAntigravityRunning() ? "Detected" : "Standby"}`);
  console.log("============================================================");
  console.log("  Scan this QR code in the Antigravity Remote Android app:");
  console.log("");
  qrcode.generate(qrPayload, { small: true });
  console.log("");
  console.log("  Press Ctrl+C to stop the bridge server.");
  console.log("============================================================");
});

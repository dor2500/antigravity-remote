import crypto from "crypto";

/**
 * Address relay: lets a paired phone find the PC after its addresses change (for example a new
 * Cloudflare quick-tunnel URL after every restart) without scanning the QR code again.
 *
 * The bridge publishes its current routes to a public ntfy.sh topic. Both the topic name and
 * the AES-256-GCM key are derived from the pairing token, so only a phone that holds the token
 * can find the topic or read the message; ntfy.sh only ever sees ciphertext.
 */

const RELAY_SERVER = "https://ntfy.sh";
const REPUBLISH_MS = 30 * 60 * 1000;

export function relayTopic(token: string): string {
  return "agr-" + crypto.createHash("sha256").update(`antigravity-remote/topic/${token}`).digest("hex").slice(0, 32);
}

function relayKey(token: string): Buffer {
  return crypto.createHash("sha256").update(`antigravity-remote/key/${token}`).digest();
}

/** base64(iv | ciphertext | tag), the layout Android's AES/GCM/NoPadding reads directly. */
export function sealRoutes(token: string, payload: object): string {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", relayKey(token), iv);
  const body = Buffer.concat([cipher.update(JSON.stringify(payload), "utf-8"), cipher.final()]);
  return Buffer.concat([iv, body, cipher.getAuthTag()]).toString("base64");
}

export class AddressRelay {
  private timer: NodeJS.Timeout | null = null;
  private lastError: string | null = null;

  constructor(
    private readonly token: string,
    private readonly routes: () => { hosts: string[]; port: number; url?: string }
  ) {}

  start(): void {
    void this.publish();
    this.timer = setInterval(() => void this.publish(), REPUBLISH_MS);
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  async publish(): Promise<void> {
    const sealed = sealRoutes(this.token, { ...this.routes(), at: new Date().toISOString() });
    try {
      const res = await fetch(`${RELAY_SERVER}/${relayTopic(this.token)}`, {
        method: "POST",
        body: sealed,
        headers: { Cache: "yes", Title: "antigravity-remote" },
        signal: AbortSignal.timeout(15000),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      if (this.lastError) console.log("[bridge] address relay reachable again");
      this.lastError = null;
    } catch (err) {
      const message = (err as Error).message;
      if (message !== this.lastError) console.warn(`[bridge] address relay unavailable (${message}); pairing by QR still works`);
      this.lastError = message;
    }
  }
}

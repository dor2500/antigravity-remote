export type Channel = "agent" | "terminal";

export type LogLevel =
  | "user"
  | "agent"
  | "thought"
  | "tool"
  | "output"
  | "error"
  | "system"
  | "action";

export interface StreamEvent {
  type: "log" | "status" | "clear" | "permission" | "permission_resolved";
  channel?: Channel;
  level?: LogLevel;
  message?: string;
  /** Events sharing a streamId are fragments of one entry and must be concatenated. */
  streamId?: string;
  timestamp: string;
  data?: Record<string, unknown>;
}

type Listener = (event: StreamEvent) => void;

const MAX_ENTRIES_PER_CHANNEL = 1000;

/**
 * Fan-out hub for everything the phone sees. Keeps a bounded history per channel so a
 * client that (re)connects gets the recent output, with streamed fragments already merged.
 */
export class EventHub {
  private listeners = new Set<Listener>();
  private history: Record<Channel, StreamEvent[]> = { agent: [], terminal: [] };

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  emit(event: Omit<StreamEvent, "timestamp">): void {
    const full: StreamEvent = { ...event, timestamp: new Date().toISOString() };
    if (full.type === "log" && full.channel) {
      this.remember(full);
    } else if (full.type === "clear" && full.channel) {
      this.history[full.channel] = [];
    }
    for (const listener of this.listeners) {
      try {
        listener(full);
      } catch (err) {
        console.error("[bridge] listener failed:", (err as Error).message);
      }
    }
  }

  log(channel: Channel, level: LogLevel, message: string, streamId?: string): void {
    this.emit({ type: "log", channel, level, message, streamId });
  }

  getHistory(): StreamEvent[] {
    return [...this.history.agent, ...this.history.terminal];
  }

  private remember(event: StreamEvent): void {
    const list = this.history[event.channel as Channel];
    const last = list[list.length - 1];
    if (event.streamId && last && last.streamId === event.streamId) {
      list[list.length - 1] = { ...last, message: (last.message ?? "") + (event.message ?? "") };
      return;
    }
    list.push({ ...event });
    if (list.length > MAX_ENTRIES_PER_CHANNEL) list.shift();
  }
}

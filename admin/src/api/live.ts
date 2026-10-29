import type { LiveEvent, Pulse } from "./types";

/* The live feed: one Server-Sent Events stream read with fetch (so it carries the bearer header, which EventSource
   can't), reconnecting with backoff, and a slow poll of the pulse while the stream is down. */

export interface SseMessage {
  event: string;
  data: string;
  id?: string;
}

/**
 * An incremental text/event-stream parser: push decoded chunks, get whole messages back. Handles \n, \r\n and \r line
 * ends, multi-line data, comments and the default "message" event, per the HTML spec.
 */
export class SseParser {
  private buffer = "";
  private event = "";
  private data: string[] = [];
  private id: string | undefined;
  private sawCr = false;

  push(chunk: string): SseMessage[] {
    // A chunk can end between \r and \n; drop that \n when it starts the next chunk.
    if (this.sawCr && chunk.startsWith("\n")) chunk = chunk.slice(1);
    this.sawCr = chunk.endsWith("\r");
    this.buffer += chunk;
    const out: SseMessage[] = [];
    const lines = this.buffer.split(/\r\n|\r|\n/);
    this.buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (line === "") {
        if (this.data.length > 0) out.push({ event: this.event || "message", data: this.data.join("\n"), ...(this.id !== undefined ? { id: this.id } : {}) });
        this.event = "";
        this.data = [];
        continue;
      }
      if (line.startsWith(":")) continue;
      const colon = line.indexOf(":");
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? "" : line.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);
      if (field === "event") this.event = value;
      else if (field === "data") this.data.push(value);
      else if (field === "id") this.id = value;
    }
    return out;
  }
}

/** Seconds before reconnect attempt n (0-based): 1, 2, 5, 10, then every 30. */
export const BACKOFF_SECONDS = [1, 2, 5, 10, 30] as const;
export const backoff = (attempt: number) => BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)]! * 1000;
export const POLL_MS = 60_000;

export type LiveStatus = "connecting" | "open" | "retrying" | "polling" | "stopped";

export interface LiveDeps {
  fetch: typeof fetch;
  setTimeout: (fn: () => void, ms: number) => unknown;
  clearTimeout: (handle: unknown) => void;
}

export interface LiveOptions {
  url: string;
  pulseUrl: string;
  headers: () => Record<string, string> | null;
  onEvent: (event: LiveEvent) => void;
  onPulse: (pulse: Pulse) => void;
  onStatus?: (status: LiveStatus, retryInMs?: number) => void;
  /** The server refused the token: stop for good (the session is gone). */
  onUnauthorized?: () => void;
  deps?: Partial<LiveDeps>;
}

export class LiveClient {
  private readonly deps: LiveDeps;
  private attempt = 0;
  private abort: AbortController | null = null;
  private retryTimer: unknown = null;
  private pollTimer: unknown = null;
  private stopped = true;
  status: LiveStatus = "stopped";

  constructor(private readonly options: LiveOptions) {
    this.deps = {
      fetch: options.deps?.fetch ?? ((...args) => globalThis.fetch(...args)),
      setTimeout: options.deps?.setTimeout ?? ((fn, ms) => globalThis.setTimeout(fn, ms)),
      clearTimeout: options.deps?.clearTimeout ?? ((handle) => globalThis.clearTimeout(handle as number)),
    };
  }

  start() {
    if (!this.stopped) return;
    this.stopped = false;
    this.attempt = 0;
    void this.poll();
    void this.connect();
  }

  stop() {
    this.stopped = true;
    this.abort?.abort();
    this.abort = null;
    this.deps.clearTimeout(this.retryTimer);
    this.deps.clearTimeout(this.pollTimer);
    this.setStatus("stopped");
  }

  private setStatus(status: LiveStatus, retryInMs?: number) {
    this.status = status;
    this.options.onStatus?.(status, retryInMs);
  }

  /** The pulse: counts now, and the recent events (which fill gaps while the stream is down). Every 60 s regardless. */
  async poll() {
    this.deps.clearTimeout(this.pollTimer);
    const headers = this.options.headers();
    if (this.stopped || !headers) return;
    try {
      const response = await this.deps.fetch(this.options.pulseUrl, { headers, cache: "no-store", credentials: "omit" });
      if (response.status === 401) return this.unauthorized();
      if (response.ok) {
        const pulse = (await response.json()) as Pulse;
        if (!this.stopped) this.options.onPulse(pulse);
      }
    } catch {
      // Offline: try again at the next tick.
    }
    if (!this.stopped) this.pollTimer = this.deps.setTimeout(() => void this.poll(), POLL_MS);
  }

  private unauthorized() {
    this.stop();
    this.options.onUnauthorized?.();
  }

  private async connect() {
    const headers = this.options.headers();
    if (this.stopped || !headers) return;
    this.setStatus(this.attempt === 0 ? "connecting" : "retrying");
    const abort = new AbortController();
    this.abort = abort;
    try {
      const response = await this.deps.fetch(this.options.url, {
        headers: { ...headers, accept: "text/event-stream" },
        signal: abort.signal,
        cache: "no-store",
        credentials: "omit",
      });
      if (response.status === 401) return this.unauthorized();
      if (!response.ok || !response.body) throw new Error(`stream ${response.status}`);
      this.attempt = 0;
      this.setStatus("open");
      // Back on the stream: catch up on anything missed while it was down.
      void this.poll();
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      const parser = new SseParser();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        for (const message of parser.push(value)) {
          if (message.event !== "live") continue;
          try {
            this.options.onEvent(JSON.parse(message.data) as LiveEvent);
          } catch {
            // A malformed message is skipped, not fatal.
          }
        }
      }
    } catch {
      if (abort.signal.aborted && this.stopped) return;
    }
    if (this.stopped) return;
    // Ended (token expiry, a deploy, the network): back off, and poll meanwhile.
    const delay = backoff(this.attempt++);
    this.setStatus("polling", delay);
    this.retryTimer = this.deps.setTimeout(() => void this.connect(), delay);
  }
}

/** Keeps the newest `limit` events, newest first, dropping ones already seen (by id). */
export function mergeEvents(current: LiveEvent[], incoming: LiveEvent[], limit = 60): LiveEvent[] {
  const seen = new Set(current.map((event) => event.id));
  const fresh = incoming.filter((event) => !seen.has(event.id) && (seen.add(event.id), true));
  if (fresh.length === 0) return current;
  return [...fresh, ...current].sort((a, b) => Date.parse(b.atUtc) - Date.parse(a.atUtc)).slice(0, limit);
}

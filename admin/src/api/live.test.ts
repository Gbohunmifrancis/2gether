import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { backoff, LiveClient, mergeEvents, POLL_MS, SseParser, type LiveStatus } from "./live";
import type { LiveEvent, Pulse } from "./types";

const event = (id: string, atUtc = "2026-10-26T10:00:00Z", kind: LiveEvent["kind"] = "dareCompleted"): LiveEvent => ({
  id,
  kind,
  atUtc,
  userId: null,
  displayName: "Maya",
  partnerName: "Theo",
  detail: "squat",
  severity: "good",
});

describe("SseParser", () => {
  it("parses whole messages across chunk boundaries and line endings", () => {
    const parser = new SseParser();
    expect(parser.push("event: live\nda")).toEqual([]);
    expect(parser.push('ta: {"a":1}\n')).toEqual([]);
    expect(parser.push("\nevent: ping\r\ndata: {}\r")).toEqual([{ event: "live", data: '{"a":1}' }]);
    // The \n of a \r\n split across chunks isn't a second blank line.
    expect(parser.push("\n\r\n")).toEqual([{ event: "ping", data: "{}" }]);
  });

  it("joins multi-line data, skips comments, defaults the event name and keeps the id", () => {
    const parser = new SseParser();
    const out = parser.push(": hello\nid: 7\ndata: one\ndata:two\n\nevent: live\n\n");
    expect(out).toEqual([{ event: "message", data: "one\ntwo", id: "7" }]);
  });
});

describe("backoff", () => {
  it("waits 1, 2, 5, 10, then 30 seconds", () => {
    expect([0, 1, 2, 3, 4, 5, 9].map(backoff)).toEqual([1000, 2000, 5000, 10_000, 30_000, 30_000, 30_000]);
  });
});

describe("mergeEvents", () => {
  it("adds new events newest first and drops repeats by id", () => {
    const current = [event("b", "2026-10-26T10:00:02Z"), event("a", "2026-10-26T10:00:01Z")];
    const merged = mergeEvents(current, [event("a", "2026-10-26T10:00:01Z"), event("c", "2026-10-26T10:00:03Z"), event("c", "2026-10-26T10:00:03Z")]);
    expect(merged.map((item) => item.id)).toEqual(["c", "b", "a"]);
    expect(mergeEvents(current, [event("a")])).toBe(current);
  });
});

/** A controllable stream response: push text, then end it. */
function stream() {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({ start: (c) => void (controller = c) });
  const encoder = new TextEncoder();
  return {
    response: new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } }),
    push: (text: string) => controller.enqueue(encoder.encode(text)),
    end: () => controller.close(),
  };
}

const pulse = (recent: LiveEvent[] = []): Pulse => ({
  nowUtc: "2026-10-26T10:00:00Z",
  onlineNow: 3,
  activeToday: 10,
  ringingNow: 1,
  onCameraNow: 0,
  openGames: 2,
  heartsToday: 5,
  signupsToday: 1,
  couplesLinkedToday: 0,
  plusStartedToday: 0,
  recent,
});

describe("LiveClient", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  const flush = async () => {
    for (let i = 0; i < 10; i++) await Promise.resolve();
  };

  it("streams live events, reconnects with backoff when the stream ends, and polls the pulse meanwhile", async () => {
    const streams: ReturnType<typeof stream>[] = [];
    const calls: string[] = [];
    const fetch = vi.fn(async (url: string | URL | Request) => {
      calls.push(String(url));
      if (String(url).endsWith("/pulse")) return new Response(JSON.stringify(pulse([event("p1")])), { status: 200 });
      // The second try finds the network down.
      if (calls.filter((item) => item === "/stream").length === 2) throw new TypeError("offline");
      const next = stream();
      streams.push(next);
      return next.response;
    });
    const events: string[] = [];
    const statuses: LiveStatus[] = [];
    const pulses: Pulse[] = [];
    const client = new LiveClient({
      url: "/stream",
      pulseUrl: "/pulse",
      headers: () => ({ authorization: "Bearer t" }),
      onEvent: (item) => events.push(item.id),
      onPulse: (item) => pulses.push(item),
      onStatus: (status) => statuses.push(status),
      deps: { fetch: fetch as typeof globalThis.fetch },
    });

    client.start();
    await vi.advanceTimersByTimeAsync(0);
    await flush();
    expect(client.status).toBe("open");
    streams[0]!.push('event: live\ndata: {"id":"e1","kind":"signedUp","atUtc":"2026-10-26T10:00:00Z"}\n\nevent: ping\ndata: {"nowUtc":"x"}\n\n');
    await vi.advanceTimersByTimeAsync(0);
    await flush();
    expect(events).toEqual(["e1"]);

    // The stream ends: wait 1 s; the next try fails (offline): wait 2 s; then it's back.
    streams[0]!.end();
    await vi.advanceTimersByTimeAsync(0);
    await flush();
    expect(client.status).toBe("polling");
    const streamCalls = () => calls.filter((url) => url === "/stream").length;
    expect(streamCalls()).toBe(1);
    await vi.advanceTimersByTimeAsync(999);
    expect(streamCalls()).toBe(1);
    await vi.advanceTimersByTimeAsync(1);
    await flush();
    expect(streamCalls()).toBe(2);
    expect(client.status).toBe("polling");
    await vi.advanceTimersByTimeAsync(1999);
    expect(streamCalls()).toBe(2);
    await vi.advanceTimersByTimeAsync(1);
    await flush();
    expect(streamCalls()).toBe(3);
    expect(client.status).toBe("open");
    expect(statuses).toContain("retrying");

    // The pulse comes at start, on reconnect, and every 60 s.
    const before = calls.filter((url) => url === "/pulse").length;
    await vi.advanceTimersByTimeAsync(POLL_MS);
    await flush();
    expect(calls.filter((url) => url === "/pulse").length).toBe(before + 1);
    expect(pulses[0]!.recent[0]!.id).toBe("p1");

    client.stop();
    expect(client.status).toBe("stopped");
  });

  it("stops for good on 401", async () => {
    const onUnauthorized = vi.fn();
    const fetch = vi.fn(async () => new Response(null, { status: 401 }));
    const client = new LiveClient({
      url: "/stream",
      pulseUrl: "/pulse",
      headers: () => ({}),
      onEvent: () => {},
      onPulse: () => {},
      onUnauthorized,
      deps: { fetch: fetch as unknown as typeof globalThis.fetch },
    });
    client.start();
    await vi.advanceTimersByTimeAsync(0);
    await flush();
    expect(onUnauthorized).toHaveBeenCalled();
    expect(client.status).toBe("stopped");
    const count = fetch.mock.calls.length;
    await vi.advanceTimersByTimeAsync(120_000);
    expect(fetch.mock.calls.length).toBe(count);
  });
});

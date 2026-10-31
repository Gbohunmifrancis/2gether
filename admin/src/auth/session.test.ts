import { afterEach, describe, expect, it, vi } from "vitest";
import type { AdminSession } from "../api/types";

const session = (expiresInMs: number): AdminSession => ({
  accessToken: "token",
  expiresAtUtc: new Date(Date.now() + expiresInMs).toISOString(),
  admin: { id: "a", email: "ops@heartpact.me", displayName: "Ops" },
});

afterEach(() => {
  vi.useRealTimers();
  vi.resetModules();
});

describe("session", () => {
  it("keeps the session in this tab's storage and tells listeners", async () => {
    const store = await import("./session");
    const seen: (AdminSession | null)[] = [];
    store.onSessionChange((value) => seen.push(value));
    const live = session(60_000);
    store.setSession(live);
    expect(store.getSession()).toEqual(live);
    expect(JSON.parse(sessionStorage.getItem("heartpact.admin.session")!)).toEqual(live);
    store.setSession(null);
    expect(sessionStorage.getItem("heartpact.admin.session")).toBeNull();
    expect(seen).toEqual([live, null]);
  });

  it("ignores an expired stored session on load", async () => {
    sessionStorage.setItem("heartpact.admin.session", JSON.stringify(session(-1000)));
    const store = await import("./session");
    expect(store.getSession()).toBeNull();
  });

  it("signs out when the token expires", async () => {
    vi.useFakeTimers();
    const store = await import("./session");
    store.setSession(session(5_000));
    vi.advanceTimersByTime(5_001);
    expect(store.getSession()).toBeNull();
  });
});

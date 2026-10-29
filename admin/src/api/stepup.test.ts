import { afterEach, describe, expect, it, vi } from "vitest";
import { setSession } from "../auth/session";
import { api, setStepUpPrompt, STEP_UP_CANCELLED, type StepUpPrompt } from "./client";

const json = (status: number, body?: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const required = () => json(403, { code: "admin.step_up_required", message: "Enter a fresh code." });
const invalid = () => json(403, { code: "admin.step_up_invalid", message: "That code didn't work." });

/** A prompt that answers with `codes` in turn and records what it was asked and told. */
function prompter(codes: (string | null)[]) {
  const asked: { invalid: boolean; message: string }[] = [];
  const settled: boolean[] = [];
  const prompt: StepUpPrompt = {
    ask: async (request) => {
      asked.push(request);
      return codes.shift() ?? null;
    },
    settle: (ok) => settled.push(ok),
  };
  return { prompt, asked, settled };
}

const headerOf = (init: RequestInit | undefined, name: string) => (init?.headers as Record<string, string> | undefined)?.[name];

let unregister: (() => void) | null = null;
afterEach(() => {
  unregister?.();
  setSession(null);
});

describe("step-up", () => {
  it("asks for a code and sends the same request again with X-Admin-Code", async () => {
    setSession({ accessToken: "t", expiresAtUtc: new Date(Date.now() + 60_000).toISOString(), admin: { id: "1", email: "a@b.c", displayName: "A" } });
    const { prompt, asked, settled } = prompter(["123456"]);
    unregister = setStepUpPrompt(prompt);
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(required()).mockResolvedValueOnce(json(204));

    await expect(api("/api/admin/users/u1/disable", { method: "POST", body: { reason: "spam" } })).resolves.toBeNull();

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(headerOf(fetch.mock.calls[0]![1], "x-admin-code")).toBeUndefined();
    expect(headerOf(fetch.mock.calls[1]![1], "x-admin-code")).toBe("123456");
    // The same body and token go again.
    expect(fetch.mock.calls[1]![1]!.body).toBe(JSON.stringify({ reason: "spam" }));
    expect(headerOf(fetch.mock.calls[1]![1], "authorization")).toBe("Bearer t");
    expect(asked).toEqual([{ invalid: false, message: "Enter a fresh code." }]);
    expect(settled).toEqual([true]);
  });

  it("asks again when a code is refused, saying so", async () => {
    const { prompt, asked, settled } = prompter(["000000", "654321"]);
    unregister = setStepUpPrompt(prompt);
    const fetch = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(required())
      .mockResolvedValueOnce(invalid())
      .mockResolvedValueOnce(json(200, { ok: 1 }));

    await expect(api("/api/admin/subscriptions/s/refund", { method: "POST", body: {} })).resolves.toEqual({ ok: 1 });
    expect(asked.map((request) => request.invalid)).toEqual([false, true]);
    expect(asked[1]!.message).toBe("That code didn't work.");
    expect(fetch.mock.calls.map((call) => headerOf(call[1], "x-admin-code"))).toEqual([undefined, "000000", "654321"]);
    expect(settled).toEqual([true]);
  });

  it("gives up without sending anything more when the admin cancels", async () => {
    const { prompt } = prompter([null]);
    unregister = setStepUpPrompt(prompt);
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(required());
    await expect(api("/api/admin/users/u1/delete", { method: "POST", body: {} })).rejects.toMatchObject({ code: STEP_UP_CANCELLED });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("passes other failures of the retry through, and says when there were too many tries", async () => {
    const { prompt, settled } = prompter(["111111", "222222"]);
    unregister = setStepUpPrompt(prompt);
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(required())
      .mockResolvedValueOnce(json(409, { code: "admin.self", message: "Not yourself." }))
      .mockResolvedValueOnce(required())
      .mockResolvedValueOnce(json(429, { code: "admin.step_up_locked", message: "Slow down." }));
    await expect(api("/api/admin/users/u1/disable", { method: "POST", body: {} })).rejects.toMatchObject({ status: 409, code: "admin.self" });
    await expect(api("/api/admin/users/u1/disable", { method: "POST", body: {} })).rejects.toMatchObject({
      status: 429,
      message: "Too many tries. Wait a few minutes and try again.",
    });
    expect(settled).toEqual([false, false]);
  });

  it("leaves the 403 alone when nobody can ask, and never for anonymous calls", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(required());
    await expect(api("/api/admin/users/u1/disable", { method: "POST", body: {} })).rejects.toMatchObject({ code: "admin.step_up_required" });

    const { prompt, asked } = prompter(["123456"]);
    unregister = setStepUpPrompt(prompt);
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(required());
    await expect(api("/api/admin/auth/login", { method: "POST", body: {}, anonymous: true })).rejects.toMatchObject({ code: "admin.step_up_required" });
    expect(asked).toEqual([]);
  });
});

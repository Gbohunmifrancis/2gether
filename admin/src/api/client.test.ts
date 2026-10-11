import { afterEach, describe, expect, it, vi } from "vitest";
import { getSession, setSession } from "../auth/session";
import { api, ApiError, qs, toApiError } from "./client";

const json = (status: number, body: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

afterEach(() => setSession(null));

describe("toApiError", () => {
  it("reads our errors, validation problems and bare statuses", () => {
    expect(toApiError(409, { code: "admin.self", message: "Not yourself." })).toMatchObject({ code: "admin.self", message: "Not yourself." });
    expect(toApiError(400, { title: "One or more validation errors occurred.", errors: { Days: ["Days is required."] } })).toMatchObject({
      code: "validation",
      message: "Days is required.",
    });
    expect(toApiError(503, null)).toMatchObject({ code: "http.503", message: "The server had a problem. Try again in a moment." });
  });
});

describe("api", () => {
  it("sends the admin token and parses JSON", async () => {
    setSession({ accessToken: "abc", expiresAtUtc: new Date(Date.now() + 60_000).toISOString(), admin: { id: "1", email: "a@b.c", displayName: "A" } });
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(json(200, { ok: true }));
    await expect(api("/api/admin/system")).resolves.toEqual({ ok: true });
    const [, init] = fetch.mock.calls[0]!;
    expect((init!.headers as Record<string, string>).authorization).toBe("Bearer abc");
    expect(init!.credentials).toBe("omit");
  });

  it("drops the session on 401, except for sign-in", async () => {
    setSession({ accessToken: "abc", expiresAtUtc: new Date(Date.now() + 60_000).toISOString(), admin: { id: "1", email: "a@b.c", displayName: "A" } });
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(json(401, { code: "admin.code_required", message: "Code?" }));
    await expect(api("/api/admin/auth/login", { method: "POST", body: {}, anonymous: true })).rejects.toBeInstanceOf(ApiError);
    expect(getSession()).not.toBeNull();

    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(json(401, undefined));
    await expect(api("/api/admin/users")).rejects.toMatchObject({ status: 401 });
    expect(getSession()).toBeNull();
  });

  it("turns a failed connection into a readable error", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));
    await expect(api("/api/admin/users")).rejects.toMatchObject({ code: "network", status: 0 });
  });

  it("builds query strings from what's set", () => {
    expect(qs({ query: "ada", page: 2, status: undefined, empty: "" })).toBe("?query=ada&page=2");
    expect(qs({})).toBe("");
  });
});

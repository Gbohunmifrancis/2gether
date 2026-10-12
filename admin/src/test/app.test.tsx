import { createMemoryHistory } from "@tanstack/react-router";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp } from "../app";
import type { AdminSession, Page, UserDetail, UserRow } from "../api/types";
import { getSession, setSession } from "../auth/session";

type Handler = (method: string, path: string, body: unknown) => { status: number; body?: unknown } | undefined;

/** Answers fetches from `handler`; anything it doesn't know is a test failure. */
function mockApi(handler: Handler) {
  const calls: { method: string; path: string; body: unknown; auth?: string }[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = new URL(String(input), "http://admin.test");
    const method = init?.method ?? "GET";
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method, path: url.pathname + url.search, body, auth: (init?.headers as Record<string, string>)?.authorization });
    const answer = handler(method, url.pathname + url.search, body);
    if (!answer) throw new Error(`Unexpected ${method} ${url.pathname}${url.search}`);
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { "content-type": "application/json" },
    });
  });
  return calls;
}

function start(path: string) {
  const app = createApp(createMemoryHistory({ initialEntries: [path] }));
  render(app.element);
  return app;
}

const admin: AdminSession = {
  accessToken: "admin-token",
  expiresAtUtc: new Date(Date.now() + 4 * 3600_000).toISOString(),
  admin: { id: "00000000-0000-0000-0000-00000000000a", email: "ops@heartpact.app", displayName: "Ops" },
};

const maya: UserRow = {
  id: "11111111-1111-1111-1111-111111111111",
  email: "maya@example.com",
  displayName: "Maya",
  createdAtUtc: "2026-09-01T10:00:00Z",
  lastActiveAtUtc: new Date().toISOString(),
  disabledAtUtc: null,
  google: false,
  password: true,
  coupleStatus: "active",
  paying: true,
};

const page = <T,>(items: T[]): Page<T> => ({ items, total: items.length, page: 1, pageSize: 25 });

const detail = (overrides: Partial<UserDetail> = {}): UserDetail => ({
  ...maya,
  disabledReason: null,
  emailVerified: true,
  timeZoneId: "Europe/London",
  couple: {
    id: "22222222-2222-2222-2222-222222222222",
    status: "active",
    linkedAtUtc: "2026-09-02T10:00:00Z",
    dissolvedAtUtc: null,
    partner: { id: "33333333-3333-3333-3333-333333333333", email: "theo@example.com", displayName: "Theo" },
  },
  plan: "plus",
  complimentary: false,
  devices: [],
  subscriptions: [],
  grants: [],
  activity: { daresCompleted: 4, daresMissed: 1, foodEntries: 9, foodScans: 2, photosShared: 3, photoBytes: 3_000_000, questionsAnswered: 12, activeDays: 20 },
  history: [],
  ...overrides,
});

afterEach(() => act(() => setSession(null)));

describe("the dashboard", () => {
  it("sends a signed-out visitor to sign in, remembering where they were going", async () => {
    const app = start("/users?status=disabled");
    expect(await screen.findByRole("heading", { name: "Sign in" })).toBeTruthy();
    expect(app.router.state.location.pathname).toBe("/sign-in");
    expect(app.router.state.location.search).toEqual({ redirect: "/users?status=disabled&page=1" });
  });

  it("enrolls an authenticator, then lands where the admin was going", async () => {
    const user = userEvent.setup();
    const calls = mockApi((method, path, body) => {
      if (method === "POST" && path === "/api/admin/auth/login") {
        const code = (body as { code?: string }).code;
        if (!code)
          return {
            status: 409,
            body: {
              code: "admin.enrollment_required",
              message: "Scan this",
              secret: "JBSWY3DPEHPK3PXPJBSWY3DP",
              otpauthUri: "otpauth://totp/Heartpact:ops@heartpact.app?secret=JBSWY3DPEHPK3PXPJBSWY3DP",
            },
          };
        if (code === "000000") return { status: 401, body: { code: "admin.invalid_code", message: "That code didn't work. Codes change every 30 seconds." } };
        return { status: 200, body: admin };
      }
      if (method === "GET" && path.startsWith("/api/admin/users?")) return { status: 200, body: page([maya]) };
      return undefined;
    });

    start("/sign-in?redirect=%2Fusers");
    await user.type(await screen.findByLabelText("Email"), "ops@heartpact.app");
    await user.type(screen.getByLabelText("Password"), "longenough");
    await user.click(screen.getByRole("button", { name: "Continue" }));

    expect(await screen.findByRole("heading", { name: "Set up your authenticator" })).toBeTruthy();
    expect(screen.getByLabelText("Authenticator key").textContent).toBe("JBSW Y3DP EHPK 3PXP JBSW Y3DP");

    await user.type(screen.getByLabelText("Code"), "000000");
    await user.click(screen.getByRole("button", { name: "Finish set-up" }));
    expect(await screen.findByText(/Codes change every 30 seconds/)).toBeTruthy();

    await user.type(screen.getByLabelText("Code"), "123 456");
    await user.click(screen.getByRole("button", { name: "Finish set-up" }));

    expect(await screen.findByRole("heading", { name: "Users" })).toBeTruthy();
    expect(await screen.findByText("maya@example.com")).toBeTruthy();
    expect(getSession()?.accessToken).toBe("admin-token");
    // The password goes only to sign-in, the code without its space, and the token on later calls.
    const logins = calls.filter((call) => call.path === "/api/admin/auth/login");
    expect(logins.map((call) => (call.body as { code?: string }).code)).toEqual([undefined, "000000", "123456"]);
    expect(logins.every((call) => call.auth === undefined)).toBe(true);
    expect(calls.find((call) => call.path.startsWith("/api/admin/users?"))?.auth).toBe("Bearer admin-token");
  });

  it("won't disable an account without a reason, and records the one given", async () => {
    const user = userEvent.setup();
    let disabled = false;
    const calls = mockApi((method, path) => {
      if (method === "GET" && path === `/api/admin/users/${maya.id}`)
        return { status: 200, body: detail(disabled ? { disabledAtUtc: "2026-10-11T12:00:00Z", disabledReason: "Chargeback fraud" } : {}) };
      if (method === "POST" && path === `/api/admin/users/${maya.id}/disable`) {
        disabled = true;
        return { status: 204 };
      }
      return undefined;
    });
    act(() => setSession(admin));
    start(`/users/${maya.id}`);

    await user.click(await screen.findByRole("button", { name: "Disable" }));
    const confirm = screen.getByRole("button", { name: "Disable account" }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    await user.type(screen.getByLabelText(/Reason/), "Chargeback fraud");
    expect(confirm.disabled).toBe(false);
    await user.click(confirm);

    expect(await screen.findByText(/Disabled .*: Chargeback fraud/)).toBeTruthy();
    expect(calls.find((call) => call.method === "POST")?.body).toEqual({ reason: "Chargeback fraud" });
    expect(screen.getByText("Account disabled.")).toBeTruthy();
  });

  it("only deletes after the admin types the account's email", async () => {
    const user = userEvent.setup();
    const calls = mockApi((method, path) => {
      if (method === "GET" && path === `/api/admin/users/${maya.id}`) return { status: 200, body: detail() };
      if (method === "POST" && path === `/api/admin/users/${maya.id}/delete`) return { status: 204 };
      if (method === "GET" && path.startsWith("/api/admin/users?")) return { status: 200, body: page([]) };
      return undefined;
    });
    act(() => setSession(admin));
    const app = start(`/users/${maya.id}`);

    await user.click(await screen.findByRole("button", { name: "Delete account" }));
    const confirm = screen.getByRole("button", { name: "Delete forever" }) as HTMLButtonElement;
    await user.type(screen.getByLabelText(/Reason/), "Asked by email, ticket 41");
    await user.type(screen.getByLabelText(/to confirm/), "theo@example.com");
    expect(confirm.disabled).toBe(true);
    await user.clear(screen.getByLabelText(/to confirm/));
    await user.type(screen.getByLabelText(/to confirm/), "MAYA@example.com");
    expect(confirm.disabled).toBe(false);
    await user.click(confirm);

    await waitFor(() => expect(app.router.state.location.pathname).toBe("/users"));
    expect(calls.find((call) => call.method === "POST")?.body).toEqual({ confirmEmail: "MAYA@example.com", reason: "Asked by email, ticket 41" });
  });

  it("signs out when the server stops accepting the token", async () => {
    mockApi((method, path) => (method === "GET" && path.startsWith("/api/admin/analytics/overview") ? { status: 401 } : undefined));
    act(() => setSession(admin));
    const app = start("/");
    expect(await screen.findByRole("heading", { name: "Sign in" })).toBeTruthy();
    expect(app.router.state.location.pathname).toBe("/sign-in");
    expect(getSession()).toBeNull();
  });
});

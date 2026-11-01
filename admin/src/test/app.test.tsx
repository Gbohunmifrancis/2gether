import { createMemoryHistory } from "@tanstack/react-router";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp } from "../app";
import type { AdminSession, BillingCheck, GameInsights, Page, SystemStatus, UserDetail, UserRow } from "../api/types";
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
  admin: { id: "00000000-0000-0000-0000-00000000000a", email: "ops@heartpact.me", displayName: "Ops" },
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
              otpauthUri: "otpauth://totp/Heartpact:ops@heartpact.me?secret=JBSWY3DPEHPK3PXPJBSWY3DP",
            },
          };
        if (code === "000000") return { status: 401, body: { code: "admin.invalid_code", message: "That code didn't work. Codes change every 30 seconds." } };
        return { status: 200, body: admin };
      }
      if (method === "GET" && path.startsWith("/api/admin/users?")) return { status: 200, body: page([maya]) };
      return undefined;
    });

    start("/sign-in?redirect=%2Fusers");
    await user.type(await screen.findByLabelText("Email"), "ops@heartpact.me");
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

  it("asks for a fresh code when the API wants one, then retries with it", async () => {
    const user = userEvent.setup();
    const sent: (string | undefined)[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = new URL(String(input), "http://admin.test");
      const headers = (init?.headers ?? {}) as Record<string, string>;
      if ((init?.method ?? "GET") === "GET" && url.pathname === `/api/admin/users/${maya.id}`)
        return new Response(JSON.stringify(detail()), { status: 200, headers: { "content-type": "application/json" } });
      if (init?.method === "POST" && url.pathname === `/api/admin/users/${maya.id}/internal`) {
        sent.push(headers["x-admin-code"]);
        if (!headers["x-admin-code"]) return new Response(JSON.stringify({ code: "admin.step_up_required", message: "Fresh code, please." }), { status: 403 });
        return new Response(null, { status: 204 });
      }
      // The live feed, alerts and the rest aren't part of this test.
      return new Response(null, { status: 404 });
    });
    act(() => setSession(admin));
    start(`/users/${maya.id}`);

    await user.click(await screen.findByRole("button", { name: "Mark internal" }));
    await user.type(screen.getByLabelText(/Reason/), "QA account");
    await user.click(screen.getAllByRole("button", { name: "Mark internal" }).at(-1)!);

    const code = await screen.findByLabelText("Authenticator code");
    await waitFor(() => expect((code as HTMLInputElement).disabled).toBe(false));
    await user.type(code, "123456");
    await user.click(screen.getByRole("button", { name: "Confirm" }));

    await waitFor(() => expect(sent).toEqual([undefined, "123456"]));
    expect(await screen.findByText("Marked internal.")).toBeTruthy();
  });

  it("switches the Christmas theme on after a confirmation and a fresh code, and shows the saved state", async () => {
    const user = userEvent.setup();
    const puts: { code: string | undefined; body: unknown }[] = [];
    let christmas = false;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = new URL(String(input), "http://admin.test");
      const headers = (init?.headers ?? {}) as Record<string, string>;
      const reply = (status: number, body?: unknown) => new Response(body === undefined ? null : JSON.stringify(body), { status });
      if (url.pathname !== "/api/admin/features") return reply(404);
      if ((init?.method ?? "GET") === "GET") return reply(200, { christmas });
      if (init?.method === "PUT") {
        puts.push({ code: headers["x-admin-code"], body: JSON.parse(String(init.body)) });
        if (!headers["x-admin-code"]) return reply(403, { code: "admin.step_up_required", message: "Fresh code, please." });
        christmas = (JSON.parse(String(init.body)) as { christmas: boolean }).christmas;
        return reply(200, { christmas });
      }
      return reply(405);
    });
    act(() => setSession(admin));
    start("/features");

    expect(await screen.findByRole("heading", { name: "Features" })).toBeTruthy();
    const toggle = await screen.findByRole("switch", { name: "Christmas theme" });
    expect(toggle.getAttribute("aria-checked")).toBe("false");
    expect(screen.getByText("Turns the festive look on for every app.")).toBeTruthy();

    await user.click(toggle);
    await user.click(screen.getByRole("button", { name: "Turn on" }));
    const code = await screen.findByLabelText("Authenticator code");
    await waitFor(() => expect((code as HTMLInputElement).disabled).toBe(false));
    await user.type(code, "123456");
    await user.click(screen.getByRole("button", { name: "Confirm" }));

    await waitFor(() => expect(screen.getByRole("switch", { name: "Christmas theme" }).getAttribute("aria-checked")).toBe("true"));
    expect(puts).toEqual([
      { code: undefined, body: { christmas: true } },
      { code: "123456", body: { christmas: true } },
    ]);
    expect(await screen.findByText("Christmas theme is on.")).toBeTruthy();
  });

  it("switches card payments in the app on its own, leaving the Christmas theme alone", async () => {
    const user = userEvent.setup();
    const puts: unknown[] = [];
    let state = { christmas: true, bachsInApp: false };
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = new URL(String(input), "http://admin.test");
      const headers = (init?.headers ?? {}) as Record<string, string>;
      const reply = (status: number, body?: unknown) => new Response(body === undefined ? null : JSON.stringify(body), { status });
      if (url.pathname !== "/api/admin/features") return reply(404);
      if ((init?.method ?? "GET") === "GET") return reply(200, state);
      if (init?.method === "PUT") {
        // A feature switch no longer wants a fresh authenticator code.
        expect(headers["x-admin-code"]).toBeUndefined();
        const body = JSON.parse(String(init.body)) as Partial<typeof state>;
        puts.push(body);
        state = { ...state, ...body };
        return reply(200, state);
      }
      return reply(405);
    });
    act(() => setSession(admin));
    start("/features");

    const toggle = await screen.findByRole("switch", { name: "Card payments in the app" });
    expect(toggle.getAttribute("aria-checked")).toBe("false");
    expect(screen.getByText(/Google Play's payments policy doesn't allow it/)).toBeTruthy();

    await user.click(toggle);
    expect(await screen.findByText("Turn card payments in the app on?")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Turn on" }));

    await waitFor(() => expect(screen.getByRole("switch", { name: "Card payments in the app" }).getAttribute("aria-checked")).toBe("true"));
    expect(puts).toEqual([{ bachsInApp: true }]);
    expect(screen.getByRole("switch", { name: "Christmas theme" }).getAttribute("aria-checked")).toBe("true");
    expect(await screen.findByText("Card payments in the app are on.")).toBeTruthy();
  });

  it("lists Features in the navigation", async () => {
    mockApi((method, path) => (method === "GET" && path === "/api/admin/features" ? { status: 200, body: { christmas: true } } : undefined));
    act(() => setSession(admin));
    start("/features");

    expect((await screen.findAllByRole("link", { name: /Features/ })).length).toBeGreaterThan(0);
    expect((await screen.findByRole("switch", { name: "Christmas theme" })).getAttribute("aria-checked")).toBe("true");
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

  it("shows the duels' usual winning margin in hearts", async () => {
    const games: GameInsights = {
      days: 30,
      types: [{ type: "skillDuel", started: 3, finished: 2, abandoned: 0, medianMinutes: 40 }],
      skills: [],
      duels: { finished: 2, ties: 1, medianMargin: 0.5 },
    };
    mockApi((method, path) => (method === "GET" && path.startsWith("/api/admin/analytics/games") ? { status: 200, body: games } : undefined));
    act(() => setSession(admin));
    start("/games");

    expect(await screen.findByText("Usual winning margin")).toBeTruthy();
    expect(await screen.findByLabelText("0.5 hearts")).toBeTruthy();
    expect(screen.queryByText(/pts/)).toBeNull();
  });

  it("signs out when the server stops accepting the token", async () => {
    mockApi((method, path) => (method === "GET" && path.startsWith("/api/admin/analytics/overview") ? { status: 401 } : undefined));
    act(() => setSession(admin));
    const app = start("/");
    expect(await screen.findByRole("heading", { name: "Sign in" })).toBeTruthy();
    expect(app.router.state.location.pathname).toBe("/sign-in");
    expect(getSession()).toBeNull();
  });

  it("runs the billing check only on its button, shows the hints and the products, and re-runs for another country", async () => {
    const user = userEvent.setup();
    const system: SystemStatus = {
      nowUtc: "2026-11-01T10:00:00Z",
      features: [],
      pushPending: 0,
      pushSentToday: 0,
      pushFailedToday: 0,
      photosBeingDeleted: 0,
      openGames: 0,
      adminCount: 1,
      tokenHours: 4,
      currency: "USD",
      monthlyPrices: {},
    };
    const check = (country: string): BillingCheck => ({
      checkedAtUtc: "2026-11-01T10:00:00Z",
      country,
      googlePlay: {
        configured: true,
        provider: "googleplay",
        packageName: "app.heartpact",
        productIds: ["heartpact_plus_monthly", "heartpact_plus_yearly"],
        error: null,
        products: [
          {
            productId: "heartpact_plus_monthly",
            found: true,
            archived: false,
            basePlans: [
              {
                basePlanId: "monthly",
                state: "ACTIVE",
                type: "autoRenewing",
                billingPeriod: "P1M",
                regionCount: 2,
                regions: ["GB", "US"],
                closedRegions: [],
                prices: [
                  { region: "GB", price: "GBP 3.99" },
                  { region: "US", price: "USD 4.99" },
                ],
              },
            ],
            offers: [],
            offersError: null,
          },
          { productId: "heartpact_plus_yearly", found: false, archived: false, basePlans: [], offers: [], offersError: null },
        ],
        extraProducts: ["heartpact_plus_yearley"],
      },
      bachs: { configured: false, mode: null, missing: ["Bachs__ApiKey"], bachsInApp: false, products: [] },
      hints: [
        { area: "googlePlay", level: "problem", text: `heartpact_plus_monthly isn't available in ${country === "NG" ? "Nigeria (NG)" : "the US"}.` },
        { area: "bachs", level: "info", text: "Card payments (Bachs) are off until Bachs__ApiKey is set." },
      ],
    });
    const calls = mockApi((method, path) => {
      if (method !== "GET") return undefined;
      if (path === "/api/admin/system") return { status: 200, body: system };
      if (path === "/api/admin/admins") return { status: 200, body: [] };
      const match = /^\/api\/admin\/billing\/check\?country=([A-Z]{2})$/.exec(path);
      return match ? { status: 200, body: check(match[1]!) } : undefined;
    });
    act(() => setSession(admin));
    start("/system");

    const run = await screen.findByRole("button", { name: "Run check" });
    expect(calls.some((call) => call.path.startsWith("/api/admin/billing"))).toBe(false);
    await user.click(run);

    const hints = await screen.findByRole("list", { name: "What to do" });
    expect(hints.textContent).toContain("heartpact_plus_monthly isn't available in Nigeria (NG).");
    expect(hints.textContent).toContain("Card payments (Bachs) are off");
    expect(screen.getAllByRole("img", { name: "On sale in Nigeria: no" })).toHaveLength(2);
    expect(screen.getAllByRole("img", { name: "Exists in Play Console: yes" })).toHaveLength(1);
    expect(screen.getAllByRole("img", { name: "Exists in Play Console: no" })).toHaveLength(1);
    expect(screen.getByText("heartpact_plus_yearley")).toBeTruthy();
    expect(screen.getByText(/US USD 4\.99/)).toBeTruthy();

    await user.selectOptions(screen.getByLabelText("Country"), "US");
    expect(await screen.findByRole("img", { name: "On sale in United States: yes" })).toBeTruthy();
    expect(screen.getByText("USD 4.99")).toBeTruthy();
    expect(calls.filter((call) => call.path.startsWith("/api/admin/billing")).map((call) => call.path)).toEqual([
      "/api/admin/billing/check?country=NG",
      "/api/admin/billing/check?country=US",
    ]);
  });
});

import type { Alert, AuditRow, Features, Grant, LiveEvent, LiveKind, Page, SubscriptionRow, UserDetail, UserRow } from "../api/types";
import * as build from "./analytics";
import { billingCheck } from "./billing";
import { rng, uuid, weighted } from "./random";
import { buildWorld, DAY, iso, LIVE_MIX, liveEvent, NOW, type DemoUser, type DemoWorld } from "./world";

/* Demo mode (VITE_DEMO=1): the dashboard runs with no backend. This replaces fetch for /api/admin/* with an
   in-browser pretend API: realistic data for every endpoint, a live stream with an event every few seconds and the
   odd alert, step-up codes for destructive actions, and any email, password and six-digit code to sign in
   (000000 is always refused, to see the error). */

type Json = Record<string, unknown>;

const json = (status: number, body?: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const error = (status: number, code: string, message: string) => json(status, { code, message });

const STEP_UP = new Set(["disable", "delete", "refund", "revoke", "cancel", "reset-authenticator"]);
/** One code covers this long of such actions, as the real API's `StepUpGrace`. */
const STEP_UP_GRACE_MS = 5 * 60_000;

interface Stream {
  push: (event: LiveEvent) => void;
}

class DemoServer {
  readonly world: DemoWorld = buildWorld();
  private readonly random = rng(99);
  private readonly streams = new Set<Stream>();
  private readonly raised: Alert[] = [];
  private lateWorker = true;
  readonly counters: build.PulseCounters = { onlineDrift: 0, ringing: 7, onCamera: 4, openGames: 58, hearts: 1287, signups: 63, linked: 21, plus: 5 };
  private timer: ReturnType<typeof setTimeout> | null = null;
  private lastAlertAt = 0;
  private wrongCodes = 0;
  /** When a code was last accepted (the five minutes it covers). */
  private steppedUpAt = 0;
  private paused = false;
  private features: Features = { christmas: false, bachsInApp: false };

  /** Starts the pretend world ticking: an event every few seconds. */
  pause() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.paused = true;
  }

  start() {
    const next = () => {
      if (this.paused) return;
      // A quick first event so a fresh page has something arriving.
      const delay = this.timer === null ? 1800 : 2500 + this.random() * 4000;
      this.timer = setTimeout(() => {
        let kind = weighted(this.random, LIVE_MIX);
        if (kind === "alert" && Date.now() - this.lastAlertAt < 60_000) kind = "dareCompleted";
        this.emit(kind);
        next();
      }, delay);
    };
    next();
  }

  emit(kind: LiveKind): LiveEvent {
    const event = liveEvent(kind, this.world.users, this.world.couples, this.random);
    this.world.events.unshift(event);
    this.world.events.length = Math.min(this.world.events.length, 80);
    const c = this.counters;
    if (kind === "dareCompleted") {
      c.hearts++;
      c.onCamera = Math.max(1, c.onCamera + (this.random() < 0.5 ? -1 : 1));
    }
    if (kind === "signedUp") c.signups++;
    if (kind === "coupleLinked") c.linked++;
    if (kind === "plusStarted") c.plus++;
    if (kind === "gameFinished") c.openGames = Math.max(20, c.openGames - 1);
    c.ringing = Math.max(0, Math.min(14, c.ringing + Math.round((this.random() - 0.5) * 3)));
    c.onlineDrift = Math.max(-6, Math.min(9, c.onlineDrift + Math.round((this.random() - 0.48) * 3)));
    if (kind === "alert") {
      this.lastAlertAt = Date.now();
      this.raised.unshift({
        key: `demo:${event.id}`,
        severity: event.severity === "bad" ? "bad" : "warn",
        title: event.detail ?? "Something needs a look",
        detail: "Raised by the live checks a moment ago. It quietens for 60 minutes once raised.",
        sinceUtc: event.atUtc,
        link: event.detail?.startsWith("Payment") ? "/payments?status=onHold" : event.detail?.startsWith("Push") ? "/health" : null,
      });
      this.raised.length = Math.min(this.raised.length, 4);
    }
    this.streams.forEach((stream) => stream.push(event));
    return event;
  }

  private stream(signal: AbortSignal | null | undefined): Response {
    const encoder = new TextEncoder();
    let stream: Stream | null = null;
    let ping: ReturnType<typeof setInterval> | null = null;
    const body = new ReadableStream<Uint8Array>({
      start: (controller) => {
        const write = (text: string) => {
          try {
            controller.enqueue(encoder.encode(text));
          } catch {
            // Closed.
          }
        };
        stream = { push: (event) => write(`event: live\ndata: ${JSON.stringify(event)}\n\n`) };
        this.streams.add(stream);
        write(": demo stream\n\n");
        ping = setInterval(() => write("event: ping\ndata: {}\n\n"), 20_000);
        signal?.addEventListener("abort", () => {
          if (stream) this.streams.delete(stream);
          if (ping) clearInterval(ping);
          try {
            controller.close();
          } catch {
            // Already closed.
          }
        });
      },
      cancel: () => {
        if (stream) this.streams.delete(stream);
        if (ping) clearInterval(ping);
      },
    });
    return new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } });
  }

  private audit(action: string, targetType: string | null, targetId: string | null, details: string | null) {
    const row: AuditRow = {
      id: uuid(this.random),
      adminEmail: this.world.admin.email,
      action,
      targetType,
      targetId,
      details,
      ipAddress: "203.0.113.7",
      succeeded: true,
      createdAtUtc: iso(Date.now()),
    };
    this.world.audit.unshift(row);
  }

  private row(user: DemoUser): UserRow {
    return {
      id: user.id,
      email: user.email,
      displayName: user.displayName,
      createdAtUtc: user.createdAtUtc,
      lastActiveAtUtc: user.lastActiveAtUtc,
      disabledAtUtc: user.disabledAtUtc,
      google: user.google,
      password: user.password,
      coupleStatus: user.coupleStatus,
      paying: user.paying,
      internal: user.internal,
    };
  }

  private detail(user: DemoUser): UserDetail {
    const random = rng(user.id.charCodeAt(0) * 31 + user.id.charCodeAt(3));
    const partner = user.partnerId ? this.world.byId.get(user.partnerId) : undefined;
    const couple = user.coupleId ? this.world.coupleById.get(user.coupleId) : undefined;
    const days = Math.max(1, (NOW - Date.parse(user.createdAtUtc)) / DAY);
    const active = Math.round(days * user.zest * 0.8);
    const granted = Boolean(
      couple && this.world.grants.some((grant) => grant.coupleId === couple.id && !grant.revokedAtUtc && Date.parse(grant.expiresAtUtc) > Date.now()),
    );
    const done = Math.round(active * 0.9);
    return {
      ...this.row(user),
      disabledReason: user.disabledReason,
      emailVerified: user.emailVerified,
      timeZoneId: user.timeZoneId,
      couple: couple
        ? {
            id: couple.id,
            status: couple.status,
            linkedAtUtc: couple.linkedAtUtc,
            dissolvedAtUtc: couple.dissolvedAtUtc,
            partner: partner ? { id: partner.id, email: partner.email, displayName: partner.displayName } : null,
          }
        : null,
      plan: couple?.plus || granted ? "plus" : "free",
      complimentary: granted,
      devices: [
        { platform: "android", appVersion: "1.6.0", lastSeenAtUtc: user.lastActiveAtUtc ?? user.createdAtUtc, active: true },
        ...(random() < 0.25 ? [{ platform: "android", appVersion: "1.4.0", lastSeenAtUtc: iso(NOW - 90 * DAY), active: false }] : []),
      ],
      subscriptions: this.world.subscriptions.filter((row) => row.userId === user.id),
      grants: couple ? this.world.grants.filter((grant) => grant.coupleId === couple.id) : [],
      activity: {
        daresCompleted: done,
        daresMissed: Math.round(active * 0.14),
        foodEntries: Math.round(active * 1.7),
        foodScans: Math.round(active * 0.6),
        photosShared: Math.round(active * 0.8),
        photoBytes: Math.round(active * 0.8 * 1_900_000),
        questionsAnswered: Math.round(active * 0.85),
        activeDays: active,
      },
      history: this.world.audit.filter((row) => row.targetId === user.id).slice(0, 20),
    };
  }

  private page<T>(items: T[], params: URLSearchParams, size: number): Page<T> {
    const page = Math.max(1, Number(params.get("page") ?? 1));
    const pageSize = Number(params.get("pageSize") ?? size);
    return { items: items.slice((page - 1) * pageSize, page * pageSize), total: items.length, page, pageSize };
  }

  handle(method: string, path: string, params: URLSearchParams, body: Json, headers: Headers, signal?: AbortSignal | null): Response {
    const w = this.world;
    const days = Number(params.get("days") ?? 30) || 30;
    const parts = path.split("/").filter(Boolean);

    // Step-up: destructive actions want a code, like the real API; one covers the next five minutes.
    if (method === "POST" && STEP_UP.has(parts[parts.length - 1]!) && Date.now() - this.steppedUpAt >= STEP_UP_GRACE_MS) {
      const code = headers.get("x-admin-code");
      if (!code) return error(403, "admin.step_up_required", "Enter a fresh code from your authenticator to do this.");
      if (this.wrongCodes >= 5) return error(429, "admin.step_up_locked", "Too many tries. Wait a few minutes and try again.");
      if (!/^\d{6}$/.test(code) || code === "000000") {
        this.wrongCodes++;
        return error(403, "admin.step_up_invalid", "That code didn't work. Codes change every 30 seconds.");
      }
      this.wrongCodes = 0;
      this.steppedUpAt = Date.now();
    }

    if (method === "POST" && path === "auth/login") {
      const code = String(body.code ?? "");
      if (!body.email || !body.password) return error(401, "admin.invalid_credentials", "Email or password is incorrect.");
      if (!code) return error(401, "admin.code_required", "Enter the code from your authenticator app.");
      if (code === "000000") return error(401, "admin.invalid_code", "That code didn't work. Codes change every 30 seconds.");
      return json(200, { accessToken: "demo-token", expiresAtUtc: iso(Date.now() + 7 * 24 * 3_600_000), admin: w.admin });
    }
    if (path === "auth/me") return json(200, w.admin);

    switch (path) {
      case "analytics/overview":
        return json(200, build.overview(w, days));
      case "analytics/engagement":
        return json(200, build.engagement(w, days));
      case "analytics/revenue":
        return json(200, build.revenue(w, days));
      case "analytics/pulse":
        return json(200, build.pulse(w, this.counters));
      case "analytics/live/events":
        return this.stream(signal);
      case "analytics/funnel":
        return json(200, build.funnel(days));
      case "analytics/retention":
        return json(200, build.retention(Number(params.get("weeks") ?? 12) || 12));
      case "analytics/rhythm":
        return json(200, build.rhythm(days));
      case "analytics/dares":
        return json(200, build.dares(days));
      case "analytics/games":
        return json(200, build.games(days));
      case "analytics/growth":
        return json(200, build.growth(w, Number(params.get("days") ?? 90) || 90));
      case "analytics/world":
        return json(200, build.world(w));
      case "analytics/couples/top":
        return json(200, build.topCouples(w, days, Number(params.get("limit") ?? 12) || 12));
      case "system/health":
        return json(200, build.health(this.lateWorker));
      case "alerts":
        return json(200, build.alerts(this.raised, this.lateWorker));
      case "system":
        return json(200, build.system(w));
      case "admins":
        return json(200, w.admins);
      case "billing/check": {
        const check = billingCheck(params.get("country"), this.features.bachsInApp);
        const problems = check.hints.filter((hint) => hint.level === "problem").length;
        this.audit("billing.checked", "billing", null, `${check.country}: ${problems} problem(s)`);
        return json(200, check);
      }
      case "features":
        if (method === "PUT") {
          const named = (["christmas", "bachsInApp"] as const).filter((key) => typeof body[key] === "boolean");
          if (named.length === 0) return error(422, "admin.features_invalid", "Say which switch to turn on or off.");
          for (const key of named) {
            const next = body[key] as boolean;
            this.audit("features.updated", "feature", key, `${key}: ${this.features[key] ? "on" : "off"} -> ${next ? "on" : "off"}`);
            this.features = { ...this.features, [key]: next };
          }
        }
        return json(200, this.features);
    }

    if (path === "users" && method === "GET") {
      const query = (params.get("query") ?? "").trim().toLowerCase();
      const status = params.get("status");
      const items = w.users.filter((user) => {
        if (query && !(user.email.includes(query) || user.displayName.toLowerCase().includes(query) || user.id === query || user.coupleId === query))
          return false;
        if (status === "active" && user.disabledAtUtc) return false;
        if (status === "disabled" && !user.disabledAtUtc) return false;
        if (status === "unlinked" && user.coupleStatus === "active") return false;
        if (status === "internal" && !user.internal) return false;
        return true;
      });
      return json(
        200,
        this.page(
          items.map((user) => this.row(user)),
          params,
          25,
        ),
      );
    }

    if (parts[0] === "users" && parts[1]) {
      const user = w.byId.get(parts[1]);
      if (!user) return error(404, "admin.not_found", "That no longer exists.");
      const action = parts[2];
      if (!action && method === "GET") {
        this.audit("user.viewed", "user", user.id, null);
        return json(200, this.detail(user));
      }
      if (action === "journey") return json(200, build.journey(w, user, days === 30 && !params.get("days") ? 90 : days));
      if (method !== "POST") return error(405, "http.405", "Not here.");
      if (user.id === w.admin.id && (action === "disable" || action === "delete"))
        return error(409, "admin.self", "You can't do that to your own account here.");
      switch (action) {
        case "disable":
          user.disabledAtUtc = iso(Date.now());
          user.disabledReason = String(body.reason ?? "");
          this.audit("user.disabled", "user", user.id, user.disabledReason);
          return json(204);
        case "enable":
          user.disabledAtUtc = null;
          user.disabledReason = null;
          this.audit("user.enabled", "user", user.id, null);
          return json(204);
        case "sign-out":
          this.audit("user.signed_out", "user", user.id, null);
          return json(204);
        case "password-reset":
          this.audit("user.reset_sent", "user", user.id, null);
          return json(204);
        case "internal":
          user.internal = Boolean(body.internal);
          this.audit(user.internal ? "user.marked_internal" : "user.unmarked_internal", "user", user.id, String(body.reason ?? ""));
          return json(204);
        case "delete": {
          if (String(body.confirmEmail ?? "").toLowerCase() !== user.email.toLowerCase())
            return error(400, "admin.confirm_email", "Type the account's email to confirm.");
          w.users.splice(w.users.indexOf(user), 1);
          w.byId.delete(user.id);
          this.audit("user.deleted", "user", user.id, String(body.reason ?? ""));
          return json(204);
        }
      }
    }

    if (path === "subscriptions" && method === "GET") {
      const query = (params.get("query") ?? "").toLowerCase();
      const status = params.get("status");
      const items = w.subscriptions
        .filter((row) => (!query || (row.buyerEmail ?? "").includes(query) || row.productId.includes(query)) && (!status || row.status === status))
        .sort((a, b) => Date.parse(b.createdAtUtc) - Date.parse(a.createdAtUtc));
      return json(200, this.page(items, params, 25));
    }
    if (parts[0] === "subscriptions" && parts[1] && method === "POST") {
      const row = w.subscriptions.find((item) => item.id === parts[1]);
      if (!row) return error(404, "admin.not_found", "That no longer exists.");
      const update = (patch: Partial<SubscriptionRow>, action: string) => {
        Object.assign(row, patch, { lastVerifiedAtUtc: iso(Date.now()) });
        this.audit(action, "subscription", row.id, body.reason ? String(body.reason) : null);
        return json(200, row);
      };
      if (parts[2] === "sync") return update({}, "subscription.synced");
      if (parts[2] === "cancel") return update({ autoRenewing: false, status: "canceled" }, "subscription.canceled");
      if (parts[2] === "refund") return update({}, "subscription.refunded");
      if (parts[2] === "revoke") return update({ autoRenewing: false, status: "expired", expiresAtUtc: iso(Date.now()) }, "subscription.revoked");
    }

    if (path === "grants") {
      const activeOnly = params.get("activeOnly") === "true";
      return json(
        200,
        w.grants.filter((grant) => !activeOnly || (!grant.revokedAtUtc && Date.parse(grant.expiresAtUtc) > Date.now())),
      );
    }
    if (parts[0] === "grants" && parts[2] === "revoke") {
      const grant = w.grants.find((item) => item.id === parts[1]);
      if (grant) grant.revokedAtUtc = iso(Date.now());
      this.audit("plus.revoked", "couple", grant?.coupleId ?? null, null);
      return json(204);
    }
    if (parts[0] === "couples" && parts[2] === "grants" && method === "POST") {
      const daysOfPlus = Number(body.days ?? 30);
      const grant: Grant = {
        id: uuid(this.random),
        coupleId: parts[1]!,
        createdAtUtc: iso(Date.now()),
        expiresAtUtc: iso(Date.now() + daysOfPlus * DAY),
        reason: String(body.reason ?? ""),
        revokedAtUtc: null,
      };
      w.grants.unshift(grant);
      const couple = w.coupleById.get(parts[1]!);
      if (couple) couple.plus = true;
      this.audit("plus.granted", "couple", parts[1]!, `${daysOfPlus} days: ${grant.reason}`);
      return json(200, grant);
    }

    if (path === "audit") {
      const query = (params.get("query") ?? "").toLowerCase();
      const action = params.get("action");
      const items = w.audit.filter(
        (row) =>
          (!query || row.adminEmail.includes(query) || (row.targetId ?? "").includes(query)) &&
          (!action || (action.endsWith(".") ? row.action.startsWith(action) : row.action === action)),
      );
      return json(200, this.page(items, params, 50));
    }

    if (parts[0] === "admins" && parts[2] === "reset-authenticator") {
      const row = w.admins.find((item) => item.userId === parts[1]);
      if (row) row.authenticatorEnrolledAtUtc = null;
      this.audit("admin.authenticator_reset", "user", parts[1]!, null);
      return json(204);
    }

    return error(404, "http.404", `The demo has no ${method} /api/admin/${path}.`);
  }
}

/** Swaps in the pretend API. Exposes `window.heartpactDemo.emit(kind)` to fire a live event on demand. */
export function installDemo() {
  const server = new DemoServer();
  server.start();
  const realFetch = globalThis.fetch.bind(globalThis);
  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, location.origin);
    const prefix = "/api/admin/";
    const at = url.pathname.indexOf(prefix);
    if (at < 0) return realFetch(input, init);
    const path = url.pathname.slice(at + prefix.length).replace(/\/$/, "");
    const method = (init?.method ?? "GET").toUpperCase();
    let body: Json = {};
    if (typeof init?.body === "string") {
      try {
        body = JSON.parse(init.body) as Json;
      } catch {
        body = {};
      }
    }
    // A little latency, so loading states are real but brief.
    if (path !== "analytics/live/events") await new Promise((resolve) => setTimeout(resolve, 120 + Math.random() * 220));
    if (init?.signal?.aborted) throw new DOMException("Aborted", "AbortError");
    return server.handle(method, path, url.searchParams, body, new Headers(init?.headers), init?.signal);
  };
  (globalThis as { heartpactDemo?: unknown }).heartpactDemo = {
    emit: (kind: LiveKind) => server.emit(kind),
    // Screenshots hold the random events still, to catch one moment at a time.
    pause: () => server.pause(),
    server,
  };
}

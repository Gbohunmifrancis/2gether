import type { SearchSchemaInput } from "@tanstack/react-router";
import type { UserStatusFilter } from "../api/queries";

/** Every param is optional on the way in (links may omit them); the parsers fill defaults. */
type Input = Record<string, unknown> & SearchSchemaInput;

// Search-param parsers for the routes: anything odd in the URL falls back to a default, never throws.

export const RANGES = [7, 30, 90, 365] as const;
export type Range = (typeof RANGES)[number];

const text = (value: unknown, max = 200): string | undefined => {
  if (typeof value === "number") value = String(value);
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim().slice(0, max);
  return trimmed || undefined;
};

const page = (value: unknown): number => {
  const parsed = typeof value === "number" ? value : Number.parseInt(String(value ?? ""), 10);
  return Number.isInteger(parsed) && parsed >= 1 && parsed <= 100_000 ? parsed : 1;
};

export function rangeSearch(search: Input): { days: Range } {
  const days = Number(search.days);
  return { days: (RANGES as readonly number[]).includes(days) ? (days as Range) : 30 };
}

export function usersSearch(search: Input): { query?: string; status?: UserStatusFilter; page: number } {
  const status = search.status === "active" || search.status === "disabled" || search.status === "unlinked" ? search.status : undefined;
  return { query: text(search.query), status, page: page(search.page) };
}

export const SUBSCRIPTION_STATUSES = ["active", "canceled", "inGracePeriod", "onHold", "paused", "expired", "pending", "pendingPurchaseCanceled"] as const;

export function paymentsSearch(search: Input): { tab: "subscriptions" | "grants"; query?: string; status?: string; page: number; all?: boolean } {
  const status = (SUBSCRIPTION_STATUSES as readonly string[]).includes(String(search.status)) ? String(search.status) : undefined;
  return {
    tab: search.tab === "grants" ? "grants" : "subscriptions",
    query: text(search.query),
    status,
    page: page(search.page),
    all: search.all === true || search.all === "true" ? true : undefined,
  };
}

export function auditSearch(search: Input): { query?: string; action?: string; page: number } {
  const action = text(search.action, 64);
  return { query: text(search.query), action: action && /^[a-z_.]+$/.test(action) ? action : undefined, page: page(search.page) };
}

export function signInSearch(search: Input): { redirect?: string } {
  const redirect = text(search.redirect, 500);
  // Only same-app paths: never bounce a fresh admin token to another origin.
  return { redirect: redirect && /^\/(?![/\\])/.test(redirect) && !redirect.includes("\\") && !redirect.startsWith("/sign-in") ? redirect : undefined };
}

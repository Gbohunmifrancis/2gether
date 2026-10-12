import { describe, expect, it } from "vitest";
import { auditSearch, paymentsSearch, rangeSearch, signInSearch, usersSearch } from "./search";

/** Raw URL params, as the router hands them over. */
const input = (params: Record<string, unknown>) => params as Parameters<typeof rangeSearch>[0];

describe("search params", () => {
  it("falls back to defaults on nonsense", () => {
    expect(rangeSearch(input({}))).toEqual({ days: 30 });
    expect(rangeSearch(input({ days: "90" }))).toEqual({ days: 90 });
    expect(rangeSearch(input({ days: 45 }))).toEqual({ days: 30 });
    expect(usersSearch(input({ status: "root", page: "-3", query: "   " }))).toEqual({ query: undefined, status: undefined, page: 1 });
    expect(usersSearch(input({ status: "disabled", page: 4, query: " ada " }))).toEqual({ query: "ada", status: "disabled", page: 4 });
    expect(paymentsSearch(input({ tab: "grants", status: "bogus", all: "true" }))).toMatchObject({ tab: "grants", status: undefined, all: true });
    expect(paymentsSearch(input({ status: "inGracePeriod" }))).toMatchObject({ tab: "subscriptions", status: "inGracePeriod" });
    expect(auditSearch(input({ action: "user.'; drop" }))).toMatchObject({ action: undefined });
    expect(auditSearch(input({ action: "subscription." }))).toMatchObject({ action: "subscription." });
  });

  it("only redirects within the dashboard after sign-in", () => {
    expect(signInSearch(input({ redirect: "/users?page=2" }))).toEqual({ redirect: "/users?page=2" });
    expect(signInSearch(input({ redirect: "https://evil.example" }))).toEqual({ redirect: undefined });
    expect(signInSearch(input({ redirect: "//evil.example" }))).toEqual({ redirect: undefined });
    expect(signInSearch(input({ redirect: "/\\evil.example" }))).toEqual({ redirect: undefined });
    expect(signInSearch(input({ redirect: "/sign-in" }))).toEqual({ redirect: undefined });
  });
});

import { describe, expect, it } from "vitest";
import { actionLabel, ago, bytes, compact, count, humanize, money, percent, plural, productLabel, shortDay } from "./format";

describe("format", () => {
  it("counts with separators and compacts only big numbers", () => {
    expect(count(1234567)).toBe("1,234,567");
    expect(compact(9999)).toBe("9,999");
    expect(compact(12_500)).toBe("12.5K");
    expect(compact(340_000)).toBe("340K");
    expect(compact(1_200_000)).toBe("1.2M");
    expect(compact(20_000)).toBe("20K");
  });

  it("formats money, percent and bytes", () => {
    expect(money(4.99, "GBP")).toBe("£4.99");
    expect(money(10, "NOPE")).toBe("10.00 NOPE");
    expect(percent(0.756)).toBe("76%");
    expect(percent(Number.NaN)).toBe("–");
    expect(bytes(512)).toBe("512 B");
    expect(bytes(1536)).toBe("1.5 KB");
    expect(bytes(2147483648)).toBe("2.0 GB");
  });

  it("says how long ago", () => {
    const now = Date.parse("2026-10-11T12:00:00Z");
    expect(ago(null, now)).toBe("never");
    expect(ago("2026-10-11T11:59:30Z", now)).toBe("just now");
    expect(ago("2026-10-11T11:55:00Z", now)).toBe("5 min ago");
    expect(ago("2026-10-11T09:00:00Z", now)).toBe("3 h ago");
    expect(ago("2026-10-10T12:00:00Z", now)).toBe("1 day ago");
    expect(ago("2026-10-01T12:00:00Z", now)).toBe("10 days ago");
  });

  it("reads enums and ids as words", () => {
    expect(humanize("inGracePeriod")).toBe("Grace period");
    expect(humanize("pushUps")).toBe("Push ups");
    expect(humanize("memory_match")).toBe("Memory match");
    expect(productLabel("heartpact_plus_monthly")).toBe("Plus monthly");
    expect(actionLabel("subscription.revoked")).toBe("Revoked subscription");
    expect(actionLabel("something.new")).toBe("Something new");
    expect(plural(1, "couple")).toBe("1 couple");
    expect(plural(3, "couple")).toBe("3 couples");
    expect(shortDay("2026-03-01")).toBe("1 Mar");
  });
});

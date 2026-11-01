import type { CoupleStatus, SubscriptionStatus } from "../api/types";

const numberFormat = new Intl.NumberFormat("en-GB");

export const count = (value: number) => numberFormat.format(value);
/** 9,999 · 12.5K · 340K · 1.2M: written out here because Intl's compact style varies with the ICU data ("k" or "K"). */
export function compact(value: number): string {
  const size = Math.abs(value);
  if (size < 10_000) return numberFormat.format(value);
  const [divisor, suffix] = size < 1e6 ? [1e3, "K"] : size < 1e9 ? [1e6, "M"] : [1e9, "B"];
  const scaled = value / divisor;
  return `${Math.abs(scaled) < 100 ? Number(scaled.toFixed(1)) : Math.round(scaled)}${suffix}`;
}

export function percent(ratio: number, digits = 0): string {
  if (!Number.isFinite(ratio)) return "–";
  return `${(ratio * 100).toFixed(digits)}%`;
}

export function money(value: number, currency: string): string {
  try {
    return new Intl.NumberFormat("en-GB", { style: "currency", currency, maximumFractionDigits: 2 }).format(value);
  } catch {
    return `${value.toFixed(2)} ${currency}`;
  }
}

export function bytes(value: number): string {
  if (value < 1024) return `${value} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let size = value / 1024;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return `${size < 10 ? size.toFixed(1) : Math.round(size)} ${units[unit]}`;
}

const dateFormat = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric" });
const dateTimeFormat = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
const shortDayFormat = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: "UTC" });

export const date = (iso: string | null | undefined) => (iso ? dateFormat.format(new Date(iso)) : "–");
export const dateTime = (iso: string | null | undefined) => (iso ? dateTimeFormat.format(new Date(iso)) : "–");
/** Chart axis days come as "yyyy-MM-dd" in UTC. */
export function shortDay(day: string): string {
  const parsed = new Date(`${day}T00:00:00Z`);
  return Number.isNaN(parsed.getTime()) ? day : shortDayFormat.format(parsed);
}

/** "just now", "5 min ago", "3 h ago", "2 days ago", then the date. */
export function ago(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return "never";
  const seconds = Math.round((now - Date.parse(iso)) / 1000);
  if (seconds < 0) return "just now";
  if (seconds < 60) return "just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days} day${days === 1 ? "" : "s"} ago`;
  return date(iso);
}

export function plural(value: number, one: string, many = `${one}s`): string {
  return `${count(value)} ${value === 1 ? one : many}`;
}

/** A Heart to heart margin: hearts, to one decimal (a median can fall between two duels). */
export const hearts = (value: number) => plural(Math.round(value * 10) / 10, "heart");

export const coupleStatusLabel: Record<CoupleStatus, string> = {
  pendingInvite: "Invite pending",
  active: "Linked",
  dissolved: "Unlinked",
};

export const subscriptionStatusLabel: Record<SubscriptionStatus, string> = {
  pending: "Pending",
  active: "Active",
  canceled: "Canceled",
  inGracePeriod: "Grace period",
  onHold: "On hold",
  paused: "Paused",
  expired: "Expired",
  pendingPurchaseCanceled: "Never paid",
};

/** Store enums arrive as camelCase strings; analytics group by them too. */
export function humanize(name: string): string {
  const known = subscriptionStatusLabel[name as SubscriptionStatus];
  if (known) return known;
  const spaced = name.replace(/[_.-]+/g, " ").replace(/([a-z])([A-Z])/g, "$1 $2");
  return spaced.charAt(0).toUpperCase() + spaced.slice(1).toLowerCase();
}

/** "heartpact_plus_monthly" → "Plus monthly". */
export function productLabel(productId: string): string {
  return humanize(productId.replace(/^heartpact_/, ""));
}

/** Audit actions read as sentences: "user.disabled" → "Disabled user". */
const actionLabels: Record<string, string> = {
  "admin.enrolled": "Set up authenticator",
  "admin.signed_in": "Signed in",
  "admin.sign_in_failed": "Failed sign-in",
  "admin.authenticator_reset": "Reset an admin's authenticator",
  "user.viewed": "Viewed user",
  "user.disabled": "Disabled user",
  "user.enabled": "Re-enabled user",
  "user.signed_out": "Signed user out everywhere",
  "user.reset_sent": "Sent password reset",
  "user.deleted": "Deleted account",
  "subscription.synced": "Synced subscription",
  "subscription.canceled": "Canceled subscription",
  "subscription.refunded": "Refunded payment",
  "subscription.revoked": "Revoked subscription",
  "plus.granted": "Granted Plus",
  "plus.revoked": "Revoked Plus grant",
  "features.updated": "Changed a feature",
  "billing.checked": "Ran the billing check",
};

export const actionLabel = (action: string) => actionLabels[action] ?? humanize(action);

/** Failed sign-ins record the API's error code as their detail. */
const detailLabels: Record<string, string> = {
  "admin.invalid_credentials": "Wrong email or password, or not an admin",
  "admin.invalid_code": "Wrong or reused authenticator code",
};
export const auditDetail = (details: string) => detailLabels[details] ?? details;
export const auditActions = Object.keys(actionLabels);

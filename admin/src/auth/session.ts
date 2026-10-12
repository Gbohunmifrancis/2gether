import type { AdminSession } from "../api/types";

// The admin token lives for this tab only: sessionStorage, never localStorage, so closing the tab
// signs out and nothing outlives the browser session. It expires on the server after a few hours anyway.
const KEY = "heartpact.admin.session";

type Listener = (session: AdminSession | null) => void;
const listeners = new Set<Listener>();
let current: AdminSession | null = read();
let expiryTimer: ReturnType<typeof setTimeout> | undefined;

function read(): AdminSession | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as AdminSession;
    return isLive(session) ? session : null;
  } catch {
    return null;
  }
}

export function isLive(session: AdminSession, now = Date.now()): boolean {
  return typeof session.accessToken === "string" && Date.parse(session.expiresAtUtc) > now;
}

function schedule() {
  clearTimeout(expiryTimer);
  if (!current) return;
  // Timers clamp at ~24.8 days; admin tokens last hours, so this always fits.
  expiryTimer = setTimeout(() => setSession(null), Math.max(0, Date.parse(current.expiresAtUtc) - Date.now()));
}

export function getSession(): AdminSession | null {
  if (current && !isLive(current)) setSession(null);
  return current;
}

export function setSession(session: AdminSession | null) {
  current = session;
  try {
    if (session) sessionStorage.setItem(KEY, JSON.stringify(session));
    else sessionStorage.removeItem(KEY);
  } catch {
    // Storage blocked: the session still works in memory for this page.
  }
  schedule();
  listeners.forEach((listener) => listener(session));
}

export function onSessionChange(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

schedule();

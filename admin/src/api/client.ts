import { getSession, setSession } from "../auth/session";

/** A failed call, carrying the API's error code and message (or a generic one). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly body: unknown;

  constructor(status: number, code: string, message: string, body: unknown = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

const BASE = (import.meta.env.VITE_API_URL as string | undefined)?.replace(/\/$/, "") ?? "";

function messageFor(status: number): string {
  if (status === 0) return "Can't reach the server. Check your connection.";
  if (status === 403) return "You don't have access to that.";
  if (status === 404) return "That no longer exists.";
  if (status === 429) return "Too many attempts. Wait a few minutes and try again.";
  if (status >= 500) return "The server had a problem. Try again in a moment.";
  return "Something went wrong.";
}

/** Reads `{code, message}` from our errors, `{title, errors}` from ASP.NET validation problems. */
export function toApiError(status: number, body: unknown): ApiError {
  if (body && typeof body === "object") {
    const record = body as Record<string, unknown>;
    if (typeof record.code === "string") {
      return new ApiError(status, record.code, typeof record.message === "string" ? record.message : messageFor(status), body);
    }
    if (record.errors && typeof record.errors === "object") {
      const first = Object.values(record.errors as Record<string, unknown>).flat()[0];
      if (typeof first === "string") return new ApiError(status, "validation", first, body);
    }
  }
  return new ApiError(status, `http.${status}`, messageFor(status), body);
}

export interface RequestOptions {
  method?: "GET" | "POST";
  body?: unknown;
  /** Sign-in calls send no token and must not sign out on 401. */
  anonymous?: boolean;
  signal?: AbortSignal;
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { accept: "application/json" };
  if (options.body !== undefined) headers["content-type"] = "application/json";
  const session = options.anonymous ? null : getSession();
  if (session) headers.authorization = `Bearer ${session.accessToken}`;

  let response: Response;
  try {
    response = await fetch(BASE + path, {
      method: options.method ?? "GET",
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
      credentials: "omit",
      cache: "no-store",
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiError(0, "network", messageFor(0));
  }

  const text = await response.text();
  let body: unknown = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  if (!response.ok) {
    // An expired, revoked or de-listed admin: drop the token so the router sends them to sign in.
    if (response.status === 401 && !options.anonymous) setSession(null);
    throw toApiError(response.status, body);
  }
  return body as T;
}

/** Builds a query string from the defined, non-empty values. */
export function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "") continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

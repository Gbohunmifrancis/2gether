import { HubConnectionBuilder, LogLevel, type HubConnection } from "@microsoft/signalr";

export type DashboardGame = { id: string; title: string; subtitle: string; icon: "question" | "sparkles" | "heart"; tone: "rose" | "violet" | "cyan" };
export type DashboardSnapshot = { generatedAtUtc: string; currentUserName: string; partnerName: string; partnerOnline: boolean; notificationCount: number; connectionStreakDays: number; cycleSummary: string; cycleDaysUntilPeriod: number; heroDescription: string; latestNote: string; latestNoteAuthor: string; games: DashboardGame[] };
export type Gender = "female" | "male" | "nonBinary" | "preferNotToSay";
export type AuthUser = { id: string; email: string; displayName: string; coupleId: string | null; timeZoneId: string; avatarUrl: string | null; mapColor: string; cycleOwner: boolean; gender: Gender };
export type AuthResponse = { accessToken: string; user: AuthUser };
export type CycleProfile = { userId: string; averageCycleLengthDays: number; averagePeriodLengthDays: number; shareLevel: string; lastPeriodStartDate: string | null };
export type CycleLog = { logDate: string; flowLevel: string; symptoms: string[]; mood: string | null; hadSex: boolean | null; protectionUsed: boolean | null; notes: string | null };
export type CyclePrediction = { nextPeriodStart: string | null; fertileWindowStart: string | null; periodEnd: string | null };
export type SharedCycle = { profile: CycleProfile | null; prediction: CyclePrediction | null; logs: CycleLog[] };
export type GameSession = { id: string; coupleId: string; gameType: string; status: string; startedAtUtc: string | null; endedAtUtc: string | null; currentTurnUserId: string | null; deadlineUtc: string | null; stateVersion: number; stateJson: string; invitedByUserId: string | null; inviteAcceptedAtUtc: string | null; countdownEndsAtUtc: string | null; endedByUserId: string | null; setupJson: string };
export type Message = { id: string; coupleId: string; senderUserId: string; body: string; isPrivate: boolean; createdAtUtc: string; editedAtUtc: string | null };
export type Couple = { id: string; status: string; linkedAtUtc: string | null; members: { userId: string; displayName: string; avatarUrl: string | null }[] };
export type PartnerInvite = { id: string; code: string; token: string; expiresAtUtc: string; accessToken?: string | null };
export type AppNotification = { id: string; userId: string; type: string; title: string; body: string; dataJson: string | null; createdAtUtc: string; readAtUtc: string | null };
export type GameInvitation = { sessionId: string; gameType: string; invitedByUserId: string; invitedByName: string; createdAtUtc: string };
export type GameCountdown = { sessionId: string; countdownEndsAtUtc: string };
export type GameEnded = { sessionId: string; endedByUserId: string; endedByName: string; endedAtUtc: string };
export type TypingState = { userId: string; displayName: string; isTyping: boolean; isPrivate: boolean };
export type CoupleLocation = { userId: string; displayName: string; avatarUrl: string | null; mapColor: string; latitude: number; longitude: number; accuracyMeters: number; updatedAtUtc: string; isCurrentUser: boolean };
export type ApiRequestError = Error & { status?: number; code?: string };

const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:5080").replace(/\/$/, "");
const signalrBaseUrl = (process.env.NEXT_PUBLIC_SIGNALR_BASE_URL ?? apiBaseUrl).replace(/\/$/, "");
const tokenKey = "twogether.accessToken";

export function getAccessToken(): string | null { return typeof window === "undefined" ? null : window.localStorage.getItem(tokenKey); }
export function setAccessToken(token: string | null): void { if (typeof window === "undefined") return; if (token) window.localStorage.setItem(tokenKey, token); else window.localStorage.removeItem(tokenKey); }

async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  const token = getAccessToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(`${apiBaseUrl}${path}`, { ...init, headers, cache: "no-store" });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    let code: string | undefined;
    try {
      const payload = await response.json() as { code?: string; message?: string; error?: { code?: string; message?: string } };
      message = payload.message ?? payload.error?.message ?? message;
      code = payload.code ?? payload.error?.code;
    } catch { /* non-json */ }
    const error = new Error(message) as ApiRequestError;
    error.status = response.status;
    error.code = code;
    throw error;
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export async function register(request: { email: string; password: string; displayName: string; dateOfBirth: string; gender: Gender }): Promise<AuthResponse> { const response = await apiFetch<AuthResponse>("/api/auth/register", { method: "POST", body: JSON.stringify(request) }); setAccessToken(response.accessToken); return response; }
export async function login(email: string, password: string): Promise<AuthResponse> { const response = await apiFetch<AuthResponse>("/api/auth/login", { method: "POST", body: JSON.stringify({ email, password }) }); setAccessToken(response.accessToken); return response; }
export const logout = () => setAccessToken(null);
export const getMe = () => apiFetch<AuthUser>("/api/auth/me");
export async function updateProfile(profile: { displayName: string; avatarUrl: string | null; mapColor: string }): Promise<AuthResponse> { const response = await apiFetch<AuthResponse>("/api/auth/profile", { method: "PUT", body: JSON.stringify(profile) }); setAccessToken(response.accessToken); return response; }
export const getCouple = () => apiFetch<Couple>("/api/couple");
export const createPartnerInvite = () => apiFetch<PartnerInvite>("/api/couple/invites", { method: "POST" });
export async function acceptPartnerInvite(code: string): Promise<AuthResponse> { const response = await apiFetch<AuthResponse>("/api/couple/invites/accept", { method: "POST", body: JSON.stringify({ code, token: null }) }); setAccessToken(response.accessToken); return response; }
export const breakUp = () => apiFetch<void>("/api/couple/breakup", { method: "POST", body: "{}" });
export const getDashboardSnapshot = (signal?: AbortSignal) => apiFetch<DashboardSnapshot>("/api/dashboard", { signal });
export const getCycleProfile = () => apiFetch<CycleProfile | null>("/api/cycle/profile");
export const getCyclePrediction = () => apiFetch<CyclePrediction>("/api/cycle/prediction");
export const getSharedCycle = () => apiFetch<SharedCycle>("/api/cycle/shared");
export const getCycleLogs = () => apiFetch<CycleLog[]>("/api/cycle/logs");
export const saveCycleProfile = (profile: { averageCycleLengthDays: number; averagePeriodLengthDays: number; lastPeriodStartDate: string | null; shareLevel: string }) => apiFetch<CycleProfile>("/api/cycle/profile", { method: "PUT", body: JSON.stringify(profile) });
export const claimCycle = () => apiFetch<CycleProfile>("/api/cycle/claim", { method: "POST", body: "{}" });
export const saveCycleLog = (date: string, log: Omit<CycleLog, "logDate">) => apiFetch<CycleLog>(`/api/cycle/logs/${date}`, { method: "PUT", body: JSON.stringify(log) });
export const getMessages = () => apiFetch<Message[]>("/api/message?limit=100");
export const sendMessage = (body: string, isPrivate = false) => apiFetch<Message>("/api/message", { method: "POST", body: JSON.stringify({ body, isPrivate }) });
export const getNotifications = () => apiFetch<AppNotification[]>("/api/notification");
export const markNotificationRead = (id: string) => apiFetch<void>(`/api/notification/${id}/read`, { method: "POST", body: "{}" });
export const markAllNotificationsRead = () => apiFetch<void>("/api/notification/read-all", { method: "POST", body: "{}" });
export const getLocations = () => apiFetch<CoupleLocation[]>("/api/location");
export const getGameSessions = () => apiFetch<GameSession[]>("/api/game/sessions");
export const getGameSession = (id: string) => apiFetch<GameSession>(`/api/game/sessions/${id}`);
export const createGameSession = (gameType: string, letter?: string) => apiFetch<GameSession>("/api/game/sessions", { method: "POST", body: JSON.stringify({ gameType, letter }) });
export const acceptGameSession = (id: string) => apiFetch<GameSession>(`/api/game/sessions/${id}/accept`, { method: "POST", body: "{}" });
export const startGameSession = (id: string) => apiFetch<GameSession>(`/api/game/sessions/${id}/start`, { method: "POST", body: "{}" });
export const endGameSession = (id: string) => apiFetch<GameSession>(`/api/game/sessions/${id}/abandon`, { method: "POST", body: "{}" });
export const applyGameAction = (id: string, expectedStateVersion: number, action: string, value?: string, index?: number) => apiFetch<GameSession>(`/api/game/sessions/${id}/actions`, { method: "POST", body: JSON.stringify({ expectedStateVersion, action, value, index }) });

export function createCoupleHubConnection(): HubConnection { return new HubConnectionBuilder().withUrl(`${signalrBaseUrl}/hubs/couple`, { accessTokenFactory: () => getAccessToken() ?? "" }).withAutomaticReconnect().configureLogging(LogLevel.Warning).build(); }
export function createGameHubConnection(): HubConnection { return new HubConnectionBuilder().withUrl(`${signalrBaseUrl}/hubs/game`, { accessTokenFactory: () => getAccessToken() ?? "" }).withAutomaticReconnect().configureLogging(LogLevel.Warning).build(); }

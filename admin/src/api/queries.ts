import { keepPreviousData, queryOptions } from "@tanstack/react-query";
import { api, qs } from "./client";
import type {
  AdminIdentity,
  AdminRow,
  Alert,
  AuditRow,
  BillingCheck,
  DareInsights,
  Engagement,
  Features,
  Funnel,
  GameInsights,
  Grant,
  Growth,
  Health,
  Journey,
  Overview,
  Page,
  Pulse,
  Retention,
  Revenue,
  Rhythm,
  SubscriptionRow,
  SystemStatus,
  TopCouple,
  UserDetail,
  UserRow,
  World,
} from "./types";

export type UserStatusFilter = "active" | "disabled" | "unlinked" | "internal";

export const queries = {
  me: () => queryOptions({ queryKey: ["me"], queryFn: () => api<AdminIdentity>("/api/admin/auth/me"), staleTime: 5 * 60_000 }),

  overview: (days: number) =>
    queryOptions({ queryKey: ["analytics", "overview", days], queryFn: () => api<Overview>(`/api/admin/analytics/overview${qs({ days })}`) }),
  engagement: (days: number) =>
    queryOptions({ queryKey: ["analytics", "engagement", days], queryFn: () => api<Engagement>(`/api/admin/analytics/engagement${qs({ days })}`) }),
  revenue: (days: number) =>
    queryOptions({ queryKey: ["analytics", "revenue", days], queryFn: () => api<Revenue>(`/api/admin/analytics/revenue${qs({ days })}`) }),

  users: (params: { query?: string; status?: UserStatusFilter; page: number }) =>
    queryOptions({
      queryKey: ["users", params],
      queryFn: () => api<Page<UserRow>>(`/api/admin/users${qs({ ...params, pageSize: 25 })}`),
      placeholderData: keepPreviousData,
    }),
  user: (id: string) =>
    // Every open of a profile is audited as "user.viewed", so don't refetch it behind the admin's back.
    queryOptions({ queryKey: ["user", id], queryFn: () => api<UserDetail>(`/api/admin/users/${id}`), staleTime: Infinity, refetchOnWindowFocus: false }),

  subscriptions: (params: { query?: string; status?: string; page: number }) =>
    queryOptions({
      queryKey: ["subscriptions", params],
      queryFn: () => api<Page<SubscriptionRow>>(`/api/admin/subscriptions${qs({ ...params, pageSize: 25 })}`),
      placeholderData: keepPreviousData,
    }),
  grants: (activeOnly: boolean) => queryOptions({ queryKey: ["grants", activeOnly], queryFn: () => api<Grant[]>(`/api/admin/grants${qs({ activeOnly })}`) }),

  audit: (params: { query?: string; action?: string; page: number }) =>
    queryOptions({
      queryKey: ["audit", params],
      queryFn: () => api<Page<AuditRow>>(`/api/admin/audit${qs({ ...params, pageSize: 50 })}`),
      placeholderData: keepPreviousData,
    }),

  pulse: () => queryOptions({ queryKey: ["analytics", "pulse"], queryFn: () => api<Pulse>("/api/admin/analytics/pulse") }),
  funnel: (days: number) => queryOptions({ queryKey: ["analytics", "funnel", days], queryFn: () => api<Funnel>(`/api/admin/analytics/funnel${qs({ days })}`) }),
  retention: (weeks: number) =>
    queryOptions({ queryKey: ["analytics", "retention", weeks], queryFn: () => api<Retention>(`/api/admin/analytics/retention${qs({ weeks })}`) }),
  rhythm: (days: number) => queryOptions({ queryKey: ["analytics", "rhythm", days], queryFn: () => api<Rhythm>(`/api/admin/analytics/rhythm${qs({ days })}`) }),
  dares: (days: number) =>
    queryOptions({ queryKey: ["analytics", "dares", days], queryFn: () => api<DareInsights>(`/api/admin/analytics/dares${qs({ days })}`) }),
  games: (days: number) =>
    queryOptions({ queryKey: ["analytics", "games", days], queryFn: () => api<GameInsights>(`/api/admin/analytics/games${qs({ days })}`) }),
  growth: (days: number) => queryOptions({ queryKey: ["analytics", "growth", days], queryFn: () => api<Growth>(`/api/admin/analytics/growth${qs({ days })}`) }),
  world: () => queryOptions({ queryKey: ["analytics", "world"], queryFn: () => api<World>("/api/admin/analytics/world") }),
  topCouples: (days: number, limit = 12) =>
    queryOptions({
      queryKey: ["analytics", "couples", days, limit],
      queryFn: () => api<TopCouple[]>(`/api/admin/analytics/couples/top${qs({ days, limit })}`),
    }),
  journey: (id: string, days: number) =>
    queryOptions({ queryKey: ["journey", id, days], queryFn: () => api<Journey>(`/api/admin/users/${id}/journey${qs({ days })}`) }),
  health: () => queryOptions({ queryKey: ["health"], queryFn: () => api<Health>("/api/admin/system/health"), refetchInterval: 30_000 }),
  alerts: () => queryOptions({ queryKey: ["alerts"], queryFn: () => api<Alert[]>("/api/admin/alerts"), refetchInterval: 60_000 }),

  system: () => queryOptions({ queryKey: ["system"], queryFn: () => api<SystemStatus>("/api/admin/system"), refetchInterval: 60_000 }),
  admins: () => queryOptions({ queryKey: ["admins"], queryFn: () => api<AdminRow[]>("/api/admin/admins") }),
  features: () => queryOptions({ queryKey: ["features"], queryFn: () => api<Features>("/api/admin/features") }),
  /** Asks Google Play and Bachs on every run (and is audited), so it only runs when the admin presses the button. */
  billingCheck: (country: string) =>
    queryOptions({
      queryKey: ["billing", "check", country],
      queryFn: () => api<BillingCheck>(`/api/admin/billing/check${qs({ country })}`),
      staleTime: Infinity,
      gcTime: 10 * 60_000,
      refetchOnWindowFocus: false,
      retry: false,
    }),
};

const post = <T = void>(path: string, body: unknown = {}) => api<T>(path, { method: "POST", body });

export const actions = {
  disableUser: (id: string, reason: string) => post(`/api/admin/users/${id}/disable`, { reason }),
  enableUser: (id: string) => post(`/api/admin/users/${id}/enable`),
  signOutUser: (id: string) => post(`/api/admin/users/${id}/sign-out`),
  sendPasswordReset: (id: string) => post(`/api/admin/users/${id}/password-reset`),
  deleteUser: (id: string, confirmEmail: string, reason: string) => post(`/api/admin/users/${id}/delete`, { confirmEmail, reason }),

  syncSubscription: (id: string) => post<SubscriptionRow>(`/api/admin/subscriptions/${id}/sync`),
  cancelSubscription: (id: string, reason: string) => post<SubscriptionRow>(`/api/admin/subscriptions/${id}/cancel`, { reason }),
  refundSubscription: (id: string, reason: string) => post<SubscriptionRow>(`/api/admin/subscriptions/${id}/refund`, { reason }),
  revokeSubscription: (id: string, reason: string) => post<SubscriptionRow>(`/api/admin/subscriptions/${id}/revoke`, { reason }),

  grantPlus: (coupleId: string, days: number, reason: string) => post<Grant>(`/api/admin/couples/${coupleId}/grants`, { days, reason }),
  revokeGrant: (id: string) => post(`/api/admin/grants/${id}/revoke`),

  setInternal: (id: string, internal: boolean, reason: string) => post(`/api/admin/users/${id}/internal`, { internal, reason }),

  resetAuthenticator: (userId: string) => post(`/api/admin/admins/${userId}/reset-authenticator`),

  /** Needs a fresh authenticator code (the step-up dialog asks). Answers the new state. */
  /** Only the switches named change; the server refuses a body that names none. */
  setFeatures: (features: Partial<Features>) => api<Features>("/api/admin/features", { method: "PUT", body: features }),
};

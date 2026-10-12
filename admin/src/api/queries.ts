import { keepPreviousData, queryOptions } from "@tanstack/react-query";
import { api, qs } from "./client";
import type {
  AdminIdentity,
  AdminRow,
  AuditRow,
  Engagement,
  Grant,
  Overview,
  Page,
  Revenue,
  SubscriptionRow,
  SystemStatus,
  UserDetail,
  UserRow,
} from "./types";

export type UserStatusFilter = "active" | "disabled" | "unlinked";

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

  system: () => queryOptions({ queryKey: ["system"], queryFn: () => api<SystemStatus>("/api/admin/system"), refetchInterval: 60_000 }),
  admins: () => queryOptions({ queryKey: ["admins"], queryFn: () => api<AdminRow[]>("/api/admin/admins") }),
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

  resetAuthenticator: (userId: string) => post(`/api/admin/admins/${userId}/reset-authenticator`),
};

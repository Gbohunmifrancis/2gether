// Wire shapes of the admin API (camelCase, enums as camelCase strings). Mirrors
// src/Twogether.Api/Controllers/Admin and src/Twogether.Application/Features/Admin.

export type CoupleStatus = "pendingInvite" | "active" | "dissolved";
export type Plan = "free" | "plus";
export type SubscriptionStatus = "pending" | "active" | "canceled" | "inGracePeriod" | "onHold" | "paused" | "expired" | "pendingPurchaseCanceled";

export interface DayValue {
  date: string;
  value: number;
}

export interface NamedCount {
  name: string;
  count: number;
}

export interface Overview {
  days: number;
  totalUsers: number;
  newUsers: number;
  disabledUsers: number;
  activeCouples: number;
  pendingCouples: number;
  dissolvedCouples: number;
  activeToday: number;
  activeWeek: number;
  activeMonth: number;
  plusCouples: number;
  signups: DayValue[];
  activeUsers: DayValue[];
  couplesLinked: DayValue[];
  platforms: NamedCount[];
  appVersions: NamedCount[];
}

export interface Engagement {
  days: number;
  daresCompleted: number;
  daresMissed: number;
  daresCalledOff: number;
  completionRate: number;
  daresCompletedByDay: DayValue[];
  daresMissedByDay: DayValue[];
  daresByExercise: NamedCount[];
  foodEntries: number;
  foodScans: number;
  foodScansFailed: number;
  scanInputTokens: number;
  scanOutputTokens: number;
  foodEntriesByDay: DayValue[];
  photosSynced: number;
  photoBytesSynced: number;
  photosByDay: DayValue[];
  questionsAnswered: number;
  answersByDay: DayValue[];
  gamesCompleted: number;
  gamesByType: NamedCount[];
  locationSharers: number;
  activePregnancies: number;
  cycleTrackers: number;
}

export interface Revenue {
  days: number;
  currency: string;
  estimatedMonthlyRevenue: number;
  payingCouples: number;
  complimentaryCouples: number;
  activeByProduct: NamedCount[];
  byStatus: NamedCount[];
  newSubscriptions: DayValue[];
  ended: DayValue[];
  renewingOff: number;
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface UserRow {
  id: string;
  email: string;
  displayName: string;
  createdAtUtc: string;
  lastActiveAtUtc: string | null;
  disabledAtUtc: string | null;
  google: boolean;
  password: boolean;
  coupleStatus: CoupleStatus | null;
  paying: boolean;
}

export interface PersonRef {
  id: string;
  email: string;
  displayName: string;
}

export interface Couple {
  id: string;
  status: CoupleStatus;
  linkedAtUtc: string | null;
  dissolvedAtUtc: string | null;
  partner: PersonRef | null;
}

export interface Device {
  platform: string;
  appVersion: string | null;
  lastSeenAtUtc: string;
  active: boolean;
}

export interface ActivityCounts {
  daresCompleted: number;
  daresMissed: number;
  foodEntries: number;
  foodScans: number;
  photosShared: number;
  photoBytes: number;
  questionsAnswered: number;
  activeDays: number;
}

export interface SubscriptionRow {
  id: string;
  store: string;
  productId: string;
  userId: string;
  buyerEmail: string | null;
  status: SubscriptionStatus;
  expiresAtUtc: string;
  autoRenewing: boolean;
  acknowledged: boolean;
  createdAtUtc: string;
  lastVerifiedAtUtc: string | null;
  tokenSuffix: string;
}

export interface Grant {
  id: string;
  coupleId: string;
  expiresAtUtc: string;
  reason: string;
  revokedAtUtc: string | null;
  createdAtUtc: string;
}

export interface AuditRow {
  id: string;
  adminEmail: string;
  action: string;
  targetType: string | null;
  targetId: string | null;
  details: string | null;
  ipAddress: string | null;
  succeeded: boolean;
  createdAtUtc: string;
}

export interface UserDetail {
  id: string;
  email: string;
  displayName: string;
  createdAtUtc: string;
  lastActiveAtUtc: string | null;
  disabledAtUtc: string | null;
  disabledReason: string | null;
  emailVerified: boolean;
  google: boolean;
  password: boolean;
  timeZoneId: string;
  couple: Couple | null;
  plan: Plan;
  complimentary: boolean;
  devices: Device[];
  subscriptions: SubscriptionRow[];
  grants: Grant[];
  activity: ActivityCounts;
  history: AuditRow[];
}

export interface Feature {
  name: string;
  on: boolean;
  detail: string;
}

export interface SystemStatus {
  nowUtc: string;
  features: Feature[];
  pushPending: number;
  pushSentToday: number;
  pushFailedToday: number;
  photosBeingDeleted: number;
  openGames: number;
  adminCount: number;
  tokenHours: number;
  currency: string;
  monthlyPrices: Record<string, number>;
}

export interface AdminRow {
  email: string;
  userId: string | null;
  displayName: string | null;
  hasAccount: boolean;
  authenticatorEnrolledAtUtc: string | null;
}

export interface AdminIdentity {
  id: string;
  email: string;
  displayName: string;
}

export interface AdminSession {
  accessToken: string;
  expiresAtUtc: string;
  admin: AdminIdentity;
}

export interface Enrollment {
  code: "admin.enrollment_required";
  message: string;
  secret: string;
  otpauthUri: string;
}

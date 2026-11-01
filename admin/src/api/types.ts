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
  /** Test or team account, left out of analytics (v2; absent from older APIs). */
  internal?: boolean;
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
  /** Test or team account, left out of analytics (v2; absent from older APIs). */
  internal?: boolean;
}

export interface Feature {
  name: string;
  on: boolean;
  detail: string;
}

/** Switches that change every app without a release (GET and PUT /api/admin/features). */
export interface Features {
  christmas: boolean;
  /** Card payment (Bachs) next to Google Play in the app, where Google's billing choice allows it. */
  bachsInApp: boolean;
  /** The AI features (docs/ai.md). Each starts on; off hides it in every app. */
  aiDareIdeas: boolean;
  aiCoach: boolean;
  aiWeeklyRecap: boolean;
  aiPlanner: boolean;
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

/** Why a store read failed: our short code, Google's HTTP status and one-word reason, and what to do. */
export interface CheckError {
  code: string;
  httpStatus: number | null;
  reason: string | null;
  hint: string;
}

export interface PlayBasePlanCheck {
  basePlanId: string;
  /** ACTIVE, DRAFT or INACTIVE, as Google says it. */
  state: string;
  type: "autoRenewing" | "prepaid" | "installments" | "unknown";
  /** ISO 8601: P1M, P1Y. */
  billingPeriod: string | null;
  /** Regions new subscribers can buy it in. */
  regionCount: number;
  regions: string[];
  /** Priced there, but closed to new subscribers. */
  closedRegions: string[];
  /** "NGN 4,500", per region it's on sale in. */
  prices: { region: string; price: string }[];
}

export interface PlayProductCheck {
  productId: string;
  found: boolean;
  archived: boolean;
  basePlans: PlayBasePlanCheck[];
  offers: { basePlanId: string; offerId: string; state: string }[];
  offersError: CheckError | null;
}

export interface BachsProductCheck {
  plan: "monthly" | "yearly";
  productId: string | null;
  /** False when Bachs isn't set up, so nothing was asked. */
  checked: boolean;
  found: boolean;
  active: boolean;
  name: string | null;
  price: string | null;
  interval: string | null;
  trial: string | null;
  error: CheckError | null;
}

export type HintLevel = "problem" | "todo" | "info" | "ok";

/** GET /api/admin/billing/check?country=NG: what Play Console and Bachs hold for Plus, and what to fix. */
export interface BillingCheck {
  checkedAtUtc: string;
  country: string;
  googlePlay: {
    configured: boolean;
    provider: string | null;
    packageName: string | null;
    productIds: string[];
    error: CheckError | null;
    products: PlayProductCheck[];
    /** In Play Console but not in Billing__ProductIds: a likely id typo. */
    extraProducts: string[];
  };
  bachs: {
    configured: boolean;
    mode: "live" | "sandbox" | null;
    /** Setting names, never values. */
    missing: string[];
    bachsInApp: boolean;
    products: BachsProductCheck[];
  };
  hints: { area: "googlePlay" | "bachs"; level: HintLevel; text: string }[];
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

// ---- Console v2 (docs/admin/contract-v2.md) ---------------------------------------------------------------------

export type LiveKind =
  "signedUp" | "coupleLinked" | "coupleEnded" | "dareCompleted" | "dareMissed" | "gameFinished" | "plusStarted" | "plusEnded" | "accountDeleted" | "alert";

export type Severity = "info" | "good" | "warn" | "bad";

export interface LiveEvent {
  id: string;
  kind: LiveKind;
  atUtc: string;
  userId: string | null;
  displayName: string | null;
  partnerName: string | null;
  detail: string | null;
  severity: Severity;
}

export interface Pulse {
  nowUtc: string;
  onlineNow: number;
  activeToday: number;
  ringingNow: number;
  onCameraNow: number;
  openGames: number;
  heartsToday: number;
  signupsToday: number;
  couplesLinkedToday: number;
  plusStartedToday: number;
  recent: LiveEvent[];
}

export type FunnelKey = "signedUp" | "invitedOrJoined" | "linked" | "firstDareSet" | "firstDareDone" | "firstHeart" | "activeWeek2" | "plus";

export interface FunnelStep {
  key: FunnelKey;
  count: number;
  medianHoursFromPrevious: number | null;
}

export interface Funnel {
  days: number;
  steps: FunnelStep[];
}

export interface Cohort {
  weekStart: string;
  size: number;
  retained: (number | null)[];
}

export interface Retention {
  weeks: number;
  cohorts: Cohort[];
}

/** 7x24: [weekday 0=Monday..6][hour 0..23], UTC. */
export type WeekGrid = number[][];

export interface Rhythm {
  days: number;
  actions: WeekGrid;
  daresDone: WeekGrid;
  rings: WeekGrid;
}

export interface ExerciseInsight {
  exercise: string;
  set: number;
  completed: number;
  missed: number;
  calledOff: number;
  completionRate: number | null;
  medianSecondsToStart: number | null;
  medianSecondsOnCamera: number | null;
  averageConfidence: number | null;
  checksFailed: number;
}

export interface DareInsights {
  days: number;
  exercises: ExerciseInsight[];
  earlyStarts: number;
  medianRepsTarget: number | null;
  streaks: { length: string; couples: number }[];
}

export interface GameInsights {
  days: number;
  types: { type: string; started: number; finished: number; abandoned: number; medianMinutes: number | null }[];
  skills: { kind: string; rounds: number; averageScore: number | null; buckets: number[] }[];
  /** Ties are level on hearts and on points; the median margin is in hearts. */
  duels: { finished: number; ties: number; medianMargin: number | null };
}

export type GrowthPathKey = "signedUp" | "linked" | "hitScanLimit" | "nearAlbumLimit" | "plus";

export interface Growth {
  days: number;
  endingIn7Days: number;
  endingIn30Days: number;
  renewed: number;
  lapsed: number;
  renewalRate: number | null;
  path: { key: GrowthPathKey; count: number }[];
  medianDaysToPlus: number | null;
  plusByCountry: NamedCount[];
}

export interface CountryRow {
  code: string;
  people: number;
  couples: number;
  plus: number;
  activeWeek: number;
}

export interface World {
  countries: CountryRow[];
  unknown: number;
}

export interface TopCouple {
  coupleId: string;
  names: [string, string];
  hearts: number;
  daresSet: number;
  games: number;
  streak: number;
  plus: boolean;
  linkedAtUtc: string;
}

export type ProbeStatus = "up" | "degraded" | "down" | "off";
export type WorkerStatus = "ok" | "late" | "failing" | "unknown";

export interface Health {
  nowUtc: string;
  probes: { name: "database" | "storage" | "push" | "email" | "foodModel"; status: ProbeStatus; latencyMs: number | null; detail: string | null }[];
  workers: { name: string; lastRunUtc: string | null; lastErrorUtc: string | null; lastError: string | null; everySeconds: number; status: WorkerStatus }[];
  queues: { name: string; waiting: number; oldestUtc: string | null }[];
  storageBytes: number;
  foodScanCost: { days: number; inputTokens: number; outputTokens: number; estimatedCost: number | null; currency: string };
  notMonitored: string[];
  /** The AI features' calls to Claude, priced like food scans (same model). Missing from an older API. */
  aiCost?: { days: number; inputTokens: number; outputTokens: number; estimatedCost: number | null; currency: string } | null;
}

export interface Alert {
  key: string;
  severity: "warn" | "bad";
  title: string;
  detail: string;
  sinceUtc: string;
  link: string | null;
}

export type JourneyKind =
  | "signedUp"
  | "linked"
  | "unlinked"
  | "active"
  | "dareSet"
  | "dareCompleted"
  | "dareMissed"
  | "mealLogged"
  | "photoSynced"
  | "questionAnswered"
  | "gameFinished"
  | "plusStarted"
  | "plusEnded"
  | "adminAction";

export interface JourneyDay {
  day: string;
  events: { kind: JourneyKind; count: number; detail: string | null }[];
}

export interface Journey {
  days: number;
  items: JourneyDay[];
}

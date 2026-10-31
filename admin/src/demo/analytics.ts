import type {
  Alert,
  DareInsights,
  DayValue,
  Engagement,
  Funnel,
  GameInsights,
  Growth,
  Health,
  Journey,
  JourneyDay,
  NamedCount,
  Overview,
  Pulse,
  Retention,
  Revenue,
  Rhythm,
  SystemStatus,
  TopCouple,
  WeekGrid,
  World,
} from "../api/types";
import { int, noise, pick, seeded, type Rng } from "./random";
import { DAY, dayKey, EXERCISES, GAMES, iso, NOW, PRODUCTS, SKILLS, type DemoUser, type DemoWorld } from "./world";

/* Every analytics answer, made up but shaped like the real thing: weekly rhythms, growth, the odd quiet day. */

const today = () => Date.UTC(new Date(NOW).getUTCFullYear(), new Date(NOW).getUTCMonth(), new Date(NOW).getUTCDate());

/** `days` values ending today, around `level`, growing by `growth` over the window, with a weekend lift. */
function series(random: Rng, days: number, level: number, options: { growth?: number; weekend?: number; jitter?: number } = {}): DayValue[] {
  const { growth = 0.35, weekend = 0.18, jitter = 0.22 } = options;
  const end = today();
  return Array.from({ length: days }, (_, index) => {
    const at = end - (days - 1 - index) * DAY;
    const weekday = new Date(at).getUTCDay();
    const trend = 1 - growth / 2 + growth * (index / Math.max(1, days - 1));
    const lift = weekday === 0 || weekday === 6 ? 1 + weekend : 1;
    const value = Math.max(0, Math.round(level * trend * lift * (1 + noise(random) * jitter)));
    return { date: dayKey(at), value };
  });
}

const sum = (values: DayValue[]) => values.reduce((total, item) => total + item.value, 0);

export function overview(world: DemoWorld, days: number): Overview {
  const random = seeded(`overview:${days}`);
  const measured = world.users.filter((user) => !user.internal);
  const since = NOW - days * DAY;
  const activeIn = (ms: number) => measured.filter((user) => user.lastActiveAtUtc && Date.parse(user.lastActiveAtUtc) > NOW - ms).length;
  const signups = series(random, days, (measured.filter((user) => Date.parse(user.createdAtUtc) > since).length / days) * 0.9, { growth: 0.5, weekend: 0.3 });
  const activeToday = activeIn(DAY);
  const activeUsers = series(random, days, activeToday * 0.9, { growth: 0.3, weekend: 0.12, jitter: 0.08 });
  activeUsers[activeUsers.length - 1]!.value = activeToday;
  return {
    days,
    totalUsers: measured.length,
    newUsers: sum(signups),
    disabledUsers: measured.filter((user) => user.disabledAtUtc).length,
    activeCouples: world.couples.filter((couple) => couple.status === "active").length,
    pendingCouples: world.couples.filter((couple) => couple.status === "pendingInvite").length,
    dissolvedCouples: world.couples.filter((couple) => couple.status === "dissolved").length,
    activeToday,
    activeWeek: activeIn(7 * DAY),
    activeMonth: activeIn(30 * DAY),
    plusCouples: world.couples.filter((couple) => couple.plus).length,
    signups,
    activeUsers,
    couplesLinked: series(random, days, (sum(signups) / days) * 0.38, { growth: 0.45, weekend: 0.35 }),
    platforms: [{ name: "android", count: Math.round(measured.length * 0.83) }],
    appVersions: [
      { name: "1.6.0", count: 1102 },
      { name: "1.5.2", count: 512 },
      { name: "1.5.1", count: 214 },
      { name: "1.4.0", count: 96 },
      { name: "1.3.3", count: 31 },
    ],
  };
}

export function engagement(_world: DemoWorld, days: number): Engagement {
  const random = seeded(`engagement:${days}`);
  const completedByDay = series(random, days, 410, { weekend: 0.25 });
  const missedByDay = series(random, days, 74, { growth: 0.1, weekend: -0.1 });
  const completed = sum(completedByDay);
  const missed = sum(missedByDay);
  const calledOff = Math.round(completed * 0.05);
  const byExercise: NamedCount[] = EXERCISES.map((exercise, index) => ({
    name: exercise,
    count: Math.round(completed * [0.27, 0.19, 0.16, 0.12, 0.1, 0.09, 0.07][index]!),
  }));
  const meals = series(random, days, 520);
  const photos = series(random, days, 260, { weekend: 0.45 });
  const answers = series(random, days, 610);
  const games = Math.round(days * 96);
  return {
    days,
    daresCompleted: completed,
    daresMissed: missed,
    daresCalledOff: calledOff,
    completionRate: completed / (completed + missed),
    daresCompletedByDay: completedByDay,
    daresMissedByDay: missedByDay,
    daresByExercise: byExercise,
    foodEntries: sum(meals),
    foodScans: Math.round(sum(meals) * 0.41),
    foodScansFailed: Math.round(sum(meals) * 0.012),
    scanInputTokens: Math.round(sum(meals) * 0.41 * 1650),
    scanOutputTokens: Math.round(sum(meals) * 0.41 * 210),
    foodEntriesByDay: meals,
    photosSynced: sum(photos),
    photoBytesSynced: sum(photos) * 1_850_000,
    photosByDay: photos,
    questionsAnswered: sum(answers),
    answersByDay: answers,
    gamesCompleted: games,
    gamesByType: GAMES.map((game, index) => ({ name: game, count: Math.round(games * (0.2 / (index + 1) + 0.03)) })),
    locationSharers: 612,
    activePregnancies: 23,
    cycleTrackers: 288,
  };
}

export function revenue(world: DemoWorld, days: number): Revenue {
  const random = seeded(`revenue:${days}`);
  const active = world.subscriptions.filter((row) => row.status === "active" || row.status === "inGracePeriod");
  const monthly = active.filter((row) => row.productId === PRODUCTS[0]).length;
  const yearly = active.length - monthly;
  const byStatus = new Map<string, number>();
  for (const row of world.subscriptions) byStatus.set(row.status, (byStatus.get(row.status) ?? 0) + 1);
  return {
    days,
    currency: "GBP",
    estimatedMonthlyRevenue: Math.round((monthly * 4.99 + yearly * (39.99 / 12)) * 100) / 100,
    payingCouples: active.length,
    complimentaryCouples: world.grants.filter((grant) => !grant.revokedAtUtc && Date.parse(grant.expiresAtUtc) > NOW).length,
    activeByProduct: [
      { name: PRODUCTS[0], count: monthly },
      { name: PRODUCTS[1], count: yearly },
    ],
    byStatus: [...byStatus].map(([name, count]) => ({ name, count })),
    newSubscriptions: series(random, days, 2.6, { growth: 0.6, jitter: 0.6 }),
    ended: series(random, days, 0.8, { growth: 0.2, jitter: 0.9 }),
    renewingOff: world.subscriptions.filter((row) => row.status === "active" && !row.autoRenewing).length,
  };
}

export function pulse(world: DemoWorld, counters: PulseCounters): Pulse {
  const online = world.users.filter((user) => !user.internal && user.lastActiveAtUtc && Date.parse(user.lastActiveAtUtc) > Date.now() - 5 * 60_000).length;
  return {
    nowUtc: iso(Date.now()),
    onlineNow: online + counters.onlineDrift,
    activeToday: overview(world, 7).activeToday,
    ringingNow: counters.ringing,
    onCameraNow: counters.onCamera,
    openGames: counters.openGames,
    heartsToday: counters.hearts,
    signupsToday: counters.signups,
    couplesLinkedToday: counters.linked,
    plusStartedToday: counters.plus,
    recent: world.events.slice(0, 30),
  };
}

export interface PulseCounters {
  onlineDrift: number;
  ringing: number;
  onCamera: number;
  openGames: number;
  hearts: number;
  signups: number;
  linked: number;
  plus: number;
}

export function funnel(days: number): Funnel {
  const scale = days / 30;
  const random = seeded(`funnel:${days}`);
  const start = Math.round(812 * scale * (1 + noise(random) * 0.05));
  const shares = [1, 0.81, 0.62, 0.53, 0.46, 0.38, 0.3, 0.075];
  const medians = [null, 0.45, 19.5, 5.2, 13.8, 31, 170, 228];
  const keys = ["signedUp", "invitedOrJoined", "linked", "firstDareSet", "firstDareDone", "firstHeart", "activeWeek2", "plus"] as const;
  return {
    days,
    steps: keys.map((key, index) => ({
      key,
      count: Math.round(start * shares[index]! * (index ? 1 + noise(random) * 0.03 : 1)),
      // Too young a window to know week 2 for 7 days.
      medianHoursFromPrevious: key === "activeWeek2" && days < 14 ? null : medians[index]!,
    })),
  };
}

export function retention(weeks: number): Retention {
  const random = seeded(`retention:${weeks}`);
  const monday = (() => {
    const date = new Date(today());
    const weekday = (date.getUTCDay() + 6) % 7;
    return today() - weekday * DAY;
  })();
  const cohorts = Array.from({ length: weeks }, (_, index) => {
    const start = monday - (weeks - 1 - index) * 7 * DAY;
    const reached = weeks - index; // weeks this cohort has lived, including week 0
    const size = Math.round(48 + index * 4 + noise(random) * 10);
    const quality = 1 + noise(random) * 0.08 + index * 0.006;
    const retained = Array.from({ length: weeks }, (_, week) => {
      if (week === 0) return 1;
      if (week >= reached) return null;
      const base = 0.36 + 0.46 * Math.exp(-week / 2.4);
      return Math.max(0, Math.min(1, Math.round(base * quality * (1 + noise(random) * 0.05) * 1000) / 1000));
    });
    return { weekStart: dayKey(start), size, retained };
  });
  return { weeks, cohorts };
}

function grid(random: Rng, scale: number, shape: (day: number, hour: number) => number): WeekGrid {
  return Array.from({ length: 7 }, (_, day) =>
    Array.from({ length: 24 }, (_, hour) => Math.max(0, Math.round(scale * shape(day, hour) * (1 + noise(random) * 0.15)))),
  );
}

const bump = (hour: number, centre: number, width: number) => Math.exp(-((((hour - centre + 36) % 24) - 12) ** 2) / (2 * width * width));

export function rhythm(days: number): Rhythm {
  const random = seeded(`rhythm:${days}`);
  const scale = days / 30;
  // UTC hours: most people are in Europe and the Americas, so mornings at 6-8 UTC and evenings at 18-23 UTC.
  const weekend = (day: number) => day >= 5;
  return {
    days,
    actions: grid(
      random,
      60 * scale,
      (day, hour) => 0.15 + bump(hour, weekend(day) ? 9 : 7, 1.6) * 0.9 + bump(hour, 20, 2.2) * 1.3 + bump(hour, 13, 1.5) * 0.35 + bump(hour, 1, 1.5) * 0.5,
    ),
    daresDone: grid(
      random,
      24 * scale,
      (day, hour) => 0.05 + bump(hour, weekend(day) ? 9.5 : 7.3, 1.1) * 1.2 + bump(hour, 19, 1.6) * 1.1 + bump(hour, 0.5, 1.2) * 0.35,
    ),
    rings: grid(random, 20 * scale, (day, hour) => 0.02 + bump(hour, weekend(day) ? 9 : 7, 0.8) * 1.4 + bump(hour, 18.5, 1.2) * 0.9 + bump(hour, 0, 1) * 0.3),
  };
}

export function dares(days: number): DareInsights {
  const random = seeded(`dares:${days}`);
  const scale = days / 30;
  const popularity = [0.27, 0.19, 0.16, 0.12, 0.1, 0.09, 0.07];
  const difficulty = [0.86, 0.74, 0.88, 0.69, 0.9, 0.83, 0.77];
  return {
    days,
    exercises: EXERCISES.map((exercise, index) => {
      const set = Math.round(14200 * scale * popularity[index]!);
      const completed = Math.round(set * difficulty[index]! * (1 + noise(random) * 0.02));
      const calledOff = Math.round(set * 0.04);
      return {
        exercise,
        set,
        completed,
        missed: Math.max(0, set - completed - calledOff),
        calledOff,
        completionRate: completed / set,
        medianSecondsToStart: Math.round(140 + random() * 260),
        medianSecondsOnCamera: exercise === "plank" ? Math.round(70 + random() * 20) : Math.round(48 + random() * 60),
        averageConfidence: Math.round((0.78 + random() * 0.18) * 100) / 100,
        checksFailed: Math.round(completed * (0.01 + random() * 0.03)),
      };
    }),
    earlyStarts: Math.round(1830 * scale),
    medianRepsTarget: 20,
    streaks: [
      { length: "0", couples: 236 },
      { length: "1-2", couples: 188 },
      { length: "3-6", couples: 161 },
      { length: "7-13", couples: 97 },
      { length: "14-29", couples: 52 },
      { length: "30+", couples: 19 },
    ],
  };
}

export function games(days: number): GameInsights {
  const random = seeded(`games:${days}`);
  const scale = days / 30;
  return {
    days,
    types: GAMES.map((type, index) => {
      const started = Math.round((900 / (index * 0.55 + 1)) * scale * (1 + noise(random) * 0.1));
      const finished = Math.round(started * (0.62 + random() * 0.3));
      return { type, started, finished, abandoned: started - finished, medianMinutes: Math.round((2 + random() * 14) * 10) / 10 };
    }),
    skills: SKILLS.map((kind, index) => {
      // Centres spread across the middle of the range, so no game piles up at the top.
      const centre = 3 + ((index * 0.7) % 4.2) + random() * 2;
      const buckets = Array.from({ length: 10 }, (_, bucket) =>
        Math.round(220 * scale * Math.exp(-((bucket - centre) ** 2) / (2 * (1.6 + random()) ** 2)) * (1 + noise(random) * 0.15)),
      );
      const rounds = buckets.reduce((total, value) => total + value, 0);
      const average = buckets.reduce((total, value, bucket) => total + value * (bucket * 10 + 5), 0) / Math.max(1, rounds);
      return { kind, rounds, averageScore: Math.round(average * 10) / 10, buckets };
    }),
    duels: { finished: Math.round(412 * scale), ties: Math.round(7 * scale), medianMargin: 1 },
  };
}

export function growth(world: DemoWorld, days: number): Growth {
  const random = seeded(`growth:${days}`);
  const active = world.subscriptions.filter((row) => row.status === "active");
  const ending = (ms: number) =>
    active.filter((row) => Date.parse(row.expiresAtUtc) - NOW < ms && row.autoRenewing === false).length + Math.round(ms / DAY / 2.4);
  const renewed = Math.round(118 * (days / 90));
  const lapsed = Math.round(21 * (days / 90));
  const byCountry = new Map<string, number>();
  for (const couple of world.couples.filter((item) => item.plus)) {
    const country = world.byId.get(couple.a)?.country;
    if (country) byCountry.set(country, (byCountry.get(country) ?? 0) + 1);
  }
  const signedUp = Math.round(2140 * (days / 90));
  return {
    days,
    endingIn7Days: ending(7 * DAY),
    endingIn30Days: ending(30 * DAY),
    renewed,
    lapsed,
    renewalRate: renewed / (renewed + lapsed),
    path: [
      { key: "signedUp", count: signedUp },
      { key: "linked", count: Math.round(signedUp * 0.63) },
      { key: "hitScanLimit", count: Math.round(signedUp * 0.21) },
      { key: "nearAlbumLimit", count: Math.round(signedUp * 0.09) },
      { key: "plus", count: Math.round(signedUp * 0.074 * (1 + noise(random) * 0.03)) },
    ],
    medianDaysToPlus: 9.5,
    plusByCountry: [...byCountry].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count),
  };
}

export function world(data: DemoWorld): World {
  const rows = new Map<string, { people: number; couples: Set<string>; plus: Set<string>; activeWeek: number }>();
  let unknown = 0;
  for (const user of data.users) {
    if (user.internal) continue;
    if (!user.country) {
      unknown++;
      continue;
    }
    const row = rows.get(user.country) ?? { people: 0, couples: new Set(), plus: new Set(), activeWeek: 0 };
    row.people++;
    if (user.coupleId && user.coupleStatus === "active") {
      row.couples.add(user.coupleId);
      if (data.coupleById.get(user.coupleId)?.plus) row.plus.add(user.coupleId);
    }
    if (user.lastActiveAtUtc && Date.parse(user.lastActiveAtUtc) > NOW - 7 * DAY) row.activeWeek++;
    rows.set(user.country, row);
  }
  return {
    countries: [...rows]
      .map(([code, row]) => ({ code, people: row.people, couples: row.couples.size, plus: row.plus.size, activeWeek: row.activeWeek }))
      .sort((a, b) => b.people - a.people),
    unknown,
  };
}

export function topCouples(data: DemoWorld, days: number, limit: number): TopCouple[] {
  // Each couple's counts over the window: their lifetime pace times the days they've had in it.
  const inWindow = (total: number, couple: (typeof data.couples)[number]) => {
    const age = Math.max(1, (NOW - Date.parse(couple.linkedAtUtc!)) / DAY);
    return Math.round((total / age) * Math.min(days, age));
  };
  return data.couples
    .filter((couple) => couple.status === "active" && couple.b)
    .map((couple) => ({
      ...couple,
      hearts: inWindow(couple.hearts, couple),
      daresSet: inWindow(couple.daresSet, couple),
      games: inWindow(couple.games, couple),
    }))
    .sort((a, b) => b.hearts - a.hearts || b.streak - a.streak)
    .slice(0, limit)
    .map((couple) => ({
      coupleId: couple.id,
      names: [data.byId.get(couple.a)!.displayName, data.byId.get(couple.b!)!.displayName] as [string, string],
      hearts: couple.hearts,
      daresSet: Math.max(couple.daresSet, couple.hearts),
      games: couple.games,
      streak: couple.streak,
      plus: couple.plus,
      linkedAtUtc: couple.linkedAtUtc!,
    }))
    .sort((a, b) => b.hearts - a.hearts);
}

export function health(lateWorker: boolean): Health {
  const now = Date.now();
  return {
    nowUtc: iso(now),
    probes: [
      { name: "database", status: "up", latencyMs: 3, detail: "PostgreSQL 17" },
      { name: "storage", status: "up", latencyMs: 41, detail: "Album bucket" },
      { name: "push", status: "degraded", latencyMs: 212, detail: "FCM answered slowly; 4% failed in the last hour" },
      { name: "email", status: "up", latencyMs: 88, detail: "Postmark" },
      { name: "foodModel", status: "up", latencyMs: 640, detail: "Food scans" },
    ],
    workers: [
      { name: "Dare alarms", lastRunUtc: iso(now - 4_000), lastErrorUtc: null, lastError: null, everySeconds: 10, status: "ok" },
      { name: "Missed dares", lastRunUtc: iso(now - 21_000), lastErrorUtc: null, lastError: null, everySeconds: 60, status: "ok" },
      {
        name: "Push sender",
        lastRunUtc: iso(now - 2_000),
        lastErrorUtc: iso(now - 3 * 3_600_000),
        lastError: "FCM 503, retried",
        everySeconds: 5,
        status: "ok",
      },
      { name: "Game clean-up", lastRunUtc: iso(now - 140_000), lastErrorUtc: null, lastError: null, everySeconds: 300, status: "ok" },
      lateWorker
        ? { name: "Photo clean-up", lastRunUtc: iso(now - 48 * 60_000), lastErrorUtc: null, lastError: null, everySeconds: 600, status: "late" }
        : { name: "Photo clean-up", lastRunUtc: iso(now - 4 * 60_000), lastErrorUtc: null, lastError: null, everySeconds: 600, status: "ok" },
      {
        name: "Subscription sync",
        lastRunUtc: iso(now - 26 * 60_000),
        lastErrorUtc: iso(now - 26 * 60_000),
        lastError: "Google Play: 429 Too Many Requests",
        everySeconds: 900,
        status: "failing",
      },
    ],
    queues: [
      { name: "Push notifications", waiting: 12, oldestUtc: iso(now - 9_000) },
      { name: "Photos to delete", waiting: 341, oldestUtc: iso(now - 52 * 60_000) },
      { name: "Account deletions", waiting: 0, oldestUtc: null },
    ],
    storageBytes: 612 * 1024 ** 3 + 345 * 1024 ** 2,
    foodScanCost: { days: 30, inputTokens: 10_480_000, outputTokens: 1_336_000, estimatedCost: 3.42, currency: "GBP" },
    notMonitored: [
      "Backups (the host takes them; nothing here checks they ran)",
      "Email bounces and complaints",
      "Google Play real-time notifications arriving",
      "App crash rates (Play Console)",
    ],
  };
}

export function alerts(extra: Alert[], lateWorker: boolean): Alert[] {
  const now = Date.now();
  const base: Alert[] = [
    {
      key: "payments.trouble",
      severity: "warn",
      title: "2 subscriptions went on hold",
      detail: "Google couldn't take payment. Plus continues during the grace period; they may need a nudge.",
      sinceUtc: iso(now - 95 * 60_000),
      link: "/payments?status=onHold",
    },
    {
      key: "worker.failing:subscriptionSync",
      severity: "warn",
      title: "Subscription sync is failing",
      detail: "Google Play: 429 Too Many Requests, for the last 26 minutes.",
      sinceUtc: iso(now - 26 * 60_000),
      link: "/health",
    },
  ];
  if (lateWorker)
    base.push({
      key: "worker.late:photoCleanup",
      severity: "warn",
      title: "Photo clean-up is late",
      detail: "No run for 48 minutes; it runs every 10.",
      sinceUtc: iso(now - 18 * 60_000),
      link: "/health",
    });
  return [...extra, ...base];
}

export function journey(data: DemoWorld, user: DemoUser, days: number): Journey {
  const random = seeded(`journey:${user.id}:${days}`);
  const created = Date.parse(user.createdAtUtc);
  const items: JourneyDay[] = [];
  const last = user.lastActiveAtUtc ? Date.parse(user.lastActiveAtUtc) : created;
  const start = Math.max(created, NOW - days * DAY);
  const linkedAt = user.linkedAtUtc ? Date.parse(user.linkedAtUtc) : null;
  const plus = user.coupleId ? data.coupleById.get(user.coupleId)?.plus : false;
  const plusDay = plus && linkedAt ? dayKey(Math.min(NOW, linkedAt + 9 * DAY)) : null;
  for (let at = today(); at >= start - DAY; at -= DAY) {
    const key = dayKey(at);
    const events: JourneyDay["events"] = [];
    const isSignup = key === dayKey(created);
    const isLinked = linkedAt !== null && key === dayKey(linkedAt);
    const busy = at <= last && random() < 0.25 + user.zest * 0.7;
    if (isSignup) events.push({ kind: "signedUp", count: 1, detail: null });
    if (isLinked) events.push({ kind: "linked", count: 1, detail: null });
    if (busy || isSignup) {
      events.push({ kind: "active", count: 1, detail: null });
      if (linkedAt !== null && at >= linkedAt - DAY) {
        if (random() < 0.85) events.push({ kind: "dareSet", count: int(random, 1, 2), detail: pick(random, EXERCISES) });
        if (random() < 0.75) events.push({ kind: "dareCompleted", count: int(random, 1, 2), detail: pick(random, EXERCISES) });
        else if (random() < 0.5) events.push({ kind: "dareMissed", count: 1, detail: pick(random, EXERCISES) });
        if (random() < 0.5) events.push({ kind: "mealLogged", count: int(random, 1, 4), detail: null });
        if (random() < 0.3) events.push({ kind: "photoSynced", count: int(random, 1, 6), detail: null });
        if (random() < 0.55) events.push({ kind: "questionAnswered", count: 1, detail: null });
        if (random() < 0.2) events.push({ kind: "gameFinished", count: int(random, 1, 2), detail: pick(random, GAMES) });
      }
    }
    if (plusDay === key) events.push({ kind: "plusStarted", count: 1, detail: PRODUCTS[0] });
    const history = data.audit.filter((row) => row.targetId === user.id && dayKey(Date.parse(row.createdAtUtc)) === key && row.action !== "user.viewed");
    if (history.length) events.push({ kind: "adminAction", count: history.length, detail: history[0]!.action });
    if (events.length) items.push({ day: key, events });
  }
  return { days, items };
}

export function system(data: DemoWorld): SystemStatus {
  return {
    nowUtc: iso(Date.now()),
    features: [
      { name: "Push notifications", on: true, detail: "Firebase Cloud Messaging" },
      { name: "Email", on: true, detail: "Postmark" },
      { name: "Food scans", on: true, detail: "Photo to nutrition estimate" },
      { name: "Album storage", on: true, detail: "S3-compatible bucket" },
      { name: "Google Play billing", on: true, detail: "Service account connected" },
      { name: "Google sign-in", on: true, detail: "Android client id set" },
    ],
    pushPending: 12,
    pushSentToday: 18_430,
    pushFailedToday: 211,
    photosBeingDeleted: 341,
    openGames: 58,
    adminCount: data.admins.length,
    tokenHours: 4,
    currency: "GBP",
    monthlyPrices: { heartpact_plus_monthly: 4.99, heartpact_plus_yearly: 3.33 },
  };
}

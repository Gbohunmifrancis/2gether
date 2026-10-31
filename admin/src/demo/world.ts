import type { AdminRow, AuditRow, CoupleStatus, Grant, LiveEvent, LiveKind, SubscriptionRow, SubscriptionStatus, UserRow } from "../api/types";
import { int, noise, pick, rng, uuid, weighted, type Rng } from "./random";

/* The demo's pretend Heartpact: a couple of thousand people, their couples, subscriptions and an audit trail, all
   made up from a fixed seed. Only counts and account metadata, like the real API. */

export const DAY = 86_400_000;
export const NOW = Date.now();
export const iso = (ms: number) => new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z");
export const dayKey = (ms: number) => new Date(ms).toISOString().slice(0, 10);

const FIRST = [
  "Maya",
  "Theo",
  "Amara",
  "Leo",
  "Priya",
  "Sam",
  "Zoe",
  "Kofi",
  "Lena",
  "Mateo",
  "Aisha",
  "Noah",
  "Ines",
  "Ravi",
  "Chloe",
  "Tomás",
  "Yuki",
  "Ella",
  "Femi",
  "Sofia",
  "Jonas",
  "Nia",
  "Luca",
  "Hana",
  "Omar",
  "Freya",
  "Diego",
  "Ada",
  "Kai",
  "Lucía",
  "Ben",
  "Ama",
  "Elif",
  "Finn",
  "Mira",
  "Arjun",
  "Rosa",
  "Jamal",
  "Ivy",
  "Emil",
  "Zara",
  "Hugo",
  "Wanjiru",
  "Oscar",
  "Leila",
  "Pablo",
  "Ruth",
  "Tariq",
  "Nora",
  "Felix",
  "Chidi",
  "Alma",
  "Ezra",
  "Maren",
  "Kwame",
  "Isla",
  "Dev",
  "Sana",
  "Matteo",
  "Anya",
  "Jules",
  "Thandi",
  "Rafael",
  "Mei",
  "Eli",
  "Greta",
];
const LAST = [
  "Okafor",
  "Patel",
  "Nguyen",
  "Smith",
  "García",
  "Müller",
  "Rossi",
  "Kim",
  "Mensah",
  "Silva",
  "Kowalski",
  "Jensen",
  "O'Brien",
  "Haddad",
  "Tanaka",
  "Dubois",
  "Novak",
  "Andersson",
  "Reyes",
  "Mwangi",
  "Cohen",
  "Ahmed",
  "Lopez",
  "Fischer",
  "Bianchi",
  "Park",
  "Adeyemi",
  "Costa",
  "Larsen",
  "Murphy",
  "Khan",
  "Sato",
  "Moreau",
  "Horvat",
  "Lindqvist",
  "Cruz",
  "Otieno",
  "Levi",
  "Ali",
  "Brown",
];
const DOMAINS = ["example.com", "mail.test", "inbox.example", "post.example", "hey.example"];

export const COUNTRIES: (readonly [string, number])[] = [
  ["US", 22],
  ["GB", 16],
  ["DE", 7],
  ["NG", 6],
  ["IN", 6],
  ["BR", 5],
  ["CA", 5],
  ["AU", 4],
  ["FR", 4],
  ["NL", 3],
  ["ES", 3],
  ["IE", 2.5],
  ["ZA", 2.5],
  ["KE", 2],
  ["MX", 2],
  ["SE", 2],
  ["IT", 2],
  ["PH", 1.6],
  ["PL", 1.4],
  ["JP", 1.2],
  ["GH", 1.2],
  ["NZ", 1],
  ["PT", 1],
  ["SG", 0.8],
  ["AE", 0.8],
  ["DK", 0.7],
  ["AR", 0.7],
  ["CO", 0.6],
  ["TR", 0.6],
  ["NO", 0.6],
  ["KR", 0.5],
  ["EG", 0.4],
  ["CH", 0.5],
  ["BE", 0.5],
  ["ID", 0.4],
];

export const EXERCISES = ["squat", "pushUp", "jumpingJack", "plank", "armRaise", "highKnees", "sitUp"] as const;
export const GAMES = [
  "guessMyAnswer",
  "coupleQuiz",
  "memoryMatch",
  "wordDash",
  "handwriting",
  "truthOrDare",
  "iCallOn",
  "wouldYouRather",
  "ticTacToe",
  "connectFour",
  "trivia",
  "skillDuel",
  "spotTheDifference",
  "snakesAndLadders",
] as const;
export const SKILLS = ["FairShare", "FillTheHeart", "OtherHalf", "Heartbeat", "KissTheCenter", "PromiseRing", "CupidsAim", "SlowKiss", "DrawAHeart"] as const;
export const PRODUCTS = ["heartpact_plus_monthly", "heartpact_plus_yearly"] as const;

export interface DemoUser extends UserRow {
  country: string | null;
  coupleId: string | null;
  partnerId: string | null;
  linkedAtUtc: string | null;
  dissolvedAtUtc: string | null;
  disabledReason: string | null;
  emailVerified: boolean;
  timeZoneId: string;
  /** How keen: drives activity counts and the journey. */
  zest: number;
}

export interface DemoCouple {
  id: string;
  a: string;
  b: string | null;
  status: CoupleStatus;
  linkedAtUtc: string | null;
  dissolvedAtUtc: string | null;
  plus: boolean;
  hearts: number;
  daresSet: number;
  games: number;
  streak: number;
}

const ZONES: Record<string, string> = {
  US: "America/New_York",
  GB: "Europe/London",
  DE: "Europe/Berlin",
  NG: "Africa/Lagos",
  IN: "Asia/Kolkata",
  BR: "America/Sao_Paulo",
  CA: "America/Toronto",
  AU: "Australia/Sydney",
  FR: "Europe/Paris",
  KE: "Africa/Nairobi",
  JP: "Asia/Tokyo",
};

export interface DemoWorld {
  users: DemoUser[];
  byId: Map<string, DemoUser>;
  couples: DemoCouple[];
  coupleById: Map<string, DemoCouple>;
  subscriptions: SubscriptionRow[];
  grants: Grant[];
  audit: AuditRow[];
  admins: AdminRow[];
  admin: { id: string; email: string; displayName: string };
  events: LiveEvent[];
}

function person(random: Rng, index: number, createdAt: number): DemoUser {
  const first = pick(random, FIRST);
  const last = pick(random, LAST);
  const plain = (text: string) => text.normalize("NFKD").replace(/[^\w]/g, "").toLowerCase();
  const email = `${plain(first)}${random() < 0.5 ? "." : ""}${plain(last)}${random() < 0.35 ? int(random, 1, 99) : ""}@${pick(random, DOMAINS)}`;
  const country = random() < 0.04 ? null : weighted(random, COUNTRIES);
  const zest = Math.max(0.05, Math.min(1, 0.45 + noise(random) * 0.5));
  // Keen people were active recently; some drifted off.
  const lastActiveAgo = random() < 0.075 ? random() * 240_000 : zest > 0.6 ? random() * 2 * DAY : random() < 0.6 ? random() * 9 * DAY : random() * 80 * DAY;
  const lastActive = Math.max(createdAt + 60_000, NOW - lastActiveAgo);
  const google = random() < 0.45;
  return {
    id: uuid(random),
    email,
    displayName: first,
    createdAtUtc: iso(createdAt),
    lastActiveAtUtc: index % 37 === 5 ? null : iso(Math.min(NOW, lastActive)),
    disabledAtUtc: null,
    google,
    password: !google || random() < 0.2,
    coupleStatus: null,
    paying: false,
    internal: false,
    country,
    coupleId: null,
    partnerId: null,
    linkedAtUtc: null,
    dissolvedAtUtc: null,
    disabledReason: null,
    emailVerified: google || random() < 0.8,
    timeZoneId: (country && ZONES[country]) ?? "UTC",
    zest,
  };
}

export function buildWorld(): DemoWorld {
  const random = rng(20261026);
  const users: DemoUser[] = [];
  const count = 2480;
  for (let index = 0; index < count; index++) {
    // Growth: more sign-ups lately (a launch ~13 months ago, ramping).
    const age = 400 * DAY * (1 - Math.sqrt(random()));
    users.push(person(random, index, NOW - age - random() * 3_600_000));
  }
  users.sort((a, b) => Date.parse(b.createdAtUtc) - Date.parse(a.createdAtUtc));

  const admin = { id: "a0000000-0000-4000-a000-00000000a11a", email: "ada@heartpact.me", displayName: "Ada" };
  const team: DemoUser = {
    ...person(rng(7), 1, NOW - 390 * DAY),
    id: admin.id,
    email: admin.email,
    displayName: admin.displayName,
    internal: true,
    country: "GB",
    lastActiveAtUtc: iso(NOW - 30_000),
  };
  const tester: DemoUser = { ...person(rng(8), 2, NOW - 200 * DAY), email: "qa+theo@heartpact.me", displayName: "QA Theo", internal: true, country: "GB" };
  users.push(team, tester);

  // Couples: most people pair up with someone who joined around the same time.
  const couples: DemoCouple[] = [];
  const pool = users.filter((user) => !user.internal);
  for (let index = 0; index + 1 < pool.length; index += 2) {
    const a = pool[index]!;
    const b = pool[index + 1]!;
    const roll = random();
    const created = Math.max(Date.parse(a.createdAtUtc), Date.parse(b.createdAtUtc));
    if (roll < 0.1) continue; // both still alone
    const id = uuid(random);
    if (roll < 0.17) {
      couples.push({
        id,
        a: a.id,
        b: null,
        status: "pendingInvite",
        linkedAtUtc: null,
        dissolvedAtUtc: null,
        plus: false,
        hearts: 0,
        daresSet: 0,
        games: 0,
        streak: 0,
      });
      a.coupleId = id;
      a.coupleStatus = "pendingInvite";
      continue;
    }
    const linkedAt = Math.min(NOW - 600_000, created + random() * 2 * DAY);
    const dissolved = roll > 0.95;
    const zest = (a.zest + b.zest) / 2;
    const ageDays = (NOW - linkedAt) / DAY;
    const plus = !dissolved && random() < 0.08 + zest * 0.22;
    const couple: DemoCouple = {
      id,
      a: a.id,
      b: b.id,
      status: dissolved ? "dissolved" : "active",
      linkedAtUtc: iso(linkedAt),
      dissolvedAtUtc: dissolved ? iso(linkedAt + random() * (NOW - linkedAt)) : null,
      plus,
      hearts: Math.round(ageDays * zest * (0.4 + random() * 0.5)),
      daresSet: Math.round(ageDays * zest * (0.9 + random() * 0.6)),
      games: Math.round(ageDays * zest * random() * 0.4),
      streak: zest > 0.55 && !dissolved ? Math.round(random() ** 1.6 * Math.min(ageDays, 60)) : random() < 0.4 ? int(random, 0, 3) : 0,
    };
    couples.push(couple);
    for (const [self, other] of [
      [a, b],
      [b, a],
    ] as const) {
      self.coupleId = id;
      self.partnerId = other.id;
      self.coupleStatus = couple.status;
      self.linkedAtUtc = couple.linkedAtUtc;
      self.dissolvedAtUtc = couple.dissolvedAtUtc;
    }
    if (plus) (random() < 0.5 ? a : b).paying = random() < 0.9;
  }

  // A few disabled accounts, with reasons.
  for (const user of users.filter((_, index) => index % 211 === 17)) {
    user.disabledAtUtc = iso(NOW - random() * 40 * DAY);
    user.disabledReason = pick(random, ["Reported for spam", "Chargeback fraud", "Asked to pause their account", "Shared login abuse"]);
  }

  const byId = new Map(users.map((user) => [user.id, user]));
  const coupleById = new Map(couples.map((couple) => [couple.id, couple]));

  // Subscriptions: one per paying person, a handful in trouble.
  const subscriptions: SubscriptionRow[] = users
    .filter((user) => user.paying)
    .map((user) => {
      const status = weighted<SubscriptionStatus>(random, [
        ["active", 82],
        ["canceled", 7],
        ["inGracePeriod", 3],
        ["onHold", 2],
        ["expired", 5],
        ["paused", 1],
      ]);
      const created = Math.max(Date.parse(user.createdAtUtc), NOW - random() * 300 * DAY);
      const product = random() < 0.68 ? PRODUCTS[0] : PRODUCTS[1];
      const period = product === PRODUCTS[0] ? 30 : 365;
      const since = (NOW - created) / DAY;
      const expires = status === "expired" ? NOW - random() * 30 * DAY : created + (Math.floor(since / period) + 1) * period * DAY;
      return {
        id: uuid(random),
        store: "googlePlay",
        productId: product,
        userId: user.id,
        buyerEmail: user.email,
        status,
        expiresAtUtc: iso(expires),
        autoRenewing: status === "active" ? random() < 0.92 : status === "inGracePeriod" || status === "onHold",
        acknowledged: true,
        createdAtUtc: iso(created),
        lastVerifiedAtUtc: iso(NOW - random() * 3 * DAY),
        tokenSuffix: `…${Math.floor(random() * 0xffffff)
          .toString(16)
          .padStart(6, "0")}`,
      };
    });

  const linked = couples.filter((couple) => couple.status === "active");
  const grants: Grant[] = Array.from({ length: 14 }, (_, index) => {
    const couple = linked[(index * 53) % linked.length]!;
    const created = NOW - random() * 120 * DAY;
    const days = pick(random, [30, 60, 90, 365]);
    return {
      id: uuid(random),
      coupleId: couple.id,
      createdAtUtc: iso(created),
      expiresAtUtc: iso(created + days * DAY),
      reason: pick(random, [
        "Beta tester thank-you",
        "Support: lost purchase after phone swap",
        "Press review",
        "Giveaway winner",
        "Apology for the outage on 3 Oct",
      ]),
      revokedAtUtc: index === 3 ? iso(created + 10 * DAY) : null,
    };
  });

  const admins: AdminRow[] = [
    { email: admin.email, userId: admin.id, displayName: admin.displayName, hasAccount: true, authenticatorEnrolledAtUtc: iso(NOW - 380 * DAY) },
    { email: "sam@heartpact.me", userId: uuid(random), displayName: "Sam", hasAccount: true, authenticatorEnrolledAtUtc: iso(NOW - 200 * DAY) },
    { email: "support@heartpact.me", userId: null, displayName: null, hasAccount: false, authenticatorEnrolledAtUtc: null },
  ];

  const audit: AuditRow[] = [];
  const auditActions = [
    "admin.signed_in",
    "user.viewed",
    "user.viewed",
    "user.viewed",
    "plus.granted",
    "subscription.synced",
    "user.reset_sent",
    "user.disabled",
    "admin.sign_in_failed",
    "subscription.refunded",
  ];
  for (let index = 0; index < 160; index++) {
    const action = pick(random, auditActions);
    const target = pick(random, users);
    const by = random() < 0.7 ? admins[0]! : admins[1]!;
    audit.push({
      id: uuid(random),
      adminEmail: by.email,
      action,
      targetType: action.startsWith("admin.") ? null : action.startsWith("subscription") ? "subscription" : action === "plus.granted" ? "couple" : "user",
      targetId: action.startsWith("admin.") ? null : action === "plus.granted" ? (target.coupleId ?? target.id) : target.id,
      details:
        action === "user.disabled"
          ? "Reported for spam"
          : action === "plus.granted"
            ? "30 days: Beta tester thank-you"
            : action === "admin.sign_in_failed"
              ? "admin.invalid_code"
              : action === "subscription.refunded"
                ? "Double charge after a phone swap"
                : null,
      ipAddress: `203.0.113.${int(random, 2, 250)}`,
      succeeded: action !== "admin.sign_in_failed",
      createdAtUtc: iso(NOW - (index + random()) * 7_200_000),
    });
  }

  return { users, byId, couples, coupleById, subscriptions, grants, audit, admins, admin, events: seedEvents(users, couples, random) };
}

/* ---- Live events ---------------------------------------------------------------------------------------------- */

const ALERTS = [
  "Sign-up wave: 14 sign-ups in 15 minutes",
  "Push failing: 23% of 48 notifications failed in the last hour",
  "Food scan flood: one person made 22 scans in 10 minutes",
  "Payment trouble: 2 subscriptions went on hold",
];

export function liveEvent(kind: LiveKind, users: DemoUser[], couples: DemoCouple[], random: Rng, at = Date.now()): LiveEvent {
  const linked = couples.filter((couple) => couple.b);
  const couple = pick(random, linked);
  const one = (id: string | null) => (id ? users.find((user) => user.id === id) : undefined);
  const a = one(couple.a)!;
  const b = one(couple.b);
  const someone = pick(random, users.slice(0, 400));
  const base = {
    id: `${kind}:${uuid(random)}`,
    kind,
    atUtc: iso(at),
    userId: a.id,
    displayName: a.displayName,
    partnerName: null,
    detail: null,
    severity: "info" as const,
  };
  switch (kind) {
    case "signedUp":
      return { ...base, userId: someone.id, displayName: someone.displayName, detail: someone.country, severity: "good" };
    case "coupleLinked":
      return { ...base, partnerName: b?.displayName ?? null, severity: "good" };
    case "coupleEnded":
      return { ...base, partnerName: b?.displayName ?? null, severity: "warn" };
    case "dareCompleted":
      return { ...base, partnerName: b?.displayName ?? null, detail: pick(random, EXERCISES), severity: "good" };
    case "dareMissed":
      return { ...base, partnerName: b?.displayName ?? null, detail: pick(random, EXERCISES), severity: "info" };
    case "gameFinished":
      return { ...base, partnerName: b?.displayName ?? null, detail: pick(random, GAMES), severity: "info" };
    case "plusStarted":
      return { ...base, partnerName: b?.displayName ?? null, detail: random() < 0.7 ? PRODUCTS[0] : PRODUCTS[1], severity: "good" };
    case "plusEnded":
      return { ...base, detail: PRODUCTS[0], severity: "warn" };
    case "accountDeleted":
      return { ...base, userId: null, displayName: null, severity: "warn" };
    case "alert":
      return { ...base, userId: null, displayName: null, detail: pick(random, ALERTS), severity: random() < 0.5 ? "bad" : "warn" };
  }
}

export const LIVE_MIX: (readonly [LiveKind, number])[] = [
  ["dareCompleted", 34],
  ["signedUp", 15],
  ["gameFinished", 14],
  ["coupleLinked", 11],
  ["dareMissed", 8],
  ["plusStarted", 7],
  ["coupleEnded", 2],
  ["plusEnded", 2],
  ["accountDeleted", 1],
  ["alert", 2],
];

function seedEvents(users: DemoUser[], couples: DemoCouple[], random: Rng): LiveEvent[] {
  const events: LiveEvent[] = [];
  let at = NOW - 20_000;
  for (let index = 0; index < 30; index++) {
    const kind = weighted(
      random,
      LIVE_MIX.filter(([kind]) => kind !== "alert"),
    );
    events.push(liveEvent(kind, users, couples, random, at));
    at -= 30_000 + random() * 20 * 60_000;
  }
  return events;
}

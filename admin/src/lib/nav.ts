import type { IconName } from "../components/Icon";

/* Every page, grouped as the sidebar shows them; the command palette jumps to the same list. */

export type NavPath =
  | "/"
  | "/live"
  | "/alerts"
  | "/engagement"
  | "/funnel"
  | "/retention"
  | "/rhythm"
  | "/dares"
  | "/games"
  | "/growth"
  | "/revenue"
  | "/world"
  | "/couples"
  | "/users"
  | "/payments"
  | "/audit"
  | "/health"
  | "/features"
  | "/system";

export interface NavItem {
  to: NavPath;
  label: string;
  icon: IconName;
  exact?: boolean;
  /** Extra words the command palette matches. */
  keywords?: string[];
}

export const NAV_GROUPS: { title: string; items: NavItem[] }[] = [
  {
    title: "Now",
    items: [
      { to: "/", label: "Overview", icon: "overview", exact: true, keywords: ["home", "kpi", "dashboard"] },
      { to: "/live", label: "Live", icon: "live", keywords: ["feed", "stream", "realtime", "events"] },
      { to: "/alerts", label: "Alerts", icon: "bell", keywords: ["warnings", "problems", "notifications"] },
    ],
  },
  {
    title: "Insights",
    items: [
      { to: "/engagement", label: "Engagement", icon: "engagement", keywords: ["meals", "photos", "questions"] },
      { to: "/funnel", label: "Funnel", icon: "funnel", keywords: ["conversion", "onboarding", "steps"] },
      { to: "/retention", label: "Retention", icon: "retention", keywords: ["cohorts", "churn", "weeks"] },
      { to: "/rhythm", label: "Rhythm", icon: "rhythm", keywords: ["heatmap", "hours", "weekday", "busiest", "time"] },
      { to: "/dares", label: "Dares", icon: "dares", keywords: ["exercises", "streaks", "squats", "workouts"] },
      { to: "/games", label: "Games", icon: "games", keywords: ["duels", "skills", "scores", "play"] },
      { to: "/growth", label: "Growth", icon: "growth", keywords: ["renewals", "lapsed", "path to plus"] },
      { to: "/revenue", label: "Revenue", icon: "revenue", keywords: ["money", "mrr", "subscriptions"] },
      { to: "/world", label: "World", icon: "world", keywords: ["map", "countries", "geography"] },
      { to: "/couples", label: "Top couples", icon: "couples", keywords: ["leaderboard", "hearts", "best"] },
    ],
  },
  {
    title: "People",
    items: [
      { to: "/users", label: "Users", icon: "users", keywords: ["people", "accounts", "search"] },
      { to: "/payments", label: "Payments", icon: "payments", keywords: ["subscriptions", "refunds", "grants", "google play"] },
      { to: "/audit", label: "Audit log", icon: "audit", keywords: ["history", "who did what"] },
    ],
  },
  {
    title: "Ops",
    items: [
      { to: "/health", label: "Health", icon: "health", keywords: ["probes", "workers", "queues", "status", "uptime"] },
      {
        to: "/features",
        label: "Features",
        icon: "toggle",
        keywords: ["christmas", "theme", "festive", "flags", "switches", "holiday", "card", "bachs", "billing choice"],
      },
      { to: "/system", label: "System", icon: "system", keywords: ["settings", "admins", "prices"] },
    ],
  },
];

export const NAV: NavItem[] = NAV_GROUPS.flatMap((group) => group.items);

/** "Good morning" by the admin's own clock; late nights get a gentler line. */
export function greeting(hour: number): string {
  if (hour >= 5 && hour < 12) return "Good morning";
  if (hour >= 12 && hour < 17) return "Good afternoon";
  if (hour >= 17 && hour < 22) return "Good evening";
  return "Up late";
}

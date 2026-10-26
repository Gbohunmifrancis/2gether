import type { Cohort, FunnelKey, FunnelStep, GrowthPathKey } from "../api/types";

/* Formatting for the insight pages, kept pure so it can be tested. Rates the API couldn't compute are null and
   show as "—", never 0. */

export const DASH = "—";

export const funnelLabels: Record<FunnelKey, string> = {
  signedUp: "Signed up",
  invitedOrJoined: "Invited or joined a partner",
  linked: "Linked as a couple",
  firstDareSet: "Set a first dare",
  firstDareDone: "Did a first dare",
  firstHeart: "Made a whole heart",
  activeWeek2: "Came back in week 2",
  plus: "Went Plus",
};

export const pathLabels: Record<GrowthPathKey, string> = {
  signedUp: "Signed up",
  linked: "Linked",
  hitScanLimit: "Hit the food scan limit",
  nearAlbumLimit: "Album nearly full",
  plus: "Plus",
};

/** Hours as "40 min", "5.5 h", "3 days". */
export function duration(hours: number | null | undefined): string {
  if (hours === null || hours === undefined || !Number.isFinite(hours)) return DASH;
  if (hours < 1) return `${Math.max(1, Math.round(hours * 60))} min`;
  if (hours < 48) return `${Number(hours.toFixed(hours < 10 ? 1 : 0))} h`;
  const days = hours / 24;
  return `${Number(days.toFixed(days < 10 ? 1 : 0))} days`;
}

/** Seconds as "45 s", "2 min 5 s", "1 h 3 min". */
export function seconds(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return DASH;
  const total = Math.round(value);
  if (total < 60) return `${total} s`;
  if (total < 3600) {
    const rest = total % 60;
    return `${Math.floor(total / 60)} min${rest ? ` ${rest} s` : ""}`;
  }
  const minutes = Math.round((total % 3600) / 60);
  return `${Math.floor(total / 3600)} h${minutes ? ` ${minutes} min` : ""}`;
}

/** A share 0..1 as "42%", "0.5%", "—" for null. */
export function rate(value: number | null | undefined, digits?: number): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return DASH;
  const pct = value * 100;
  const places = digits ?? (pct > 0 && pct < 1 ? 1 : 0);
  return `${pct.toFixed(places)}%`;
}

export interface FunnelRow {
  key: FunnelKey;
  label: string;
  count: number;
  /** Of everyone who signed up (the first step). */
  share: number | null;
  /** Of the step before. */
  conversion: number | null;
  /** Lost since the step before. */
  dropped: number;
  median: string;
}

export function funnelRows(steps: FunnelStep[]): FunnelRow[] {
  const first = steps[0]?.count ?? 0;
  return steps.map((step, index) => {
    const previous = index > 0 ? steps[index - 1]!.count : null;
    return {
      key: step.key,
      label: funnelLabels[step.key] ?? step.key,
      count: step.count,
      share: first > 0 ? step.count / first : null,
      conversion: previous === null ? null : previous > 0 ? step.count / previous : null,
      dropped: previous === null ? 0 : Math.max(0, previous - step.count),
      median: index === 0 ? "" : duration(step.medianHoursFromPrevious),
    };
  });
}

/** The step where the most people are lost (lowest conversion), for the headline. */
export function biggestDrop(rows: FunnelRow[]): FunnelRow | null {
  let worst: FunnelRow | null = null;
  for (const row of rows) if (row.conversion !== null && (worst === null || row.conversion < worst.conversion!)) worst = row;
  return worst;
}

/** Each week's retention across cohorts, weighted by cohort size, ignoring weeks a cohort hasn't reached. */
export function averageRetention(cohorts: Cohort[]): (number | null)[] {
  const weeks = Math.max(0, ...cohorts.map((cohort) => cohort.retained.length));
  return Array.from({ length: weeks }, (_, week) => {
    let people = 0;
    let kept = 0;
    for (const cohort of cohorts) {
      const value = cohort.retained[week];
      if (value === null || value === undefined || cohort.size <= 0) continue;
      people += cohort.size;
      kept += value * cohort.size;
    }
    return people > 0 ? kept / people : null;
  });
}

/** 0..1 → a band 0..5 for the heat squares, so colour reads in steps rather than a smear; -1 for not yet. */
export function heatLevel(value: number | null | undefined): number {
  if (value === null || value === undefined) return -1;
  if (value <= 0) return 0;
  return Math.min(5, Math.ceil(value * 5));
}

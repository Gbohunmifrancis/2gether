import type { WeekGrid } from "../api/types";

export const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"] as const;

/** Minutes the admin's clock is ahead of UTC right now (London in summer: 60; New York in winter: -300). */
export const localOffsetMinutes = (at = new Date()) => -at.getTimezoneOffset();

/**
 * Moves a UTC weekday×hour grid into a time zone `offsetMinutes` ahead of UTC. An hour of UTC lands on local hour
 * h + offset, wrapping into the next or previous weekday (Sunday wraps to Monday: it's a week of averages, not a
 * calendar). Half-hour zones split each hour's count between the two local hours it straddles.
 */
export function shiftGrid(grid: WeekGrid, offsetMinutes: number): WeekGrid {
  const out = Array.from({ length: 7 }, () => Array<number>(24).fill(0));
  const hours = offsetMinutes / 60;
  const whole = Math.floor(hours);
  const part = hours - whole;
  for (let day = 0; day < 7; day++) {
    for (let hour = 0; hour < 24; hour++) {
      const value = grid[day]?.[hour] ?? 0;
      if (!value) continue;
      const put = (shift: number, share: number) => {
        if (share <= 0) return;
        const slot = (((day * 24 + hour + shift) % 168) + 168) % 168;
        out[Math.floor(slot / 24)]![slot % 24]! += value * share;
      };
      put(whole, 1 - part);
      put(whole + 1, part);
    }
  }
  return out;
}

export function gridMax(grid: WeekGrid): number {
  return Math.max(0, ...grid.flat());
}

export function gridTotal(grid: WeekGrid): number {
  return grid.flat().reduce((sum, value) => sum + value, 0);
}

/** The busiest slot: weekday index, hour and value. */
export function peak(grid: WeekGrid): { day: number; hour: number; value: number } | null {
  let best: { day: number; hour: number; value: number } | null = null;
  for (let day = 0; day < grid.length; day++) {
    const row = grid[day]!;
    for (let hour = 0; hour < row.length; hour++) {
      const value = row[hour]!;
      if (value > 0 && (best === null || value > best.value)) best = { day, hour, value };
    }
  }
  return best;
}

/** 12-hour labels for the axis: "12 am", "9 am", "3 pm". */
export function hourLabel(hour: number): string {
  const h = ((hour % 24) + 24) % 24;
  const twelve = h % 12 === 0 ? 12 : h % 12;
  return `${twelve} ${h < 12 ? "am" : "pm"}`;
}

/** "UTC", "UTC+1", "UTC−5", "UTC+5:30". */
export function offsetLabel(offsetMinutes: number): string {
  if (offsetMinutes === 0) return "UTC";
  const sign = offsetMinutes > 0 ? "+" : "−";
  const size = Math.abs(offsetMinutes);
  const minutes = size % 60;
  return `UTC${sign}${Math.floor(size / 60)}${minutes ? `:${String(minutes).padStart(2, "0")}` : ""}`;
}

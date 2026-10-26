import { describe, expect, it } from "vitest";
import { searchCommands, scoreCommand, type Command } from "./commands";
import { averageRetention, biggestDrop, duration, funnelRows, heatLevel, rate, seconds } from "./insights";
import { hourLabel, offsetLabel, peak, shiftGrid } from "./rhythm";

const grid = (fill: (day: number, hour: number) => number) => Array.from({ length: 7 }, (_, day) => Array.from({ length: 24 }, (_, hour) => fill(day, hour)));

describe("shiftGrid (rhythm in the admin's time zone)", () => {
  it("moves hours forward for zones ahead of UTC, wrapping Sunday night into Monday", () => {
    const utc = grid((day, hour) => (day === 6 && hour === 23 ? 5 : day === 0 && hour === 9 ? 2 : 0));
    const london = shiftGrid(utc, 60);
    expect(london[0]![0]).toBe(5); // Sunday 23:00 UTC is Monday 00:00 in UTC+1
    expect(london[0]![10]).toBe(2);
    expect(london[6]![23]).toBe(0);
  });

  it("moves hours back for zones behind UTC, wrapping Monday morning into Sunday", () => {
    const utc = grid((day, hour) => (day === 0 && hour === 2 ? 7 : 0));
    const newYork = shiftGrid(utc, -300);
    expect(newYork[6]![21]).toBe(7); // Monday 02:00 UTC is Sunday 21:00 in UTC−5
  });

  it("splits half-hour zones across the two hours, keeping the total", () => {
    const utc = grid((day, hour) => (day === 2 && hour === 10 ? 10 : 0));
    const india = shiftGrid(utc, 330);
    expect(india[2]![15]).toBe(5);
    expect(india[2]![16]).toBe(5);
    expect(india.flat().reduce((a, b) => a + b, 0)).toBe(10);
  });

  it("leaves UTC alone and finds the peak", () => {
    const utc = grid((day, hour) => day * 24 + hour);
    expect(shiftGrid(utc, 0)).toEqual(utc);
    expect(peak(utc)).toEqual({ day: 6, hour: 23, value: 167 });
    expect(peak(grid(() => 0))).toBeNull();
  });

  it("labels hours and offsets", () => {
    expect([0, 9, 12, 21].map(hourLabel)).toEqual(["12 am", "9 am", "12 pm", "9 pm"]);
    expect([0, 60, -300, 330].map(offsetLabel)).toEqual(["UTC", "UTC+1", "UTC−5", "UTC+5:30"]);
  });
});

describe("funnel formatting", () => {
  const rows = funnelRows([
    { key: "signedUp", count: 200, medianHoursFromPrevious: null },
    { key: "invitedOrJoined", count: 150, medianHoursFromPrevious: 0.5 },
    { key: "linked", count: 60, medianHoursFromPrevious: 30 },
    { key: "firstDareSet", count: 0, medianHoursFromPrevious: null },
    { key: "firstDareDone", count: 0, medianHoursFromPrevious: null },
  ]);

  it("gives each step its share of sign-ups, conversion from the step before, losses and median time", () => {
    expect(rows[0]).toMatchObject({ label: "Signed up", share: 1, conversion: null, dropped: 0, median: "" });
    expect(rows[1]).toMatchObject({ share: 0.75, conversion: 0.75, dropped: 50, median: "30 min" });
    expect(rows[2]).toMatchObject({ share: 0.3, conversion: 0.4, dropped: 90, median: "30 h" });
    // Nobody reached the step before: the rate can't be computed, so it's null ("—"), not 0.
    expect(rows[4]!.conversion).toBeNull();
    expect(rows[3]!.median).toBe("—");
    expect(biggestDrop(rows)?.key).toBe("firstDareSet");
  });

  it("says nothing about shares when nobody signed up", () => {
    expect(funnelRows([{ key: "signedUp", count: 0, medianHoursFromPrevious: null }])[0]!.share).toBeNull();
  });

  it("formats durations, seconds and rates", () => {
    expect([0.01, 0.75, 5.25, 30, 100, 400].map(duration)).toEqual(["1 min", "45 min", "5.3 h", "30 h", "4.2 days", "17 days"]);
    expect(duration(null)).toBe("—");
    expect([45, 125, 3600, 3725].map(seconds)).toEqual(["45 s", "2 min 5 s", "1 h", "1 h 2 min"]);
    expect([0.42, 0.004, 0, 1].map((value) => rate(value))).toEqual(["42%", "0.4%", "0%", "100%"]);
    expect(rate(null)).toBe("—");
  });
});

describe("retention formatting", () => {
  it("averages each week weighted by cohort size, skipping weeks a cohort hasn't reached", () => {
    const average = averageRetention([
      { weekStart: "2026-09-07", size: 100, retained: [1, 0.5, 0.4] },
      { weekStart: "2026-09-14", size: 50, retained: [1, 0.8, null] },
    ]);
    expect(average[0]).toBe(1);
    expect(average[1]).toBeCloseTo(0.6);
    expect(average[2]).toBeCloseTo(0.4);
    expect(averageRetention([{ weekStart: "x", size: 0, retained: [null] }])).toEqual([null]);
  });

  it("bands values for the heat squares", () => {
    expect([null, 0, 0.05, 0.2, 0.21, 0.5, 0.99, 1].map(heatLevel)).toEqual([-1, 0, 1, 1, 2, 3, 5, 5]);
  });
});

describe("command palette search", () => {
  const commands: Command[] = [
    { id: "overview", label: "Overview", group: "Pages", keywords: ["home"] },
    { id: "retention", label: "Retention", group: "Pages", keywords: ["cohorts"] },
    { id: "rhythm", label: "Rhythm", group: "Pages", keywords: ["heatmap"] },
    { id: "users", label: "Users", group: "Pages" },
    { id: "audit", label: "Audit log", group: "Pages" },
    { id: "sound", label: "Turn sounds on", group: "Actions" },
  ];

  it("ranks prefixes, then words, then substrings, then letters in order", () => {
    expect(
      searchCommands("r", commands)
        .map((item) => item.id)
        .slice(0, 2),
    ).toEqual(["retention", "rhythm"]);
    expect(searchCommands("log", commands)[0]!.id).toBe("audit");
    expect(searchCommands("rtn", commands).map((item) => item.id)).toEqual(["retention"]);
    expect(searchCommands("cohorts", commands)[0]!.id).toBe("retention");
    expect(searchCommands("sound", commands)[0]!.id).toBe("sound");
    expect(searchCommands("zzz", commands)).toEqual([]);
  });

  it("matches without accents or case, and shows everything for an empty query in order", () => {
    expect(scoreCommand("RÉTENTION", commands[1]!)).toBeGreaterThan(0);
    expect(searchCommands("", commands).map((item) => item.id)).toEqual(commands.map((item) => item.id));
  });
});

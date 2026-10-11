import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { BarList, bucket, niceTicks, TimeChart } from "./charts";

const days = (values: number[]) => values.map((value, index) => ({ date: `2026-10-${String(index + 1).padStart(2, "0")}`, value }));

describe("niceTicks", () => {
  it("steps by 1, 2 or 5 from zero and covers the max", () => {
    expect(niceTicks(0)).toEqual([0, 1]);
    expect(niceTicks(3)).toEqual([0, 1, 2, 3]);
    expect(niceTicks(7)).toEqual([0, 2, 4, 6, 8]);
    expect(niceTicks(148)).toEqual([0, 50, 100, 150]);
    expect(niceTicks(0.4)).toEqual([0, 0.1, 0.2, 0.3, 0.4]);
    expect(niceTicks(2, 4, true)).toEqual([0, 1, 2]);
  });
});

describe("bucket", () => {
  it("sums days into groups dated by their first day", () => {
    expect(bucket(days([1, 2, 3, 4, 5]), 2)).toEqual([
      { date: "2026-10-01", value: 3 },
      { date: "2026-10-03", value: 7 },
      { date: "2026-10-05", value: 5 },
    ]);
  });
});

describe("TimeChart", () => {
  it("describes itself and carries the numbers in a table", () => {
    render(<TimeChart label="Sign-ups per day" kind="bars" series={[{ name: "Sign-ups", values: days([0, 4, 2]), color: "red" }]} />);
    expect(screen.getByRole("img", { name: "Sign-ups per day" })).toBeTruthy();
    const cells = screen.getAllByRole("cell").map((cell) => cell.textContent);
    expect(cells).toEqual(["2026-10-01", "0", "2026-10-02", "4", "2026-10-03", "2"]);
  });

  it("weeks a long range of bars", () => {
    render(
      <TimeChart
        label="A year"
        kind="bars"
        series={[
          {
            name: "N",
            values: Array.from({ length: 365 }, (_, i) => ({ date: new Date(Date.UTC(2026, 0, 1 + i)).toISOString().slice(0, 10), value: 1 })),
            color: "red",
          },
        ]}
      />,
    );
    expect(screen.getByRole("columnheader", { name: "Week of" })).toBeTruthy();
    expect(screen.getAllByRole("row")).toHaveLength(1 + 53);
  });
});

describe("BarList", () => {
  it("sorts, shows shares and folds the tail", () => {
    render(
      <BarList
        limit={2}
        items={[
          { name: "b", count: 1 },
          { name: "a", count: 6 },
          { name: "c", count: 3 },
        ]}
      />,
    );
    const text = document.body.textContent ?? "";
    expect(text.indexOf("a")).toBeLessThan(text.indexOf("c"));
    expect(screen.getByText("Everything else")).toBeTruthy();
    expect(text).toContain("60%");
  });

  it("says when there is nothing", () => {
    render(<BarList items={[]} empty="No devices yet." />);
    expect(screen.getByText("No devices yet.")).toBeTruthy();
  });
});

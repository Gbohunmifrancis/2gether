import { useState, type ReactNode } from "react";
import type { DayValue } from "../api/types";
import { useCountUp } from "../lib/motion";
import { count } from "../lib/format";
import { Icon } from "./Icon";

/* Building blocks shared by the charts: a card that switches any chart to a table, a plain table, and numbers that
   count up. */

export interface TableSpec {
  caption: string;
  columns: { label: string; numeric?: boolean }[];
  rows: ReactNode[][];
}

export function PlainTable({ spec }: { spec: TableSpec }) {
  return (
    <div className="table-wrap viz-table">
      <table className="data">
        <caption className="visually-hidden">{spec.caption}</caption>
        <thead>
          <tr>
            {spec.columns.map((column) => (
              <th key={column.label} scope="col" className={column.numeric ? "num" : undefined}>
                {column.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {spec.rows.map((row, index) => (
            <tr key={index}>
              {row.map((cell, cellIndex) => (
                <td key={cellIndex} className={spec.columns[cellIndex]?.numeric ? "num" : undefined}>
                  {cell}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * A card whose chart can be swapped for the same numbers as a table, for screen readers, copying and anyone who'd
 * rather read than look.
 */
export function ChartCard({
  title,
  aside,
  table,
  children,
  className,
  defaultView = "chart",
}: {
  title: ReactNode;
  aside?: ReactNode;
  table: TableSpec | (() => TableSpec);
  children: ReactNode;
  className?: string;
  defaultView?: "chart" | "table";
}) {
  const [view, setView] = useState(defaultView);
  const spec = view === "table" ? (typeof table === "function" ? table() : table) : null;
  return (
    <section className={`card chart-card ${className ?? ""}`}>
      <div className="card-head">
        <h2 className="title-s">{title}</h2>
        <div className="card-tools">
          {aside ? <span className="small faint">{aside}</span> : null}
          <div className="mini-seg" role="group" aria-label="Show as">
            <button type="button" aria-pressed={view === "chart"} onClick={() => setView("chart")} title="Chart">
              <Icon name="chart" label="Chart" />
            </button>
            <button type="button" aria-pressed={view === "table"} onClick={() => setView("table")} title="Table">
              <Icon name="table" label="Table" />
            </button>
          </div>
        </div>
      </div>
      {spec ? <PlainTable spec={spec} /> : children}
    </section>
  );
}

export function seriesTable(caption: string, series: { name: string; values: DayValue[] }[]): TableSpec {
  return {
    caption,
    columns: [{ label: "Day" }, ...series.map((item) => ({ label: item.name, numeric: true }))],
    rows: (series[0]?.values ?? []).map((point, index) => [point.date, ...series.map((item) => count(item.values[index]?.value ?? 0))]),
  };
}

/** A whole number that counts up when it first shows and when it changes. */
export function CountUp({ value, format = count }: { value: number; format?: (value: number) => string }) {
  const shown = useCountUp(value);
  const whole = Number.isInteger(value);
  return (
    <span className="count-up" aria-label={format(value)}>
      <span aria-hidden="true">{format(whole ? Math.round(shown) : shown)}</span>
    </span>
  );
}

/** Up or down against a previous number: "▲ 12% vs last week". Nothing when there's no fair comparison. */
export function Delta({ now, before, label, invert }: { now: number; before: number | null | undefined; label: string; invert?: boolean }) {
  if (before === null || before === undefined || before <= 0) return null;
  const change = (now - before) / before;
  if (!Number.isFinite(change)) return null;
  const flat = Math.abs(change) < 0.005;
  const good = invert ? change < 0 : change > 0;
  return (
    <span className={`delta ${flat ? "flat" : good ? "up" : "down"}`}>
      <span aria-hidden="true">{flat ? "●" : change > 0 ? "▲" : "▼"}</span>{" "}
      {flat ? "Level" : `${Math.abs(change * 100).toFixed(Math.abs(change) < 0.1 ? 1 : 0)}%`} <span className="faint">{label}</span>
    </span>
  );
}

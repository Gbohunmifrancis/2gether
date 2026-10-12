import { useLayoutEffect, useMemo, useRef, useState, type PointerEvent } from "react";
import type { DayValue, NamedCount } from "../api/types";
import { compact, count, shortDay } from "../lib/format";

export interface Series {
  name: string;
  values: DayValue[];
  /** A CSS colour, normally one of the --chart-* tokens. */
  color: string;
}

/** Round axis ticks from zero: steps of 1, 2 or 5 × 10ⁿ, at most `target` steps (whole steps for counts). */
export function niceTicks(max: number, target = 4, integer = false): number[] {
  if (!(max > 0)) return [0, 1];
  // Counts can't be fractional, so a whole-number axis never steps by less than one.
  const rough = integer ? Math.max(1, max / target) : max / target;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 5, 10].map((m) => m * magnitude).find((candidate) => candidate >= rough) ?? 10 * magnitude;
  const ticks: number[] = [];
  for (let value = 0; value < max + step * 0.999; value += step) ticks.push(Number(value.toPrecision(12)));
  if (ticks.length < 2) ticks.push(step);
  return ticks;
}

/** Sums days into weeks (by position) so a year of bars stays legible. */
export function bucket(values: DayValue[], size: number): DayValue[] {
  if (size <= 1) return values;
  const out: DayValue[] = [];
  for (let start = 0; start < values.length; start += size) {
    const chunk = values.slice(start, start + size);
    out.push({ date: chunk[0]!.date, value: chunk.reduce((sum, item) => sum + item.value, 0) });
  }
  return out;
}

function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(640);
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    setWidth(element.clientWidth || 640);
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => entry && setWidth(Math.max(240, entry.contentRect.width)));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}

const PAD = { top: 12, right: 8, bottom: 26, left: 40 };

/**
 * Days along the bottom, counts up the side. Lines for levels (daily active people), stacked bars
 * for events (sign-ups, dares). Hover or tap shows the day's numbers; a hidden table carries the data.
 */
export function TimeChart({ series, kind = "line", height = 220, label }: { series: Series[]; kind?: "line" | "bars"; height?: number; label: string }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);

  const days = series[0]?.values.length ?? 0;
  const size = kind === "bars" && days > 120 ? 7 : 1;
  const data = useMemo(() => series.map((item) => ({ ...item, values: bucket(item.values, size) })), [series, size]);
  const points = data[0]?.values.length ?? 0;

  const totals = Array.from({ length: points }, (_, index) =>
    kind === "bars"
      ? data.reduce((sum, item) => sum + (item.values[index]?.value ?? 0), 0)
      : Math.max(0, ...data.map((item) => item.values[index]?.value ?? 0)),
  );
  const ticks = niceTicks(Math.max(0, ...totals), 4, true);
  const top = ticks[ticks.length - 1]!;
  const innerWidth = Math.max(10, width - PAD.left - PAD.right);
  const innerHeight = height - PAD.top - PAD.bottom;
  const y = (value: number) => PAD.top + innerHeight - (value / top) * innerHeight;
  const band = innerWidth / Math.max(1, points);
  const x = (index: number) =>
    kind === "bars" ? PAD.left + band * index + band / 2 : PAD.left + (points <= 1 ? innerWidth / 2 : (innerWidth * index) / (points - 1));

  const labelEvery = Math.max(1, Math.ceil(points / Math.max(2, Math.floor(innerWidth / 72))));
  const xLabels = Array.from({ length: points }, (_, index) => index).filter((index) => index % labelEvery === 0);

  function onMove(event: PointerEvent<SVGSVGElement>) {
    const box = event.currentTarget.getBoundingClientRect();
    const px = ((event.clientX - box.left) / box.width) * width;
    const index = kind === "bars" ? Math.floor((px - PAD.left) / band) : Math.round(((px - PAD.left) / innerWidth) * (points - 1));
    setHover(index >= 0 && index < points ? index : null);
  }

  const hoverDate = hover === null ? null : data[0]?.values[hover]?.date;

  return (
    <figure className="chart" ref={ref}>
      <svg
        width={width}
        height={height}
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label={label}
        onPointerMove={onMove}
        onPointerLeave={() => setHover(null)}
      >
        {ticks.map((tick) => (
          <g key={tick}>
            <line className={tick === 0 ? "baseline" : "grid-line"} x1={PAD.left} x2={width - PAD.right} y1={y(tick)} y2={y(tick)} />
            <text className="axis" x={PAD.left - 8} y={y(tick)} dy="0.35em" textAnchor="end">
              {compact(tick)}
            </text>
          </g>
        ))}
        {xLabels.map((index) => (
          <text key={index} className="axis" x={x(index)} y={height - 6} textAnchor={kind === "line" && index === 0 ? "start" : "middle"}>
            {shortDay(data[0]!.values[index]!.date)}
          </text>
        ))}

        {kind === "bars"
          ? Array.from({ length: points }, (_, index) => {
              let base = 0;
              const barWidth = Math.max(1, Math.min(28, band * 0.72));
              return (
                <g key={index} opacity={hover === null || hover === index ? 1 : 0.55}>
                  {data.map((item) => {
                    const value = item.values[index]?.value ?? 0;
                    if (value <= 0) return null;
                    const y0 = y(base);
                    base += value;
                    const y1 = y(base);
                    // 2px gap between stacked segments reads as two stickers, not one bar.
                    return (
                      <rect
                        key={item.name}
                        x={x(index) - barWidth / 2}
                        y={y1}
                        width={barWidth}
                        height={Math.max(1, y0 - y1 - (base === value ? 0 : 2))}
                        rx={Math.min(4, barWidth / 3)}
                        fill={item.color}
                      />
                    );
                  })}
                </g>
              );
            })
          : data.map((item, seriesIndex) => {
              const path = item.values.map((point, index) => `${index === 0 ? "M" : "L"}${x(index).toFixed(1)},${y(point.value).toFixed(1)}`).join("");
              return (
                <g key={item.name}>
                  {seriesIndex === 0 && points > 1 ? <path d={`${path}L${x(points - 1)},${y(0)}L${x(0)},${y(0)}Z`} fill={item.color} opacity={0.1} /> : null}
                  <path d={path} fill="none" stroke={item.color} strokeWidth={2.5} strokeLinejoin="round" strokeLinecap="round" />
                  {hover !== null && item.values[hover] ? (
                    <circle cx={x(hover)} cy={y(item.values[hover]!.value)} r={4.5} fill={item.color} stroke="var(--sticker)" strokeWidth={2} />
                  ) : null}
                </g>
              );
            })}
        {hover !== null && kind === "line" ? (
          <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + innerHeight} stroke="var(--rule-2)" strokeDasharray="3 3" />
        ) : null}
      </svg>

      {hover !== null && hoverDate ? (
        <div className="tip" style={{ left: Math.min(Math.max(x(hover), 70), width - 70) }}>
          <div className="faint" style={{ color: "inherit", opacity: 0.75 }}>
            {size > 1 ? `Week of ${shortDay(hoverDate)}` : shortDay(hoverDate)}
          </div>
          {data.map((item) => (
            <div key={item.name}>
              {data.length > 1 ? `${item.name}: ` : ""}
              <b>{count(item.values[hover]?.value ?? 0)}</b>
            </div>
          ))}
        </div>
      ) : null}

      {data.length > 1 ? (
        <figcaption className="legend" style={{ marginTop: 8 }}>
          {data.map((item) => (
            <span key={item.name}>
              <i style={{ background: item.color }} />
              {item.name}
            </span>
          ))}
        </figcaption>
      ) : null}

      <div className="visually-hidden">
        <table>
          <caption>{label}</caption>
          <thead>
            <tr>
              <th>{size > 1 ? "Week of" : "Day"}</th>
              {data.map((item) => (
                <th key={item.name}>{item.name}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data[0]?.values.map((point, index) => (
              <tr key={point.date}>
                <td>{point.date}</td>
                {data.map((item) => (
                  <td key={item.name}>{item.values[index]?.value ?? 0}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </figure>
  );
}

/** Categories as labelled horizontal bars, largest first, with the share of the total. */
export function BarList({
  items,
  format = (item) => item.name,
  empty = "Nothing yet.",
  limit = 8,
}: {
  items: NamedCount[];
  format?: (item: NamedCount) => string;
  empty?: string;
  limit?: number;
}) {
  if (items.length === 0) return <div className="faint small">{empty}</div>;
  const sorted = [...items].sort((a, b) => b.count - a.count);
  const shown = sorted.slice(0, limit);
  const rest = sorted.slice(limit).reduce((sum, item) => sum + item.count, 0);
  const total = sorted.reduce((sum, item) => sum + item.count, 0);
  const max = Math.max(1, ...shown.map((item) => item.count), rest);
  const rows =
    rest > 0
      ? [...shown.map((item) => ({ label: format(item), value: item.count })), { label: "Everything else", value: rest }]
      : shown.map((item) => ({ label: format(item), value: item.count }));
  return (
    <div className="bars">
      {rows.map((row) => (
        <div className="bar-row" key={row.label}>
          <span>{row.label}</span>
          <span>
            <b>{count(row.value)}</b> <span className="faint">{total > 0 ? `${Math.round((row.value / total) * 100)}%` : ""}</span>
          </span>
          <div className="track" aria-hidden="true">
            <div className="fill" style={{ width: `${(row.value / max) * 100}%` }} />
          </div>
        </div>
      ))}
    </div>
  );
}

/** A slim proportion bar for two-part splits (completed vs missed). */
export function SplitBar({ parts }: { parts: { label: string; value: number; color: string }[] }) {
  const total = parts.reduce((sum, part) => sum + part.value, 0);
  return (
    <div>
      <div style={{ display: "flex", gap: 3, height: 12, borderRadius: 6, overflow: "hidden", background: "var(--sticker-2)" }} aria-hidden="true">
        {total > 0 ? parts.map((part) => (part.value > 0 ? <div key={part.label} style={{ flex: part.value, background: part.color }} /> : null)) : null}
      </div>
      <div className="legend" style={{ marginTop: 8 }}>
        {parts.map((part) => (
          <span key={part.label}>
            <i style={{ background: part.color }} />
            {part.label} {count(part.value)}
          </span>
        ))}
      </div>
    </div>
  );
}

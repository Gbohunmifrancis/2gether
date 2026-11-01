import type { ReactNode } from "react";
import { count } from "../lib/format";
import { CountUp, Delta } from "./Viz";

/**
 * A KPI: a label, the figure (counting up) and a comparison with the period before when there is a fair one. A tone
 * only marks a status (a service down, a worker behind), as a small dot by the label.
 */
export function KpiSticker({
  label,
  value,
  format = count,
  hint,
  tone,
  delta,
  text,
}: {
  label: string;
  value: number | null;
  /** Words instead of a number (a day, an hour). */
  text?: ReactNode;
  format?: (value: number) => string;
  hint?: ReactNode;
  tone?: "good" | "bad" | "warn";
  delta?: { now: number; before: number | null | undefined; label: string; invert?: boolean };
}) {
  return (
    <div className={`card stat kpi${tone ? ` ${tone}` : ""}`}>
      <div className="label">
        {tone ? <span className="kpi-dot" aria-hidden="true" /> : null}
        {label}
      </div>
      <div className={`value${text ? " words" : ""}`}>{text ?? (value === null ? "—" : <CountUp value={value} format={format} />)}</div>
      {delta ? <Delta now={delta.now} before={delta.before} label={delta.label} invert={delta.invert} /> : null}
      {hint ? <div className="hint">{hint}</div> : null}
    </div>
  );
}

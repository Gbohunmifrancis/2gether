import type { ReactNode } from "react";
import { count } from "../lib/format";
import { Icon, type IconName } from "./Icon";
import { CountUp, Delta } from "./Viz";

/** A KPI on a sticker: the number counts up, with a comparison to the period before when there is a fair one. */
export function KpiSticker({
  label,
  value,
  format = count,
  hint,
  icon,
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
  icon?: IconName;
  tone?: "pen" | "good" | "bad" | "warn";
  delta?: { now: number; before: number | null | undefined; label: string; invert?: boolean };
}) {
  return (
    <div className={`card stat kpi${tone ? ` ${tone}` : ""}`}>
      <div className="kpi-top">
        <div className="label">{label}</div>
        {icon ? (
          <span className="kpi-icon" aria-hidden="true">
            <Icon name={icon} />
          </span>
        ) : null}
      </div>
      <div className={`value${text ? " words" : ""}`}>{text ?? (value === null ? "—" : <CountUp value={value} format={format} />)}</div>
      {delta ? <Delta now={delta.now} before={delta.before} label={delta.label} invert={delta.invert} /> : null}
      {hint ? <div className="hint">{hint}</div> : null}
    </div>
  );
}

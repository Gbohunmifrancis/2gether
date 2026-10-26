import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { useId, useState, type CSSProperties } from "react";
import { queries } from "../api/queries";
import { WHOLE } from "../components/Art";
import { RangePicker } from "../components/RangePicker";
import { ErrorState, LoadingCards, PageHead } from "../components/ui";
import { ChartCard } from "../components/Viz";
import { KpiSticker } from "../components/Kpi";
import { count } from "../lib/format";
import { biggestDrop, funnelRows, rate, type FunnelRow } from "../lib/insights";

const route = getRouteApi("/_app/funnel");

// The heart's height in its 100-unit box: lobes' top at 6.4, tip at 100.
const TOP = 6.4;
const BOTTOM = 100;

/** Colour for level i of n: periwinkle up top, deepening to indigo; Plus in the pen pink. */
function levelColor(row: FunnelRow, index: number, total: number): string {
  if (row.key === "plus") return "var(--pen)";
  const depth = Math.round(18 + (index / Math.max(1, total - 2)) * 82);
  return `color-mix(in oklab, var(--chart-1) ${depth}%, var(--chart-3))`;
}

/** A wavy surface at height y, wider than the heart so it can slide sideways. */
const wave = (y: number) => {
  let d = `M-60 ${y}`;
  for (let x = -60; x < 160; x += 20) d += ` q5 -1.6 10 0 t10 0`;
  return `${d} V110 H-60 Z`;
};

function HeartJar({ rows, active, onHover }: { rows: FunnelRow[]; active: number | null; onHover: (index: number | null) => void }) {
  const id = useId().replace(/:/g, "");
  return (
    <svg viewBox="-4 2 108 102" className="jar" role="img" aria-label="Funnel as a heart filling level by level; the table beside it has the numbers">
      <defs>
        <clipPath id={`jar${id}`}>
          <path d={WHOLE} />
        </clipPath>
      </defs>
      <path d={WHOLE} fill="var(--sticker-2)" />
      <g clipPath={`url(#jar${id})`}>
        {rows.map((row, index) => {
          const level = BOTTOM - (row.share ?? 0) * (BOTTOM - TOP);
          return (
            <g
              key={row.key}
              className={`jar-level${active !== null && active !== index ? " dim" : ""}`}
              style={{ "--i": index } as CSSProperties}
              onPointerEnter={() => onHover(index)}
              onPointerLeave={() => onHover(null)}
            >
              <path className="liquid" d={wave(level)} fill={levelColor(row, index, rows.length)} />
            </g>
          );
        })}
      </g>
      <path d={WHOLE} fill="none" stroke="var(--sticker)" strokeWidth="3.5" strokeLinejoin="round" />
      <path d="M50 18 L50 98" stroke="var(--sticker)" strokeWidth="1.6" strokeDasharray="3.5 3.5" opacity="0.7" />
      {active !== null && rows[active] ? (
        <g className="jar-mark">
          <line x1="2" x2="98" y1={BOTTOM - (rows[active]!.share ?? 0) * (BOTTOM - TOP)} y2={BOTTOM - (rows[active]!.share ?? 0) * (BOTTOM - TOP)} />
        </g>
      ) : null}
    </svg>
  );
}

export function FunnelPage() {
  const { days } = route.useSearch();
  const { data, error, isPending, refetch } = useQuery(queries.funnel(days));
  const [active, setActive] = useState<number | null>(null);

  return (
    <>
      <PageHead title="Funnel" sub={`People who signed up in the last ${days} days, and how far they got.`}>
        <RangePicker days={days} />
      </PageHead>
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : (
        (() => {
          const rows = funnelRows(data.steps);
          const drop = biggestDrop(rows);
          const linked = rows.find((row) => row.key === "linked");
          const heart = rows.find((row) => row.key === "firstHeart");
          const plus = rows.find((row) => row.key === "plus");
          return (
            <>
              <div className="grid cols-4">
                <KpiSticker label="Signed up" icon="userPlus" value={rows[0]?.count ?? 0} hint={`in the last ${days} days`} />
                <KpiSticker label="Linked as a couple" icon="link" value={linked?.count ?? null} hint={`${rate(linked?.share)} of sign-ups`} />
                <KpiSticker label="Made a whole heart" icon="heart" value={heart?.count ?? null} hint={`${rate(heart?.share)} of sign-ups`} />
                <KpiSticker label="Went Plus" icon="plus" tone="pen" value={plus?.count ?? null} hint={`${rate(plus?.share, 1)} of sign-ups`} />
              </div>
              <ChartCard
                title="The heart jar"
                aside={drop ? `Most lost before “${drop.label.toLowerCase()}”` : undefined}
                table={{
                  caption: "Funnel steps",
                  columns: [
                    { label: "Step" },
                    { label: "People", numeric: true },
                    { label: "Of sign-ups", numeric: true },
                    { label: "From the step before", numeric: true },
                    { label: "Median time", numeric: true },
                  ],
                  rows: rows.map((row) => [row.label, count(row.count), rate(row.share), row.conversion === null ? "" : rate(row.conversion), row.median]),
                }}
              >
                <div className="funnel">
                  <HeartJar rows={rows} active={active} onHover={setActive} />
                  <ol className="funnel-steps">
                    {rows.map((row, index) => (
                      <li
                        key={row.key}
                        className={`${active === index ? "on" : ""}${drop?.key === row.key ? " drop" : ""}`}
                        onPointerEnter={() => setActive(index)}
                        onPointerLeave={() => setActive(null)}
                        onFocus={() => setActive(index)}
                        onBlur={() => setActive(null)}
                        tabIndex={0}
                        style={{ "--i": index } as CSSProperties}
                      >
                        {index > 0 ? (
                          <div className="step-gap">
                            <span className="conv">{rate(row.conversion)}</span> carried on
                            {row.median !== "—" ? <span className="faint"> · median {row.median}</span> : <span className="faint"> · time —</span>}
                            {row.dropped ? <span className="faint"> · {count(row.dropped)} stopped here</span> : null}
                          </div>
                        ) : null}
                        <div className="step">
                          <i className="swatch" style={{ background: levelColor(row, index, rows.length) }} />
                          <span className="step-label">{row.label}</span>
                          <b>{count(row.count)}</b>
                          <span className="step-share">{rate(row.share)}</span>
                        </div>
                      </li>
                    ))}
                  </ol>
                </div>
              </ChartCard>
              <p className="small faint" style={{ marginTop: 12 }}>
                A whole heart: both partners have done a dare the other set. Week 2: active on days 8 to 14 after signing up. Median times are from the step
                before; “—” where there isn't enough to tell yet.
              </p>
            </>
          );
        })()
      )}
    </>
  );
}

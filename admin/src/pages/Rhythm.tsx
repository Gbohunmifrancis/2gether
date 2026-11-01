import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { useMemo, useState, type CSSProperties } from "react";
import { queries } from "../api/queries";
import type { Rhythm } from "../api/types";
import { RangePicker } from "../components/RangePicker";
import { ErrorState, LoadingCards, PageHead } from "../components/ui";
import { ChartCard } from "../components/Viz";
import { KpiSticker } from "../components/Kpi";
import { count } from "../lib/format";
import { gridMax, gridTotal, hourLabel, localOffsetMinutes, offsetLabel, peak, shiftGrid, WEEKDAYS } from "../lib/rhythm";

const route = getRouteApi("/_app/rhythm");

const LAYERS: { key: keyof Omit<Rhythm, "days">; label: string; noun: string }[] = [
  { key: "actions", label: "Everything", noun: "actions" },
  { key: "daresDone", label: "Dares done", noun: "dares done" },
  { key: "rings", label: "Alarms ringing", noun: "alarms" },
];

const timeZone = (() => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone;
  } catch {
    return "your time zone";
  }
})();

export function RhythmPage() {
  const { days } = route.useSearch();
  const { data, error, isPending, refetch } = useQuery(queries.rhythm(days));
  const [layer, setLayer] = useState<(typeof LAYERS)[number]["key"]>("actions");
  const [hover, setHover] = useState<{ day: number; hour: number } | null>(null);
  const offset = localOffsetMinutes();
  const meta = LAYERS.find((item) => item.key === layer)!;
  const grid = useMemo(() => (data ? shiftGrid(data[layer], offset) : null), [data, layer, offset]);

  return (
    <>
      <PageHead
        title="Rhythm"
        sub={`When couples do things, by weekday and hour, over the last ${days} days. Shown in your time (${timeZone === "UTC" || timeZone === "Etc/UTC" ? "UTC" : `${timeZone}, ${offsetLabel(offset)}`}).`}
      >
        <RangePicker days={days} ranges={[7, 30, 90]} />
      </PageHead>
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending || !grid ? (
        <LoadingCards />
      ) : (
        (() => {
          const max = Math.max(1, gridMax(grid));
          const best = peak(grid);
          const totals = WEEKDAYS.map((_, day) => grid[day]!.reduce((sum, value) => sum + value, 0));
          const busiestDay = totals.indexOf(Math.max(...totals));
          const hourTotals = Array.from({ length: 24 }, (_, hour) => grid.reduce((sum, row) => sum + row[hour]!, 0));
          const quiet = hourTotals.indexOf(Math.min(...hourTotals));
          const shown = hover ? grid[hover.day]![hover.hour]! : null;
          return (
            <>
              <div className="grid cols-4">
                <KpiSticker label={`All ${meta.noun}`} value={Math.round(gridTotal(grid))} hint={`in ${days} days`} />
                <KpiSticker
                  label="Busiest hour"
                  value={null}
                  text={best ? `${WEEKDAYS[best.day]!.slice(0, 3)} ${hourLabel(best.hour)}` : undefined}
                  hint={best ? `${count(Math.round(best.value))} ${meta.noun} in that hour` : "Nothing yet"}
                />
                <KpiSticker label="Busiest day" value={null} text={WEEKDAYS[busiestDay]} hint={`${count(Math.round(totals[busiestDay] ?? 0))} ${meta.noun}`} />
                <KpiSticker label="Quietest hour" value={null} text={hourLabel(quiet)} hint="Fewest, across the week" />
              </div>
              <ChartCard
                title="The week, hour by hour"
                aside={
                  <span className="seg small-seg" role="group" aria-label="What to show">
                    {LAYERS.map((item) => (
                      <button
                        key={item.key}
                        type="button"
                        className={item.key === layer ? "on" : undefined}
                        aria-pressed={item.key === layer}
                        onClick={() => setLayer(item.key)}
                      >
                        {item.label}
                      </button>
                    ))}
                  </span>
                }
                table={() => ({
                  caption: `${meta.label} by weekday and hour, your time`,
                  columns: [{ label: "Hour" }, ...WEEKDAYS.map((day) => ({ label: day.slice(0, 3), numeric: true }))],
                  rows: Array.from({ length: 24 }, (_, hour) => [hourLabel(hour), ...WEEKDAYS.map((_, day) => count(Math.round(grid[day]![hour]!)))]),
                })}
              >
                <div className="rhythm" onPointerLeave={() => setHover(null)} key={layer}>
                  <div className="rhythm-hours" aria-hidden="true">
                    <span />
                    {Array.from({ length: 24 }, (_, hour) => (
                      <span key={hour}>{hour % 3 === 0 ? hourLabel(hour).replace(" ", "") : ""}</span>
                    ))}
                  </div>
                  {WEEKDAYS.map((name, day) => (
                    <div className="rhythm-row" key={name}>
                      <span className="rhythm-day">{name.slice(0, 3)}</span>
                      {grid[day]!.map((value, hour) => {
                        const share = value / max;
                        return (
                          <span
                            key={hour}
                            className={`blob-cell${hover && hover.day === day && hover.hour === hour ? " on" : ""}`}
                            onPointerEnter={() => setHover({ day, hour })}
                            aria-label={`${name} ${hourLabel(hour)}: ${count(Math.round(value))}`}
                          >
                            <i
                              className="blob"
                              style={
                                {
                                  "--s": share > 0 ? 0.14 + share * 0.86 : 0.08,
                                  "--o": share > 0 ? 0.2 + share * 0.8 : 0.2,
                                  "--i": day * 3 + hour,
                                  background: share >= 0.94 ? "var(--pen)" : undefined,
                                } as CSSProperties
                              }
                            />
                          </span>
                        );
                      })}
                    </div>
                  ))}
                </div>
                <div className="rhythm-foot small">
                  {hover ? (
                    <span>
                      <b>
                        {WEEKDAYS[hover.day]}s, {hourLabel(hover.hour)}–{hourLabel(hover.hour + 1)}
                      </b>
                      : {count(Math.round(shown ?? 0))} {meta.noun} over {days} days
                    </span>
                  ) : (
                    <span className="faint">Bigger, darker blobs are busier hours; the busiest glow pink. Hover one for its number.</span>
                  )}
                </div>
              </ChartCard>
            </>
          );
        })()
      )}
    </>
  );
}

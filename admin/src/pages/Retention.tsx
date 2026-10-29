import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { useState, type CSSProperties } from "react";
import { queries } from "../api/queries";
import type { Cohort } from "../api/types";
import { RangePicker } from "../components/RangePicker";
import { ErrorState, LoadingCards, PageHead } from "../components/ui";
import { ChartCard } from "../components/Viz";
import { KpiSticker } from "../components/Kpi";
import { count, shortDay } from "../lib/format";
import { averageRetention, heatLevel, rate } from "../lib/insights";

const route = getRouteApi("/_app/retention");

interface Hover {
  cohort: Cohort | null;
  week: number;
  value: number | null;
  x: number;
  y: number;
}

function Square({
  value,
  onHover,
  label,
  index,
}: {
  value: number | null | undefined;
  onHover: (event: React.PointerEvent | React.FocusEvent) => void;
  label: string;
  index: number;
}) {
  const level = heatLevel(value ?? null);
  return (
    <span
      className={`heat lv${level}`}
      role="gridcell"
      tabIndex={-1}
      aria-label={label}
      onPointerEnter={onHover}
      onFocus={onHover}
      style={{ "--i": index } as CSSProperties}
    >
      {level >= 0 ? <span className="heat-text">{Math.round((value ?? 0) * 100)}</span> : null}
    </span>
  );
}

export function RetentionPage() {
  const { weeks } = route.useSearch();
  const { data, error, isPending, refetch } = useQuery(queries.retention(weeks));
  const [hover, setHover] = useState<Hover | null>(null);

  return (
    <>
      <PageHead title="Retention" sub="Couples by the week they linked, and the share still doing things together in each week after (either partner active).">
        <RangePicker days={weeks} ranges={[8, 12, 26]} param="weeks" />
      </PageHead>
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : (
        (() => {
          const average = averageRetention(data.cohorts);
          const couples = data.cohorts.reduce((sum, cohort) => sum + cohort.size, 0);
          const point = (event: React.PointerEvent | React.FocusEvent, cohort: Cohort | null, week: number, value: number | null) => {
            const target = event.currentTarget as HTMLElement;
            const box = target.closest(".cohorts")!.getBoundingClientRect();
            const cell = target.getBoundingClientRect();
            setHover({ cohort, week, value, x: cell.left - box.left + cell.width / 2, y: cell.top - box.top });
          };
          return (
            <>
              <div className="grid cols-4">
                <KpiSticker label="Couples in these cohorts" icon="couples" value={couples} hint={`${data.cohorts.length} weekly cohorts`} />
                {[1, 4, 8].map((week) => (
                  <KpiSticker
                    key={week}
                    label={week === 1 ? "Still at it in week 1" : `Still at it in week ${week}`}
                    icon={week === 1 ? "heart" : "calendar"}
                    value={average[week] === null || average[week] === undefined ? null : average[week]! * 100}
                    format={(value) => `${Math.round(value)}%`}
                    hint={average[week] === null || average[week] === undefined ? "Not enough weeks yet" : "of couples, across cohorts"}
                  />
                ))}
              </div>
              <ChartCard
                title="Cohorts"
                aside="Darker is more couples still active"
                table={{
                  caption: "Retention by cohort",
                  columns: [
                    { label: "Linked in week of" },
                    { label: "Couples", numeric: true },
                    ...average.map((_, week) => ({ label: `W${week}`, numeric: true })),
                  ],
                  rows: data.cohorts.map((cohort) => [shortDay(cohort.weekStart), count(cohort.size), ...cohort.retained.map((value) => rate(value))]),
                }}
              >
                <div className="cohorts" role="grid" aria-label="Share of each cohort active per week" onPointerLeave={() => setHover(null)}>
                  <div className="cohort-row cohort-head" role="row">
                    <span className="cohort-name" role="columnheader">
                      Week of
                    </span>
                    <span className="cohort-size" role="columnheader">
                      Couples
                    </span>
                    {average.map((_, week) => (
                      <span key={week} className="week-label" role="columnheader">
                        W{week}
                      </span>
                    ))}
                  </div>
                  <div className="cohort-row average" role="row">
                    <span className="cohort-name" role="rowheader">
                      All cohorts
                    </span>
                    <span className="cohort-size">{count(couples)}</span>
                    {average.map((value, week) => (
                      <Square
                        key={week}
                        index={week}
                        value={value}
                        label={`All cohorts, week ${week}: ${rate(value)}`}
                        onHover={(event) => point(event, null, week, value)}
                      />
                    ))}
                  </div>
                  {[...data.cohorts].reverse().map((cohort, row) => (
                    <div className="cohort-row" role="row" key={cohort.weekStart}>
                      <span className="cohort-name" role="rowheader">
                        {shortDay(cohort.weekStart)}
                      </span>
                      <span className="cohort-size">{count(cohort.size)}</span>
                      {average.map((_, week) => (
                        <Square
                          key={week}
                          index={row + week}
                          value={cohort.retained[week]}
                          label={`Week of ${shortDay(cohort.weekStart)}, week ${week}: ${rate(cohort.retained[week] ?? null)}`}
                          onHover={(event) => point(event, cohort, week, cohort.retained[week] ?? null)}
                        />
                      ))}
                    </div>
                  ))}
                  {hover ? (
                    <div className="tip" style={{ left: hover.x, top: hover.y - 8, transform: "translate(-50%, -100%)" }}>
                      <div style={{ opacity: 0.75 }}>{hover.cohort ? `Linked week of ${shortDay(hover.cohort.weekStart)}` : "All cohorts, weighted"}</div>
                      <div>
                        Week {hover.week}: <b>{hover.value === null ? "not yet" : rate(hover.value)}</b>
                        {hover.cohort && hover.value !== null
                          ? ` · ${count(Math.round(hover.value * hover.cohort.size))} of ${count(hover.cohort.size)} couples`
                          : ""}
                      </div>
                    </div>
                  ) : null}
                </div>
                <div className="legend heat-legend" aria-hidden="true">
                  <span>Fewer</span>
                  {[0, 1, 2, 3, 4, 5].map((level) => (
                    <i key={level} className={`heat lv${level}`} />
                  ))}
                  <span>More</span>
                  <i className="heat lv-1" />
                  <span>Not yet</span>
                </div>
              </ChartCard>
            </>
          );
        })()
      )}
    </>
  );
}

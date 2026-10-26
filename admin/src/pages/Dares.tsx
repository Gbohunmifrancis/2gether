import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { createColumnHelper } from "@tanstack/react-table";
import { useState, type CSSProperties } from "react";
import { queries } from "../api/queries";
import type { ExerciseInsight } from "../api/types";
import { MiniHeart } from "../components/Art";
import { OutcomeBar } from "../components/charts";
import { DataTable } from "../components/DataTable";
import { RangePicker } from "../components/RangePicker";
import { Card, ErrorState, LoadingCards, PageHead } from "../components/ui";
import { ChartCard } from "../components/Viz";
import { KpiSticker } from "../components/Kpi";
import { count } from "../lib/format";
import { rate, seconds } from "../lib/insights";
import { exerciseLabel } from "../lib/labels";

const route = getRouteApi("/_app/dares");
const column = createColumnHelper<ExerciseInsight>();

const columns = (most: number) => [
  column.accessor("exercise", { header: "Exercise", cell: ({ getValue }) => <b>{exerciseLabel(getValue())}</b> }),
  column.accessor("set", {
    header: "Set",
    meta: { numeric: true },
    cell: ({ getValue }) => (
      <span className="set-cell">
        <span className="inline-bar" aria-hidden="true">
          <i className="grow-x" style={{ width: `${(getValue() / Math.max(1, most)) * 100}%` }} />
        </span>
        {count(getValue())}
      </span>
    ),
  }),
  column.display({
    id: "outcome",
    header: "How they ended",
    cell: ({ row }) => (
      <div className="outcome-cell">
        <OutcomeBar
          label={`${count(row.original.completed)} done, ${count(row.original.calledOff)} called off, ${count(row.original.missed)} missed`}
          parts={[
            { name: "Done", value: row.original.completed, color: "var(--chart-1)" },
            { name: "Called off", value: row.original.calledOff, color: "var(--chart-2)" },
            { name: "Missed", value: row.original.missed, color: "var(--chart-3)" },
          ]}
        />
        <span className="small faint">{rate(row.original.completionRate)} done</span>
      </div>
    ),
  }),
  column.accessor("medianSecondsToStart", {
    header: "To start",
    meta: { numeric: true, secondary: true },
    cell: ({ getValue }) => seconds(getValue()),
  }),
  column.accessor("medianSecondsOnCamera", { header: "On camera", meta: { numeric: true, secondary: true }, cell: ({ getValue }) => seconds(getValue()) }),
  column.accessor("averageConfidence", {
    header: "Camera sure",
    meta: { numeric: true, secondary: true },
    cell: ({ getValue }) => rate(getValue()),
  }),
  column.accessor("checksFailed", { header: "Checks failed", meta: { numeric: true, secondary: true }, cell: ({ getValue }) => count(getValue()) }),
];

/** Couples by their current streak, as columns of hearts stacked like stickers. */
function Streaks({ streaks }: { streaks: { length: string; couples: number }[] }) {
  const [hover, setHover] = useState<number | null>(null);
  const max = Math.max(1, ...streaks.map((item) => item.couples));
  const total = streaks.reduce((sum, item) => sum + item.couples, 0);
  return (
    <div className="streaks" onPointerLeave={() => setHover(null)}>
      {streaks.map((item, index) => (
        <div key={item.length} className={`streak-col${hover === index ? " on" : ""}`} onPointerEnter={() => setHover(index)}>
          <span className="streak-n">{count(item.couples)}</span>
          <div className="streak-bar" style={{ "--h": `${(item.couples / max) * 100}%`, "--i": index } as CSSProperties}>
            <MiniHeart size={18} tone={index === 0 ? "empty" : index === streaks.length - 1 ? "pink" : "both"} />
          </div>
          <span className="streak-label">{item.length === "0" ? "None" : item.length}</span>
          {hover === index ? <span className="streak-share small faint">{total ? `${Math.round((item.couples / total) * 100)}%` : ""}</span> : null}
        </div>
      ))}
    </div>
  );
}

export function DaresPage() {
  const { days } = route.useSearch();
  const { data, error, isPending, refetch } = useQuery(queries.dares(days));

  return (
    <>
      <PageHead title="Dares" sub={`Every exercise couples set each other in the last ${days} days, and how they ended. Counts and timings only.`}>
        <RangePicker days={days} />
      </PageHead>
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : (
        (() => {
          const set = data.exercises.reduce((sum, item) => sum + item.set, 0);
          const done = data.exercises.reduce((sum, item) => sum + item.completed, 0);
          const failed = data.exercises.reduce((sum, item) => sum + item.checksFailed, 0);
          const streaking = data.streaks.filter((item) => item.length !== "0").reduce((sum, item) => sum + item.couples, 0);
          return (
            <>
              <div className="grid cols-4">
                <KpiSticker label="Dares set" icon="dares" value={set} hint={`${count(done)} done · ${rate(set ? done / set : null)}`} />
                <KpiSticker label="Started before the alarm" icon="ring" value={data.earlyStarts} hint="Keen ones" />
                <KpiSticker label="Usual target" icon="check" value={data.medianRepsTarget} hint="Median reps asked for" />
                <KpiSticker label="Couples on a streak" icon="flame" tone="pen" value={streaking} hint={`${count(failed)} camera checks failed`} />
              </div>
              <div className="grid cols-3">
                <Card flush title="By exercise" className="span-2">
                  <DataTable
                    data={data.exercises}
                    columns={columns(Math.max(...data.exercises.map((item) => item.set)))}
                    getRowId={(row) => row.exercise}
                    empty="No dares in this window."
                  />
                </Card>
                <ChartCard
                  title="Streaks"
                  aside="Couples by days in a row, right now"
                  table={{
                    caption: "Couples by current streak",
                    columns: [{ label: "Streak" }, { label: "Couples", numeric: true }],
                    rows: data.streaks.map((item) => [item.length === "0" ? "None" : `${item.length} days`, count(item.couples)]),
                  }}
                >
                  <Streaks streaks={data.streaks} />
                </ChartCard>
              </div>
            </>
          );
        })()
      )}
    </>
  );
}

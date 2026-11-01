import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { createColumnHelper } from "@tanstack/react-table";
import { queries } from "../api/queries";
import type { GameInsights } from "../api/types";
import { Histogram, OutcomeBar } from "../components/charts";
import { DataTable } from "../components/DataTable";
import { RangePicker } from "../components/RangePicker";
import { Card, ErrorState, LoadingCards, PageHead } from "../components/ui";
import { ChartCard } from "../components/Viz";
import { KpiSticker } from "../components/Kpi";
import { count, hearts } from "../lib/format";
import { DASH, rate } from "../lib/insights";
import { gameLabel, skillLabel } from "../lib/labels";

const route = getRouteApi("/_app/games");
type GameRow = GameInsights["types"][number];
const column = createColumnHelper<GameRow>();

const columns = [
  column.accessor("type", { header: "Game", cell: ({ getValue }) => <b>{gameLabel(getValue())}</b> }),
  column.accessor("started", { header: "Started", meta: { numeric: true }, cell: ({ getValue }) => count(getValue()) }),
  column.display({
    id: "finished",
    header: "Finished",
    cell: ({ row }) => (
      <div className="outcome-cell">
        <OutcomeBar
          label={`${count(row.original.finished)} finished, ${count(row.original.abandoned)} left unfinished`}
          parts={[
            { name: "Finished", value: row.original.finished, color: "var(--chart-1)" },
            { name: "Left", value: row.original.abandoned, color: "var(--chart-3)" },
          ]}
        />
        <span className="small faint">{rate(row.original.started ? row.original.finished / row.original.started : null)}</span>
      </div>
    ),
  }),
  column.accessor("abandoned", { header: "Left", meta: { numeric: true, secondary: true }, cell: ({ getValue }) => count(getValue()) }),
  column.accessor("medianMinutes", {
    header: "Median length",
    meta: { numeric: true, secondary: true },
    cell: ({ getValue }) => (getValue() === null ? DASH : `${getValue()} min`),
  }),
];

export function GamesPage() {
  const { days } = route.useSearch();
  const { data, error, isPending, refetch } = useQuery(queries.games(days));

  return (
    <>
      <PageHead title="Games" sub={`What couples play, how often they finish, and how the Heart to heart duel skills score. Last ${days} days.`}>
        <RangePicker days={days} />
      </PageHead>
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : (
        (() => {
          const started = data.types.reduce((sum, item) => sum + item.started, 0);
          const finished = data.types.reduce((sum, item) => sum + item.finished, 0);
          const favourite = [...data.types].sort((a, b) => b.started - a.started)[0];
          return (
            <>
              <div className="grid cols-4">
                <KpiSticker label="Games started" value={started} hint={`${rate(started ? finished / started : null)} finished`} />
                <KpiSticker
                  label="Favourite"
                  value={null}
                  text={favourite ? gameLabel(favourite.type) : DASH}
                  hint={favourite ? `${count(favourite.started)} started` : ""}
                />
                <KpiSticker label="Duels finished" value={data.duels.finished} hint={`${count(data.duels.ties)} ties`} />
                <KpiSticker label="Usual winning margin" value={data.duels.medianMargin} format={hearts} hint="Median gap in hearts at the end of a duel" />
              </div>
              <Card flush title="By game">
                <DataTable
                  data={[...data.types].sort((a, b) => b.started - a.started)}
                  columns={columns}
                  getRowId={(row) => row.type}
                  empty="No games in this window."
                />
              </Card>
              <section className="section">
                <div className="section-head">
                  <h2 className="title">Skill scores</h2>
                  <span className="small faint">Each round scores 0–100 · the pink pin is the average</span>
                </div>
                <div className="grid cols-3">
                  {data.skills.map((skill) => (
                    <ChartCard
                      key={skill.kind}
                      title={skillLabel(skill.kind)}
                      aside={`${count(skill.rounds)} rounds`}
                      table={{
                        caption: `${skillLabel(skill.kind)} scores`,
                        columns: [{ label: "Score" }, { label: "Rounds", numeric: true }],
                        rows: skill.buckets.map((value, index) => [`${index * 10}–${index === 9 ? 100 : index * 10 + 9}`, count(value)]),
                      }}
                    >
                      <Histogram buckets={skill.buckets} average={skill.averageScore} label={`${skillLabel(skill.kind)} score distribution`} />
                      <div className="small muted" style={{ marginTop: 6 }}>
                        Average <b>{skill.averageScore === null ? DASH : skill.averageScore.toFixed(1)}</b>
                      </div>
                    </ChartCard>
                  ))}
                </div>
              </section>
            </>
          );
        })()
      )}
    </>
  );
}

import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { queries } from "../api/queries";
import { BarList, SplitBar, TimeChart } from "../components/charts";
import { RangePicker } from "../components/RangePicker";
import { KpiSticker } from "../components/Kpi";
import { Card, ErrorState, LoadingCards, PageHead } from "../components/ui";
import { ChartCard, seriesTable } from "../components/Viz";
import { bytes, compact, count, percent } from "../lib/format";
import { exerciseLabel, gameLabel } from "../lib/labels";

const route = getRouteApi("/_app/engagement");

export function EngagementPage() {
  const { days } = route.useSearch();
  const { data, error, isPending, refetch } = useQuery(queries.engagement(days));

  return (
    <>
      <PageHead title="Engagement" sub="What couples do together: dares, meals, photos, questions and games.">
        <RangePicker days={days} />
      </PageHead>
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : (
        <>
          <div className="grid cols-4">
            <KpiSticker label="Dares completed" icon="check" value={data.daresCompleted} hint={`${percent(data.completionRate)} of dares done or missed`} />
            <KpiSticker
              label="Meals logged"
              icon="meal"
              value={data.foodEntries}
              hint={`${count(data.foodScans)} photo scans · ${count(data.foodScansFailed)} failed`}
            />
            <KpiSticker label="Photos shared" icon="photo" value={data.photosSynced} hint={`${bytes(data.photoBytesSynced)} stored`} />
            <KpiSticker label="Questions answered" icon="question" value={data.questionsAnswered} hint={`${count(data.gamesCompleted)} games finished`} />
          </div>

          <div className="grid cols-3">
            <ChartCard
              title="Dares per day"
              className="span-2"
              table={() =>
                seriesTable("Dares per day", [
                  { name: "Completed", values: data.daresCompletedByDay },
                  { name: "Missed", values: data.daresMissedByDay },
                ])
              }
            >
              <TimeChart
                label={`Dares completed and missed per day, last ${days} days`}
                kind="bars"
                series={[
                  { name: "Completed", values: data.daresCompletedByDay, color: "var(--chart-1)" },
                  { name: "Missed", values: data.daresMissedByDay, color: "var(--chart-3)" },
                ]}
              />
            </ChartCard>
            <Card title="How dares end">
              <SplitBar
                parts={[
                  { label: "Done", value: data.daresCompleted, color: "var(--chart-1)" },
                  { label: "Called off", value: data.daresCalledOff, color: "var(--chart-2)" },
                  { label: "Missed", value: data.daresMissed, color: "var(--chart-3)" },
                ]}
              />
              <h3 className="title-s" style={{ margin: "22px 0 12px" }}>
                By exercise
              </h3>
              <BarList items={data.daresByExercise} format={(item) => exerciseLabel(item.name)} empty="No dares yet." />
            </Card>
          </div>

          <div className="grid cols-3">
            <ChartCard title="Meals logged" table={() => seriesTable("Meals logged per day", [{ name: "Meals", values: data.foodEntriesByDay }])}>
              <TimeChart
                label={`Meals logged per day, last ${days} days`}
                kind="bars"
                height={160}
                series={[{ name: "Meals", values: data.foodEntriesByDay, color: "var(--chart-2)" }]}
              />
            </ChartCard>
            <ChartCard title="Photos shared" table={() => seriesTable("Photos shared per day", [{ name: "Photos", values: data.photosByDay }])}>
              <TimeChart
                label={`Photos shared per day, last ${days} days`}
                kind="bars"
                height={160}
                series={[{ name: "Photos", values: data.photosByDay, color: "var(--chart-2)" }]}
              />
            </ChartCard>
            <ChartCard title="Questions answered" table={() => seriesTable("Answers per day", [{ name: "Answers", values: data.answersByDay }])}>
              <TimeChart
                label={`Answers per day, last ${days} days`}
                kind="bars"
                height={160}
                series={[{ name: "Answers", values: data.answersByDay, color: "var(--chart-2)" }]}
              />
            </ChartCard>
          </div>

          <div className="grid cols-2">
            <Card title="Games finished" aside={`${count(data.gamesCompleted)} in ${days} days`}>
              <BarList items={data.gamesByType} format={(item) => gameLabel(item.name)} empty="No games finished yet." />
            </Card>
            <Card title="Features in use" aside="Right now">
              <div className="list">
                <div className="list-row">
                  <span>Sharing location with their partner</span>
                  <b>{count(data.locationSharers)}</b>
                </div>
                <div className="list-row">
                  <span>Tracking a cycle</span>
                  <b>{count(data.cycleTrackers)}</b>
                </div>
                <div className="list-row">
                  <span>Following a pregnancy</span>
                  <b>{count(data.activePregnancies)}</b>
                </div>
                <div className="list-row">
                  <span>Food scan tokens (in / out)</span>
                  <b>
                    {compact(data.scanInputTokens)} / {compact(data.scanOutputTokens)}
                  </b>
                </div>
              </div>
            </Card>
          </div>
        </>
      )}
    </>
  );
}

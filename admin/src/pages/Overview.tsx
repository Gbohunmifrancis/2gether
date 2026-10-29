import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import type { DayValue } from "../api/types";
import { queries } from "../api/queries";
import { BarList, TimeChart } from "../components/charts";
import { LiveFeed, LiveStatus, PulseTiles } from "../components/LiveFeed";
import { RangePicker } from "../components/RangePicker";
import { Card, ErrorState, LoadingCards, PageHead } from "../components/ui";
import { ChartCard, seriesTable } from "../components/Viz";
import { KpiSticker } from "../components/Kpi";
import { count, humanize, percent, plural } from "../lib/format";

const route = getRouteApi("/_app/");

/** The sum of the last `size` days and of the `size` days before them; null when the window is too short. */
export function lastTwo(values: DayValue[], size = 7): { now: number; before: number | null } {
  const sum = (items: DayValue[]) => items.reduce((total, item) => total + item.value, 0);
  const now = sum(values.slice(-size));
  return { now, before: values.length >= size * 2 ? sum(values.slice(-size * 2, -size)) : null };
}

export function OverviewPage() {
  const { days } = route.useSearch();
  const { data, error, isPending, refetch } = useQuery(queries.overview(days));

  return (
    <>
      <PageHead title="Overview" sub="Everyone using Heartpact, in counts. Nothing here shows what couples write, share or track.">
        <RangePicker days={days} />
      </PageHead>
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : (
        <>
          {(() => {
            const signups = lastTwo(data.signups);
            const linked = lastTwo(data.couplesLinked);
            const active = data.activeUsers;
            const lastWeek = active.length > 7 ? active[active.length - 8]!.value : null;
            return (
              <div className="grid cols-4">
                <KpiSticker
                  label="People"
                  icon="users"
                  value={data.totalUsers}
                  hint={`${plural(data.newUsers, "new sign-up")} in ${days} days${data.disabledUsers ? ` · ${count(data.disabledUsers)} disabled` : ""}`}
                  delta={{ now: signups.now, before: signups.before, label: "sign-ups vs the week before" }}
                />
                <KpiSticker
                  label="Linked couples"
                  icon="couples"
                  value={data.activeCouples}
                  hint={`${plural(data.pendingCouples, "invite")} waiting · ${count(data.dissolvedCouples)} unlinked`}
                  delta={{ now: linked.now, before: linked.before, label: "links vs the week before" }}
                />
                <KpiSticker
                  label="Active today"
                  icon="live"
                  value={data.activeToday}
                  hint={`${count(data.activeWeek)} this week · ${count(data.activeMonth)} this month`}
                  delta={{ now: data.activeToday, before: lastWeek, label: "vs a week ago" }}
                />
                <KpiSticker
                  label="Couples on Plus"
                  icon="plus"
                  tone="pen"
                  value={data.plusCouples}
                  hint={data.activeCouples > 0 ? `${percent(data.plusCouples / data.activeCouples)} of linked couples` : "No linked couples yet"}
                />
              </div>
            );
          })()}

          <section className="section">
            <div className="section-head">
              <h2 className="title">Right now</h2>
              <LiveStatus />
            </div>
            <PulseTiles />
          </section>

          <div className="grid cols-3 overview-board">
            <div className="grid span-2" style={{ alignContent: "start" }}>
              <ChartCard
                title="Active people per day"
                aside="Used the app that day"
                table={() => seriesTable("Active people per day", [{ name: "Active", values: data.activeUsers }])}
              >
                <TimeChart
                  label={`Active people per day, last ${days} days`}
                  series={[{ name: "Active", values: data.activeUsers, color: "var(--chart-1)" }]}
                />
              </ChartCard>
              <div className="grid cols-2">
                <ChartCard title="Sign-ups" table={() => seriesTable("Sign-ups per day", [{ name: "Sign-ups", values: data.signups }])}>
                  <TimeChart
                    label={`Sign-ups per day, last ${days} days`}
                    kind="bars"
                    height={180}
                    series={[{ name: "Sign-ups", values: data.signups, color: "var(--chart-2)" }]}
                  />
                </ChartCard>
                <ChartCard title="Couples linked" table={() => seriesTable("Couples linked per day", [{ name: "Linked", values: data.couplesLinked }])}>
                  <TimeChart
                    label={`Couples linked per day, last ${days} days`}
                    kind="bars"
                    height={180}
                    series={[{ name: "Linked", values: data.couplesLinked, color: "var(--chart-1)" }]}
                  />
                </ChartCard>
              </div>
            </div>
            <div className="grid" style={{ alignContent: "start" }}>
              <Card
                title="Happening now"
                aside={
                  <Link to="/live" className="small">
                    Everything live
                  </Link>
                }
                className="feed-card"
              >
                <LiveFeed limit={6} />
              </Card>
              <Card title="Devices" aside="Last 30 days">
                <BarList items={data.platforms} format={(item) => humanize(item.name)} empty="No devices registered yet." />
              </Card>
              <Card title="App versions" aside="Last 30 days">
                <BarList
                  limit={4}
                  items={data.appVersions}
                  format={(item) => (item.name && item.name !== "unknown" ? `v${item.name}` : "Unknown")}
                  empty="No versions reported yet."
                />
              </Card>
            </div>
          </div>
        </>
      )}
    </>
  );
}

import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { queries } from "../api/queries";
import { BarList, TimeChart } from "../components/charts";
import { RangePicker } from "../components/RangePicker";
import { Card, ErrorState, LoadingCards, PageHead, Stat } from "../components/ui";
import { count, humanize, percent, plural } from "../lib/format";

const route = getRouteApi("/_app/");

export function OverviewPage() {
  const { days } = route.useSearch();
  const { data, error, isPending, refetch } = useQuery(queries.overview(days));

  return (
    <>
      <PageHead title="Overview" sub="Everyone using Heartpact, in counts. Nothing here shows what couples write, share or track.">
        <RangePicker to="/" days={days} />
      </PageHead>
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : (
        <>
          <div className="grid cols-4">
            <Stat
              label="People"
              value={count(data.totalUsers)}
              hint={`${plural(data.newUsers, "new sign-up")} in ${days} days${data.disabledUsers ? ` · ${count(data.disabledUsers)} disabled` : ""}`}
            />
            <Stat
              label="Linked couples"
              value={count(data.activeCouples)}
              hint={`${plural(data.pendingCouples, "invite")} waiting · ${count(data.dissolvedCouples)} unlinked`}
            />
            <Stat label="Active today" value={count(data.activeToday)} hint={`${count(data.activeWeek)} this week · ${count(data.activeMonth)} this month`} />
            <Stat
              label="Couples on Plus"
              value={count(data.plusCouples)}
              hint={data.activeCouples > 0 ? `${percent(data.plusCouples / data.activeCouples)} of linked couples` : "No linked couples yet"}
            />
          </div>

          <div className="grid cols-2">
            <Card title="Active people per day" aside="Used the app that day" className="span-2">
              <TimeChart label={`Active people per day, last ${days} days`} series={[{ name: "Active", values: data.activeUsers, color: "var(--chart-1)" }]} />
            </Card>
            <Card title="Sign-ups">
              <TimeChart
                label={`Sign-ups per day, last ${days} days`}
                kind="bars"
                height={180}
                series={[{ name: "Sign-ups", values: data.signups, color: "var(--chart-2)" }]}
              />
            </Card>
            <Card title="Couples linked">
              <TimeChart
                label={`Couples linked per day, last ${days} days`}
                kind="bars"
                height={180}
                series={[{ name: "Linked", values: data.couplesLinked, color: "var(--chart-1)" }]}
              />
            </Card>
          </div>

          <div className="grid cols-2">
            <Card title="Devices" aside="Seen in the last 30 days">
              <BarList items={data.platforms} format={(item) => humanize(item.name)} empty="No devices registered yet." />
            </Card>
            <Card title="App versions" aside="Devices seen in the last 30 days">
              <BarList
                items={data.appVersions}
                format={(item) => (item.name && item.name !== "unknown" ? `v${item.name}` : "Unknown")}
                empty="No versions reported yet."
              />
            </Card>
          </div>
        </>
      )}
    </>
  );
}

import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { queries } from "../api/queries";
import { BarList, TimeChart } from "../components/charts";
import { RangePicker } from "../components/RangePicker";
import { Card, ErrorState, LoadingCards, Notice, PageHead, Stat } from "../components/ui";
import { count, humanize, money, plural, productLabel } from "../lib/format";

const route = getRouteApi("/_app/revenue");

export function RevenuePage() {
  const { days } = route.useSearch();
  const { data, error, isPending, refetch } = useQuery(queries.revenue(days));

  return (
    <>
      <PageHead title="Revenue" sub="Heartpact Plus through Google Play, plus the couples you've given Plus.">
        <RangePicker to="/revenue" days={days} />
      </PageHead>
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : (
        <>
          <div className="grid cols-4">
            <Stat label="Monthly revenue (estimate)" value={money(data.estimatedMonthlyRevenue, data.currency)} hint="Renewing subscriptions × list price" />
            <Stat label="Paying couples" value={count(data.payingCouples)} hint={`${plural(data.renewingOff, "subscription")} set not to renew`} />
            <Stat
              label="Complimentary Plus"
              value={count(data.complimentaryCouples)}
              hint={
                <Link to="/payments" search={{ tab: "grants", page: 1 }}>
                  See grants
                </Link>
              }
            />
            <Stat
              label="Subscriptions started"
              value={count(data.newSubscriptions.reduce((sum, day) => sum + day.value, 0))}
              hint={`${count(data.ended.reduce((sum, day) => sum + day.value, 0))} ended in ${days} days`}
            />
          </div>

          <div className="grid cols-3">
            <Card title="Started and ended" className="span-2">
              <TimeChart
                label={`Subscriptions started and ended per day, last ${days} days`}
                series={[
                  { name: "Started", values: data.newSubscriptions, color: "var(--chart-1)" },
                  { name: "Ended", values: data.ended, color: "var(--chart-2)" },
                ]}
              />
            </Card>
            <div className="grid" style={{ alignContent: "start" }}>
              <Card title="Active by plan">
                <BarList items={data.activeByProduct} format={(item) => productLabel(item.name)} empty="No active subscriptions." />
              </Card>
              <Card title="All subscriptions by state">
                <BarList items={data.byStatus} format={(item) => humanize(item.name)} empty="No subscriptions yet." />
              </Card>
            </div>
          </div>

          <div style={{ marginTop: 16 }}>
            <Notice>
              An estimate for steering, not accounting: it ignores Google's fee, tax, trials and currency. Google Play Console is the record of what was paid.
              Set list prices with <code>Admin__MonthlyPrices__&lt;productId&gt;</code>.
            </Notice>
          </div>
        </>
      )}
    </>
  );
}

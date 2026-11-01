import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import type { CSSProperties } from "react";
import { queries } from "../api/queries";
import { MiniHeart } from "../components/Art";
import { Ring } from "../components/charts";
import { Icon, type IconName } from "../components/Icon";
import { RangePicker } from "../components/RangePicker";
import { Card, ErrorState, LoadingCards, PageHead } from "../components/ui";
import { ChartCard } from "../components/Viz";
import { KpiSticker } from "../components/Kpi";
import { countryName, flag } from "../lib/countries";
import { count } from "../lib/format";
import { DASH, pathLabels, rate } from "../lib/insights";
import type { GrowthPathKey } from "../api/types";

const route = getRouteApi("/_app/growth");

const PATH_ICONS: Record<GrowthPathKey, IconName> = { signedUp: "userPlus", linked: "link", hitScanLimit: "meal", nearAlbumLimit: "photo", plus: "plus" };

export function GrowthPage() {
  const { days } = route.useSearch();
  const { data, error, isPending, refetch } = useQuery(queries.growth(days));

  return (
    <>
      <PageHead title="Growth" sub="Renewals coming up, who renewed and who lapsed, and the path people take from free to Plus.">
        <RangePicker days={days} />
      </PageHead>
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : (
        (() => {
          const first = data.path[0]?.count ?? 0;
          const maxCountry = Math.max(1, ...data.plusByCountry.map((item) => item.count));
          return (
            <>
              <div className="grid cols-4">
                <KpiSticker label="Renewing in 7 days" value={data.endingIn7Days} hint="Subscriptions that end or renew" />
                <KpiSticker label="Renewing in 30 days" value={data.endingIn30Days} />
                <KpiSticker label="Renewed" value={data.renewed} hint={`in ${days} days`} />
                <KpiSticker label="Lapsed" value={data.lapsed} hint={`in ${days} days`} />
              </div>
              <div className="grid cols-3">
                <Card title="Renewal rate">
                  <div className="ring-row">
                    <Ring value={data.renewalRate} size={120} label={`Renewal rate ${rate(data.renewalRate)}`}>
                      <b>{rate(data.renewalRate)}</b>
                      <small>renewed</small>
                    </Ring>
                    <div className="small muted">
                      Of subscriptions that came up for renewal in {days} days, {count(data.renewed)} renewed and {count(data.lapsed)} lapsed.
                      <div style={{ marginTop: 10 }}>
                        Median time to Plus: <b>{data.medianDaysToPlus === null ? DASH : `${data.medianDaysToPlus} days`}</b> after signing up.
                      </div>
                    </div>
                  </div>
                </Card>
                <ChartCard
                  title="The path to Plus"
                  aside={`People who signed up in ${days} days`}
                  className="span-2"
                  table={{
                    caption: "Path to Plus",
                    columns: [{ label: "Step" }, { label: "People", numeric: true }, { label: "Of sign-ups", numeric: true }],
                    rows: data.path.map((step) => [pathLabels[step.key], count(step.count), rate(first ? step.count / first : null)]),
                  }}
                >
                  <ol className="path">
                    {data.path.map((step, index) => (
                      <li key={step.key} className={step.key === "plus" ? "plus" : undefined} style={{ "--i": index } as CSSProperties}>
                        <span className="path-icon" aria-hidden="true">
                          {step.key === "plus" ? <MiniHeart size={22} tone="pink" /> : <Icon name={PATH_ICONS[step.key]} />}
                        </span>
                        <b>{count(step.count)}</b>
                        <span className="path-label">{pathLabels[step.key]}</span>
                        <span className="small faint">{index === 0 ? "everyone" : rate(first ? step.count / first : null)}</span>
                      </li>
                    ))}
                  </ol>
                  <p className="small faint" style={{ marginTop: 14 }}>
                    Not a strict funnel: someone can go Plus without hitting a limit. The limits are where free runs out: food scans and album space.
                  </p>
                </ChartCard>
              </div>
              <Card title="Plus by country" aside="Couples on Plus, by where the payer is">
                {data.plusByCountry.length === 0 ? (
                  <p className="faint">No Plus couples yet.</p>
                ) : (
                  <div className="bars cols-bars">
                    {data.plusByCountry.slice(0, 12).map((item) => (
                      <div className="bar-row" key={item.name}>
                        <span>
                          <span className="flag">{flag(item.name)}</span> {countryName(item.name)}
                        </span>
                        <b>{count(item.count)}</b>
                        <div className="track" aria-hidden="true">
                          <div className="fill grow-x" style={{ width: `${(item.count / maxCountry) * 100}%` }} />
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </Card>
            </>
          );
        })()
      )}
    </>
  );
}

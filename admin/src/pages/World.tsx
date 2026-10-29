import { useQuery } from "@tanstack/react-query";
import { lazy, Suspense } from "react";
import { queries } from "../api/queries";
import { ErrorState, LoadingCards, PageHead, Card } from "../components/ui";
import { ChartCard } from "../components/Viz";
import { KpiSticker } from "../components/Kpi";
import { countryName, flag } from "../lib/countries";
import { count } from "../lib/format";
import { rate } from "../lib/insights";

// The map's projection code and shapes load only when this page opens.
const WorldMap = lazy(() => import("../components/WorldMap").then((module) => ({ default: module.WorldMap })));

export function WorldPage() {
  const { data, error, isPending, refetch } = useQuery(queries.world());

  return (
    <>
      <PageHead title="World" sub="Where people are, from the country on their account. Never their location." />
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : (
        (() => {
          const people = data.countries.reduce((sum, row) => sum + row.people, 0);
          const couples = data.countries.reduce((sum, row) => sum + row.couples, 0);
          const plus = data.countries.reduce((sum, row) => sum + row.plus, 0);
          const top = data.countries[0];
          return (
            <>
              <div className="grid cols-4">
                <KpiSticker label="Countries" icon="world" value={data.countries.length} hint={`${count(data.unknown)} people with no country`} />
                <KpiSticker label="People placed" icon="users" value={people} />
                <KpiSticker
                  label="Most people"
                  icon="flame"
                  value={null}
                  text={top ? `${flag(top.code)} ${countryName(top.code)}` : "—"}
                  hint={top ? `${count(top.people)} people` : ""}
                />
                <KpiSticker label="Couples on Plus" icon="plus" tone="pen" value={plus} hint={`${rate(couples ? plus / couples : null)} of couples placed`} />
              </div>
              <ChartCard
                title="The map"
                aside={
                  <span className="map-legend">
                    <i className="dot-s" /> fewer people <i className="dot-l" /> more · <i className="dot-free" /> free <i className="dot-plus" /> more Plus
                  </span>
                }
                table={{
                  caption: "People by country",
                  columns: [
                    { label: "Country" },
                    { label: "People", numeric: true },
                    { label: "Couples", numeric: true },
                    { label: "On Plus", numeric: true },
                    { label: "Active this week", numeric: true },
                  ],
                  rows: data.countries.map((row) => [
                    `${flag(row.code)} ${countryName(row.code)}`,
                    count(row.people),
                    count(row.couples),
                    count(row.plus),
                    count(row.activeWeek),
                  ]),
                }}
              >
                <Suspense
                  fallback={
                    <div className="world-loading small faint" style={{ position: "static", minHeight: 300 }}>
                      Unfolding the map…
                    </div>
                  }
                >
                  <WorldMap rows={data.countries} />
                </Suspense>
              </ChartCard>
              <Card title="Top countries" aside="By people">
                <ol className="country-list">
                  {data.countries.slice(0, 12).map((row, index) => (
                    <li key={row.code}>
                      <span className="rank">{index + 1}</span>
                      <span className="flag big">{flag(row.code)}</span>
                      <span className="country-name">
                        <b>{countryName(row.code)}</b>
                        <small>
                          {count(row.couples)} couples · {count(row.plus)} on Plus · {count(row.activeWeek)} active this week
                        </small>
                      </span>
                      <b className="country-n">{count(row.people)}</b>
                    </li>
                  ))}
                </ol>
              </Card>
            </>
          );
        })()
      )}
    </>
  );
}

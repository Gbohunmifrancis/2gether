import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { queries } from "../api/queries";
import { Characters } from "../components/Art";
import { markAlertsSeen } from "../components/Header";
import { FeedItem } from "../components/LiveFeed";
import { Card, ErrorState, LoadingCards, PageHead } from "../components/ui";
import { ago, dateTime } from "../lib/format";
import { useLive } from "../live/LiveProvider";

const RULES = [
  ["Sign-up wave", "10 or more sign-ups in 15 minutes"],
  ["Food scan flood", "one person making 20 or more scans in 10 minutes"],
  ["Payment trouble", "subscriptions newly on hold or in a grace period, or failed checks with Google"],
  ["Push failing", "20% or more of at least 10 notifications failed in the last hour"],
  ["Workers", "a background loop late (no run for 3× its interval) or failing"],
  ["Album storage", "over 80% of Admin__StorageBudgetGb, when that's set"],
];

export function AlertsPage() {
  const { data, error, isPending, refetch } = useQuery(queries.alerts());
  const live = useLive();
  const navigate = useNavigate();
  const recent = live.events.filter((event) => event.kind === "alert");

  // Opening this page counts as reading them.
  useEffect(() => {
    if (data?.length) markAlertsSeen(data);
  }, [data]);

  return (
    <>
      <PageHead title="Alerts" sub="Problems the server is seeing right now. Each one quietens for 60 minutes after it's raised." />
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards count={2} />
      ) : data.length === 0 ? (
        <div className="card all-clear">
          <Characters who="both" pose="cheer" size={110} />
          <div>
            <h2 className="title">All clear</h2>
            <p className="muted">Nothing needs you right now. The bell rings if that changes.</p>
          </div>
        </div>
      ) : (
        <div className="alert-list">
          {data.map((alert) => (
            <article key={`${alert.key}@${alert.sinceUtc}`} className={`card alert-card ${alert.severity}`}>
              <span className="sev-icon" aria-hidden="true">
                !
              </span>
              <div className="alert-body">
                <h2 className="title-s">{alert.title}</h2>
                <p className="muted">{alert.detail}</p>
                <p className="small faint" title={dateTime(alert.sinceUtc)}>
                  {alert.severity === "bad" ? "Needs action" : "Worth a look"} · since {ago(alert.sinceUtc)}
                </p>
              </div>
              {alert.link && /^\/(?![/\\])/.test(alert.link) ? (
                <a
                  href={alert.link}
                  className="btn sm outline"
                  onClick={(event) => {
                    event.preventDefault();
                    void navigate({ href: alert.link! });
                  }}
                >
                  Look
                </a>
              ) : null}
            </article>
          ))}
        </div>
      )}
      <div className="grid cols-2">
        <Card title="Raised while you've been here">
          {recent.length === 0 ? (
            <p className="faint small">None this session.</p>
          ) : (
            <ol className="feed">
              {recent.map((event) => (
                <li key={event.id}>
                  <FeedItem event={event} fresh={live.fresh.has(event.id)} />
                </li>
              ))}
            </ol>
          )}
        </Card>
        <Card title="What raises an alert">
          <dl className="kv">
            {RULES.map(([name, rule]) => (
              <div key={name} style={{ display: "contents" }}>
                <dt>{name}</dt>
                <dd>{rule}</dd>
              </div>
            ))}
          </dl>
        </Card>
      </div>
    </>
  );
}

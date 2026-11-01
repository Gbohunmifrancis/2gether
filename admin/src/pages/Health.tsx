import { useQuery } from "@tanstack/react-query";
import type { CSSProperties } from "react";
import { queries } from "../api/queries";
import type { Health } from "../api/types";
import { WHOLE } from "../components/Art";
import { Icon } from "../components/Icon";
import { Badge, Card, ErrorState, LoadingCards, PageHead, type Tone } from "../components/ui";
import { KpiSticker } from "../components/Kpi";
import { ago, bytes, compact, count, dateTime, money } from "../lib/format";
import { seconds } from "../lib/insights";
import { probeLabel } from "../lib/labels";

type Worker = Health["workers"][number];

const PROBE_TONE: Record<string, Tone> = { up: "good", degraded: "warn", down: "bad", off: undefined };
const PROBE_TEXT: Record<string, string> = { up: "Up", degraded: "Slow", down: "Down", off: "Off" };
const WORKER_TEXT: Record<Worker["status"], string> = { ok: "Beating", late: "Late", failing: "Failing", unknown: "Not seen yet" };

/**
 * A worker as a heart: beating at a pace that follows how often it runs while it's fine, gone still and grey when
 * late, red and cracked when failing.
 */
function WorkerHeart({ worker }: { worker: Worker }) {
  // A heartbeat each loop, but never faster than ~1.5/s nor slower than one per 4 s, so it reads as alive.
  const period = Math.max(0.7, Math.min(4, worker.everySeconds / 4));
  return (
    <svg
      viewBox="0 0 100 106"
      width="44"
      height="46"
      className={`worker-heart ${worker.status}`}
      style={{ "--beat": `${period}s` } as CSSProperties}
      aria-hidden="true"
    >
      <path d={WHOLE} />
      {worker.status === "failing" ? <path className="crack" d="M50 16 L43 42 L56 54 L46 76 L52 98" /> : null}
    </svg>
  );
}

export function HealthPage() {
  const { data, error, isPending, refetch, dataUpdatedAt } = useQuery(queries.health());

  return (
    <>
      <PageHead
        title="Health"
        sub="Whether the pieces are up, whether background work is keeping time, and what nothing watches yet. Refreshes every 30 seconds."
      />
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <LoadingCards />
      ) : (
        (() => {
          const troubled = data.workers.filter((worker) => worker.status === "late" || worker.status === "failing").length;
          const down = data.probes.filter((probe) => probe.status === "down" || probe.status === "degraded").length;
          return (
            <>
              <div className="grid cols-4">
                <KpiSticker
                  label="Services"
                  value={null}
                  text={down ? `${down} need${down === 1 ? "s" : ""} a look` : "All up"}
                  tone={down ? "warn" : "good"}
                  hint={`${data.probes.filter((probe) => probe.status === "up").length} of ${data.probes.length} up`}
                />
                <KpiSticker
                  label="Workers"
                  value={null}
                  text={troubled ? `${troubled} behind` : "Keeping time"}
                  tone={troubled ? "warn" : "good"}
                  hint={`${data.workers.length} background loops`}
                />
                <KpiSticker label="Album storage" value={null} text={bytes(data.storageBytes)} hint="Counted from the database" />
                <KpiSticker
                  label={`Food scans, ${data.foodScanCost.days} days`}
                  value={null}
                  text={data.foodScanCost.estimatedCost === null ? "—" : money(data.foodScanCost.estimatedCost, data.foodScanCost.currency)}
                  hint={`${compact(data.foodScanCost.inputTokens)} in · ${compact(data.foodScanCost.outputTokens)} out tokens`}
                />
              </div>
              <div className="grid cols-2">
                <Card title="Probes" aside={`Checked ${dateTime(data.nowUtc)}`}>
                  <div className="probes">
                    {data.probes.map((probe) => (
                      <div key={probe.name} className={`probe ${probe.status}`}>
                        <span className="probe-dot" aria-hidden="true" />
                        <div className="probe-text">
                          <b>{probeLabel(probe.name)}</b>
                          {probe.detail ? <small>{probe.detail}</small> : null}
                        </div>
                        <span className="probe-ms">{probe.latencyMs === null ? "" : `${count(probe.latencyMs)} ms`}</span>
                        <Badge tone={PROBE_TONE[probe.status]}>{PROBE_TEXT[probe.status]}</Badge>
                      </div>
                    ))}
                  </div>
                </Card>
                <Card title="Worker heartbeats" aside={dataUpdatedAt ? `Updated ${ago(new Date(dataUpdatedAt).toISOString())}` : undefined}>
                  <div className="workers">
                    {data.workers.map((worker) => (
                      <div key={worker.name} className={`worker ${worker.status}`}>
                        <WorkerHeart worker={worker} />
                        <div className="worker-text">
                          <b>{worker.name}</b>
                          <small>
                            {worker.lastRunUtc ? `ran ${ago(worker.lastRunUtc)}` : "hasn't run yet"} · every {seconds(worker.everySeconds)}
                          </small>
                          {worker.lastError && worker.lastErrorUtc ? (
                            <small className={worker.status === "failing" ? "err" : "faint"}>
                              {worker.status === "failing" ? "" : "Last error "}
                              {ago(worker.lastErrorUtc)}: {worker.lastError}
                            </small>
                          ) : null}
                        </div>
                        <Badge tone={worker.status === "ok" ? "good" : worker.status === "late" ? "warn" : worker.status === "failing" ? "bad" : undefined}>
                          {WORKER_TEXT[worker.status]}
                        </Badge>
                      </div>
                    ))}
                  </div>
                </Card>
              </div>
              <div className="grid cols-2">
                <Card title="Queues">
                  <div className="list">
                    {data.queues.map((queue) => (
                      <div className="list-row" key={queue.name}>
                        <span>
                          <b>{queue.name}</b>
                          <div className="small faint">{queue.oldestUtc ? `oldest waiting since ${ago(queue.oldestUtc)}` : "nothing waiting"}</div>
                        </span>
                        <span className={`queue-n${queue.waiting > 100 ? " deep" : ""}`}>{count(queue.waiting)}</span>
                      </div>
                    ))}
                  </div>
                </Card>
                <Card title="Not monitored" className="not-monitored">
                  <p className="small muted" style={{ marginBottom: 10 }}>
                    Honest about the gaps: nothing here watches these yet.
                  </p>
                  <ul>
                    {data.notMonitored.map((item) => (
                      <li key={item}>
                        <Icon name="eyeOff" />
                        {item}
                      </li>
                    ))}
                  </ul>
                </Card>
              </div>
            </>
          );
        })()
      )}
    </>
  );
}

import { useEffect, useState } from "react";
import type { LiveEvent, LiveKind } from "../api/types";
import { Characters, Halves, MiniHeart } from "../components/Art";
import { LiveFeed, LiveStatus, PulseTiles } from "../components/LiveFeed";
import { Card, PageHead } from "../components/ui";
import { LIVE_KIND_LABELS, liveLine } from "../lib/labels";
import { setSoundEnabled, useSoundEnabled } from "../lib/sound";
import { useLive } from "../live/LiveProvider";

const FILTERS: LiveKind[] = ["signedUp", "coupleLinked", "dareCompleted", "dareMissed", "gameFinished", "plusStarted", "plusEnded", "coupleEnded", "alert"];

/**
 * The stage: Maya and Theo act out the latest big moment. Couples linking: they fly together and hug. Plus: they
 * cheer under a burst of hearts. A dare done: a big tick draws. An alert: they look worried and the stage shakes.
 */
function Stage() {
  const { latest, events } = useLive();
  const moment = latest ?? events[0] ?? null;
  const [shown, setShown] = useState<LiveEvent | null>(moment);
  useEffect(() => {
    if (moment && moment.kind !== "gameFinished" && moment.kind !== "accountDeleted") setShown(moment);
  }, [moment]);
  const kind = shown?.kind;
  const line = shown ? liveLine(shown) : null;
  const pose = kind === "coupleLinked" ? "hug" : kind === "plusStarted" ? "cheer" : kind === "alert" ? "stand" : kind === "dareCompleted" ? "cheer" : "wave";
  const mood =
    kind === "alert" || kind === "coupleEnded" || kind === "dareMissed" ? "worried" : kind === "plusStarted" || kind === "dareCompleted" ? "cheer" : "smile";
  return (
    <div className={`stage kind-${kind ?? "none"}`} key={shown?.id ?? "none"}>
      <div className="stage-art">
        {kind === "coupleLinked" ? (
          <>
            <Halves className="fly big" size={92} />
            <div className="stage-pair">
              <Characters who="maya" pose="hug" size={88} className="slide-l" />
              <Characters who="theo" pose="hug" size={88} className="slide-r" />
            </div>
          </>
        ) : (
          <Characters who="both" pose={pose} mood={mood} size={120} gap={6} className={kind === "alert" ? "shake" : "bob"} />
        )}
        {kind === "plusStarted" ? (
          <div className="stage-burst" aria-hidden="true">
            {Array.from({ length: 12 }, (_, index) => (
              <i
                key={index}
                style={{ "--a": `${index * 30}deg`, "--d": `${70 + (index % 3) * 22}px`, animationDelay: `${(index % 4) * 40}ms` } as React.CSSProperties}
              >
                <MiniHeart size={index % 3 === 0 ? 22 : 15} tone={index % 2 ? "pink" : "both"} />
              </i>
            ))}
          </div>
        ) : null}
        {kind === "dareCompleted" ? (
          <svg className="stage-tick" viewBox="0 0 100 100" aria-hidden="true">
            <path
              className="tick-draw"
              pathLength={1}
              d="M24 54 L42 72 L78 32"
              fill="none"
              stroke="var(--good)"
              strokeWidth="11"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        ) : null}
      </div>
      <div className="stage-text" aria-live="polite">
        <span className="eyebrow">{shown ? "Just now" : "Waiting for something to happen"}</span>
        <h2 className="headline">{line?.title ?? "All quiet for a moment"}</h2>
        <p className="muted">{line?.sub ?? "Maya and Theo are keeping watch."}</p>
      </div>
    </div>
  );
}

export function LivePage() {
  const [kinds, setKinds] = useState<Set<LiveKind>>(new Set());
  const sound = useSoundEnabled();
  const { events } = useLive();
  const counts = new Map<LiveKind, number>();
  for (const event of events) counts.set(event.kind, (counts.get(event.kind) ?? 0) + 1);
  const toggle = (kind: LiveKind) =>
    setKinds((current) => {
      const next = new Set(current);
      if (next.has(kind)) next.delete(kind);
      else next.add(kind);
      return next;
    });

  return (
    <>
      <PageHead title="Live" sub="What couples are doing, as it happens. Names and counts only.">
        <div className="actions" style={{ alignItems: "center" }}>
          <LiveStatus />
          <button type="button" className={`btn sm ${sound ? "filled" : "outline"}`} aria-pressed={sound} onClick={() => setSoundEnabled(!sound)}>
            {sound ? "Sounds on" : "Turn sounds on"}
          </button>
        </div>
      </PageHead>
      <div className="grid cols-3">
        <Card className="span-2 stage-card">
          <Stage />
        </Card>
        <Card title="Sounds" aside={sound ? "On" : "Off"}>
          <ul className="sound-legend">
            <li>
              <Halves size={18} /> A soft two-note chime when a couple links
            </li>
            <li>
              <span className="ev-art good tiny">✓</span> A tick for each dare done
            </li>
            <li>
              <MiniHeart size={18} tone="pink" /> A warm “ka-ching” when someone goes Plus
            </li>
            <li>
              <span className="ev-art bad tiny">!</span> A low tone for alerts
            </li>
          </ul>
        </Card>
      </div>
      <section className="section">
        <div className="section-head">
          <h2 className="title">Right now</h2>
        </div>
        <PulseTiles />
      </section>
      <Card
        title="Feed"
        aside={
          kinds.size ? (
            <button className="btn sm ghost" type="button" onClick={() => setKinds(new Set())}>
              Show everything
            </button>
          ) : (
            "Newest first · last 24 hours"
          )
        }
      >
        <div className="chips" role="group" aria-label="Show only">
          {FILTERS.map((kind) => (
            <button key={kind} type="button" className={`chip${kinds.has(kind) ? " on" : ""}`} aria-pressed={kinds.has(kind)} onClick={() => toggle(kind)}>
              {LIVE_KIND_LABELS[kind]} <span className="faint">{counts.get(kind) ?? 0}</span>
            </button>
          ))}
        </div>
        <LiveFeed limit={40} kinds={kinds.size ? kinds : undefined} />
      </Card>
    </>
  );
}

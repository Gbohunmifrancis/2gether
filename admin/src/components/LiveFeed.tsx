import { Link } from "@tanstack/react-router";
import { useEffect, useState, type CSSProperties } from "react";
import type { LiveEvent, LiveKind, Pulse } from "../api/types";
import { ago, count } from "../lib/format";
import { liveLine } from "../lib/labels";
import { useLive } from "../live/LiveProvider";
import { Characters, Halves, MiniHeart } from "./Art";
import { Icon, type IconName } from "./Icon";
import { CountUp } from "./Viz";

/* The live feed as stickers sliding in, each kind with its own little moment, and the pulse tiles. */

const KIND: Record<LiveKind, { icon: IconName; tone: string }> = {
  signedUp: { icon: "userPlus", tone: "theo" },
  coupleLinked: { icon: "link", tone: "maya" },
  coupleEnded: { icon: "unlink", tone: "soft" },
  dareCompleted: { icon: "check", tone: "good" },
  dareMissed: { icon: "missed", tone: "soft" },
  gameFinished: { icon: "games", tone: "theo" },
  plusStarted: { icon: "plus", tone: "pen" },
  plusEnded: { icon: "plus", tone: "soft" },
  accountDeleted: { icon: "userX", tone: "soft" },
  alert: { icon: "bell", tone: "bad" },
};

/** Re-renders every 20 s so "just now" grows up. */
function useTick(ms = 20_000) {
  const [, setNow] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(timer);
  }, [ms]);
}

function Burst() {
  return (
    <span className="burst" aria-hidden="true">
      {Array.from({ length: 7 }, (_, index) => (
        <i key={index} style={{ "--a": `${index * (360 / 7)}deg`, animationDelay: `${index * 25}ms` } as CSSProperties}>
          <MiniHeart size={10} tone={index % 2 ? "pink" : "both"} />
        </i>
      ))}
    </span>
  );
}

/** The icon tile, with the moment when the event has just arrived. */
function EventArt({ event, fresh }: { event: LiveEvent; fresh: boolean }) {
  const kind = KIND[event.kind];
  if (event.kind === "coupleLinked") {
    return (
      <span className={`ev-art ${kind.tone}`}>
        <Halves className={fresh ? "fly" : undefined} size={20} />
      </span>
    );
  }
  if (event.kind === "dareCompleted") {
    return (
      <span className={`ev-art ${kind.tone}`}>
        <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <path
            className={fresh ? "tick-draw" : undefined}
            pathLength={1}
            d="M5 12.5l4.5 4.5L19 7.5"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.6"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </span>
    );
  }
  if (event.kind === "plusStarted") {
    return (
      <span className={`ev-art ${kind.tone}`}>
        <MiniHeart size={20} tone="pink" className={fresh ? "pop" : undefined} />
        {fresh ? <Burst /> : null}
      </span>
    );
  }
  return (
    <span className={`ev-art ${kind.tone}`}>
      <Icon name={kind.icon} />
    </span>
  );
}

export function FeedItem({ event, fresh }: { event: LiveEvent; fresh: boolean }) {
  const line = liveLine(event);
  const body = (
    <>
      <EventArt event={event} fresh={fresh} />
      <span className="ev-text">
        <b>{line.title}</b>
        <small>{line.sub}</small>
      </span>
      <time className="ev-time" dateTime={event.atUtc} title={new Date(event.atUtc).toLocaleString("en-GB")}>
        {ago(event.atUtc)}
      </time>
    </>
  );
  const className = `feed-item ${event.kind} sev-${event.severity}${fresh ? " fresh" : ""}`;
  return event.userId && event.kind !== "accountDeleted" ? (
    <Link to="/users/$userId" params={{ userId: event.userId }} className={className}>
      {body}
    </Link>
  ) : (
    <div className={className}>{body}</div>
  );
}

export function LiveFeed({ limit = 12, kinds }: { limit?: number; kinds?: ReadonlySet<LiveKind> }) {
  const live = useLive();
  useTick();
  const events = live.events.filter((event) => !kinds || kinds.has(event.kind)).slice(0, limit);
  if (live.events.length === 0) {
    return (
      <div className="feed-empty">
        <Characters who="both" pose="rest" mood="sleep" size={70} />
        <span>
          {live.status === "open" || live.status === "connecting" ? "Listening… events show up here as they happen." : "Reconnecting to the live feed…"}
        </span>
      </div>
    );
  }
  return (
    <ol className="feed" aria-live="polite" aria-relevant="additions">
      {events.map((event) => (
        <li key={event.id}>
          <FeedItem event={event} fresh={live.fresh.has(event.id)} />
        </li>
      ))}
      {events.length === 0 ? <li className="faint small">Nothing of that kind lately.</li> : null}
    </ol>
  );
}

export function LiveStatus() {
  const { status } = useLive();
  const text = status === "open" ? "Live" : status === "connecting" ? "Connecting" : status === "stopped" ? "Paused" : "Reconnecting · polling each minute";
  return (
    <span className={`live-status ${status}`}>
      <span className="dot" aria-hidden="true" />
      {text}
    </span>
  );
}

const TILES: { key: keyof Pulse; label: string; icon: IconName; className: string; to?: "/live" | "/dares" | "/games" | "/funnel" | "/growth" }[] = [
  { key: "onlineNow", label: "Online now", icon: "users", className: "online" },
  { key: "ringingNow", label: "Dares ringing", icon: "ring", className: "ringing", to: "/dares" },
  { key: "onCameraNow", label: "On camera", icon: "camera", className: "camera", to: "/dares" },
  { key: "heartsToday", label: "Hearts today", icon: "heart", className: "hearts", to: "/dares" },
  { key: "openGames", label: "Games open", icon: "games", className: "games", to: "/games" },
  { key: "signupsToday", label: "Sign-ups today", icon: "userPlus", className: "signups", to: "/funnel" },
  { key: "couplesLinkedToday", label: "Couples linked today", icon: "link", className: "linked", to: "/funnel" },
  { key: "plusStartedToday", label: "Plus today", icon: "plus", className: "plus", to: "/growth" },
];

/** What's happening right now, from the pulse; nudged by live events between polls. */
export function PulseTiles() {
  const { pulse } = useLive();
  return (
    <div className="pulse-grid">
      {TILES.map((tile) => {
        const value = pulse ? (pulse[tile.key] as number) : null;
        const content = (
          <>
            <span className="pulse-icon" aria-hidden="true">
              <Icon name={tile.icon} />
              {tile.className === "camera" && value ? <span className="rec" /> : null}
            </span>
            <span className="pulse-value">{value === null ? "–" : <CountUp value={value} />}</span>
            <span className="pulse-label">{tile.label}</span>
          </>
        );
        const className = `pulse-tile ${tile.className}${value ? " lit" : ""}`;
        return tile.to ? (
          <Link key={tile.key} to={tile.to} className={className} aria-label={`${tile.label}: ${value === null ? "unknown" : count(value)}`}>
            {content}
          </Link>
        ) : (
          <div key={tile.key} className={className}>
            {content}
          </div>
        );
      })}
    </div>
  );
}

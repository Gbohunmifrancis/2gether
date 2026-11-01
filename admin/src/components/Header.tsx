import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { queries } from "../api/queries";
import type { Alert } from "../api/types";
import { count } from "../lib/format";
import { useLive } from "../live/LiveProvider";
import { setSoundEnabled, useSoundEnabled } from "../lib/sound";
import { Mascot, MiniHeart, type MascotMood } from "./Art";
import { Icon } from "./Icon";
import { ago } from "../lib/format";

/* The header: today's line, the live heart that beats once per event, who's online, the alerts bell, the command
   palette and the sound switch. The mascot lives in the brand. */

const SEEN_KEY = "heartpact.admin.seenAlerts";
const seenListeners = new Set<() => void>();
let seenCache: string | null = null;

function readSeen(): string {
  if (seenCache !== null) return seenCache;
  try {
    seenCache = localStorage.getItem(SEEN_KEY) ?? "";
  } catch {
    seenCache = "";
  }
  return seenCache;
}

const alertId = (alert: Alert) => `${alert.key}@${alert.sinceUtc}`;

export function markAlertsSeen(alerts: Alert[]) {
  seenCache = alerts.map(alertId).join("|");
  try {
    localStorage.setItem(SEEN_KEY, seenCache);
  } catch {
    // Storage blocked: remembered for this page.
  }
  seenListeners.forEach((listener) => listener());
}

function useSeenAlerts(): Set<string> {
  const raw = useSyncExternalStore(
    (listener) => {
      seenListeners.add(listener);
      return () => seenListeners.delete(listener);
    },
    readSeen,
    readSeen,
  );
  return new Set(raw.split("|").filter(Boolean));
}

/** The admin's hour, refreshed each minute, for the sleepy mascot. */
function useHour(): number {
  const [hour, setHour] = useState(() => new Date().getHours());
  useEffect(() => {
    const timer = setInterval(() => setHour(new Date().getHours()), 60_000);
    return () => clearInterval(timer);
  }, []);
  return hour;
}

/** The mark, alive: worried while there's a bad alert, asleep late at night, a cheer when a couple links or goes Plus. */
export function LiveMark({ size = 28 }: { size?: number }) {
  const hour = useHour();
  const live = useLive();
  const alerts = useQuery(queries.alerts());
  const worried = (alerts.data ?? []).some((alert) => alert.severity === "bad");
  const [cheer, setCheer] = useState(0);
  const latest = live.latest;
  useEffect(() => {
    if (latest && (latest.kind === "coupleLinked" || latest.kind === "plusStarted")) setCheer((value) => value + 1);
  }, [latest]);
  const night = hour >= 23 || hour < 5;
  const mood: MascotMood = worried ? "worried" : night ? "sleepy" : "idle";
  return <Mascot mood={mood} beat={cheer} size={size} title={worried ? "The mascot looks worried: there's an alert" : "Heartpact"} />;
}

/** Today so far, from the pulse: one quiet line in the header. */
export function TodayLine() {
  const { pulse } = useLive();
  return (
    <p className="today-line small">
      {pulse ? (
        <>
          Today <span aria-hidden="true">·</span> <b>{count(pulse.heartsToday)}</b> hearts <span aria-hidden="true">·</span> <b>{count(pulse.signupsToday)}</b>{" "}
          new people <span aria-hidden="true">·</span> <b>{count(pulse.couplesLinkedToday)}</b> couples linked
        </>
      ) : (
        "Listening for what couples are up to"
      )}
    </p>
  );
}

export function LiveHeart() {
  const live = useLive();
  const connected = live.status === "open";
  const label = connected ? "Live: connected" : live.status === "connecting" ? "Live: connecting" : "Live: reconnecting, checking every minute";
  return (
    <Link to="/live" className={`live-heart${connected ? "" : " off"}`} title={label} aria-label={label}>
      <span key={live.beat} className={live.beat ? "beat" : undefined}>
        <MiniHeart size={22} />
      </span>
      <span className="live-word">{connected ? "Live" : "…"}</span>
    </Link>
  );
}

export function OnlinePill() {
  const live = useLive();
  const online = live.pulse?.onlineNow;
  return (
    <Link to="/live" className="online-pill" title="People active in the last 5 minutes">
      <span className="dot" aria-hidden="true" />
      <b>{online === undefined ? "–" : count(online)}</b>
      <span className="online-word">online</span>
    </Link>
  );
}

export function SoundToggle() {
  const on = useSoundEnabled();
  return (
    <button
      type="button"
      className={`icon-btn${on ? " on" : ""}`}
      aria-pressed={on}
      aria-label={on ? "Sounds on" : "Sounds off"}
      title={on ? "Sounds on: chimes for couples, Plus and alerts" : "Sounds off"}
      onClick={() => setSoundEnabled(!on)}
    >
      <Icon name={on ? "soundOn" : "soundOff"} />
    </button>
  );
}

export function PaletteButton({ onOpen, compact }: { onOpen: () => void; compact?: boolean }) {
  const mac = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform);
  return compact ? (
    <button type="button" className="icon-btn" onClick={onOpen} aria-label="Search and jump (command palette)">
      <Icon name="search" />
    </button>
  ) : (
    <button type="button" className="palette-btn" onClick={onOpen} aria-label="Search and jump (command palette)">
      <Icon name="search" />
      <span>Search or jump</span>
      <kbd>{mac ? "⌘" : "Ctrl"} K</kbd>
    </button>
  );
}

export function Bell() {
  const { data } = useQuery(queries.alerts());
  const live = useLive();
  const seen = useSeenAlerts();
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const alerts = data ?? [];
  const unread = alerts.filter((alert) => !seen.has(alertId(alert))).length;
  const [ring, setRing] = useState(0);
  const latest = live.latest;
  useEffect(() => {
    if (latest?.kind === "alert") setRing((value) => value + 1);
  }, [latest]);

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent ? event.key === "Escape" : !box.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, [open]);

  return (
    <div className="bell" ref={box}>
      <button
        type="button"
        className={`icon-btn${unread ? " has" : ""}`}
        aria-label={unread ? `Alerts, ${unread} new` : "Alerts"}
        aria-expanded={open}
        onClick={() => {
          setOpen(!open);
          if (!open && alerts.length) markAlertsSeen(alerts);
        }}
      >
        <span key={ring} className={ring ? "ring" : undefined} style={{ display: "grid" }}>
          <Icon name="bell" />
        </span>
        {unread ? <span className="bell-count">{unread}</span> : null}
      </button>
      {open ? (
        <div className="popover" role="dialog" aria-label="Alerts">
          <div className="popover-head">
            <b>Alerts</b>
            <Link to="/alerts" className="small" onClick={() => setOpen(false)}>
              See all
            </Link>
          </div>
          {alerts.length === 0 ? (
            <div className="popover-empty">
              <MiniHeart size={28} />
              <span>All quiet. Nothing needs you.</span>
            </div>
          ) : (
            <div className="list">
              {alerts.slice(0, 5).map((alert) => (
                <div key={alertId(alert)} className={`alert-row ${alert.severity}`}>
                  <span className="sev" aria-hidden="true" />
                  <div>
                    <b>{alert.title}</b>
                    <div className="small muted">{alert.detail}</div>
                    <div className="small faint">since {ago(alert.sinceUtc)}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      ) : null}
    </div>
  );
}

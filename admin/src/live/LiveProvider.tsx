import { useQueryClient } from "@tanstack/react-query";
import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { authHeaders, BASE_URL } from "../api/client";
import { LiveClient, mergeEvents, type LiveStatus } from "../api/live";
import type { LiveEvent, Pulse } from "../api/types";
import { getSession, setSession } from "../auth/session";
import { Halves, MiniHeart } from "../components/Art";
import { useToast } from "../components/feedback";
import { Icon } from "../components/Icon";
import { liveLine } from "../lib/labels";
import { playSound, type SoundKind } from "../lib/sound";

/* One live connection for the whole console: the pulse counts, the recent events, and a beat each time something
   happens, for the header's heart, the feed, toasts and sounds. */

export interface LiveState {
  status: LiveStatus;
  pulse: Pulse | null;
  events: LiveEvent[];
  /** Goes up by one per new event, newest last: drives the beating heart. */
  beat: number;
  /** The newest event that arrived live (not from the first snapshot). */
  latest: LiveEvent | null;
  /** Ids that arrived live, so the feed animates only those. */
  fresh: ReadonlySet<string>;
}

const empty: LiveState = { status: "stopped", pulse: null, events: [], beat: 0, latest: null, fresh: new Set() };
const LiveContext = createContext<LiveState>(empty);
export const useLive = () => useContext(LiveContext);

const SOUND: Partial<Record<LiveEvent["kind"], SoundKind>> = {
  coupleLinked: "chime",
  dareCompleted: "tick",
  plusStarted: "kaching",
  alert: "alert",
};

/** Applies an event to the pulse counts, so tiles move between polls. */
export function bumpPulse(pulse: Pulse | null, event: LiveEvent): Pulse | null {
  if (!pulse) return pulse;
  const next = { ...pulse };
  if (event.kind === "dareCompleted") next.heartsToday++;
  if (event.kind === "signedUp") next.signupsToday++;
  if (event.kind === "coupleLinked") next.couplesLinkedToday++;
  if (event.kind === "plusStarted") next.plusStartedToday++;
  return next;
}

export function LiveProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<LiveState>(empty);
  const toast = useToast();
  const client = useQueryClient();
  const toastRef = useRef(toast);
  toastRef.current = toast;

  useEffect(() => {
    // Ids already shown, checked synchronously so each event sounds and toasts once.
    const known = new Set<string>();
    let started = false;
    const live = new LiveClient({
      url: `${BASE_URL}/api/admin/analytics/live/events`,
      pulseUrl: `${BASE_URL}/api/admin/analytics/pulse`,
      headers: () => (getSession() ? authHeaders() : null),
      onStatus: (status) => setState((current) => ({ ...current, status })),
      onUnauthorized: () => setSession(null),
      onPulse: (pulse) => {
        // The first snapshot fills the feed quietly; later polls add what the stream missed, as fresh.
        const first = !started;
        started = true;
        const added = pulse.recent.filter((event) => !known.has(event.id));
        added.forEach((event) => known.add(event.id));
        setState((current) => ({
          ...current,
          pulse,
          events: mergeEvents(current.events, pulse.recent),
          beat: first ? current.beat : current.beat + added.length,
          fresh: first || !added.length ? current.fresh : new Set([...current.fresh, ...added.map((event) => event.id)].slice(-80)),
        }));
      },
      onEvent: (event) => {
        if (known.has(event.id)) return;
        known.add(event.id);
        setState((current) => ({
          ...current,
          events: mergeEvents(current.events, [event]),
          pulse: bumpPulse(current.pulse, event),
          beat: current.beat + 1,
          latest: event,
          fresh: new Set([...current.fresh, event.id].slice(-80)),
        }));
        const sound = SOUND[event.kind];
        if (sound) playSound(sound);
        announce(event);
        if (event.kind === "alert") void client.invalidateQueries({ queryKey: ["alerts"] });
      },
    });

    function announce(event: LiveEvent) {
      const line = liveLine(event);
      if (event.kind === "coupleLinked") toastRef.current(line.title, { tone: "good", art: <Halves className="fly" size={26} />, sub: "A new couple" });
      else if (event.kind === "plusStarted") toastRef.current(line.title, { tone: "plus", art: <MiniHeart size={26} tone="pink" />, sub: line.sub });
      else if (event.kind === "alert" && event.severity === "bad") toastRef.current(line.title, { tone: "bad", art: <Icon name="bell" />, ms: 7000 });
    }

    live.start();
    return () => live.stop();
  }, [client]);

  const value = useMemo(() => state, [state]);
  return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>;
}

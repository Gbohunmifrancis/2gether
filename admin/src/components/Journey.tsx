import { useQuery } from "@tanstack/react-query";
import { Fragment, useState, type CSSProperties } from "react";
import { queries } from "../api/queries";
import type { JourneyDay, JourneyKind } from "../api/types";
import { actionLabel, count } from "../lib/format";
import { exerciseLabel, gameLabel, JOURNEY_LABELS } from "../lib/labels";
import { productLabel } from "../lib/format";
import { MiniHeart } from "./Art";
import { Icon, type IconName } from "./Icon";
import { Card, ErrorState, Skeleton } from "./ui";

/* One person's days, newest first, as a strip of stickers: what they did each day, in counts, never content. */

const ICONS: Record<JourneyKind, IconName> = {
  signedUp: "userPlus",
  linked: "link",
  unlinked: "unlink",
  active: "spark",
  dareSet: "dares",
  dareCompleted: "check",
  dareMissed: "missed",
  mealLogged: "meal",
  photoSynced: "photo",
  questionAnswered: "question",
  gameFinished: "games",
  plusStarted: "plus",
  plusEnded: "plus",
  adminAction: "shield",
};

const MILESTONES = new Set<JourneyKind>(["signedUp", "linked", "unlinked", "plusStarted", "plusEnded"]);
const ORDER: JourneyKind[] = [
  "signedUp",
  "linked",
  "plusStarted",
  "dareCompleted",
  "dareSet",
  "dareMissed",
  "gameFinished",
  "questionAnswered",
  "mealLogged",
  "photoSynced",
  "unlinked",
  "plusEnded",
  "adminAction",
  "active",
];

function detailText(kind: JourneyKind, detail: string | null): string | null {
  if (!detail) return null;
  if (kind === "dareSet" || kind === "dareCompleted" || kind === "dareMissed") return exerciseLabel(detail);
  if (kind === "gameFinished") return gameLabel(detail);
  if (kind === "plusStarted" || kind === "plusEnded") return productLabel(detail);
  if (kind === "adminAction") return actionLabel(detail);
  return detail;
}

const dayFormat = new Intl.DateTimeFormat("en-GB", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });

/** Whole days between two "yyyy-MM-dd" days. */
export const daysBetween = (later: string, earlier: string) => Math.round((Date.parse(`${later}T00:00:00Z`) - Date.parse(`${earlier}T00:00:00Z`)) / 86_400_000);

function Day({ item, index }: { item: JourneyDay; index: number }) {
  const events = [...item.events].sort((a, b) => ORDER.indexOf(a.kind) - ORDER.indexOf(b.kind));
  const milestone = events.find((event) => MILESTONES.has(event.kind));
  const onlyOpened = events.every((event) => event.kind === "active");
  const [weekday, day, month] = dayFormat
    .format(new Date(`${item.day}T00:00:00Z`))
    .replace(",", "")
    .split(" ");
  return (
    <li
      className={`j-day${milestone ? ` milestone ${milestone.kind}` : ""}${onlyOpened ? " quiet" : ""}`}
      style={{ "--i": Math.min(index, 20) } as CSSProperties}
    >
      <time className="j-date" dateTime={item.day}>
        <b>{day}</b>
        <span>
          {weekday} {month}
        </span>
      </time>
      <span className="j-dot" aria-hidden="true">
        {milestone?.kind === "plusStarted" ? <MiniHeart size={14} tone="pink" /> : milestone ? <MiniHeart size={14} /> : null}
      </span>
      <div className="j-events">
        {events
          .filter((event) => event.kind !== "active" || onlyOpened)
          .map((event, position) => {
            const label = JOURNEY_LABELS[event.kind];
            const detail = detailText(event.kind, event.detail);
            const big = MILESTONES.has(event.kind);
            return (
              <span key={`${event.kind}-${position}`} className={`j-chip ${event.kind}${big ? " big" : ""}`}>
                <Icon name={ICONS[event.kind]} />
                {big || event.kind === "active" ? (
                  <b>{label.one}</b>
                ) : (
                  <>
                    <b>{count(event.count)}</b> {event.count === 1 ? label.one : label.many}
                  </>
                )}
                {detail ? <span className="faint"> · {detail}</span> : null}
              </span>
            );
          })}
      </div>
    </li>
  );
}

export function JourneyCard({ userId }: { userId: string }) {
  const [days, setDays] = useState(90);
  const [all, setAll] = useState(false);
  const { data, error, isPending, refetch } = useQuery(queries.journey(userId, days));
  const items = data?.items ?? [];
  const shown = all ? items : items.slice(0, 14);
  const activeDays = items.length;
  const hearts = items.reduce((sum, item) => sum + (item.events.find((event) => event.kind === "dareCompleted")?.count ?? 0), 0);

  return (
    <Card
      title="Journey"
      aside={
        <span className="seg small-seg" role="group" aria-label="How far back">
          {[30, 90, 180].map((value) => (
            <button key={value} type="button" className={value === days ? "on" : undefined} aria-pressed={value === days} onClick={() => setDays(value)}>
              {value} days
            </button>
          ))}
        </span>
      }
      className="journey-card"
    >
      {error ? (
        <ErrorState error={error} retry={() => void refetch()} />
      ) : isPending ? (
        <div className="grid">
          <Skeleton height={42} />
          <Skeleton height={42} />
          <Skeleton height={42} />
        </div>
      ) : items.length === 0 ? (
        <p className="faint">Nothing in the last {days} days.</p>
      ) : (
        <>
          <p className="small muted" style={{ marginBottom: 12 }}>
            Did something on <b>{count(activeDays)}</b> of the last {days} days · <b>{count(hearts)}</b> dares done. Days with nothing are left out.
          </p>
          <ol className="journey">
            {shown.map((item, index) => {
              const next = shown[index + 1];
              const gap = next ? daysBetween(item.day, next.day) - 1 : 0;
              return (
                <Fragment key={item.day}>
                  <Day item={item} index={index} />
                  {gap >= 2 ? (
                    <li className="j-gap" aria-label={`${gap} quiet days`}>
                      <span>{gap} quiet days</span>
                    </li>
                  ) : null}
                </Fragment>
              );
            })}
          </ol>
          {items.length > shown.length ? (
            <button type="button" className="btn sm outline" style={{ marginTop: 12 }} onClick={() => setAll(true)}>
              Show all {count(items.length)} days
            </button>
          ) : null}
        </>
      )}
    </Card>
  );
}

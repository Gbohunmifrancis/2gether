import type { ReactNode } from "react";
import { ApiError } from "../api/client";
import { Icon } from "./Icon";

export function PageHead({ title, sub, children }: { title: string; sub?: ReactNode; children?: ReactNode }) {
  return (
    <header className="head">
      <div>
        <h1 className="headline">{title}</h1>
        {sub ? <p>{sub}</p> : null}
      </div>
      {children}
    </header>
  );
}

export function Card({
  title,
  aside,
  children,
  flush,
  className,
}: {
  title?: ReactNode;
  aside?: ReactNode;
  children: ReactNode;
  flush?: boolean;
  className?: string;
}) {
  return (
    <section className={["card", flush ? "flush" : "", className ?? ""].join(" ").trim()}>
      {title || aside ? (
        <div className={`card-head${flush ? " pad" : ""}`}>
          {title ? <h2 className="title-s">{title}</h2> : <span />}
          {aside ? <div className="small faint">{aside}</div> : null}
        </div>
      ) : null}
      {children}
    </section>
  );
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <div className="card stat">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {hint ? <div className="hint">{hint}</div> : null}
    </div>
  );
}

export type Tone = "good" | "bad" | "warn" | "plus" | "soft" | undefined;

export function Badge({ tone, children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`badge${tone ? ` ${tone}` : ""}`}>{children}</span>;
}

export function Notice({ tone, children }: { tone?: "bad" | "good" | "warn"; children: ReactNode }) {
  return (
    <div className={`notice${tone ? ` ${tone}` : ""}`} role={tone === "bad" ? "alert" : "status"}>
      <Icon name={tone === "good" ? "check" : "alert"} />
      <div>{children}</div>
    </div>
  );
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return "Something went wrong.";
}

export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  return (
    <div className="card">
      <Notice tone="bad">
        {errorMessage(error)}{" "}
        {retry ? (
          <button type="button" className="btn sm ghost" onClick={retry}>
            Try again
          </button>
        ) : null}
      </Notice>
    </div>
  );
}

export function Skeleton({ height = 18, width = "100%" }: { height?: number; width?: number | string }) {
  return <div className="skeleton" style={{ height, width }} aria-hidden="true" />;
}

export function LoadingCards({ count = 4 }: { count?: number }) {
  return (
    <div className="grid cols-4" aria-busy="true" aria-label="Loading">
      {Array.from({ length: count }, (_, index) => (
        <div className="card" key={index}>
          <Skeleton width="50%" height={14} />
          <div style={{ height: 14 }} />
          <Skeleton width="70%" height={30} />
        </div>
      ))}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

/** An initial on a soft disc; a green dot when they're online now. Its colour follows the name, so people are told apart. */
export function Avatar({ name, large, online }: { name: string; large?: boolean; online?: boolean }) {
  const initial = name.trim().charAt(0).toUpperCase() || "?";
  const hue = [...name].reduce((sum, char) => sum + char.charCodeAt(0), 0) % 3;
  return (
    <span
      className={`avatar${large ? " lg" : ""} hue-${hue}`}
      aria-hidden={online ? undefined : true}
      role={online ? "img" : undefined}
      aria-label={online ? `${name}, online now` : undefined}
    >
      {initial}
      {online ? <span className="avatar-online" /> : null}
    </span>
  );
}

export function Pager({ page, pageSize, total, onPage }: { page: number; pageSize: number; total: number; onPage: (page: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);
  return (
    <nav className="pager" aria-label="Pages">
      <span>
        {from}–{to} of {total.toLocaleString("en-GB")}
      </span>
      <div className="actions">
        <button type="button" className="btn sm outline" disabled={page <= 1} onClick={() => onPage(page - 1)}>
          Previous
        </button>
        <button type="button" className="btn sm outline" disabled={page >= pages} onClick={() => onPage(page + 1)}>
          Next
        </button>
      </div>
    </nav>
  );
}

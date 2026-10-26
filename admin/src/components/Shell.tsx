import { Link, Outlet } from "@tanstack/react-router";
import { useEffect, useState, useSyncExternalStore } from "react";
import { getSession, onSessionChange, setSession } from "../auth/session";
import { NAV_GROUPS } from "../lib/nav";
import { LiveProvider } from "../live/LiveProvider";
import { Mark } from "./Art";
import { CommandPalette } from "./CommandPalette";
import { Bell, Greeting, LiveHeart, OnlinePill, PaletteButton, SoundToggle } from "./Header";
import { Icon } from "./Icon";
import { StepUpDialog } from "./StepUpDialog";
import { Avatar } from "./ui";

export function useSession() {
  return useSyncExternalStore(onSessionChange, getSession, getSession);
}

function Nav({ grouped }: { grouped?: boolean }) {
  return (
    <nav className="nav" aria-label="Sections">
      {NAV_GROUPS.map((group) => (
        <div key={group.title} className="nav-group" role="group" aria-label={group.title}>
          {grouped ? <div className="nav-title">{group.title}</div> : null}
          {group.items.map((item) => (
            <Link key={item.to} to={item.to} activeOptions={{ exact: item.exact, includeSearch: false }}>
              <Icon name={item.icon} />
              {item.label}
            </Link>
          ))}
        </div>
      ))}
    </nav>
  );
}

function Brand() {
  return (
    <Link to="/" className="brand" aria-label="Heartpact admin home">
      <Mark />
      <div>
        <b>heartpact</b>
        <span>Admin</span>
      </div>
    </Link>
  );
}

const DEMO = import.meta.env.VITE_DEMO === "1";

export function Shell() {
  const session = useSession();
  const [palette, setPalette] = useState(false);
  const ends = session ? new Date(session.expiresAtUtc).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }) : null;

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPalette((open) => !open);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <LiveProvider>
      <div className="shell">
        <aside className="side">
          <Brand />
          <Nav grouped />
          <div className="side-foot">
            <Avatar name={session?.admin.displayName ?? "?"} />
            <div className="side-me">
              <b>{session?.admin.displayName}</b>
              <small title={session?.admin.email}>{DEMO ? "Demo data" : ends ? `Session ends ${ends}` : session?.admin.email}</small>
            </div>
            <button type="button" className="icon-btn flat" onClick={() => setSession(null)} aria-label="Sign out" title="Sign out">
              <Icon name="signout" />
            </button>
          </div>
        </aside>
        <div className="topbar">
          <div className="topbar-row">
            <Brand />
            <div className="tools">
              <OnlinePill />
              <PaletteButton compact onOpen={() => setPalette(true)} />
              <SoundToggle />
              <Bell />
            </div>
          </div>
          <Nav />
        </div>
        <div className="main-col">
          <header className="hello">
            <Greeting name={session?.admin.displayName ?? "there"} />
            <div className="tools">
              <PaletteButton onOpen={() => setPalette(true)} />
              <SoundToggle />
              <Bell />
              <OnlinePill />
              <LiveHeart />
            </div>
          </header>
          <main className="main" id="main">
            <Outlet />
          </main>
          <footer className="privacy-line small faint">
            <Icon name="eyeOff" />
            Counts and account metadata only. Nothing here shows what couples write, photograph, log or answer, their cycle or pregnancy, or where they are.
          </footer>
        </div>
      </div>
      <CommandPalette open={palette} onClose={() => setPalette(false)} />
      <StepUpDialog />
    </LiveProvider>
  );
}

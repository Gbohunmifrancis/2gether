import { Link, Outlet } from "@tanstack/react-router";
import { useSyncExternalStore } from "react";
import { getSession, onSessionChange, setSession } from "../auth/session";
import { Icon, Mark, type IconName } from "./Icon";

const NAV: { to: "/" | "/engagement" | "/revenue" | "/users" | "/payments" | "/audit" | "/system"; label: string; icon: IconName; exact?: boolean }[] = [
  { to: "/", label: "Overview", icon: "overview", exact: true },
  { to: "/engagement", label: "Engagement", icon: "engagement" },
  { to: "/revenue", label: "Revenue", icon: "revenue" },
  { to: "/users", label: "Users", icon: "users" },
  { to: "/payments", label: "Payments", icon: "payments" },
  { to: "/audit", label: "Audit log", icon: "audit" },
  { to: "/system", label: "System", icon: "system" },
];

export function useSession() {
  return useSyncExternalStore(onSessionChange, getSession, getSession);
}

function Nav() {
  return (
    <nav className="nav" aria-label="Sections">
      {NAV.map((item) => (
        <Link key={item.to} to={item.to} activeOptions={{ exact: item.exact, includeSearch: false }}>
          <Icon name={item.icon} />
          {item.label}
        </Link>
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

export function Shell() {
  const session = useSession();
  const ends = session ? new Date(session.expiresAtUtc).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }) : null;
  return (
    <div className="shell">
      <aside className="side">
        <Brand />
        <Nav />
        <div className="side-foot">
          <div>
            <div style={{ fontWeight: 700 }}>{session?.admin.displayName}</div>
            <div className="small faint" style={{ overflowWrap: "anywhere" }}>
              {session?.admin.email}
            </div>
            {ends ? <div className="small faint">Session ends {ends}</div> : null}
          </div>
          <button type="button" className="btn sm outline" onClick={() => setSession(null)}>
            <Icon name="signout" />
            Sign out
          </button>
        </div>
      </aside>
      <div className="topbar">
        <div className="topbar-row">
          <Brand />
          <button type="button" className="btn sm ghost" onClick={() => setSession(null)}>
            <Icon name="signout" />
            Sign out
          </button>
        </div>
        <Nav />
      </div>
      <main className="main" id="main">
        <Outlet />
      </main>
    </div>
  );
}

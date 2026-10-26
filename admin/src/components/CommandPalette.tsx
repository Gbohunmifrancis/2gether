import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { queries } from "../api/queries";
import { setSession } from "../auth/session";
import { searchCommands, searchesPeople, type Command } from "../lib/commands";
import { NAV } from "../lib/nav";
import { setSoundEnabled, soundEnabled } from "../lib/sound";
import { Icon, type IconName } from "./Icon";
import { Avatar, Badge } from "./ui";

const DEMO = import.meta.env.VITE_DEMO === "1";

interface Entry {
  command: Command;
  icon: IconName | "person";
  run: () => void;
  sub?: string;
  badge?: string;
}

/** Waits for typing to pause before searching people. */
function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return settled;
}

/**
 * Cmd/Ctrl+K: jump to a page, find a person by email, name or id (the users search), or do a quick action.
 * Arrow keys move, Enter runs, Escape closes.
 */
export function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const navigate = useNavigate();
  const client = useQueryClient();
  const people = useDebounced(query.trim(), 220);
  const peopleQuery = useQuery({ ...queries.users({ query: people, page: 1 }), enabled: open && searchesPeople(people) });

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open) {
      setQuery("");
      setActive(0);
      if (!dialog.open) dialog.showModal?.();
      setTimeout(() => inputRef.current?.focus(), 20);
    } else if (dialog.open) dialog.close();
  }, [open]);

  const entries = useMemo<Entry[]>(() => {
    const go = (to: string, search?: Record<string, unknown>) => () => void navigate({ to, search: search as never });
    const pages: Entry[] = NAV.map((item) => ({
      command: { id: `page:${item.to}`, label: item.label, group: "Pages", keywords: item.keywords },
      icon: item.icon,
      run: go(item.to),
    }));
    const actionList: Entry[] = [
      {
        command: { id: "sound", label: soundEnabled() ? "Turn sounds off" : "Turn sounds on", group: "Actions", keywords: ["mute", "audio", "chime"] },
        icon: soundEnabled() ? "soundOff" : "soundOn",
        run: () => setSoundEnabled(!soundEnabled()),
      },
      {
        command: { id: "refresh", label: "Refresh every number", group: "Actions", keywords: ["reload", "update"] },
        icon: "refresh",
        run: () => void client.invalidateQueries(),
      },
      {
        command: { id: "disabled", label: "Show disabled accounts", group: "Actions", keywords: ["banned", "blocked"] },
        icon: "userX",
        run: go("/users", { status: "disabled", page: 1 }),
      },
      {
        command: { id: "onhold", label: "Subscriptions on hold", group: "Actions", keywords: ["payment trouble", "failed payments"] },
        icon: "payments",
        run: go("/payments", { tab: "subscriptions", status: "onHold", page: 1 }),
      },
      { command: { id: "signout", label: "Sign out", group: "Actions", keywords: ["log out", "leave"] }, icon: "signout", run: () => setSession(null) },
    ];
    if (DEMO) {
      for (const [kind, label] of [
        ["coupleLinked", "Demo: a couple links"],
        ["plusStarted", "Demo: someone goes Plus"],
        ["dareCompleted", "Demo: a dare is done"],
        ["alert", "Demo: raise an alert"],
      ] as const) {
        actionList.push({
          command: { id: `demo:${kind}`, label, group: "Actions", keywords: ["demo", "fire", "simulate"] },
          icon: "spark",
          run: () => (globalThis as { heartpactDemo?: { emit: (kind: string) => void } }).heartpactDemo?.emit(kind),
        });
      }
    }
    const matched = searchCommands(
      query,
      [...pages, ...actionList].map((entry) => entry.command),
      query ? 9 : 30,
    );
    const byId = new Map([...pages, ...actionList].map((entry) => [entry.command.id, entry]));
    const found: Entry[] = matched.map((command) => byId.get(command.id)!);
    if (searchesPeople(query) && peopleQuery.data && people === query.trim()) {
      for (const user of peopleQuery.data.items.slice(0, 6)) {
        found.push({
          command: { id: `user:${user.id}`, label: user.displayName, group: "People" },
          icon: "person",
          sub: user.email,
          badge: user.internal ? "Internal" : user.disabledAtUtc ? "Disabled" : undefined,
          run: () => void navigate({ to: "/users/$userId", params: { userId: user.id } }),
        });
      }
    }
    return found;
  }, [query, people, peopleQuery.data, navigate, client]);

  useEffect(() => setActive((current) => Math.min(current, Math.max(0, entries.length - 1))), [entries.length]);

  const run = (entry: Entry | undefined) => {
    if (!entry) return;
    onClose();
    entry.run();
  };

  function onKey(event: KeyboardEvent) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActive((current) => (current + 1) % Math.max(1, entries.length));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActive((current) => (current - 1 + entries.length) % Math.max(1, entries.length));
    } else if (event.key === "Enter") {
      event.preventDefault();
      run(entries[active]);
    }
  }

  useEffect(() => {
    document.getElementById(`cmd-${active}`)?.scrollIntoView?.({ block: "nearest" });
  }, [active]);

  let lastGroup = "";
  const searching = searchesPeople(query) && (people !== query.trim() || peopleQuery.isFetching);

  return (
    <dialog
      ref={ref}
      className="palette"
      aria-label="Command palette"
      onCancel={onClose}
      onClick={(event) => {
        if (event.target === ref.current) onClose();
      }}
    >
      <div className="palette-input">
        <Icon name="command" />
        <input
          ref={inputRef}
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setActive(0);
          }}
          onKeyDown={onKey}
          placeholder="Jump to a page, find someone, or do something…"
          aria-label="Search pages, people and actions"
          role="combobox"
          aria-expanded="true"
          aria-controls="palette-list"
          aria-activedescendant={entries[active] ? `cmd-${active}` : undefined}
          spellCheck={false}
          autoComplete="off"
        />
        <kbd>Esc</kbd>
      </div>
      <ul className="palette-list" id="palette-list" role="listbox" aria-label="Results">
        {entries.map((entry, index) => {
          const header = entry.command.group !== lastGroup ? entry.command.group : null;
          lastGroup = entry.command.group;
          return (
            <li key={entry.command.id} role="presentation">
              {header ? <div className="palette-group">{header}</div> : null}
              <div
                id={`cmd-${index}`}
                role="option"
                aria-selected={index === active}
                className={`palette-item${index === active ? " on" : ""}`}
                onMouseMove={() => setActive(index)}
                onClick={() => run(entry)}
              >
                {entry.icon === "person" ? <Avatar name={entry.command.label} /> : <Icon name={entry.icon} />}
                <span className="palette-text">
                  <b>{entry.command.label}</b>
                  {entry.sub ? <small>{entry.sub}</small> : null}
                </span>
                {entry.badge ? <Badge tone="soft">{entry.badge}</Badge> : null}
                {index === active ? <Icon name="arrow" /> : null}
              </div>
            </li>
          );
        })}
        {searching ? <li className="palette-note">Looking for people…</li> : null}
        {!searching && entries.length === 0 ? <li className="palette-note">Nothing matches “{query}”.</li> : null}
      </ul>
      <div className="palette-foot small faint">
        <span>
          <kbd>↑</kbd> <kbd>↓</kbd> to move · <kbd>Enter</kbd> to go
        </span>
        <span>People by email, name or id</span>
      </div>
    </dialog>
  );
}

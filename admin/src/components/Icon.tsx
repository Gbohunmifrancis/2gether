import type { ReactNode } from "react";

// A small stroke icon set drawn on a 24px grid, so the dashboard needs no icon font.
const paths: Record<string, ReactNode> = {
  overview: (
    <>
      <path d="M4 20V10" />
      <path d="M10 20V4" />
      <path d="M16 20v-7" />
      <path d="M22 20H2" />
    </>
  ),
  engagement: <path d="M2 12h4l3-8 6 16 3-8h4" />,
  revenue: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M15 8.5c-.6-.9-1.7-1.5-3-1.5-1.9 0-3 1-3 2.3 0 3.2 6 1.8 6 5 0 1.4-1.2 2.7-3 2.7-1.4 0-2.6-.6-3.2-1.6" />
      <path d="M12 5v2M12 17v2" />
    </>
  ),
  users: (
    <>
      <circle cx="9" cy="8" r="3.5" />
      <path d="M2.5 20c.6-3.6 3.3-6 6.5-6s5.9 2.4 6.5 6" />
      <path d="M16 4.6a3.5 3.5 0 0 1 0 6.8" />
      <path d="M18 14.4c2 .8 3.3 2.9 3.6 5.6" />
    </>
  ),
  payments: (
    <>
      <rect x="2.5" y="5" width="19" height="14" rx="3" />
      <path d="M2.5 10h19" />
      <path d="M6.5 15h4" />
    </>
  ),
  audit: (
    <>
      <path d="M6 2.5h8l4.5 4.5v14.5H6z" />
      <path d="M14 2.5V7h4.5" />
      <path d="M9 12h6M9 16h6" />
    </>
  ),
  system: (
    <>
      <path d="M4 6h10M18 6h2M4 12h3M11 12h9M4 18h11M19 18h1" />
      <circle cx="16" cy="6" r="2" />
      <circle cx="9" cy="12" r="2" />
      <circle cx="17" cy="18" r="2" />
    </>
  ),
  signout: (
    <>
      <path d="M14 4h4.5a1.5 1.5 0 0 1 1.5 1.5v13a1.5 1.5 0 0 1-1.5 1.5H14" />
      <path d="M10 8l-4 4 4 4" />
      <path d="M6 12h10" />
    </>
  ),
  back: (
    <>
      <path d="M15 5l-7 7 7 7" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="6.5" />
      <path d="M16 16l4.5 4.5" />
    </>
  ),
  check: <path d="M5 12.5l4.5 4.5L19 7.5" />,
  alert: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.5v5.5" />
      <path d="M12 16.5v.01" />
    </>
  ),
  shield: (
    <>
      <path d="M12 2.5l8 3v6c0 5-3.4 8.6-8 10-4.6-1.4-8-5-8-10v-6z" />
      <path d="M8.5 12l2.5 2.5 4.5-5" />
    </>
  ),
  refresh: (
    <>
      <path d="M20 11a8 8 0 0 0-14.3-4.3L4 8.5" />
      <path d="M4 3.5v5h5" />
      <path d="M4 13a8 8 0 0 0 14.3 4.3l1.7-1.8" />
      <path d="M20 20.5v-5h-5" />
    </>
  ),
  live: (
    <>
      <circle cx="12" cy="12" r="2.5" />
      <path d="M7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4" />
      <path d="M4.9 4.9a10 10 0 0 0 0 14.2M19.1 4.9a10 10 0 0 1 0 14.2" />
    </>
  ),
  bell: (
    <>
      <path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2H4.5z" />
      <path d="M10 21h4" />
    </>
  ),
  funnel: <path d="M3.5 4.5h17l-6.5 8v6l-4 2v-8z" />,
  retention: (
    <>
      <rect x="3.5" y="3.5" width="7" height="7" rx="2" />
      <rect x="13.5" y="3.5" width="7" height="7" rx="2" />
      <rect x="3.5" y="13.5" width="7" height="7" rx="2" />
      <rect x="13.5" y="13.5" width="7" height="7" rx="2" opacity="0.45" />
    </>
  ),
  rhythm: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3.5 2" />
    </>
  ),
  dares: (
    <>
      <path d="M6.5 8v8M17.5 8v8M3.5 10v4M20.5 10v4M6.5 12h11" />
    </>
  ),
  games: (
    <>
      <rect x="2.5" y="7" width="19" height="11" rx="5.5" />
      <path d="M7.5 10.5v4M5.5 12.5h4" />
      <circle cx="15.5" cy="11.5" r="0.6" fill="currentColor" />
      <circle cx="17.5" cy="13.8" r="0.6" fill="currentColor" />
    </>
  ),
  growth: (
    <>
      <path d="M3 17l6-6 4 4 8-8" />
      <path d="M15 7h6v6" />
    </>
  ),
  world: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18" />
      <path d="M12 3c2.6 2.6 3.8 5.6 3.8 9s-1.2 6.4-3.8 9c-2.6-2.6-3.8-5.6-3.8-9S9.4 5.6 12 3z" />
    </>
  ),
  couples: (
    <>
      <path d="M8.5 19.5L3.6 14.6a3.6 3.6 0 0 1 4.9-5.3 3.6 3.6 0 0 1 4.9 5.3z" />
      <path d="M14.5 6.4a3.6 3.6 0 0 1 5.9 4.1l-3.4 3.5" />
    </>
  ),
  health: (
    <>
      <path d="M12 20.5L4.2 12.8a4.9 4.9 0 0 1 7.8-5.9 4.9 4.9 0 0 1 7.8 5.9z" />
      <path d="M3.5 12.5h4l1.5-2.5 2.5 5 2-3.5h7" />
    </>
  ),
  soundOn: (
    <>
      <path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" />
      <path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11" />
    </>
  ),
  soundOff: (
    <>
      <path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" />
      <path d="M16 9.5l5 5M21 9.5l-5 5" />
    </>
  ),
  chart: (
    <>
      <path d="M4 19V5" />
      <path d="M4 19h16" />
      <path d="M7.5 15l3.5-4 3 2.5 5-6" />
    </>
  ),
  table: (
    <>
      <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
      <path d="M3.5 9.5h17M3.5 14.5h17M10 9.5v10" />
    </>
  ),
  heart: <path d="M12 20.5L4.2 12.8a4.9 4.9 0 0 1 7.8-5.9 4.9 4.9 0 0 1 7.8 5.9z" />,
  camera: (
    <>
      <path d="M3.5 8.5a2 2 0 0 1 2-2h2l1.5-2h6l1.5 2h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z" />
      <circle cx="12" cy="13" r="3.5" />
    </>
  ),
  ring: (
    <>
      <circle cx="12" cy="13" r="7" />
      <path d="M12 9.5V13l2 1.5" />
      <path d="M3.5 6.5l3-3M20.5 6.5l-3-3" />
    </>
  ),
  plus: (
    <>
      <path d="M12 3.5l2.4 5.1 5.6.7-4.1 3.9 1 5.5-4.9-2.7-4.9 2.7 1-5.5L4 9.3l5.6-.7z" />
    </>
  ),
  userPlus: (
    <>
      <circle cx="9" cy="8" r="3.5" />
      <path d="M2.5 20c.6-3.6 3.3-6 6.5-6s5.9 2.4 6.5 6" />
      <path d="M19 8v6M16 11h6" />
    </>
  ),
  userX: (
    <>
      <circle cx="9" cy="8" r="3.5" />
      <path d="M2.5 20c.6-3.6 3.3-6 6.5-6s5.9 2.4 6.5 6" />
      <path d="M17 9l4 4M21 9l-4 4" />
    </>
  ),
  broken: (
    <>
      <path d="M12 20.5L4.2 12.8a4.9 4.9 0 0 1 7.8-5.9 4.9 4.9 0 0 1 7.8 5.9z" />
      <path d="M12 6.9l-1.5 4 3 2-1.5 3" />
    </>
  ),
  missed: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M8.5 8.5l7 7M15.5 8.5l-7 7" />
    </>
  ),
  lock: (
    <>
      <rect x="4.5" y="10.5" width="15" height="10" rx="3" />
      <path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" />
    </>
  ),
  flask: (
    <>
      <path d="M9.5 3.5h5M10 3.5v6l-5.2 8.6a1.6 1.6 0 0 0 1.4 2.4h11.6a1.6 1.6 0 0 0 1.4-2.4L14 9.5v-6" />
      <path d="M7.5 14.5h9" />
    </>
  ),
  close: <path d="M6 6l12 12M18 6L6 18" />,
  arrow: <path d="M5 12h14M13 6l6 6-6 6" />,
  command: (
    <>
      <circle cx="11" cy="11" r="6.5" />
      <path d="M16 16l4.5 4.5" />
    </>
  ),
  meal: (
    <>
      <path d="M7 3.5v17M4.5 3.5v5a2.5 2.5 0 0 0 5 0v-5" />
      <path d="M17 20.5v-17c-2 1-3.5 3.5-3.5 7 0 2 1.5 3 3.5 3" />
    </>
  ),
  photo: (
    <>
      <rect x="3.5" y="4.5" width="17" height="15" rx="3" />
      <circle cx="9" cy="10" r="1.8" />
      <path d="M20.5 15.5l-5-5-9 9" />
    </>
  ),
  question: (
    <>
      <path d="M4 5.5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H10l-4.5 4v-4H6a2 2 0 0 1-2-2z" />
      <path d="M10 8.2a2 2 0 1 1 2.5 1.9c-.4.2-.5.5-.5.9v.4M12 13.5v.01" />
    </>
  ),
  link: (
    <>
      <path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1" />
      <path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" />
    </>
  ),
  unlink: (
    <>
      <path d="M15.5 13.5l3-3a4 4 0 0 0-5.7-5.7l-1.3 1.3" />
      <path d="M8.5 10.5l-3 3a4 4 0 0 0 5.7 5.7l1.3-1.3" />
      <path d="M4 4l16 16" />
    </>
  ),
  spark: <path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M6 18l2.5-2.5M15.5 8.5L18 6" />,
  flame: (
    <path d="M12 21c-3.9 0-6.5-2.6-6.5-6.2 0-3.6 3-5.6 3.6-9.3 2.4 1.5 3.4 3.6 3.4 5.4.9-.6 1.6-1.6 1.9-2.9 2 1.8 4.1 4.3 4.1 6.8 0 3.6-2.6 6.2-6.5 6.2z" />
  ),
  storage: (
    <>
      <ellipse cx="12" cy="6" rx="8" ry="2.8" />
      <path d="M4 6v12c0 1.5 3.6 2.8 8 2.8s8-1.3 8-2.8V6" />
      <path d="M4 12c0 1.5 3.6 2.8 8 2.8s8-1.3 8-2.8" />
    </>
  ),
  queue: (
    <>
      <path d="M4 6.5h16M4 12h16M4 17.5h10" />
    </>
  ),
  eyeOff: (
    <>
      <path d="M3 3l18 18" />
      <path d="M10.6 5.6A10 10 0 0 1 12 5.5c5 0 8.5 4.5 9.5 6.5-.5 1-1.6 2.6-3.2 4M6.4 7.4C4.6 8.7 3.2 10.6 2.5 12c1 2 4.5 6.5 9.5 6.5 1.6 0 3-.4 4.3-1.1" />
      <path d="M9.9 10a3 3 0 0 0 4.2 4.2" />
    </>
  ),
  calendar: (
    <>
      <rect x="3.5" y="5" width="17" height="15.5" rx="3" />
      <path d="M3.5 10h17M8 3v4M16 3v4" />
    </>
  ),
};

export type IconName = keyof typeof paths;

export function Icon({ name, label }: { name: IconName; label?: string }) {
  return (
    <svg
      className="ic"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.9}
      strokeLinecap="round"
      strokeLinejoin="round"
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      {paths[name]}
    </svg>
  );
}

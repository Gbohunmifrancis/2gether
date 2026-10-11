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

/** The Heartpact mark: the heart folded in two, indigo and periwinkle, with the tick cut out. */
export function Mark() {
  return (
    <svg viewBox="-2 4.4 104 97.6" aria-hidden="true">
      <defs>
        <mask id="hp-tick" maskUnits="userSpaceOnUse" x="-10" y="-10" width="130" height="130">
          <rect x="-10" y="-10" width="130" height="130" fill="#fff" />
          <path d="M28 56 L44 72 L74 38" fill="none" stroke="#000" strokeWidth="9.5" strokeLinecap="round" strokeLinejoin="round" />
        </mask>
      </defs>
      <g mask="url(#hp-tick)">
        <path d="M50 100 L7.57 57.57 A30 30 0 0 1 50 15.15 Z" fill="var(--chart-1)" />
        <path d="M50 100 L92.43 57.57 A30 30 0 0 0 50 15.15 Z" fill="var(--theo-art)" />
      </g>
    </svg>
  );
}

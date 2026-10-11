import { Link } from "@tanstack/react-router";
import { RANGES } from "../lib/search";

const LABELS: Record<number, string> = { 7: "7 days", 30: "30 days", 90: "90 days", 365: "1 year" };

/** The analytics window, kept in the URL so a view can be shared. */
export function RangePicker({ to, days }: { to: "/" | "/engagement" | "/revenue"; days: number }) {
  return (
    <div className="seg" role="group" aria-label="Date range">
      {RANGES.map((range) => (
        <Link
          key={range}
          to={to}
          search={{ days: range }}
          className={range === days ? "on" : undefined}
          aria-current={range === days ? "true" : undefined}
          activeOptions={{ includeSearch: true }}
        >
          {LABELS[range]}
        </Link>
      ))}
    </div>
  );
}

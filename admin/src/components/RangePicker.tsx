import { Link } from "@tanstack/react-router";
import { RANGES } from "../lib/search";

const LABELS: Record<number, string> = { 7: "7 days", 30: "30 days", 90: "90 days", 365: "1 year" };

/** The analytics window, kept in the URL so a view can be shared. Works on any page with a `days` search param. */
export function RangePicker({ days, ranges = RANGES, param = "days" }: { days: number; ranges?: readonly number[]; param?: "days" | "weeks" }) {
  return (
    <div className="seg" role="group" aria-label={param === "weeks" ? "Weeks" : "Date range"}>
      {ranges.map((range) => (
        <Link
          key={range}
          to="."
          search={((prev: Record<string, unknown>) => ({ ...prev, [param]: range })) as never}
          className={range === days ? "on" : undefined}
          aria-current={range === days ? "true" : undefined}
          activeOptions={{ includeSearch: true }}
          replace
        >
          {param === "weeks" ? `${range} weeks` : (LABELS[range] ?? `${range} days`)}
        </Link>
      ))}
    </div>
  );
}

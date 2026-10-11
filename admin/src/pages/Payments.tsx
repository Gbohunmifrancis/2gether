import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { queries } from "../api/queries";
import { SearchBox } from "../components/SearchBox";
import { GrantsTable, SubscriptionsTable } from "../components/Subscriptions";
import { Card, ErrorState, PageHead, Pager } from "../components/ui";
import { subscriptionStatusLabel } from "../lib/format";
import { SUBSCRIPTION_STATUSES } from "../lib/search";

const route = getRouteApi("/_app/payments");

export function PaymentsPage() {
  const search = route.useSearch();
  return (
    <>
      <PageHead title="Payments" sub="Google Play subscriptions, and Plus you've given couples for free.">
        <div className="seg" role="tablist" aria-label="Payments">
          <Link
            to="/payments"
            search={{ tab: "subscriptions", page: 1 }}
            className={search.tab === "subscriptions" ? "on" : undefined}
            role="tab"
            aria-selected={search.tab === "subscriptions"}
          >
            Subscriptions
          </Link>
          <Link
            to="/payments"
            search={{ tab: "grants", page: 1 }}
            className={search.tab === "grants" ? "on" : undefined}
            role="tab"
            aria-selected={search.tab === "grants"}
          >
            Complimentary Plus
          </Link>
        </div>
      </PageHead>
      {search.tab === "grants" ? <Grants all={search.all ?? false} /> : <Subscriptions />}
    </>
  );
}

function Subscriptions() {
  const search = route.useSearch();
  const navigate = useNavigate({ from: "/payments" });
  const { data, error, isPending, isFetching, refetch } = useQuery(queries.subscriptions({ query: search.query, status: search.status, page: search.page }));
  return (
    <>
      {error ? <ErrorState error={error} retry={() => void refetch()} /> : null}
      <Card flush>
        <div className="toolbar">
          <SearchBox
            value={search.query}
            placeholder="Buyer email or product"
            onSearch={(query) => void navigate({ search: (prev) => ({ ...prev, query, page: 1 }), replace: true })}
          />
          <label>
            <span className="visually-hidden">State</span>
            <select
              className="select"
              value={search.status ?? ""}
              onChange={(event) => void navigate({ search: (prev) => ({ ...prev, status: event.target.value || undefined, page: 1 }) })}
            >
              <option value="">Every state</option>
              {SUBSCRIPTION_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {subscriptionStatusLabel[status]}
                </option>
              ))}
            </select>
          </label>
          {isFetching && !isPending ? <span className="small faint">Updating…</span> : null}
        </div>
        <SubscriptionsTable rows={data?.items} loading={isPending} />
        {data ? (
          <Pager page={data.page} pageSize={data.pageSize} total={data.total} onPage={(page) => void navigate({ search: (prev) => ({ ...prev, page }) })} />
        ) : null}
      </Card>
      <p className="small faint" style={{ marginTop: 12 }}>
        Stop renewal keeps Plus until the paid period ends. Refund returns the latest payment and keeps the subscription. Revoke refunds and ends Plus now.
        Every action asks Google Play first and is recorded in the audit log.
      </p>
    </>
  );
}

function Grants({ all }: { all: boolean }) {
  const navigate = useNavigate({ from: "/payments" });
  const { data, error, isPending, refetch } = useQuery(queries.grants(!all));
  return (
    <>
      {error ? <ErrorState error={error} retry={() => void refetch()} /> : null}
      <Card
        flush
        title={all ? "Every grant" : "Active grants"}
        aside={
          <label className="small" style={{ display: "inline-flex", gap: 8, alignItems: "center", cursor: "pointer" }}>
            <input
              type="checkbox"
              checked={all}
              onChange={(event) => void navigate({ search: (prev) => ({ ...prev, all: event.target.checked || undefined }) })}
            />
            Include ended and revoked
          </label>
        }
      >
        <GrantsTable rows={data} loading={isPending} />
      </Card>
      <p className="small faint" style={{ marginTop: 12 }}>
        To give a couple Plus, open either partner from Users and choose Give Plus.
      </p>
    </>
  );
}

import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { createColumnHelper } from "@tanstack/react-table";
import { queries } from "../api/queries";
import type { AuditRow } from "../api/types";
import { DataTable } from "../components/DataTable";
import { SearchBox } from "../components/SearchBox";
import { Badge, Card, ErrorState, PageHead, Pager } from "../components/ui";
import { actionLabel, auditActions, auditDetail, dateTime } from "../lib/format";

const route = getRouteApi("/_app/audit");
const column = createColumnHelper<AuditRow>();

function Target({ row }: { row: AuditRow }) {
  if (!row.targetId) return <span className="faint">–</span>;
  if (row.targetType === "user" && row.action !== "user.deleted") {
    return (
      <Link to="/users/$userId" params={{ userId: row.targetId }} className="mono">
        user {row.targetId.slice(0, 8)}…
      </Link>
    );
  }
  if (row.targetType === "couple") {
    return (
      <Link to="/users" search={{ query: row.targetId, page: 1 }} className="mono">
        couple {row.targetId.slice(0, 8)}…
      </Link>
    );
  }
  return (
    <span className="mono" title={row.targetId}>
      {row.targetType} {row.targetId.slice(0, 8)}…
    </span>
  );
}

const columns = [
  column.accessor("createdAtUtc", { header: "When", cell: ({ getValue }) => <span style={{ whiteSpace: "nowrap" }}>{dateTime(getValue())}</span> }),
  column.accessor("adminEmail", { header: "Admin", meta: { secondary: true } }),
  column.accessor("action", {
    header: "What",
    cell: ({ row }) => (
      <div>
        <b>{actionLabel(row.original.action)}</b> {row.original.succeeded ? null : <Badge tone="bad">Failed</Badge>}
        {row.original.details ? (
          <div className="small muted" style={{ overflowWrap: "anywhere", maxWidth: 420 }}>
            {auditDetail(row.original.details)}
          </div>
        ) : null}
      </div>
    ),
  }),
  column.accessor("targetId", { header: "On", meta: { secondary: true }, cell: ({ row }) => <Target row={row.original} /> }),
  column.accessor("ipAddress", { header: "From", meta: { secondary: true }, cell: ({ getValue }) => <span className="mono faint">{getValue() ?? "–"}</span> }),
];

export function AuditPage() {
  const search = route.useSearch();
  const navigate = useNavigate({ from: "/audit" });
  const { data, error, isPending, isFetching, refetch } = useQuery(queries.audit(search));
  return (
    <>
      <PageHead title="Audit log" sub="Every admin sign-in and action, newest first. It can't be edited from here." />
      {error ? <ErrorState error={error} retry={() => void refetch()} /> : null}
      <Card flush>
        <div className="toolbar">
          <SearchBox
            value={search.query}
            placeholder="Admin email or target id"
            onSearch={(query) => void navigate({ search: (prev) => ({ ...prev, query, page: 1 }), replace: true })}
          />
          <label>
            <span className="visually-hidden">Action</span>
            <select
              className="select"
              value={search.action ?? ""}
              onChange={(event) => void navigate({ search: (prev) => ({ ...prev, action: event.target.value || undefined, page: 1 }) })}
            >
              <option value="">Every action</option>
              <option value="admin.">Admin sign-ins</option>
              <option value="user.">Account actions</option>
              <option value="subscription.">Subscription actions</option>
              <option value="plus.">Complimentary Plus</option>
              <optgroup label="Exactly">
                {auditActions.map((action) => (
                  <option key={action} value={action}>
                    {actionLabel(action)}
                  </option>
                ))}
              </optgroup>
            </select>
          </label>
          {isFetching && !isPending ? <span className="small faint">Updating…</span> : null}
        </div>
        <DataTable data={data?.items} columns={columns} loading={isPending} getRowId={(row) => row.id} empty="Nothing recorded yet." />
        {data ? (
          <Pager page={data.page} pageSize={data.pageSize} total={data.total} onPage={(page) => void navigate({ search: (prev) => ({ ...prev, page }) })} />
        ) : null}
      </Card>
    </>
  );
}

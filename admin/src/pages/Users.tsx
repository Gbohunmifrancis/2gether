import { useQuery } from "@tanstack/react-query";
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { createColumnHelper } from "@tanstack/react-table";
import { queries, type UserStatusFilter } from "../api/queries";
import type { UserRow } from "../api/types";
import { DataTable } from "../components/DataTable";
import { SearchBox } from "../components/SearchBox";
import { Icon } from "../components/Icon";
import { Avatar, Badge, Card, ErrorState, PageHead, Pager } from "../components/ui";
import { ago, coupleStatusLabel, date } from "../lib/format";

/** Active in the last 5 minutes, like the pulse's "online now". */
export const isOnline = (lastActive: string | null | undefined, now = Date.now()) => Boolean(lastActive && now - Date.parse(lastActive) < 5 * 60_000);

export function InternalBadge() {
  return (
    <Badge tone="warn">
      <Icon name="flask" />
      Internal
    </Badge>
  );
}

const route = getRouteApi("/_app/users");
const column = createColumnHelper<UserRow>();

export function SignInMethods({ google, password }: { google: boolean; password: boolean }) {
  return <span className="small muted">{[password ? "Password" : null, google ? "Google" : null].filter(Boolean).join(" + ") || "None"}</span>;
}

const columns = [
  column.accessor("displayName", {
    header: "Person",
    cell: ({ row }) => (
      <div className="who">
        <Avatar name={row.original.displayName} online={isOnline(row.original.lastActiveAtUtc)} />
        <div style={{ minWidth: 0 }}>
          <b>
            {row.original.displayName} {row.original.internal ? <InternalBadge /> : null}
          </b>
          <small>{row.original.email}</small>
        </div>
      </div>
    ),
  }),
  column.accessor("coupleStatus", {
    header: "Couple",
    meta: { secondary: true },
    cell: ({ getValue }) => {
      const status = getValue();
      return status ? <Badge tone={status === "active" ? "soft" : undefined}>{coupleStatusLabel[status]}</Badge> : <span className="faint">No couple</span>;
    },
  }),
  column.accessor("paying", {
    header: "Plan",
    meta: { secondary: true },
    cell: ({ getValue }) => (getValue() ? <Badge tone="plus">Pays for Plus</Badge> : <span className="faint">Free</span>),
  }),
  column.display({
    id: "signIn",
    header: "Signs in with",
    meta: { secondary: true },
    cell: ({ row }) => <SignInMethods google={row.original.google} password={row.original.password} />,
  }),
  column.accessor("createdAtUtc", { header: "Joined", meta: { secondary: true }, cell: ({ getValue }) => date(getValue()) }),
  column.accessor("lastActiveAtUtc", {
    header: "Last active",
    meta: { secondary: true },
    cell: ({ getValue }) =>
      isOnline(getValue()) ? (
        <span className="online-badge">
          <span className="dot" aria-hidden="true" />
          Online now
        </span>
      ) : (
        ago(getValue())
      ),
  }),
  column.accessor("disabledAtUtc", {
    header: "Status",
    cell: ({ getValue }) => (getValue() ? <Badge tone="bad">Disabled</Badge> : <span className="faint">Active</span>),
  }),
];

export function UsersPage() {
  const search = route.useSearch();
  const navigate = useNavigate({ from: "/users" });
  const { data, error, isPending, isFetching, refetch } = useQuery(queries.users(search));

  return (
    <>
      <PageHead title="Users" sub="Find someone by email, name, user id or couple id." />
      {error ? <ErrorState error={error} retry={() => void refetch()} /> : null}
      <Card flush>
        <div className="toolbar">
          <SearchBox
            value={search.query}
            placeholder="Email, name or id"
            onSearch={(query) => void navigate({ search: (prev) => ({ ...prev, query, page: 1 }), replace: true })}
          />
          <label>
            <span className="visually-hidden">Status</span>
            <select
              className="select"
              value={search.status ?? ""}
              onChange={(event) =>
                void navigate({ search: (prev) => ({ ...prev, status: (event.target.value || undefined) as UserStatusFilter | undefined, page: 1 }) })
              }
            >
              <option value="">Everyone</option>
              <option value="active">Active</option>
              <option value="disabled">Disabled</option>
              <option value="unlinked">Not in a couple</option>
              <option value="internal">Internal (test and team)</option>
            </select>
          </label>
          {isFetching && !isPending ? <span className="small faint">Updating…</span> : null}
        </div>
        <DataTable
          data={data?.items}
          columns={columns}
          loading={isPending}
          getRowId={(row) => row.id}
          rowLabel={(row) => `Open ${row.displayName}, ${row.email}`}
          onRowClick={(row) => void navigate({ to: "/users/$userId", params: { userId: row.id } })}
          empty={search.query ? `No one matches “${search.query}”.` : "No users yet."}
        />
        {data ? (
          <Pager page={data.page} pageSize={data.pageSize} total={data.total} onPage={(page) => void navigate({ search: (prev) => ({ ...prev, page }) })} />
        ) : null}
      </Card>
    </>
  );
}

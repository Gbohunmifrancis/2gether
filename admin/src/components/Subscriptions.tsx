import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { createColumnHelper } from "@tanstack/react-table";
import { useCallback, useMemo } from "react";
import { actions } from "../api/queries";
import type { Grant, SubscriptionRow, SubscriptionStatus } from "../api/types";
import { date, dateTime, productLabel, subscriptionStatusLabel } from "../lib/format";
import { DataTable } from "./DataTable";
import { useConfirm, useToast, type ConfirmOptions } from "./feedback";
import { Icon } from "./Icon";
import { Badge, errorMessage, type Tone } from "./ui";

const tones: Partial<Record<SubscriptionStatus, Tone>> = {
  active: "good",
  canceled: "warn",
  inGracePeriod: "warn",
  onHold: "bad",
  paused: "warn",
  expired: undefined,
};

export function StatusBadge({ status }: { status: SubscriptionStatus }) {
  return <Badge tone={tones[status]}>{subscriptionStatusLabel[status]}</Badge>;
}

/** Refreshes everything a payment change can move. */
export function usePaymentsRefresh() {
  const client = useQueryClient();
  return useCallback(
    () =>
      Promise.all([
        client.invalidateQueries({ queryKey: ["subscriptions"] }),
        client.invalidateQueries({ queryKey: ["grants"] }),
        client.invalidateQueries({ queryKey: ["analytics", "revenue"] }),
        client.invalidateQueries({ queryKey: ["analytics", "overview"] }),
        client.invalidateQueries({ queryKey: ["users"] }),
        client.invalidateQueries({ queryKey: ["user"] }),
      ]),
    [client],
  );
}

const live = (status: SubscriptionStatus) =>
  status === "active" || status === "canceled" || status === "inGracePeriod" || status === "onHold" || status === "paused";

/** Card subscriptions are Bachs's: refunds happen in the Bachs dashboard, never through Google Play. */
const isCard = (row: SubscriptionRow) => row.store === "bachs";

function SubscriptionActions({ row, ask }: { row: SubscriptionRow; ask: (options: ConfirmOptions) => void }) {
  const toast = useToast();
  const card = isCard(row);
  const storeName = card ? "Bachs" : "Google Play";
  const refresh = usePaymentsRefresh();
  const sync = useMutation({
    mutationFn: () => actions.syncSubscription(row.id),
    onSuccess: async () => {
      await refresh();
      toast(`Synced with ${storeName}.`);
    },
    onError: (error) => toast(errorMessage(error), true),
  });

  const run =
    (call: (reason: string) => Promise<unknown>, done: string) =>
    async ({ reason }: { reason: string }) => {
      await call(reason);
      await refresh();
      toast(done);
    };

  return (
    <div className="actions" style={{ justifyContent: "flex-end", flexWrap: "nowrap" }}>
      <button
        type="button"
        className="btn sm ghost"
        onClick={() => sync.mutate()}
        disabled={sync.isPending}
        title={`Re-read this subscription from ${storeName}`}
      >
        <Icon name="refresh" />
        {sync.isPending ? "Syncing…" : "Sync"}
      </button>
      {row.autoRenewing && live(row.status) ? (
        <button
          type="button"
          className="btn sm outline"
          onClick={() =>
            ask({
              title: "Stop renewal?",
              body: `${row.buyerEmail ?? "The buyer"} keeps Plus until ${date(row.expiresAtUtc)}, then it ends. Nothing is refunded.${card ? " Bachs cancels the card subscription at the period end." : ""}`,
              confirmLabel: "Stop renewal",
              reason: true,
              run: run((reason) => actions.cancelSubscription(row.id, reason), "Renewal stopped."),
            })
          }
        >
          Stop renewal
        </button>
      ) : null}
      {card ? (
        <span className="small faint" title="Refunds and ending a card subscription now are done in the Bachs dashboard">
          Refund in Bachs
        </span>
      ) : null}
      {!card && row.status !== "pending" && row.status !== "pendingPurchaseCanceled" ? (
        <button
          type="button"
          className="btn sm outline"
          onClick={() =>
            ask({
              title: "Refund the latest payment?",
              body: "Google refunds the most recent order. The subscription carries on; cancel or revoke it separately if that's wanted.",
              confirmLabel: "Refund",
              reason: true,
              run: run((reason) => actions.refundSubscription(row.id, reason), "Refund requested from Google Play."),
            })
          }
        >
          Refund
        </button>
      ) : null}
      {!card && live(row.status) ? (
        <button
          type="button"
          className="btn sm danger"
          onClick={() =>
            ask({
              title: "Revoke Plus now?",
              body: "Refunds the subscription in full and ends Plus for this couple straight away. This can't be undone.",
              confirmLabel: "Refund and revoke",
              danger: true,
              reason: true,
              run: run((reason) => actions.revokeSubscription(row.id, reason), "Subscription revoked and refunded."),
            })
          }
        >
          Revoke
        </button>
      ) : null}
    </div>
  );
}

const column = createColumnHelper<SubscriptionRow>();

export function SubscriptionsTable({ rows, loading, showBuyer = true }: { rows: SubscriptionRow[] | undefined; loading?: boolean; showBuyer?: boolean }) {
  const [ask, dialog] = useConfirm();
  const columns = useMemo(
    () => [
      ...(showBuyer
        ? [
            column.accessor("buyerEmail", {
              header: "Bought by",
              cell: ({ row }) => (
                <Link to="/users/$userId" params={{ userId: row.original.userId }} style={{ fontWeight: 700 }}>
                  {row.original.buyerEmail ?? "Deleted account"}
                </Link>
              ),
            }),
          ]
        : []),
      column.accessor("productId", {
        header: "Plan",
        cell: ({ row }) => (
          <div>
            <b>{productLabel(row.original.productId)}</b> {isCard(row.original) ? <Badge>Card</Badge> : null}
            <div className="small faint mono" title="End of the purchase token">
              {row.original.tokenSuffix}
            </div>
          </div>
        ),
      }),
      column.accessor("status", { header: "State", cell: ({ getValue }) => <StatusBadge status={getValue()} /> }),
      column.accessor("autoRenewing", {
        header: "Renews",
        meta: { secondary: true },
        cell: ({ getValue }) => (getValue() ? "Yes" : <span className="faint">No</span>),
      }),
      column.accessor("expiresAtUtc", { header: "Paid until", cell: ({ getValue }) => date(getValue()) }),
      column.accessor("lastVerifiedAtUtc", {
        header: "Last checked",
        meta: { secondary: true },
        cell: ({ getValue }) => <span className="small muted">{dateTime(getValue())}</span>,
      }),
      column.display({
        id: "actions",
        header: () => <span className="visually-hidden">Actions</span>,
        cell: ({ row }) => <SubscriptionActions row={row.original} ask={ask} />,
      }),
    ],
    [ask, showBuyer],
  );
  return (
    <>
      <DataTable data={rows} columns={columns} loading={loading} getRowId={(row) => row.id} empty="No subscriptions." />
      {dialog}
    </>
  );
}

const grantColumn = createColumnHelper<Grant>();

export function grantState(grant: Grant, now = Date.now()): { label: string; tone: Tone } {
  if (grant.revokedAtUtc) return { label: "Revoked", tone: undefined };
  if (Date.parse(grant.expiresAtUtc) <= now) return { label: "Ended", tone: undefined };
  return { label: "Active", tone: "good" };
}

export function GrantsTable({ rows, loading, showCouple = true }: { rows: Grant[] | undefined; loading?: boolean; showCouple?: boolean }) {
  const [ask, dialog] = useConfirm();
  const toast = useToast();
  const refresh = usePaymentsRefresh();
  const columns = useMemo(
    () => [
      ...(showCouple
        ? [
            grantColumn.accessor("coupleId", {
              header: "Couple",
              cell: ({ getValue }) => (
                <Link to="/users" search={{ query: getValue(), page: 1 }} className="mono" title="Show this couple's two accounts">
                  {getValue().slice(0, 8)}…
                </Link>
              ),
            }),
          ]
        : []),
      grantColumn.accessor("reason", { header: "Why", cell: ({ getValue }) => <span style={{ overflowWrap: "anywhere" }}>{getValue()}</span> }),
      grantColumn.accessor("createdAtUtc", { header: "Given", meta: { secondary: true }, cell: ({ getValue }) => date(getValue()) }),
      grantColumn.accessor("expiresAtUtc", { header: "Until", cell: ({ getValue }) => date(getValue()) }),
      grantColumn.display({
        id: "state",
        header: "State",
        cell: ({ row }) => {
          const state = grantState(row.original);
          return <Badge tone={state.tone}>{state.label}</Badge>;
        },
      }),
      grantColumn.display({
        id: "actions",
        header: () => <span className="visually-hidden">Actions</span>,
        cell: ({ row }) =>
          grantState(row.original).label === "Active" ? (
            <div className="actions" style={{ justifyContent: "flex-end" }}>
              <button
                type="button"
                className="btn sm outline"
                onClick={() =>
                  ask({
                    title: "End this grant?",
                    body: "The couple loses complimentary Plus now, unless one of them pays for it.",
                    confirmLabel: "End grant",
                    danger: true,
                    run: async () => {
                      await actions.revokeGrant(row.original.id);
                      await refresh();
                      toast("Grant ended.");
                    },
                  })
                }
              >
                End
              </button>
            </div>
          ) : null,
      }),
    ],
    [ask, refresh, showCouple, toast],
  );
  return (
    <>
      <DataTable data={rows} columns={columns} loading={loading} getRowId={(row) => row.id} empty="No grants." />
      {dialog}
    </>
  );
}

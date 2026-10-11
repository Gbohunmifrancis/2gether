import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { ApiError } from "../api/client";
import { actions, queries } from "../api/queries";
import type { UserDetail } from "../api/types";
import { useSession } from "../components/Shell";
import { useConfirm, useToast, type ConfirmOptions } from "../components/feedback";
import { Icon } from "../components/Icon";
import { GrantsTable, SubscriptionsTable, usePaymentsRefresh } from "../components/Subscriptions";
import { Avatar, Badge, Card, ErrorState, Notice, Skeleton } from "../components/ui";
import { actionLabel, ago, bytes, count, coupleStatusLabel, date, dateTime, humanize } from "../lib/format";
import { SignInMethods } from "./Users";

const route = getRouteApi("/_app/users/$userId");

export function UserPage() {
  const { userId } = route.useParams();
  const { data, error, isPending, refetch } = useQuery(queries.user(userId));

  return (
    <>
      <Link to="/users" search={{ page: 1 }} className="crumb">
        <Icon name="back" />
        Users
      </Link>
      {error ? (
        error instanceof ApiError && error.status === 404 ? (
          <Notice tone="warn">This account doesn't exist any more. It may have been deleted.</Notice>
        ) : (
          <ErrorState error={error} retry={() => void refetch()} />
        )
      ) : isPending ? (
        <div className="card">
          <Skeleton width={240} height={30} />
          <div style={{ height: 12 }} />
          <Skeleton width={320} />
        </div>
      ) : (
        <Profile user={data} />
      )}
    </>
  );
}

function Profile({ user }: { user: UserDetail }) {
  const session = useSession();
  const self = session?.admin.id === user.id;
  const [ask, dialog] = useConfirm();
  const toast = useToast();
  const client = useQueryClient();
  const refreshPayments = usePaymentsRefresh();
  const navigate = useNavigate();

  const refresh = () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ["user", user.id] }),
      client.invalidateQueries({ queryKey: ["users"] }),
      client.invalidateQueries({ queryKey: ["audit"] }),
    ]);
  const act = (options: Omit<ConfirmOptions, "run"> & { run: (input: { reason: string; days: number; typed: string }) => Promise<unknown>; done: string }) =>
    ask({
      ...options,
      run: async (input) => {
        await options.run(input);
        await refresh();
        toast(options.done);
      },
    });

  const disabled = Boolean(user.disabledAtUtc);
  const linked = user.couple?.status === "active";
  const renewing = user.subscriptions.some(
    (item) => item.autoRenewing && (item.status === "active" || item.status === "inGracePeriod" || item.status === "onHold" || item.status === "paused"),
  );

  return (
    <>
      <header className="head">
        <div className="who" style={{ gap: 16 }}>
          <Avatar name={user.displayName} large />
          <div>
            <h1 className="headline">{user.displayName}</h1>
            <div className="muted" style={{ marginTop: 4, overflowWrap: "anywhere" }}>
              {user.email}
            </div>
            <div className="actions" style={{ marginTop: 10 }}>
              {disabled ? <Badge tone="bad">Disabled</Badge> : <Badge tone="good">Active</Badge>}
              {user.plan === "plus" ? <Badge tone="plus">{user.complimentary ? "Plus · complimentary" : "Plus"}</Badge> : <Badge>Free</Badge>}
              {user.couple ? <Badge tone={linked ? "soft" : undefined}>{coupleStatusLabel[user.couple.status]}</Badge> : <Badge>No couple</Badge>}
              {self ? <Badge tone="warn">You</Badge> : null}
            </div>
          </div>
        </div>
        <div className="actions">
          {disabled ? (
            <button
              type="button"
              className="btn filled"
              onClick={() =>
                act({
                  title: `Re-enable ${user.displayName}?`,
                  body: "They can sign in again straight away.",
                  confirmLabel: "Re-enable",
                  run: () => actions.enableUser(user.id),
                  done: "Account re-enabled.",
                })
              }
            >
              Re-enable
            </button>
          ) : (
            <button
              type="button"
              className="btn outline"
              disabled={self}
              onClick={() =>
                act({
                  title: `Disable ${user.displayName}?`,
                  body: "They're signed out everywhere and can't sign in until you re-enable them. Their partner and data are untouched.",
                  confirmLabel: "Disable account",
                  danger: true,
                  reason: true,
                  run: ({ reason }) => actions.disableUser(user.id, reason),
                  done: "Account disabled.",
                })
              }
            >
              Disable
            </button>
          )}
          <button
            type="button"
            className="btn outline"
            onClick={() =>
              act({
                title: "Sign out everywhere?",
                body: "Every phone signed in to this account has to sign in again. Use this if a phone is lost or the account looks taken over.",
                confirmLabel: "Sign out everywhere",
                run: () => actions.signOutUser(user.id),
                done: "Signed out on every device.",
              })
            }
          >
            Sign out everywhere
          </button>
          <button
            type="button"
            className="btn outline"
            onClick={() =>
              act({
                title: "Email a reset code?",
                body: `Sends ${user.email} the same six-digit code “Forgot password?” would. It works for 15 minutes.`,
                confirmLabel: "Send code",
                run: () => actions.sendPasswordReset(user.id),
                done: "Reset code sent.",
              })
            }
          >
            Send password reset
          </button>
        </div>
      </header>

      {disabled ? (
        <div style={{ marginBottom: 16 }}>
          <Notice tone="bad">
            Disabled {dateTime(user.disabledAtUtc)}
            {user.disabledReason ? `: ${user.disabledReason}` : ""}
          </Notice>
        </div>
      ) : null}

      <div className="grid cols-3">
        <Card title="Account">
          <dl className="kv">
            <dt>Joined</dt>
            <dd>{date(user.createdAtUtc)}</dd>
            <dt>Last active</dt>
            <dd>{ago(user.lastActiveAtUtc)}</dd>
            <dt>Signs in with</dt>
            <dd>
              <SignInMethods google={user.google} password={user.password} />
            </dd>
            <dt>Email</dt>
            <dd>{user.emailVerified ? "Verified" : "Not verified"}</dd>
            <dt>Time zone</dt>
            <dd>{user.timeZoneId}</dd>
            <dt>User id</dt>
            <dd className="mono">{user.id}</dd>
          </dl>
        </Card>

        <Card title="Couple">
          {user.couple ? (
            <dl className="kv">
              <dt>State</dt>
              <dd>{coupleStatusLabel[user.couple.status]}</dd>
              <dt>Partner</dt>
              <dd>
                {user.couple.partner ? (
                  <Link to="/users/$userId" params={{ userId: user.couple.partner.id }} style={{ fontWeight: 700 }}>
                    {user.couple.partner.displayName}
                  </Link>
                ) : (
                  <span className="faint">Not joined</span>
                )}
                {user.couple.partner ? <div className="small faint">{user.couple.partner.email}</div> : null}
              </dd>
              <dt>Linked</dt>
              <dd>{date(user.couple.linkedAtUtc)}</dd>
              {user.couple.dissolvedAtUtc ? (
                <>
                  <dt>Unlinked</dt>
                  <dd>{date(user.couple.dissolvedAtUtc)}</dd>
                </>
              ) : null}
              <dt>Couple id</dt>
              <dd className="mono">{user.couple.id}</dd>
            </dl>
          ) : (
            <p className="faint">Not in a couple yet.</p>
          )}
        </Card>

        <Card title="Activity" aside="All time, counts only">
          <div className="list">
            <Row label="Days active" value={count(user.activity.activeDays)} />
            <Row label="Dares done / missed" value={`${count(user.activity.daresCompleted)} / ${count(user.activity.daresMissed)}`} />
            <Row label="Meals logged" value={`${count(user.activity.foodEntries)} (${count(user.activity.foodScans)} scans)`} />
            <Row label="Photos shared" value={`${count(user.activity.photosShared)} · ${bytes(user.activity.photoBytes)}`} />
            <Row label="Questions answered" value={count(user.activity.questionsAnswered)} />
          </div>
        </Card>
      </div>

      <Card
        flush
        title="Subscriptions"
        aside={
          user.couple ? (
            <button
              type="button"
              className="btn sm filled"
              onClick={() =>
                ask({
                  title: "Give this couple Plus?",
                  body: "Both partners get Plus for free until it ends. Nothing is charged, and it shows as complimentary.",
                  confirmLabel: "Give Plus",
                  days: true,
                  reason: true,
                  run: async ({ days, reason }) => {
                    await actions.grantPlus(user.couple!.id, days, reason);
                    await refreshPayments();
                    toast(`Plus given for ${days} days.`);
                  },
                })
              }
            >
              Give Plus
            </button>
          ) : null
        }
      >
        <SubscriptionsTable rows={user.subscriptions} showBuyer={false} />
      </Card>

      {user.grants.length > 0 ? (
        <Card flush title="Complimentary Plus">
          <GrantsTable rows={user.grants} showCouple={false} />
        </Card>
      ) : null}

      <div className="grid cols-2">
        <Card title="Devices">
          {user.devices.length === 0 ? (
            <p className="faint">No phones registered for notifications.</p>
          ) : (
            <div className="list">
              {user.devices.map((device, index) => (
                <div className="list-row" key={index}>
                  <span>
                    <b>{humanize(device.platform)}</b> {device.appVersion ? <span className="faint">v{device.appVersion}</span> : null}
                  </span>
                  <span className="small muted">{device.active ? `seen ${ago(device.lastSeenAtUtc)}` : "signed out"}</span>
                </div>
              ))}
            </div>
          )}
        </Card>
        <Card title="Admin history" aside="Latest 20">
          {user.history.length === 0 ? (
            <p className="faint">No admin has touched this account.</p>
          ) : (
            <div className="list">
              {user.history.map((event) => (
                <div className="list-row" key={event.id} style={{ alignItems: "flex-start" }}>
                  <div>
                    <b>{actionLabel(event.action)}</b>
                    {event.details ? <div className="small muted">{event.details}</div> : null}
                    <div className="small faint">{event.adminEmail}</div>
                  </div>
                  <span className="small faint" style={{ whiteSpace: "nowrap" }}>
                    {dateTime(event.createdAtUtc)}
                  </span>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      <Card title="Delete account" className="danger-zone">
        <div className="head" style={{ marginBottom: 0, alignItems: "center" }}>
          <p className="muted" style={{ maxWidth: 640, marginTop: 0 }}>
            Erases this person and everything of theirs, exactly as if they had deleted it in the app: photos, answers, meals, cycle and pregnancy data. Their
            partner is unlinked and told. Only do this when they've asked.
          </p>
          <button
            type="button"
            className="btn danger"
            disabled={self}
            onClick={() =>
              ask({
                title: `Delete ${user.displayName}'s account?`,
                body: renewing
                  ? "This can't be undone. They still have a renewing Google Play subscription: deleting the account won't stop Google charging them, so cancel or revoke it above first."
                  : "This can't be undone.",
                confirmLabel: "Delete forever",
                danger: true,
                reason: "Reason (for example, the support ticket)",
                typeToConfirm: user.email,
                run: async ({ reason, typed }) => {
                  await actions.deleteUser(user.id, typed, reason);
                  client.removeQueries({ queryKey: ["user", user.id] });
                  await client.invalidateQueries({ queryKey: ["users"] });
                  toast("Account deleted.");
                  await navigate({ to: "/users", search: { page: 1 } });
                },
              })
            }
          >
            Delete account
          </button>
        </div>
      </Card>
      {dialog}
    </>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="list-row">
      <span className="muted">{label}</span>
      <b>{value}</b>
    </div>
  );
}

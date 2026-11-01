import { useQuery, useQueryClient } from "@tanstack/react-query";
import { actions, queries } from "../api/queries";
import { BillingCheckCard } from "../components/BillingCheck";
import { useConfirm, useToast } from "../components/feedback";
import { useSession } from "../components/Shell";
import { Badge, Card, ErrorState, LoadingCards, PageHead, Stat } from "../components/ui";
import { count, dateTime, money, productLabel } from "../lib/format";

export function SystemPage() {
  const system = useQuery(queries.system());
  const admins = useQuery(queries.admins());
  const session = useSession();
  const [ask, dialog] = useConfirm();
  const toast = useToast();
  const client = useQueryClient();

  return (
    <>
      <PageHead title="System" sub="What's switched on, whether Plus can be sold, whether background work is keeping up, and who can sign in here." />
      {system.error ? (
        <ErrorState error={system.error} retry={() => void system.refetch()} />
      ) : system.isPending ? (
        <LoadingCards />
      ) : (
        <>
          <div className="grid cols-4">
            <Stat
              label="Push waiting to send"
              value={count(system.data.pushPending)}
              hint={`${count(system.data.pushSentToday)} sent · ${count(system.data.pushFailedToday)} failed in 24 h`}
            />
            <Stat label="Games in progress" value={count(system.data.openGames)} hint="Stale ones end on their own" />
            <Stat label="Photos being deleted" value={count(system.data.photosBeingDeleted)} hint="Storage clean-up queue" />
            <Stat label="Admin session length" value={`${system.data.tokenHours} h`} hint="Admin__TokenHours" />
          </div>
          <div className="grid cols-2">
            <Card title="Services" aside={`As of ${dateTime(system.data.nowUtc)}`}>
              <div className="list">
                {system.data.features.map((feature) => (
                  <div className="list-row" key={feature.name}>
                    <div>
                      <b>{feature.name}</b>
                      <div className="small faint">{feature.detail}</div>
                    </div>
                    {feature.on ? <Badge tone="good">On</Badge> : <Badge>Off</Badge>}
                  </div>
                ))}
              </div>
            </Card>
            <Card title="List prices" aside={`Used for the revenue estimate · ${system.data.currency}`}>
              {Object.keys(system.data.monthlyPrices).length === 0 ? (
                <p className="faint">
                  None set. Add <code>Admin__MonthlyPrices__&lt;productId&gt;</code> to estimate revenue.
                </p>
              ) : (
                <div className="list">
                  {Object.entries(system.data.monthlyPrices).map(([product, price]) => (
                    <div className="list-row" key={product}>
                      <span>
                        <b>{productLabel(product)}</b> <span className="small faint mono">{product}</span>
                      </span>
                      <span>{money(price, system.data.currency)} / month</span>
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </div>
        </>
      )}

      <BillingCheckCard />

      <Card flush title="Admins" aside="Set by Admin__Emails on the server">
        {admins.error ? (
          <div style={{ padding: 18 }}>
            <ErrorState error={admins.error} />
          </div>
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Email</th>
                  <th scope="col">Account</th>
                  <th scope="col">Authenticator</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {(admins.data ?? []).map((admin) => (
                  <tr key={admin.email}>
                    <td>
                      <b>{admin.displayName ?? admin.email}</b>
                      {admin.displayName ? <div className="small faint">{admin.email}</div> : null}
                    </td>
                    <td>{admin.hasAccount ? <Badge tone="good">Has an account</Badge> : <Badge tone="warn">Must sign up in the app first</Badge>}</td>
                    <td>
                      {admin.authenticatorEnrolledAtUtc ? (
                        `Set up ${dateTime(admin.authenticatorEnrolledAtUtc)}`
                      ) : (
                        <span className="faint">Not set up yet</span>
                      )}
                    </td>
                    <td className="num">
                      {admin.userId && admin.authenticatorEnrolledAtUtc && admin.userId !== session?.admin.id ? (
                        <button
                          type="button"
                          className="btn sm outline"
                          onClick={() =>
                            ask({
                              title: "Reset their authenticator?",
                              body: "For a lost or replaced phone. They're signed out, and set up a new authenticator the next time they sign in with their password.",
                              confirmLabel: "Reset authenticator",
                              danger: true,
                              run: async () => {
                                await actions.resetAuthenticator(admin.userId!);
                                await client.invalidateQueries({ queryKey: ["admins"] });
                                toast("Authenticator reset.");
                              },
                            })
                          }
                        >
                          Reset authenticator
                        </button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {dialog}
    </>
  );
}

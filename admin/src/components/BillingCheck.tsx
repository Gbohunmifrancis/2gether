import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useId, useState } from "react";
import { queries } from "../api/queries";
import type { BachsProductCheck, BillingCheck, CheckError, HintLevel, PlayProductCheck } from "../api/types";
import { dateTime, plural } from "../lib/format";
import { Icon } from "./Icon";
import { Badge, Card, Notice, errorMessage } from "./ui";

/** Countries offered before the first run; the run adds every region Play sells in. */
const STARTING_COUNTRIES = ["NG", "GH", "KE", "ZA", "US", "GB"];

const regionNames = (() => {
  try {
    return new Intl.DisplayNames(["en"], { type: "region" });
  } catch {
    return null;
  }
})();

export function countryName(code: string): string {
  try {
    return regionNames?.of(code) ?? code;
  } catch {
    return code;
  }
}

/** Every region a base plan has a price in, plus the starting list and the chosen one, by name. */
export function countryOptions(check: BillingCheck | undefined, chosen: string): string[] {
  const codes = new Set([...STARTING_COUNTRIES, chosen]);
  for (const product of check?.googlePlay.products ?? [])
    for (const plan of product.basePlans) for (const region of [...plan.regions, ...plan.closedRegions]) codes.add(region);
  return [...codes].sort((a, b) => countryName(a).localeCompare(countryName(b)));
}

/** What the product row shows: does it exist, does a base plan sell, and in the chosen country, at what price. */
export function productStatus(product: PlayProductCheck, country: string) {
  const exists = product.found && !product.archived;
  const active = product.basePlans.filter((plan) => plan.state === "ACTIVE");
  const selling = active.find((plan) => plan.regions.includes(country));
  return {
    exists,
    active: active.length > 0,
    available: Boolean(selling),
    price: selling?.prices.find((item) => item.region === country)?.price ?? null,
  };
}

const LEVEL: Record<HintLevel, { icon: "close" | "alert" | "spark" | "check"; tone: "bad" | "warn" | "soft" | "good"; label: string }> = {
  problem: { icon: "close", tone: "bad", label: "Fix" },
  todo: { icon: "alert", tone: "warn", label: "To do" },
  info: { icon: "spark", tone: "soft", label: "Note" },
  ok: { icon: "check", tone: "good", label: "OK" },
};
const ORDER: HintLevel[] = ["problem", "todo", "info", "ok"];

function Tick({ on, label }: { on: boolean; label: string }) {
  return (
    <span className={`tick ${on ? "good" : "bad"}`} role="img" aria-label={`${label}: ${on ? "yes" : "no"}`}>
      <Icon name={on ? "check" : "close"} />
    </span>
  );
}

function ErrorLine({ error }: { error: CheckError }) {
  return (
    <Notice tone="bad">
      <b>{error.httpStatus ? `Google answered ${error.httpStatus}` : "Couldn't read it"}</b>{" "}
      <span className="mono">
        {error.code}
        {error.reason ? ` · ${error.reason}` : ""}
      </span>
      <div>{error.hint}</div>
    </Notice>
  );
}

/** "P1M" → "month", "P1Y" → "year", "P3M" → "3 months". */
function period(iso: string | null): string {
  const match = iso ? /^P(\d+)([DWMY])$/.exec(iso) : null;
  if (!match) return iso ?? "";
  const unit = { D: "day", W: "week", M: "month", Y: "year" }[match[2] as "D" | "W" | "M" | "Y"];
  return match[1] === "1" ? unit : `${match[1]} ${unit}s`;
}

/** The price in the chosen country first, then a few others. */
function shortPrices(prices: { region: string; price: string }[], country: string, max = 4): string {
  const sorted = [...prices].sort((a, b) => (a.region === country ? -1 : b.region === country ? 1 : a.region.localeCompare(b.region)));
  const shown = sorted.slice(0, max).map((item) => `${item.region} ${item.price}`);
  return sorted.length > max ? `${shown.join(" · ")} · +${sorted.length - max} more` : shown.join(" · ");
}

function PlayTable({ check, country }: { check: BillingCheck; country: string }) {
  const name = countryName(country);
  return (
    <div className="table-wrap">
      <table className="data billing-table">
        <thead>
          <tr>
            <th scope="col">Product</th>
            <th scope="col">Exists</th>
            <th scope="col">Active</th>
            <th scope="col">In {name}</th>
            <th scope="col">Base plans</th>
            <th scope="col">Offers</th>
          </tr>
        </thead>
        <tbody>
          {check.googlePlay.products.map((product) => {
            const status = productStatus(product, country);
            return (
              <tr key={product.productId}>
                <td>
                  <b className="mono">{product.productId}</b>
                  {product.archived ? (
                    <div>
                      <Badge tone="bad">Archived</Badge>
                    </div>
                  ) : null}
                </td>
                <td>
                  <Tick on={status.exists} label="Exists in Play Console" />
                </td>
                <td>
                  <Tick on={status.active} label="Has an active base plan" />
                </td>
                <td>
                  <Tick on={status.available} label={`On sale in ${name}`} />
                  {status.price ? <div className="small">{status.price}</div> : null}
                </td>
                <td>
                  {product.basePlans.length === 0 ? (
                    <span className="faint">{product.found ? "None" : "–"}</span>
                  ) : (
                    product.basePlans.map((plan) => (
                      <div key={plan.basePlanId} className="billing-plan">
                        <span className="mono">{plan.basePlanId}</span>{" "}
                        <Badge tone={plan.state === "ACTIVE" ? "good" : "warn"}>{plan.state.toLowerCase()}</Badge>{" "}
                        <span className="small faint">
                          {plan.type === "prepaid" ? "prepaid" : plan.type === "autoRenewing" ? "renews" : plan.type}
                          {plan.billingPeriod ? ` every ${period(plan.billingPeriod)}` : ""} · {plural(plan.regionCount, "country", "countries")}
                        </span>
                        {plan.prices.length ? <div className="small">{shortPrices(plan.prices, country)}</div> : null}
                      </div>
                    ))
                  )}
                </td>
                <td>
                  {product.offers.length === 0 ? (
                    <span className="faint">{product.offersError ? product.offersError.code : "None"}</span>
                  ) : (
                    product.offers.map((offer) => (
                      <div key={`${offer.basePlanId}/${offer.offerId}`}>
                        <span className="mono">{offer.offerId}</span>{" "}
                        <Badge tone={offer.state === "ACTIVE" ? "good" : "warn"}>{offer.state.toLowerCase()}</Badge>
                      </div>
                    ))
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function BachsRow({ product }: { product: BachsProductCheck }) {
  return (
    <tr>
      <td>
        <b>{product.plan === "monthly" ? "Monthly" : "Yearly"}</b>
        <div className="small faint mono">{product.productId ?? "not set"}</div>
      </td>
      <td>{product.checked ? <Tick on={product.found} label="Exists in Bachs" /> : <span className="faint">–</span>}</td>
      <td>{product.checked ? <Tick on={product.active} label="Active in Bachs" /> : <span className="faint">–</span>}</td>
      <td>
        {product.error ? (
          <span className="small">
            <span className="mono">{product.error.code}</span>
          </span>
        ) : (
          <>
            {product.price ?? <span className="faint">–</span>}
            {product.interval ? <span className="small faint"> every {product.interval.replace(/^1 /, "")}</span> : null}
            {product.trial ? <div className="small faint">{product.trial} free</div> : null}
          </>
        )}
      </td>
    </tr>
  );
}

function Report({ check, country }: { check: BillingCheck; country: string }) {
  const hints = [...check.hints].sort((a, b) => ORDER.indexOf(a.level) - ORDER.indexOf(b.level));
  const play = check.googlePlay;
  const bachs = check.bachs;
  return (
    <>
      <ul className="checklist" aria-label="What to do">
        {hints.map((hint, index) => (
          <li key={index} className={`hint ${hint.level}`}>
            <span className={`tick ${LEVEL[hint.level].tone}`} role="img" aria-label={LEVEL[hint.level].label}>
              <Icon name={LEVEL[hint.level].icon} />
            </span>
            <span>
              <span className="small faint">{hint.area === "bachs" ? "Card payments" : "Google Play"} · </span>
              {hint.text}
            </span>
          </li>
        ))}
      </ul>

      <h3 className="billing-sub">
        Google Play <span className="small faint mono">{play.packageName ?? "no package name"}</span>{" "}
        {play.configured ? <Badge tone="good">Service account set</Badge> : <Badge tone="bad">Not set up</Badge>}{" "}
        <Badge tone={play.provider?.toLowerCase() === "googleplay" ? "good" : "warn"}>Provider: {play.provider ?? "off"}</Badge>
      </h3>
      {play.error ? <ErrorLine error={play.error} /> : null}
      {play.products.length ? <PlayTable check={check} country={country} /> : <p className="faint">Billing__ProductIds is empty.</p>}
      {play.extraProducts.length ? (
        <p className="small">
          <Badge tone="warn">Also in Play Console</Badge> <span className="mono">{play.extraProducts.join(", ")}</span>{" "}
          <span className="faint">(not in Billing__ProductIds)</span>
        </p>
      ) : null}

      <h3 className="billing-sub">
        Card payments (Bachs) {bachs.configured ? <Badge tone={bachs.mode === "live" ? "good" : "warn"}>{bachs.mode ?? "sandbox"}</Badge> : <Badge>Off</Badge>}{" "}
        <Badge tone={bachs.bachsInApp ? "good" : undefined}>In the app: {bachs.bachsInApp ? "on" : "off"}</Badge>
      </h3>
      {bachs.missing.length ? (
        <p className="small">
          Not set: <span className="mono">{bachs.missing.join(", ")}</span>
        </p>
      ) : null}
      <div className="table-wrap">
        <table className="data billing-table">
          <thead>
            <tr>
              <th scope="col">Plan</th>
              <th scope="col">Exists</th>
              <th scope="col">Active</th>
              <th scope="col">Price</th>
            </tr>
          </thead>
          <tbody>
            {bachs.products.map((product) => (
              <BachsRow key={product.plan} product={product} />
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

/**
 * "Why does Google Play say Plus isn't on sale?": reads Play Console's subscriptions and the Bachs products through
 * the API and lists what to fix. Runs only on the button (it asks Google and Bachs and is audited); after a first
 * run, picking another country runs it for that country.
 */
export function BillingCheckCard() {
  const [country, setCountry] = useState("NG");
  const [ran, setRan] = useState(false);
  const selectId = useId();
  const check = useQuery({ ...queries.billingCheck(country), enabled: ran, placeholderData: keepPreviousData });
  const data = check.data;
  const busy = check.isFetching;

  return (
    <Card title="Billing check" aside={data ? `As of ${dateTime(data.checkedAtUtc)}` : "Asks Google Play and Bachs; audited"}>
      <div className="billing-controls">
        <label htmlFor={selectId} className="small">
          Country
        </label>
        <select id={selectId} className="select" value={country} onChange={(event) => setCountry(event.target.value)}>
          {countryOptions(data, country).map((code) => (
            <option key={code} value={code}>
              {countryName(code)} ({code})
            </option>
          ))}
        </select>
        <button
          type="button"
          className="btn filled sm"
          disabled={busy}
          onClick={() => {
            if (ran) void check.refetch();
            else setRan(true);
          }}
        >
          <Icon name="refresh" />
          {busy ? "Checking…" : ran ? "Run again" : "Run check"}
        </button>
      </div>
      {!ran ? (
        <p className="faint">
          When the app says Plus isn&apos;t on sale, Google Play returned no product. This reads what Play Console and Bachs actually have for{" "}
          <span className="mono">Billing__ProductIds</span> and lists what to fix.
        </p>
      ) : check.error ? (
        <Notice tone="bad">{errorMessage(check.error)}</Notice>
      ) : !data ? (
        <p className="faint" aria-busy="true">
          Asking Google Play and Bachs…
        </p>
      ) : (
        <div aria-busy={busy} className={check.isPlaceholderData ? "billing-stale" : undefined}>
          <Report check={data} country={data.country} />
        </div>
      )}
    </Card>
  );
}

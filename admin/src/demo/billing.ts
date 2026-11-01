import type { BillingCheck, PlayBasePlanCheck } from "../api/types";
import { iso } from "./world";

/* The billing check in demo mode: a Play Console that isn't quite ready (the monthly plan isn't sold in Nigeria, the
   yearly base plan is still a draft, a stray test product) and Bachs on a sandbox key. The hints are worked out here
   the way the API words them (BillingCheckHints in src/Twogether.Application/Features/Admin/Ops/BillingCheck.cs). */

const PRICES: Record<string, [string, number, number]> = {
  // region: [currency, monthly, yearly]
  US: ["USD", 4.99, 39.99],
  GB: ["GBP", 3.99, 29.99],
  IE: ["EUR", 4.49, 34.99],
  DE: ["EUR", 4.49, 34.99],
  FR: ["EUR", 4.49, 34.99],
  NL: ["EUR", 4.49, 34.99],
  ES: ["EUR", 4.49, 34.99],
  IT: ["EUR", 4.49, 34.99],
  CA: ["CAD", 6.49, 52.99],
  AU: ["AUD", 7.49, 59.99],
  ZA: ["ZAR", 69.99, 549.99],
  KE: ["KES", 499, 3999],
  GH: ["GHS", 49.99, 399.99],
  NG: ["NGN", 4500, 36000],
  IN: ["INR", 249, 1999],
  BR: ["BRL", 19.99, 159.99],
  MX: ["MXN", 79, 649],
};

const price = (currency: string, value: number) =>
  `${currency} ${value.toLocaleString("en-US", { minimumFractionDigits: Number.isInteger(value) ? 0 : 2, maximumFractionDigits: 2 })}`;

function plan(id: string, state: string, periodIso: string, yearly: boolean, leaveOut: string[] = []): PlayBasePlanCheck {
  const regions = Object.keys(PRICES)
    .filter((region) => !leaveOut.includes(region))
    .sort();
  return {
    basePlanId: id,
    state,
    type: "autoRenewing",
    billingPeriod: periodIso,
    regionCount: regions.length,
    regions,
    closedRegions: [],
    prices: regions.map((region) => {
      const [currency, monthly, year] = PRICES[region]!;
      return { region, price: price(currency, yearly ? year : monthly) };
    }),
  };
}

const regionName = (code: string) => {
  try {
    return new Intl.DisplayNames(["en"], { type: "region" }).of(code) ?? code;
  } catch {
    return code;
  }
};

export function billingCheck(countryParam: string | null, bachsInApp: boolean): BillingCheck {
  const country = /^[a-z]{2}$/i.test(countryParam ?? "") ? countryParam!.toUpperCase() : "NG";
  const name = regionName(country);
  const monthly = plan("monthly", "ACTIVE", "P1M", false, ["NG"]);
  const yearly = plan("yearly", "DRAFT", "P1Y", true);
  const hints: BillingCheck["hints"] = [];
  if (!monthly.regions.includes(country))
    hints.push({
      area: "googlePlay",
      level: "problem",
      text: `heartpact_plus_monthly isn't available in ${name} (${country}): edit base plan monthly's prices and countries and add ${name}.`,
    });
  hints.push(
    { area: "googlePlay", level: "problem", text: "Base plan yearly of heartpact_plus_yearly is DRAFT: open it and press Activate." },
    {
      area: "googlePlay",
      level: "info",
      text: "Offer trial-week on heartpact_plus_yearly / yearly is DRAFT: activate it if the Plus screen should show it (the plan sells without it).",
    },
    {
      area: "googlePlay",
      level: "info",
      text: "Play Console also has heartpact_test_sub, which isn't in Billing__ProductIds: the app never asks for it and the server won't accept it. If it's meant to be Plus, add it there or fix the id.",
    },
    {
      area: "bachs",
      level: "todo",
      text: "Bachs is on a sandbox key, so real cards can't pay: when going live, swap in the sk_live_ key and re-create the products and the webhook in production.",
    },
    bachsInApp
      ? { area: "bachs", level: "ok", text: "Bachs (sandbox) has both products and card payments in the app are on." }
      : { area: "bachs", level: "todo", text: "Turn on Card payments in the app (bachsInApp) under Features once this build is live." },
  );
  return {
    checkedAtUtc: iso(Date.now()),
    country,
    googlePlay: {
      configured: true,
      provider: "googleplay",
      packageName: "app.heartpact",
      productIds: ["heartpact_plus_monthly", "heartpact_plus_yearly"],
      error: null,
      products: [
        { productId: "heartpact_plus_monthly", found: true, archived: false, basePlans: [monthly], offers: [], offersError: null },
        {
          productId: "heartpact_plus_yearly",
          found: true,
          archived: false,
          basePlans: [yearly],
          offers: [{ basePlanId: "yearly", offerId: "trial-week", state: "DRAFT" }],
          offersError: null,
        },
      ],
      extraProducts: ["heartpact_test_sub"],
    },
    bachs: {
      configured: true,
      mode: "sandbox",
      missing: [],
      bachsInApp,
      products: [
        {
          plan: "monthly",
          productId: "prod_8f3a2c9d1e4b7a6c5d0f",
          checked: true,
          found: true,
          active: true,
          name: "Heartpact Plus (monthly)",
          price: "USD 7.99",
          interval: "1 month",
          trial: null,
          error: null,
        },
        {
          plan: "yearly",
          productId: "prod_2b7e91c04d5a6f3e8c1d",
          checked: true,
          found: true,
          active: true,
          name: "Heartpact Plus (yearly)",
          price: "USD 39.99",
          interval: "1 year",
          trial: "7 days",
          error: null,
        },
      ],
    },
    hints,
  };
}

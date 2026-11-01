# Billing: one Plus plan per couple (Google Play, and cards through Bachs)

One partner pays; both get Plus. Each store is the source of truth for its own subscriptions: Google Play for Play
purchases, Bachs (bachs.io) for card subscriptions bought on the website or, under Google Play's billing choice, in
the app (see "Bachs (web and card checkout)" below).

## Rules

- A subscription belongs to the person who paid and covers whichever couple they are in. After a breakup the plan
  leaves with its buyer; nothing else needs to happen.
- Plus is granted while a subscription is `active`, `canceled` (auto-renew off, still paid up) or `inGracePeriod`, and
  not past expiry. `onHold`, `paused`, `pending` and `expired` grant nothing.
- The app must set Play Billing's obfuscated account id to the user id without dashes at checkout. Verification refuses
  a purchase without it, made on another account, or already claimed by someone else (unique purchase token).
- Only products listed in `Billing:ProductIds` unlock Plus, so a cheap test product cannot.
- Every purchase is acknowledged with Google after verification; Google refunds unacknowledged purchases after three
  days. A failed acknowledgement is retried by the next verify call or notification.

## Endpoints (`api/billing`)

| Method | Route | Auth | Notes |
|---|---|---|---|
| GET | `entitlement` | user | `{ plan, limits, subscription, canPurchase, complimentary, complimentaryUntilUtc, freeLimits, plusLimits, productIds, webCheckout, cardInApp }`; `canPurchase` is false when the partner already pays. The app builds the comparison from `freeLimits`/`plusLimits` and asks Play only for `productIds`. `subscription.store` is `googlePlay` or `bachs`. `webCheckout`: card checkout is set up (the website can sell Plus). `cardInApp`: the app may offer card payment next to Google Play (see Billing choice) |
| POST | `google/verify` | user | `{ productId, purchaseToken }` after a purchase and on app start for unacknowledged purchases |
| POST | `google/rtdn?secret=` | Pub/Sub push | real-time developer notifications; the payload only names a token and the state is re-fetched from Google; 503 makes Pub/Sub retry |

Error codes: `billing.unknown_product`, `billing.account_missing`, `billing.not_your_purchase` (403),
`billing.unknown_purchase` (404), `billing.store_unavailable` (502 or 503).

## The Android app

The Plus screen (Us → Heartpact Plus, or "Get more with Plus" when the food scan limit or a nearly full album is
reached) follows mockup 18:

- It asks the server for the entitlement and Google Play for the prices of `productIds` (Play Billing 9, behind
  `billing/PlayStore.kt`). Each plan uses the cheapest offer Play says this person may take, such as a free trial,
  and shows it as "1 week free, then £23.99 a year". Yearly comes first, with its saving over monthly.
- Checkout sets the obfuscated account id to the user id without dashes, then sends the purchase to `google/verify`.
  Plus shows only once the server has checked it with Google. A pending payment (cash, bank transfer) shows as
  waiting until Google confirms it.
- On every app start, the app sends purchases Play hasn't acknowledged yet to `google/verify`, in case it closed
  mid-purchase. "Restore a purchase" sends every purchase made for this account. Purchases made for another
  Heartpact account on the same Google account are left alone.
- When the partner pays, nothing is offered. The buyer gets "Manage in Google Play", which opens Play's
  subscription page, the only place a subscription is cancelled or its payment fixed.
- Card in the app (Google Play's billing choice programme, "alternative billing with user choice"): when the
  entitlement says `cardInApp`, `GooglePlayStore` builds its BillingClient with `enableUserChoiceBilling` (rebuilt
  before the next Play call when the flag changes; without it the client is built as before). Buying still starts
  from the Google Play plans and `launchBillingFlow`; where Google offers it, Play's own choice screen comes first.
  Choosing Google Play carries on as above. Choosing card calls the listener instead: the product id becomes
  `monthly` or `yearly` (`PlusOffers.cardPlan`), the app posts `bachs/checkout {plan, from: "app",
  externalTransactionToken}` and opens the `checkoutUrl` in the browser. Back in the app it shows "Waiting for your
  payment to be confirmed…" and asks for the entitlement every 3 seconds for up to 90 until Bachs' webhook has
  switched Plus on. Refusals (`billing.bachs_unavailable`, `billing.unknown_plan`, `billing.already_plus`, offline)
  show as notes (`billing.card_in_app_unavailable` reads like `bachs_unavailable`). There is no card button of the
  app's own.
- A card subscription I bought (`subscription.store == "bachs"`) gets "Manage billing" instead of "Manage in Google
  Play": `bachs/portal` is called on tap and its page opens in the browser.
- Celebration (`ui/plus/PlusCelebration.kt`, `PlusCelebrationScreen.kt`): when the couple goes from Free to Plus,
  each partner gets a full-screen celebration once, the next time Home shows (or at once on the Plus screen after a
  purchase or a confirmed card payment). It is decided from the entitlement, not the `plusStarted` push (which only
  makes an open Home ask again): the app's prefs remember, per signed-in person, the last "Plus key" seen
  (`sub:{store}:{productId}:me|partner`, `gift:{complimentaryUntilUtc}`, or `free`), and a Plus whose key differs is
  celebrated. Never on Free; Free is remembered, so a later Plus is new. On a person's first look with this version an
  existing Plus is remembered silently. Three variants: bought by me ("Welcome to Heartpact Plus"), by my partner
  ("{partner} got you Heartpact Plus"), and a gift ("A gift from Heartpact", with its end date), each listing what
  `plusLimits` unlocks. Still with animations off; the level-up chime only with the ringer on.
- The Plus look: on Plus, a gold "PLUS" badge (`ui/components/PlusBadge.kt`, a slow shine, still with animations
  off) by the titles of Today and Us, a thin gold ring around the two avatars on Us, and gold on the Us Plus row.
  The gold is `PlusGold` / `colors.gold` in the theme. Free couples see none of it.
- Pushes `plusStarted`, `locationShared` and `cycleShared` open the Us tab (`PushInbox.tabFor`) and refresh an open
  Home at once (`PushInbox.refreshesHome`).

Testing: create `heartpact_plus_monthly` and `heartpact_plus_yearly` (each with one base plan, optionally a trial
offer) in Play Console, add license testers, and install from an internal testing track. Play only sells to an app it knows: a build with
another package name or an unknown signing key gets "Google Play isn't available".

## Why Plus isn't on sale

The app says "Plus isn't on sale in your country yet" when Google Play's `queryProductDetails` returns nothing for
`productIds`. The server isn't asked anything at that point, so a correct server config doesn't rule it out. Run
**System → Billing check** in the admin dashboard (`GET api/admin/billing/check?country=NG`): it lists the app's
subscriptions in Play Console with the billing service account (`monetization.subscriptions.list` with archived ones,
and `basePlans.offers.list`), compares them with `Billing:ProductIds`, and says what to fix. In order of likelihood:

1. **The product doesn't exist, or its id differs.** Monetize with Play → Products → Subscriptions → Create
   subscription with exactly `heartpact_plus_monthly` / `heartpact_plus_yearly`. Ids can't be renamed; the check
   points out a near miss (`heartpact_plus_montly`) among the subscriptions Play has.
2. **No active base plan.** A new base plan is a draft: open it and press **Activate**. An archived subscription isn't
   sold either.
3. **Not available in the country.** Edit the base plan's prices and countries and add the country (Nigeria, for a
   tester in Lagos); a country can also be listed but closed to new subscribers.
4. **The service account can't see the app.** Play Console → Users and permissions → invite the service account's
   email with View app information and View financial data, Manage orders and subscriptions (401/403). If Google
   says the API is off, enable the Google Play Android Developer API in the service account's Cloud project. A 404
   means no app with `Billing:GooglePlayPackageName`, or no subscriptions at all.
5. **Everything above is fine.** Then it's the phone: the Google account on it must be a tester who opened the internal
   testing opt-in link and installed the app from Play (not a sideloaded or debug build with another signing key), and
   it should be under License testing too. Play can take a few hours to serve a newly activated product.

The same check reports Bachs: live or sandbox key, which settings are missing, whether both products exist
(`GET v1/products/{id}`), are active and bill monthly and yearly, and whether `bachsInApp` is on. Turn on Card payments
in the app under Features only once the build with Google's choice screen is live.

## Bachs (web and card checkout)

Bachs (bachs.io) sells the same two plans by card: on the website, and in the Android app where Google Play's billing
choice allows it. A card subscription is a `store_subscriptions` row like a Play one (store `bachs`, the Bachs
subscription id `sub_...` in `PurchaseToken`, the Bachs customer `cust_...` in `ExternalCustomerId`), stored under our
product ids `heartpact_plus_monthly` and `heartpact_plus_yearly`, so the plan rules, limits, alerts, the admin
dashboard and the revenue estimate treat both stores alike. Bachs bills USD cards only.

### Flow

1. The signed-in person picks a plan; the app or site calls `POST api/billing/bachs/checkout`. The server picks the
   Bachs product for that plan from its own settings (a client never sends a price or a product), checks the couple
   isn't already on Plus, and creates a hosted checkout with `Idempotency-Key` (the same body within half an hour
   reuses the key, so a double tap opens one checkout). The customer is the person's saved Bachs customer, or their
   email and display name the first time; `metadata.user_id` is our user id (Bachs copies checkout metadata onto the
   subscription and its webhooks).
2. The person pays on Bachs's page and lands on `Bachs:SuccessUrl` (`?from=app` when started in the app). That page
   is only a display: **Plus comes only from the webhook**, so it should say "Activating your plan..." until the
   entitlement shows it.
3. Bachs sends `customer.subscription.created`; the server stores the subscription and both partners have Plus. The
   admin live feed and journey show "Plus started" from the new row, exactly as for Google Play, and both partners get
   the `plusStarted` notification (see Alerts).
4. Renewals, failed payments, card changes and cancellations arrive as the same webhooks. "Manage billing" calls
   `POST api/billing/bachs/portal` and opens the URL it returns (Bachs's customer portal: cancel, update the card,
   invoices). Mint a new one on every tap; the URL signs the customer in, so it is never logged or stored.

### Endpoints (`api/billing/bachs`)

| Method | Route | Auth | Body and answer |
|---|---|---|---|
| POST | `checkout` | user | `{ "plan": "monthly"\|"yearly", "from": "web"\|"app", "externalTransactionToken"?: "..." }` → `200 { "checkoutUrl": "https://..." }`. `from` defaults to `web`; the token only with `from: "app"` (see Billing choice) |
| POST | `portal` | user | no body → `200 { "url": "https://..." }` for the caller's own Bachs customer |
| POST | `webhook` | anonymous, signed | Bachs's events, raw JSON with `X-Bachs-Signature-V2` |

Errors (`{ code, message }`, messages translated):

| Status | Code | When |
|---|---|---|
| 503 | `billing.bachs_unavailable` | Bachs isn't configured (key, webhook secret and both product ids) |
| 400 | `billing.unknown_plan` | plan other than `monthly` or `yearly` |
| 400 | `billing.invalid_from` | `from` other than `web` or `app`, a token without `from: "app"`, `from: "app"` without the token from Google's choice screen (it could not be reported), or a token over 2048 characters |
| 409 | `billing.already_plus` | the caller already has Plus: their own or their partner's subscription (either store), or a complimentary grant |
| 403 | `billing.card_in_app_unavailable` | `from: "app"` while the entitlement's `cardInApp` is false |
| 404 | `billing.no_bachs_customer` | portal: the caller has never had a card subscription |
| 502 | `billing.bachs_failed`, `billing.bachs_refused`, `billing.bachs_not_found` | Bachs was unreachable or refused |
| 429 | `rate_limited` | more than 10 checkouts or 20 portal sessions an hour per person |

### The webhook

- The signature is checked first, on the raw bytes: `X-Bachs-Signature-V2: t={unix},v1={hex}[,v1={hex}...]`, where
  each hex is HMAC-SHA256 of `"{t}.{raw body}"` with `Bachs:WebhookSecret`. Any `v1` may match (there is one per
  valid secret while a secret is rotated), compared in constant time, and `t` must be within 300 seconds. A bad one
  is `400` (and counts against the sender's address: 30 in 15 minutes, then `429`); nothing else ever answers 4xx.
- Each event is applied in one transaction under a lock on its subscription. Its `id` is recorded in
  `bachs_webhook_events` (`Id`, `Type`, `ReceivedAtUtc`) in the same save as the state it changed, so a redelivery
  answers `200` and changes nothing. Each subscription keeps the `created_at` of the newest event applied
  (`LastEventAtUtc`); an older event delivered late is recorded and skipped.
- `200` once handled; `500` when handling failed (Bachs retries for about 80 minutes; replay with `bachs events replay`
  after a longer outage). A subscription for a product that isn't one of the two plans, or for nobody in Heartpact
  (a deleted account), is logged, recorded and ignored.
- Events (subscribe the endpoint to these five): `customer.subscription.created`, `customer.subscription.updated`,
  `customer.subscription.deleted` (each carries the whole subscription, saved as it is), `invoice.paid` and
  `invoice.payment_failed`. Add `refund.paid` when the app takes card payments (Billing choice). Fields named two ways
  in the docs are both read: `subscription_id` or `id`, `product_id` or `product.id`.

Status mapping, chosen so the Play rules in "Rules" apply unchanged:

| Bachs `status` | Stored as | Plus? | Until |
|---|---|---|---|
| `trialing`, `active` | `active` (`canceled` when `cancel_at_period_end`) | yes | `current_period_end` (or `trial_end` while trialing, if later) |
| `past_due` (Bachs retrying a renewal) | `inGracePeriod` | yes | the period end |
| `unpaid` (retries exhausted, kept) | `onHold` | no | |
| `canceled` | `expired` | no | `canceled_at` |

`autoRenewing` is `!cancel_at_period_end` and not canceled. A status Bachs adds later is stored as `pending` and grants
nothing.

### Admin and account deletion

- Payments page: Sync re-reads the subscription from Bachs; Stop renewal cancels it in Bachs at the period end
  (`cancel_at_period_end: true`, sent explicitly). Refund and Revoke never go to Google Play for a card subscription:
  the API answers `409 admin.bachs_dashboard`; refund (and cancel at once, if wanted) in the Bachs dashboard, and the
  webhooks bring the result.
- Deleting an account with a renewing card subscription: the `409 account.subscription_renews` warning says it is
  cancelled now without a refund; once confirmed, the server cancels it in Bachs immediately before erasing
  (`docs/account-deletion.md`).

### Bachs dashboard setup

1. Products (in the sandbox first, then again in production: they share nothing), both USD:
   - "Heartpact Plus (monthly)": $7.99, billing cycle 1 month.
   - "Heartpact Plus (yearly)": $39.99, billing cycle 1 year, with a 7-day free trial.
   Put their `prod_...` ids in `Bachs__MonthlyProductId` and `Bachs__YearlyProductId`.
2. Developer portal, Webhooks: endpoint `https://api.heartpact.me/api/billing/bachs/webhook` for
   `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.paid`
   and `invoice.payment_failed` (plus `refund.paid` for Billing choice). Its signing secret goes in
   `Bachs__WebhookSecret`.
3. Customer portal settings: allow cancelling (at the period end), updating the card, and switching between the two
   plans; set the return URL to `https://heartpact.me/plus/`.
4. Subscription settings: when payment recovery runs out, cancel (the default; `unpaid` would keep the row on hold).
5. Going live: verify the account, swap in the `sk_live_` key (the key decides sandbox or production), re-create the
   products and the webhook in production and update the ids and secret.

A real sandbox payment is the only end-to-end check: `bachs trigger` does not emit subscription events. The tests
cover signatures, ordering, duplicates, the mapping, checkout and portal requests against fakes.

### Billing choice (in-app card payments)

The owner is enrolled in Google Play's billing choice programme ("alternative billing with user choice"). In the
countries it covers (the entitlement's `cardInApp`: US, GB, the EU, IS, LI, NO, and the `bachsInApp` flag on), the
Plus screen may offer Bachs next to Google Play, through Google's own choice screen:

1. The app starts the purchase with Play Billing's user choice billing. Google shows its choice screen; if the person
   picks Google Play, nothing changes. If they pick card, Play Billing hands the app `UserChoiceDetails` with an
   `externalTransactionToken`.
2. The app calls `POST api/billing/bachs/checkout` with `{ "plan", "from": "app", "externalTransactionToken" }` and
   opens the checkout URL. Before calling Bachs, the server saves a row in `google_external_transactions` for the
   person and the token, with their account country as the tax region, and puts that row's id (never the token) in
   the checkout's `metadata.external_ref`.
3. When `customer.subscription.created` arrives with that `external_ref`, the row becomes the **initial** transaction:
   the subscription's amount, or `0` for the yearly plan's free trial, at the subscription's start.
4. Each later `invoice.paid` for that subscription becomes a **renewal** row (`hp-{invoice id}`) pointing at the
   initial transaction. The first paid invoice is not reported again when the initial transaction already carried its
   amount; after a free trial, the first paid invoice is the first renewal. `$0` invoices are not reported. A web
   purchase has no initial row and reports nothing.
5. `GoogleExternalTransactionsWorker` (every 5 minutes) reports due rows to
   `POST https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{package}/externalTransactions?externalTransactionId={id}`
   with the Play billing service account (`Billing__GooglePlayCredentialsJson`, scope `androidpublisher`):
   `originalPreTaxAmount` (micros, USD), `originalTaxAmount` 0, `transactionTime`, `recurringTransaction` (the token on
   the initial one, `initialExternalTransactionId` on renewals, `externalSubscription.subscriptionType: RECURRING`) and
   `userTaxAddress.regionCode`. A renewal waits until its initial transaction is reported.

Google requires each report **within 24 hours** of the transaction. The rows are an outbox written in the same save as
the webhook's state change, so nothing is lost to a crash. A failed report is retried with backoff (1, 2, 4 ...
minutes, at most hourly); `409`/`ALREADY_EXISTS` for the same id counts as reported. Rows still unreported 12 hours
after the payment are logged as errors and shown on the admin Health page (queues `googleReports` and
`googleReportsOverdue`). Without Google credentials (`Billing__Provider` other than `googleplay`) the rows wait and
are sent once the credentials are set.

Refunds: Bachs sends `refund.paid` with the refunded `charge_id`. When that charge paid a reported transaction (the
charge id is read from `invoice.paid`'s `charge`), the worker reports it with
`POST .../externalTransactions/{id}:refund` (`fullRefund`, or `partialRefund` with the amount). If an `invoice.paid`
carried no charge id, the refund can't be matched: report it by hand in Play Console or with the same API call
(the transaction id is in `google_external_transactions`).

Play Console setup (Monetize, Alternative billing):

- Opt the app in to alternative billing (user choice billing) for the eligible countries.
- Payment method logos for the choice screen: 192 × 20 dp images of the card brands Bachs accepts.
- Subscription management link: `https://heartpact.me/plus/` (where card subscribers find "Manage billing").
- Then switch on `bachsInApp` in the admin dashboard (`docs/feature-flags.md`), only once the app release with
  Google's choice screen is live.

## Plan limits

| Limit | Free | Plus | Used by |
|---|---|---|---|
| Active daily dares per partner | 1 | no limit | `POST /api/exercise/goals`, `PUT /api/exercise/goals/{id}` (resuming, reassigning), the alarm worker |
| Photo calorie scans per day | 3 | 20 | `POST /api/food/scans`, `GET /api/food/day` |
| Shared album storage | 500 MB | 2 GB | `POST /api/memories/uploads`, `GET /api/memories/usage` |
| Daily question history | 7 days | 90 days | `GET /api/questions/history` |

The free loop stays free, as the research recommended: daily questions, streaks, partner answers and a camera-verified
daily dare for each partner are never gated; Plus adds more dares. Limits are configurable under `Billing:Free:*` and
`Billing:Plus:*` (`ActiveDaresPerPerson`: a number, or `0`/`none` for no limit).

### Dares over the limit

Creating a dare, resuming a paused one, or handing an active one to the other partner past the limit is refused with
403 `exercise.dare_limit`; the app says so on the new-dare screen before the form is filled in, with a way to Plus.
When Plus ends with more dares running, nothing is deleted: as each extra dare comes due the alarm worker pauses it
instead of ringing (the oldest keep ringing) and tells both partners (`exercisePausedByPlan`). Resuming it takes Plus or
pausing another.

## Alerts

`PlanAlertsWorker` runs hourly and tells both partners once, reading what was already told back from the notifications:

| Alert | When | Type, category |
|---|---|---|
| Album filling up / almost full | the shared album passes 80%, then 95%, of the plan's room (not again for 30 days at the same level) | `albumAlmostFull`, Album |
| Plus ends soon | 3 days before a cancelled subscription or a complimentary grant ends (not again for 7 days) | `plusEnding`, Couple (always delivered) |

When Plus starts, both partners hear it once (`plusStarted`, Couple, `PlusStartedNotices`): the buyer "Welcome to
Heartpact Plus" (`source: "bought"`), the partner "{name} got you Heartpact Plus" (`"partner"`), and for an admin's
complimentary grant both "A gift from Heartpact" with its end date in their own language and time zone (`"gift"`). A
store subscription tells it on its first grant only: a new row that grants, or one that was `pending` (a Play payment
waiting, a Bachs subscription created `incomplete`) moving to a granting state, so renewals, recoveries from hold and
renewals arriving after the old period ended say nothing. It is skipped when the couple already had Plus (the partner's
subscription, the buyer's own other one, a live grant), and a buyer with no partner yet hears it alone. The notices
are saved with the subscription or grant, in the same transaction. List in `docs/push-notifications.md`.

In the app, the album offers Plus from 80% full, the food tab shows the photo estimates left today, and the new-dare
screen shows when the partner's free dare is taken.

## Configuration

`Billing:Provider` = `googlePlay`, `fake` or empty (everyone is Free). `Billing:GooglePlayPackageName`,
`Billing:GooglePlayCredentialsJson` (service account JSON with Play Console financial access, as a secret environment
variable), `Billing:ProductIds` (comma separated), `Billing:RtdnSecret` (also put in the Pub/Sub push URL).

`fake` is for development: it understands tokens shaped `fake.{active|canceled|grace|onhold|expired}.{days}.{accountId}.{productId}`.
The server refuses to start with it in Production.

`Billing:ProductIds` only gates Google Play purchases; card subscriptions are stored under the same two ids whatever
that list says, and the entitlement counts every store.

Bachs: `Bachs:ApiKey` (`sk_sandbox_...` or `sk_live_...`; the key picks `https://sandbox-api.bachs.io` or
`https://api.bachs.io`), `Bachs:WebhookSecret`, `Bachs:MonthlyProductId`, `Bachs:YearlyProductId`; card checkout is on
only when all four are set. Optional: `Bachs:ApiUrl` (another API address), `Bachs:SuccessUrl` (default
`https://heartpact.me/plus/thanks/`), `Bachs:CancelUrl` (default `https://heartpact.me/plus/`). The key, the secret,
portal URLs and Bachs's error bodies are never logged. Billing choice reports reuse `Billing:GooglePlayPackageName`
and `Billing:GooglePlayCredentialsJson` (the service account needs Play Console access to the app's financial data
and orders, as for refunds).

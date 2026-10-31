# Billing: one Plus plan per couple (Google Play)

One partner pays; both get Plus. Google Play is the source of truth for every subscription state.

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
| GET | `entitlement` | user | `{ plan, limits, subscription, canPurchase, complimentary, complimentaryUntilUtc, freeLimits, plusLimits, productIds }`; `canPurchase` is false when the partner already pays. The app builds the comparison from `freeLimits`/`plusLimits` and asks Play only for `productIds` |
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

Testing: create `heartpact_plus_monthly` and `heartpact_plus_yearly` (each with one base plan, optionally a trial
offer) in Play Console, add license testers, and install from an internal testing track. Play only sells to an app it knows: a build with
another package name or an unknown signing key gets "Google Play isn't available".

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

In the app, the album offers Plus from 80% full, the food tab shows the photo estimates left today, and the new-dare
screen shows when the partner's free dare is taken.

## Configuration

`Billing:Provider` = `googlePlay`, `fake` or empty (everyone is Free). `Billing:GooglePlayPackageName`,
`Billing:GooglePlayCredentialsJson` (service account JSON with Play Console financial access, as a secret environment
variable), `Billing:ProductIds` (comma separated), `Billing:RtdnSecret` (also put in the Pub/Sub push URL).

`fake` is for development: it understands tokens shaped `fake.{active|canceled|grace|onhold|expired}.{days}.{accountId}.{productId}`.
The server refuses to start with it in Production.

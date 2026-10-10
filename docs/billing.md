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
| GET | `entitlement` | user | `{ plan, limits, subscription, canPurchase }`; `canPurchase` is false when the partner already pays |
| POST | `google/verify` | user | `{ productId, purchaseToken }` after a purchase and on app start for unacknowledged purchases |
| POST | `google/rtdn?secret=` | Pub/Sub push | real-time developer notifications; the payload only names a token and the state is re-fetched from Google; 503 makes Pub/Sub retry |

Error codes: `billing.unknown_product`, `billing.account_missing`, `billing.not_your_purchase` (403),
`billing.unknown_purchase` (404), `billing.store_unavailable` (502 or 503).

## Plan limits

| Limit | Free | Plus | Used by |
|---|---|---|---|
| Photo calorie scans per day | 3 | 20 | `POST /api/food/scans`, `GET /api/food/day` |
| Shared album storage | 500 MB | 2 GB | `POST /api/memories/uploads`, `GET /api/memories/usage` |
| Daily question history | 7 days | 90 days | `GET /api/questions/history` |

The free loop stays free, as the research recommended: daily questions, streaks, camera-verified challenges and
partner answers are never gated. Limits are configurable under `Billing:Free:*` and `Billing:Plus:*`.

## Configuration

`Billing:Provider` = `googlePlay`, `fake` or empty (everyone is Free). `Billing:GooglePlayPackageName`,
`Billing:GooglePlayCredentialsJson` (service account JSON with Play Console financial access, as a secret environment
variable), `Billing:ProductIds` (comma separated), `Billing:RtdnSecret` (also put in the Pub/Sub push URL).

`fake` is for development: it understands tokens shaped `fake.{active|canceled|grace|onhold|expired}.{days}.{accountId}.{productId}`.
The server refuses to start with it in Production.

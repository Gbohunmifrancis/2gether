# Feature flags

Server-wide switches an admin can flip without a deploy. They live in the `app_settings` table (`Key`, `Value`, `UpdatedAtUtc`), created by `CREATE TABLE IF NOT EXISTS` in `DatabaseSchemaUpgrade.cs`. Nothing is seeded: a flag with no row is off.

| Flag | Row key | Meaning |
|---|---|---|
| `christmas` | `feature.christmas` (`"true"` / `"false"`) | Turns the festive look on in every app |
| `bachsInApp` | `feature.bachsInApp` (`"true"` / `"false"`) | Lets the Android app offer card payment (Bachs) next to Google Play, under Google Play's billing choice ("alternative billing with user choice"). Off by default |

## `bachsInApp`: card payments in the app

The app does not read this flag directly. It reads `cardInApp` in `GET /api/billing/entitlement`, which is true only
when Bachs is configured on the server, this flag is on, and the person's account country is one where billing choice
applies (US, GB, the 27 EU states, IS, LI, NO). `POST /api/billing/bachs/checkout` with `"from": "app"` is refused
(`403 billing.card_in_app_unavailable`) whenever `cardInApp` is false, so the server enforces it too.

**It must stay off** until all of this is true:

- Google Play has approved the app's billing choice enrolment, and alternative billing is turned on for the app in
  Play Console (see `docs/billing.md`, Billing choice), with the payment method logos and the management link set.
- The app release that shows Google's own choice screen (Play Billing Library's user choice billing, which hands the
  app an `externalTransactionToken` when the person picks card) is live, and it sends that token with the checkout.
- External transaction reporting works: the server reports every in-app card payment to Google within 24 hours
  (`GoogleExternalTransactionsWorker`, `docs/billing.md`). Check the Health page's `googleReports` queues after
  switching it on.

Turning it off stops new in-app card checkouts only; running card subscriptions carry on, and their renewals are still
reported to Google.

## Public: `GET /api/config`

No sign-in. Called on every app start, so it is one primary-key read, and cacheable: `Cache-Control: public, max-age=60` (also kept by the API's response cache). A flip therefore reaches apps within about a minute.

```json
{ "christmas": false }
```

Apps should treat a failed call as "off". `bachsInApp` is not here: whether card payment is offered depends on the
person, so it comes with their entitlement (`cardInApp`).

## Admin: `GET` and `PUT /api/admin/features`

Under the admin session (see `docs/admin.md`). Same body both ways; in a `PUT` every flag is optional and only the
flags named change:

```json
{ "christmas": true, "bachsInApp": false }
```

`PUT` needs a fresh authenticator code in `X-Admin-Code` like other changes that reach every user, answers `200` with the new state of every flag, and writes one audit log entry per flag named, as `features.updated` (target `feature` / `christmas` or `bachsInApp`, details such as `christmas: off -> on`). A body that names no flag is `422 admin.features_invalid`; a flag left out is never switched off by omission.

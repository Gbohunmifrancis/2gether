# Account export and deletion

Google Play requires any app that creates accounts to let people delete them, both in the app and from a web page that works without the app installed. GDPR also gives people the right to a copy of their data. Both endpoints need a signed-in user.

## Export

`GET /api/account/export` returns one JSON file (sent with `Content-Disposition: attachment`). It contains everything stored about the caller:

- profile
- cycle settings and day logs
- pregnancy, appointments and kick counts
- food entries, targets and scan records
- exercise goals and attempts
- last shared location
- messages they sent
- daily answers, with the question text
- notifications
- devices
- subscriptions

It also includes the memories they uploaded, each with a signed download link that expires after `Media:DownloadUrlLifetime` (default one hour). The export repeats that lifetime as `memoryLinksValidFor`.

What the export leaves out:

- **The partner's data.** Their messages, answers, food log and uploads belong to them.
- **Secrets.** No password hash, push tokens or purchase tokens.

Records are written as stored, so new fields appear without code changes. `formatVersion` marks the shape, but this is a snapshot and not a stable API.

## Deletion

`POST /api/account/delete`

```json
{ "password": "…", "confirmEmail": null, "acknowledgeActiveSubscription": false }
```

The request is checked in this order:

1. **Confirmation.**
   - Password accounts send their password. A wrong one returns `401 account.confirmation_failed`.
   - Accounts without a password (Google sign-in) send their email in `confirmEmail` instead.
2. **Subscription.** Deleting an account does not stop Google Play from charging.
   - If the person has a subscription that will renew (active, grace period, on hold or paused, with auto-renew on), the API returns `409 account.subscription_renews`.
   - The app should then send them to the Play Store subscription screen, or let them confirm with `acknowledgeActiveSubscription: true`.
   - Cancelled and expired subscriptions need no acknowledgement.
3. **Erasure.** Everything happens in one database transaction and returns `204`.
   - If another write clashes with it (the alarm worker opening a dare, or the partner deleting their own account
     at the same moment), it rolls back and starts once more from what is there now. A second clash returns
     `409 account.delete_conflict` with nothing deleted; trying again is safe.

### What happens

- **Active couple.** The couple is ended first, just like a breakup.
  - Exercise goals stop, and any alarm that is ringing is cancelled.
  - The partner is unlinked and receives a `CoupleEnded` event plus a notification that says "… deleted their account".
- **Hard delete everywhere** of the person's own records:
  - user row, cycle profile and logs, pregnancies (appointments and kick sessions cascade), food entries, targets and scans
  - shared location, devices, subscriptions, notifications, invites they created
  - messages they sent, their daily answers, and exercise goals they set or were given, together with every attempt
- **Games.** All games from any couple they were in are deleted, because game state embeds both players' answers.
- **Memories they uploaded.**
  - The rows are marked `Deleting`, with caption and date wiped inside the same transaction.
  - `MemoryCleanupWorker` deletes the stored objects within a few minutes, once no upload link handed out for them
    still works (a phone still syncing could otherwise put a photo back after the account is gone).
  - Storage calls cannot be part of a database transaction, which is why this runs as a separate step.
- **Shared couple rows** (the couple itself, the daily question list, the partner's memories, messages and answers):
  - If a partner still exists, these rows stay with that partner. The deleted person shows up only as an id that no longer matches anyone.
  - If no partner is left, for example the solo couple every sign-up starts with, the rows are deleted too.
- **Pregnancy appointments the person added to their partner's pregnancy** stay, because they are part of the partner's health record.
- **Afterwards:**
  - Other signed-in devices get an `AccountDeleted` hub event.
  - Every API call fails with `401` immediately, because the JWT bearer handler checks on each request that the user still exists. Without that check, a token could keep writing rows for hours after deletion.
  - The email can be registered again.

## Outside the API

- **Web deletion page.** Play Console asks for a URL where people can delete their account without the app:
  https://heartpact.app/delete-account (`website/delete-account/`, docs/website.md). It signs in with
  `/api/auth/login` and calls this endpoint, as the app does.
- **Data safety form (still to do).** Declare in Play Console's Data safety form that deletion is offered and is
  immediate.

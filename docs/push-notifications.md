# Push notifications: backend contract

Every in-app notification is also pushed to the recipient's phones. Nothing that creates notifications had to change.

## How it works

- **The notification row is the outbox.** It is saved with `PushState = pending` in the same transaction as the
  change it describes, so a push is never lost and never sent for a change that rolled back.
- **`PushDispatchWorker`** (every 2 s, draining bursts) claims up to 100 due rows with a 60 s lease using
  `FOR UPDATE SKIP LOCKED`, so several API instances claim disjoint rows and a crashed instance's rows return
  when the lease expires. It sends one message per active device of the recipient and records one decision per
  notification: `sent` (delivered to at least one device), `skipped` (no devices, stale, or push off), retry with
  backoff (15 s doubling to 10 min, 5 attempts) or `failed`.
- **At least once.** Every push carries `notificationId`; the app drops ids it has already shown.
- **The album sync reminder** (`albumSyncReminder`) is data-only at normal priority: it carries `preferWifi`, and the
  app holds it until the phone is on an unmetered network (or the day ends) before showing it.
- **Alarms** (`exerciseDue`) go out as high-priority, data-only messages on the `alarms` channel so the app can open
  its full-screen alarm, and only while the 30-minute window is open. Everything else is a normal notification on the
  `general` channel; chat-like types expire after an hour, the rest after a day. `exerciseStarted` ("Maya started
  their squats", data `{ occurrenceId, exercise }`, once per dare, not sent when the partner already has the live view
  open) expires after 10 minutes, and a tap opens the live view of that dare (`docs/couple-goals.md`, Watching live).
- **Dead tokens** (FCM `UNREGISTERED`, `SENDER_ID_MISMATCH`) disable the device. `INVALID_ARGUMENT` fails the message
  without punishing the device. Quota, outage and network errors retry.
- Rows that existed before this feature were marked `skipped` by the upgrade, and while push is off the worker settles
  new rows as `skipped`, so turning push on never sends a backlog.

## Devices (`api/devices`, authorized)

| Method | Route | Body | Notes |
|---|---|---|---|
| PUT | `` | `{ token, platform, appVersion?, tokenKind }` | call on every app start and on token rotation; a token moves to whoever registers it last; newest 10 installs per user stay active |
| POST | `unregister` | `{ token }` | call on sign-out; always 204, so tokens cannot be probed |
| GET | `` | | my installs, with only the last 6 characters of each token |

`tokenKind` is `registrationToken` (default) or `installationId`. FCM has deprecated targeting by registration
token in favour of Firebase Installation IDs; both are sent correctly.

## Configuration

`Push:Provider` = `fcm`, `log` (development: logs each push instead of sending) or empty (off).
For FCM: `Push:FcmProjectId` and `Push:FcmCredentialsJson` (the service account JSON, as a secret environment
variable `Push__FcmCredentialsJson`). `Push:MaxDevicesPerUser` defaults to 10.

## Preferences and quiet hours (`api/notification/preferences`, authorized)

Each person can change two things:

- **Which categories reach the phone:** messages, games, exercise updates, cycle, pregnancy, daily questions, album
  (`memoriesSynced`: the partner synced photos; `albumSyncReminder`: your own photos are waiting to sync).
- **A quiet-hours window** in their own time zone.

With no saved settings, everything is on and quiet hours are off. The default window is 22:00 to 07:00.

```json
PUT { "messages": true, "games": false, "exercise": true, "cycle": true, "pregnancy": true,
      "dailyQuestions": true, "album": true, "quietHoursEnabled": true, "quietStart": "22:00",
      "quietEnd": "07:00", "timeZoneId": "Africa/Lagos" }
```

The response is the same object plus `quietNow`, which says whether quiet hours apply at this moment.

How the fields are handled:

- **Times** are 24-hour `HH:mm`. The window may cross midnight. Start and end must differ.
- **`timeZoneId`** is an IANA id and is saved on the profile. Leaving it out keeps the current one.
- **`album`** may be left out by apps built before it existed; leaving it out keeps the current value (on by default).
- **Saving** is a full replace and the last save wins. Two devices saving at once both succeed and leave one row.

How each notification is routed:

- **Switched-off category:** the push is skipped and the row is marked `skipped`. The notification still appears in the in-app list.
- **During quiet hours:** the push is still sent, but on the `quiet` channel with `data.quiet = "true"`. It waits silently in the shade instead of expiring overnight.
  - The app must create this channel with `IMPORTANCE_LOW`: no sound, vibration or heads-up.
- **Never affected:**
  - The exercise alarm (`exerciseDue`). Both partners agreed to it, and the app always offers a local dismiss.
  - Couple changes (`coupleConnected`, `breakup`). These cannot be switched off, but they do arrive quietly at night.

Every notification type must be assigned a category in `NotificationRouting.CategoryOf`. A unit test fails when a new type is missing.

## Partner notices

Types the app receives as camelCase strings in `data.type` (with `data.notificationId`); `dataJson` is the
notification's `data` in the in-app list.

| Type | To | When | Category | `dataJson` |
|---|---|---|---|---|
| `plusStarted` | both partners (only the buyer while unlinked) | Plus newly starts for the couple: a store subscription's first grant (Google Play `google/verify` or RTDN, a Bachs webhook or admin sync), or an admin's complimentary grant. Never for a renewal, a recovery from hold or a late renewal; never when the couple already had Plus (the partner pays, a gift, the plan being replaced) | Couple (always delivered) | `{ "source": "bought" \| "partner" \| "gift", "untilUtc": iso or null }`: `source` from the reader's side; `untilUtc` is the gift's end, or a subscription's end when it won't renew, else null |
| `locationShared` | the other partner | a partner starts sharing (`POST api/location/start` on a share that was off or had lapsed; not a resume, a pause ending or a heartbeat). Not again for the same sharer within 12 hours | Couple | `{ "userId": sharer }` |
| `cycleShared` | the owner's partner | `PUT api/cycle/profile` moves the share level from `none` to `predictionsOnly` or `fullDetail` (not other saves, nor a change between the two visible levels) | Cycle | `{ "userId": owner }` |

The location text invites rather than reports ("Open the map to see each other. Share yours too, and you can both
pause any time."): sharing is mutual, and nothing may read as tracking someone (PRODUCT.md, Play policy).

## Not done yet

iOS. APNs through FCM works the same way once an iOS app exists.

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
- **Alarms** (`exerciseDue`) go out as high-priority, data-only messages on the `alarms` channel so the app can open
  its full-screen alarm, and only while the 30-minute window is open. Everything else is a normal notification on the
  `general` channel; chat-like types expire after an hour, the rest after a day.
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

## Not done yet

Per-type preferences and quiet hours, and iOS (APNs through FCM works the same way once an iOS app exists).

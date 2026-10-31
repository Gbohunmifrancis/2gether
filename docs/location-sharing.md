# Location sharing: backend contract

Sharing is mutual and always visible to the other partner, which is what Google Play's location and
stalkerware policies require of a two-person app.

- **Mutual or nothing.** A partner's position reaches you only while you are sharing too and neither of you is
  paused. This holds for `GET /api/location` and for every `LocationUpdated` event.
- **Only the latest point is stored.** There is no location history.
- **The state is never hidden.** Each partner can always read the other's status (`off`, `sharing`, `paused`
  with its end time), without coordinates.
- **Pause** lasts 15 minutes to 24 hours and ends on its own. Fixes sent during a pause are dropped, not stored.
- **Starting is explicit.** `POST start` starts a share; a fix on a stopped share is dropped, so a stale tab or a
  late fix can never turn sharing back on.
- **A share lapses when its phone goes quiet.** A share with no fix or heartbeat for 10 minutes counts as `off`,
  for both partners and for the mutual rule. The sharing phone sends `POST heartbeat` about every 4 minutes,
  moving or not.
- **Stopping wipes the point.** Stop keeps no coordinates; a breakup deletes both partners' rows; deleting an
  account deletes every row of its couples; an hourly sweep deletes rows untouched for 7 days.
- **Signing out stops sharing on all of the person's phones.** A share belongs to the person, not the phone. The
  app's sign-out stops its own service at once (offline too) and sends `POST stop` with the session that is ending
  (retried until it lands; the lease ends the share meanwhile). Signing out everywhere (a password reset, the admin
  console's sign-out, disabling an account, an admin's authenticator reset) stops it on the server, exactly like
  Stop. The person's other phones hear it: `LocationSharingChanged` with their own `off` (live), the next fix
  answered with the same event, or the next heartbeat's `off`; each stops its service and its notification.
- **The time of a point is when the phone took the fix** (capped at the server's clock), not when the row last
  changed. A fix older than the stored one is dropped. The partner's `lastUpdatedAtUtc` in the status is shown
  only while sharing is mutual.

## Endpoints (`api/location`, authorized)

| Method | Route | Body | Returns | Notes |
|---|---|---|---|---|
| GET | `` | | `LocationDto[]` | my point, plus my partner's only when mutual; empty when unlinked |
| GET | `status` | | `LocationSharingStatusDto` | `me`, `partner`, `mutual` |
| POST | `start` | | `LocationSharingStatusDto` | starts, or confirms a running share; ends a pause. 400 `location.no_couple` |
| POST | `heartbeat` | | `LocationSharingStatusDto` | renews the lease; ends a pause whose time is up; `me.status` `off` tells the phone to stop |
| POST | `stop` | | 204 | idempotent; wipes my point; the app retries it until it lands |
| POST | `pause` | `{ minutes }` | `LocationSharingStatusDto` | 422 `location.invalid_pause`, 409 `location.not_sharing` |
| POST | `resume` | | `LocationSharingStatusDto` | re-sends both positions when mutual again |

## Couple hub

- `UpdateLocationAt(lat, lng, accuracy, fixedAtUnixMs)` (the app) and `UpdateLocation(lat, lng, accuracy)` (the
  web app, timed by the server) store the fix, or return null when it is dropped: stopped, paused, older than
  the stored fix, or less than 5 seconds after the last accepted one. The partner receives it only when mutual.
  When the caller becomes visible again (the first fix, the end of a pause), they also receive the partner's
  current position.
  A fix on a stopped share is answered, to the caller only, with `LocationSharingChanged` and the caller's own
  `off`: a phone still sending after sharing stopped elsewhere stops.
- `StopLocationSharing()` stops sharing, like `POST stop`. Both maps drop each other.
- Events: `LocationUpdated` (`LocationDto`), `LocationStopped` (user id to remove from the map), and
  `LocationSharingChanged` (`LocationSharingStateDto`) for the indicator. The first two keep the names the
  existing frontend already handles.

## The Android map

No server change: the map only draws what `GET api/location` and the hub events already deliver, under the same rules.

- **MapLibre Native on OpenFreeMap** (`https://tiles.openfreemap.org/styles/liberty`, no API key). The style is restyled at runtime in `ui/map/MapStyler.kt`: pastel by day, navy with glowing roads at night (`Heartpact.colors.isNight`), no points of interest, 3D buildings from `render_height`, camera pitched 55 degrees with a slow sway when untouched. If the style cannot load (no network and nothing cached, or 15 s without it), the screen falls back to the drawn map of the two of you.
- **Both of you are shown only while sharing is mutual**, exactly as `GET api/location` returns them: a stopped or paused share removes the markers (`LocationStopped`, the cleared `points`). Each marker is the profile picture in a ring of that person's side colour, a name pill, and for your partner how old the fix is ("now", "2 min ago"). A halo pulses while the fix is under 5 minutes old. Markers glide between fixes over 800 ms.
- **Controls**: fly-in to both of you on open, "show both of us" and "center on me", tap a marker to fly to that person. Pause (1 hour, until tomorrow), resume and stop are in the sheet, unchanged.
- OpenFreeMap and OpenStreetMap contributors must stay credited: the map keeps MapLibre's attribution button on.

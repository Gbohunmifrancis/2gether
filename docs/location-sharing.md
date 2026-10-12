# Location sharing: backend contract

Sharing is mutual and always visible to the other partner, which is what Google Play's location and
stalkerware policies require of a two-person app.

- **Mutual or nothing.** A partner's position reaches you only while you are sharing too and neither of you is
  paused. This holds for `GET /api/location` and for every `LocationUpdated` event.
- **Only the latest point is stored.** There is no location history.
- **The state is never hidden.** Each partner can always read the other's status (`off`, `sharing`, `paused`
  with its end time), without coordinates.
- **Pause** lasts 15 minutes to 24 hours and ends on its own. Fixes sent during a pause are dropped, not stored.

## Endpoints (`api/location`, authorized)

| Method | Route | Body | Returns | Notes |
|---|---|---|---|---|
| GET | `` | | `LocationDto[]` | my point, plus my partner's only when mutual; empty when unlinked |
| GET | `status` | | `LocationSharingStatusDto` | `me`, `partner`, `mutual` |
| POST | `pause` | `{ minutes }` | `LocationSharingStatusDto` | 422 `location.invalid_pause`, 409 `location.not_sharing` |
| POST | `resume` | | `LocationSharingStatusDto` | re-sends both positions when mutual again |

## Couple hub

- `UpdateLocation(lat, lng, accuracy)` stores the fix (dropped while paused). The partner receives it only when
  mutual. On starting or returning from a pause, the caller also receives the partner's current position.
- `StopLocationSharing()` stops sharing. Both maps drop each other.
- Events: `LocationUpdated` (`LocationDto`), `LocationStopped` (user id to remove from the map), and
  `LocationSharingChanged` (`LocationSharingStateDto`) for the indicator. The first two keep the names the
  existing frontend already handles.

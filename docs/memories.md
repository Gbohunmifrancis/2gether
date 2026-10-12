# Memories: backend contract

The couple's shared album. Route prefix `api/memories`, authorized. Bytes live in S3-compatible object storage
(Cloudflare R2, AWS S3 or MinIO) and never pass through the API.

## Offline-first sync

Photos work like WhatsApp: a photo stays on the phone until the person taps **Sync**. Only then does it upload and
appear in the partner's album. Nothing uploads by itself. Synced photos live in the same cloud album as before
(signed PUT links to R2, plan quotas apply). Food photo scans are not part of this: they stay online.

The server never sees the phone's queue. It keeps one row per person (`album_sync_states`) with what the app last
reported, and uses it to answer `GET sync` and to send at most one reminder a day.

### Endpoints (all under `api/memories`, authorized)

| Method | Route | Body | Answer |
|---|---|---|---|
| PUT | `sync/pending` | `{ pendingCount, pendingBytes }` | `204`. Out of range: `422 memories.invalid_pending` |
| GET | `sync` | | `{ pendingCount, pendingBytes, pendingSinceUtc, lastSyncedAtUtc, usage: { count, usedBytes, limitBytes }, maxBatchItems }` |
| POST | `sync` | `{ items: [{ clientId, contentType, sizeBytes, caption?, takenAtUtc?, source, thumbnailContentType? }] }` | `{ items: [{ clientId, status, memoryId?, upload?, error? }], usage }` |
| POST | `sync/complete` | `{ memoryIds: [...], finished = true }` | `{ items: [{ memoryId, status, memory?, error? }] }` |

- **`PUT sync/pending`**: `pendingCount` is 0 to 100000. `pendingBytes` is 0 or more, and at most `pendingCount` times
  the video cap (so a count of 0 must come with 0 bytes). Two reports racing to create the row both succeed.
  `pendingSinceUtc` is set when the count goes from 0 to more than 0, kept while it stays above 0, and cleared at 0.
- **`GET sync`** returns zeros when the person has never reported. Without a couple, usage is zero (like `usage`).
- **`POST sync`** takes 1 to 50 items with distinct, non-empty `clientId`s. Otherwise the whole request is
  `422 memories.invalid_batch`. No couple: `400 memories.no_couple`. No storage: `503 memories.storage_unavailable`.
- **`POST sync/complete`** takes 1 to 50 distinct ids (otherwise `422 memories.invalid_batch`) and runs exactly the
  check of `POST {id}/complete` on each one. Each newly ready memory sends the usual `MemoriesChanged` `added` event.

Two syncs from the same account racing on the same photo: the loser gets `409 memories.sync_in_progress` and can
simply retry.

### Per-item statuses

`POST sync`, looked up by the caller's own `(clientId)`:

| Status | When | The app |
|---|---|---|
| `upload` | New and accepted, or reserved earlier and not completed yet (fresh links, longer upload window) | PUT the bytes to `upload.uploadUrl` (and `upload.thumbnail` if asked for), then complete |
| `done` | Already in the album | drops it from the queue |
| `removed` | Deleted from the album while it was waiting | drops it from the queue |
| `rejected` | Fails the single-upload rules (`error.code` as for `POST uploads`, e.g. `memories.too_large`), the album is full (`memories.quota_exceeded`), or the id was used in an earlier, ended album (`memories.client_id_taken`) | keeps it and shows the reason |

`upload` is the same `MemoryUploadDto` as `POST uploads`, thumbnail slot included.

`POST sync/complete`: `ready` (with `memory`, the `MemoryDto`), `notUploaded` (`memories.not_uploaded`: PUT it
again), `rejected` (`memories.upload_rejected`: the file did not match and was removed; sending it to `POST sync`
again reserves a new slot), or `notFound` (not in the caller's couple, not the caller's own upload, or being deleted).

### Idempotency through clientId

`clientId` is the phone's own id for the photo. Memories carry it, unique per uploader
(`IX_memories_UploadedByUserId_ClientId`). A retried batch, after a timeout or a crash, finds what it already made:
nothing is added twice and no quota is spent twice. Uploads through the single `POST uploads` flow have no
`clientId`.

### Quota

Same rule and wording as `POST uploads`: reserved slots count at their declared size. The batch starts from the
album's current use and adds each new item's size in order. The first new item that does not fit is
`rejected` with `memories.quota_exceeded`, and so is every new item after it, even a smaller one that would fit, so
photos go up in the order the app sent them. Items already reserved (`upload` again) and `done` items are not
counted twice.

### The partner hears about it once

The first `POST sync` starts a sync session (a session older than a day is treated as abandoned and starts again, so a slow sync of videos over mobile data still counts every batch).
`POST sync/complete` with `finished: true` (the default) closes it. It counts the caller's ready memories in this album
made ready since the session started, and if there are any, creates **one** `memoriesSynced` notification for the
partner, such as "Maya added 12 photos to your album." with `data: { "count": 12 }`. The notification is saved in the
same transaction as the memories it announces, since notification rows are the push outbox. Send `finished: false`
on every batch but the last.

### Reminders

`AlbumSyncReminderWorker` runs a minute after startup and then every 15 minutes. It reminds a person when all of
these hold (the time rules are `AlbumSyncRules.IsDueForReminder`):

- photos are waiting (`pendingCount > 0`) and have been for at least 6 hours;
- the app reported within the last 7 days (an app that stopped reporting has probably been removed);
- no reminder in the last 24 hours;
- the person is in an active couple.

People who switched the `album` notification category off, or who are inside their quiet hours right now, are
skipped and stay due, so they are tried again on a later run. Each reminder is claimed with a conditional update in the
same transaction as the notification insert, so only one API instance sends it. The `albumSyncReminder` notification
reads like "12 photos are waiting on this phone. Sync them to share with Theo." with
`data: { "pendingCount": 12, "preferWifi": true }`, and is pushed **data-only** so the app decides when to show it.

### What the app does

1. Keep a local queue of photos, each with a stable `clientId` (a UUID made when the photo is added).
2. Report the queue with `PUT sync/pending` whenever it changes (debounced) and on every app start.
3. On Sync, send the queue to `POST sync` in batches of 50. PUT the bytes for every `upload` item (and its thumbnail),
   then `POST sync/complete` with that batch's memory ids: `finished: false`, except `finished: true` on the last
   batch. Drop `done` and `removed` items; keep `rejected` ones and show why.
4. Afterwards report the pending count again: 0, or whatever is left.
5. On an `albumSyncReminder` push with `preferWifi`, hold it until the phone is on an unmetered network, or until the
   end of the day, and only then show it. The reminder only asks; it never starts an upload.

The Android app also:

- Takes photos and videos (MP4, MOV) from the system picker. A video's size is checked before it is copied, its
  first frame becomes the thumbnail, and its recording time (UTC in the file) becomes `takenAtUtc`.
- Shows a shared video as its poster with a play button. It streams with the platform player only when tapped,
  so opening one never spends mobile data on its own.
- Lets the uploader add or change a caption (`PATCH {id}`, sending the date back unchanged) and delete a shared photo
  or video (`DELETE {id}`). The partner sees the photo but not these actions; the server refuses them anyway.

## Configuration

`Media:ServiceUrl`, `Media:Bucket`, `Media:AccessKey`, `Media:SecretKey`, `Media:Region` (default `auto`, right for
R2). The storage quota per couple is a plan limit (Free 500 MB, Plus 2 GB by default; see `docs/billing.md`). Set the secrets as environment variables (`Media__AccessKey`, ...),
never in appsettings. Without them, upload and album endpoints answer `503 memories.storage_unavailable` and the
cleanup worker stays off. Railway's disk is wiped on deploy, so local disk is not an option there.

## Upload flow

1. `POST uploads` with `{ contentType, sizeBytes, caption?, takenAtUtc?, source }` reserves a slot and returns
   `{ memoryId, uploadUrl, method: "PUT", headers, expiresAtUtc }`. The URL lives 15 minutes and is signed for that
   content type: send exactly the returned `Content-Type` header. Pending slots count against the quota.
2. The app `PUT`s the bytes straight to `uploadUrl`.
3. `POST {id}/complete` checks the stored object: it must exist, match the declared type and fit the size cap. A
   mismatch is deleted from storage and answers `422 memories.upload_rejected`; not uploaded yet answers
   `409 memories.not_uploaded`. Completing again is harmless.

Allowed types: JPEG, PNG, WebP, HEIC/HEIF photos up to 15 MB; MP4 and MOV videos up to 100 MB. Object keys are
`couples/{coupleId}/{memoryId}.{ext}`, built by the server only. Google Photos and gallery imports use the Android
photo picker on the device and then this same flow (`source` is a display label only).

## Endpoints

| Method | Route | Notes |
|---|---|---|
| GET | `?cursor=&limit=30` | ready memories, newest first by date taken (or added), keyset-paged; each item has a 1-hour signed `url` |
| GET | `on-this-day?date=` | same calendar day in earlier years; 29 Feb memories show on 28 Feb in other years |
| GET | `usage` | `{ count, usedBytes, limitBytes }` |
| POST | `uploads`, `{id}/complete` | see above |
| PUT, GET, POST | `sync/pending`, `sync`, `sync/complete` | offline-first sync, see above |
| PATCH | `{id}` | `{ caption, takenAtUtc }`, uploader only |
| DELETE | `{id}` | uploader only; hidden at once, file removed, retried by the worker if storage fails |

Either partner sees every memory of their couple; only the uploader edits or deletes. Another couple's ids answer 404.
`MemoriesChanged` (`{ memoryId, change }`) goes to the couple group on add, update and delete.

## After a breakup

When a couple ends, the shared album closes for both people: `GET /api/memories` returns an empty album. Each person can still reach the photos **they uploaded themselves** for `Media:BreakupRetentionDays` (default 30) after the breakup. The partner's uploads stay the partner's.

- `GET /api/memories/archive?cursor=&limit=` lists the caller's own photos from ended couples, newest first, with the same keyset cursor as the album.
  - Each item is `{ memory, coupleEndedAtUtc, availableUntilUtc }`.
  - `memory.url` is a signed download link.
- `DELETE /api/memories/archive/{id}` deletes one of those photos right away. The caption is wiped and the object is removed. Anyone else's photo returns `404`.

When the window closes, the cleanup worker deletes the whole album, both people's uploads.

Other notes:
- A couple's end time is stored as `couples.DissolvedAtUtc`. For couples that ended before this column existed, the upgrade copies it from `UpdatedAtUtc`.
- Linking again with the same person creates a new couple. The old album is not carried over.

## Cleanup worker

Runs every 5 minutes, and once at startup. In each run it:

1. Queues every photo from albums whose post-breakup window has closed. Captions and dates are wiped in that same step.
2. Removes upload slots that were not completed within an hour, once no upload link handed out for them still works
   (a sync retry can renew the links of an old slot).
3. Finishes deletes whose storage call failed.

It works in batches of 100 and keeps going while batches come back full. It always deletes the object before the row.

## Thumbnails

The server does not process images. The app makes a small rendition (about 512 px on the long side, JPEG or WebP, at most 256 KB) and uploads it straight to storage, the same way it uploads the photo.

**At upload time.** Add `"thumbnailContentType": "image/webp"` to `POST /api/memories/uploads`.
- The response then carries a second signed PUT in `thumbnail`, with its own headers and a `maxBytes` limit.
- Upload both files, then call `complete` once. Completing the photo also checks the thumbnail.
- A missing or invalid thumbnail never fails the photo. The photo simply completes without one.

**Later**, for a video's poster frame or for photos added before thumbnails existed:
- `POST /api/memories/{id}/thumbnail` with `{ "contentType": "image/jpeg" }` returns a slot.
- `POST /api/memories/{id}/thumbnail/complete` confirms the upload. It returns `409` if nothing has been uploaded yet, and `422` if the file is too large or the wrong type.
- Only the person who uploaded the photo can do this. Anyone else gets `403`.

`MemoryDto.thumbnailUrl` is signed like `url` and is `null` until a thumbnail is confirmed. Grids should use the thumbnail and fall back to `url`.

Storage details:
- Each memory has exactly one thumbnail key, `couples/{couple}/{memory}-thumb`, so a new upload overwrites the old one.
- A rejected upload is deleted, and because it overwrote the earlier thumbnail, the memory is left with none.
- Every delete path (deleting one photo, the archive, account deletion, the post-breakup expiry) removes that key as well, including thumbnails that were uploaded but never confirmed.
- Thumbnail bytes count toward the couple's storage limit.

## After the upload links expire

A signed PUT link keeps working until it expires (`Media:UploadUrlLifetime`, 15 minutes), even after the upload it was meant for has been verified. A signed PUT also fixes the content type but not the size. So for that window the uploader could put any file, of any size, at the same key.

To close that gap:
- Each memory records when the last link handed out for it expires, in `UploadWindowEndsAtUtc`.
- Once that time has passed, the cleanup worker checks the stored objects against what was verified:
  - **A photo that no longer matches** (size or type) is deleted together with its memory.
  - **A thumbnail that does not match the confirmed one,** or was uploaded and never confirmed, is removed. The photo itself stays.
- Then the worker clears the time.

Both the photo upload and the thumbnail endpoint extend the window. That leaves an exposure of at most the link lifetime plus one sweep, about 20 minutes. During that time the uploader's partner could load a swapped image, and the swapped bytes are not counted toward storage.

# Memories: backend contract

The couple's shared album. Route prefix `api/memories`, authorized. Bytes live in S3-compatible object storage
(Cloudflare R2, AWS S3 or MinIO) and never pass through the API.

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
2. Removes upload slots that were not completed within an hour.
3. Finishes deletes whose storage call failed.

It works in batches of 100 and keeps going while batches come back full. It always deletes the object before the row.

## Not done yet

Thumbnails. There is no server-side image processing; the app can upload a small rendition as its own memory or as a later `thumbnailKey`.

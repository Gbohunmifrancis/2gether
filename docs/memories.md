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

## Cleanup worker

Every 5 minutes (and once at startup): removes upload slots not completed within an hour and finishes deletes whose
storage call failed, in batches of 100, deleting the object before the row.

## Not done yet

Thumbnails (no server-side image processing; the app can upload a small rendition as its own memory or a later
`thumbnailKey`), and what happens to a couple's album after a breakup (today it simply becomes unreachable).

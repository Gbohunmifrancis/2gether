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

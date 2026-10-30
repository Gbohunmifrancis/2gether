# Profile pictures

A person can have one profile picture. It is stored in their `users` row (`AvatarBytes` bytea, `AvatarVersion` bigint), so there is no object storage to run. The older `avatarUrl` text field still exists and is unchanged; a client that knows about pictures prefers the picture when `avatarVersion` is not 0.

Production databases are never recreated, so the columns come from the additive `ALTER TABLE users ADD COLUMN IF NOT EXISTS` lines in `DatabaseSchemaUpgrade.cs` (and from the EF model for new databases).

## Endpoints

All need a signed-in user. The couple is always read from the database, never from the request.

| Call | Does |
|---|---|
| `PUT /api/account/avatar` | Body is the raw image. `Content-Type` must be `image/jpeg`, `image/png` or `image/webp`; at most 400 KB (409600 bytes). Answers `200 { "avatarVersion": 3 }`. |
| `DELETE /api/account/avatar` | Removes the picture. `204`, also when there was none. |
| `GET /api/users/{userId}/avatar` | The bytes, for the caller or the person they are linked with now. `404` for anyone else, after a breakup, and when there is no picture. |

Refusals are `422` with the usual `{ "code", "message" }`: `avatar.unsupported_type`, `avatar.empty`, `avatar.too_large`, `avatar.not_an_image` (the first bytes are not a JPEG, PNG or WebP; the header alone is not trusted). The picture is served as the type its bytes really are, with `X-Content-Type-Options: nosniff`.

`GET` answers with `ETag: "<version>"` and `Cache-Control: private, max-age=86400`, and `304` for a matching `If-None-Match` without reading the picture. Clients should add `?v=<avatarVersion>` or key their image cache on the version, so a changed picture is fetched at once.

## `avatarVersion`

`avatarVersion` (a number, `0` = no picture) is a field of the user object in every auth response (`login`, `register`, `google`, `PUT /api/auth/profile`, and `POST /api/couple/invites/accept`: `user.avatarVersion`), of `GET /api/auth/me` (`avatarVersion`), and of every entry of `members[]` in `GET /api/couple`. The stored version goes up on every change, removal included, so an ETag is never reused; the number clients see is `0` while there is no picture. Existing clients ignore the new field.

## Account export and deletion

The export (`GET /api/account/export`) puts the picture in `profile.profilePicture` as `{ "contentType", "sizeBytes", "version", "base64" }`, or `null`. Deleting an account deletes the row and so the picture; the partner's `avatarVersion` for that person is gone with it.

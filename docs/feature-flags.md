# Feature flags

Server-wide switches an admin can flip without a deploy. They live in the `app_settings` table (`Key`, `Value`, `UpdatedAtUtc`), created by `CREATE TABLE IF NOT EXISTS` in `DatabaseSchemaUpgrade.cs`. Nothing is seeded: a flag with no row is off.

| Flag | Row key | Meaning |
|---|---|---|
| `christmas` | `feature.christmas` (`"true"` / `"false"`) | Turns the festive look on in every app |

## Public: `GET /api/config`

No sign-in. Called on every app start, so it is one primary-key read, and cacheable: `Cache-Control: public, max-age=60` (also kept by the API's response cache). A flip therefore reaches apps within about a minute.

```json
{ "christmas": false }
```

Apps should treat a failed call as "off".

## Admin: `GET` and `PUT /api/admin/features`

Under the admin session (see `docs/admin.md`). Same body both ways:

```json
{ "christmas": true }
```

`PUT` needs a fresh authenticator code in `X-Admin-Code` like other changes that reach every user, answers `200` with the new state, and is written to the audit log as `features.updated` (target `feature` / `christmas`, details `christmas: off -> on`). A body without `christmas` is `422 admin.features_invalid`; it never switches the flag off by omission.

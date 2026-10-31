# Deploying

`.github/workflows/deploy.yml` ships what changed on `main`:

| Part | Goes to | When |
|---|---|---|
| API (`src/`, `tests/`, `Dockerfile`, `railway.json`) | Railway | first, after `dotnet build` and `dotnet test` pass |
| Admin dashboard (`admin/`) | Cloudflare Pages | after the API, once format, lint, types, tests and build pass |
| Marketing site (`website/`) | Cloudflare Pages (its own project) | straight away; static pages (see `docs/website.md`) |
| Android app (`android/`) | Google Play, internal testing track, plus a signed production APK on the run | after the API, once unit tests pass and a signed bundle is built |

The dashboard and app wait for the API because they may use its newest endpoints. If the API deploy fails, they don't
ship; if the API had nothing to deploy, they go ahead. A push that only touches `docs/` deploys nothing.

**Run it by hand** from Actions → Deploy → Run workflow: choose any of the three, and a Play track. Manual runs
deploy the branch you pick, so use `main` unless you mean otherwise.

Until a target's secrets are set, its job is skipped with a notice, so the workflow is safe to merge before any
of this is set up.

## After each deploy, the pipeline checks

- **API:** the image carries the commit it was built from (`version.txt`, shown on `/health`). The job polls
  `/health` until it reports this commit, then calls `/api/health/ready`. Railway only switches traffic to a build
  whose readiness check passes, so a broken build never replaces a working one. It then checks that the API's CORS
  allows the dashboard's origin. Missing that is the usual reason a freshly deployed dashboard can't sign in.
- **Dashboard:** checks that the site serves the script this build produced, with its security headers
  (`X-Frame-Options`, `Content-Security-Policy` from `admin/public/_headers`).
- **App:** Google confirms the upload, or the job fails with Google's reason.

## Setting it up

### GitHub

Secrets go under Settings → Secrets and variables → Actions → Secrets; plain settings go under Variables.

| Name | Kind | What |
|---|---|---|
| `RAILWAY_TOKEN` | secret | a Railway **project token** for the production environment (Railway → project → Settings → Tokens) |
| `RAILWAY_SERVICE` | variable | the API's service name in Railway; default `api` |
| `API_URL` | variable | the API's public URL, no trailing slash, e.g. `https://api.heartpact.me`. Used to verify the deploy, by the dashboard build, and by the app's release build |
| `ADMIN_URL` | variable | the dashboard's public URL, e.g. `https://admin.heartpact.me` |
| `CLOUDFLARE_API_TOKEN` | secret | a Cloudflare API token with **Cloudflare Pages: Edit** |
| `CLOUDFLARE_ACCOUNT_ID` | secret | Cloudflare dashboard → the account's ID |
| `CLOUDFLARE_PAGES_PROJECT` | variable | the Pages project name; default `heartpact-admin` |
| `CLOUDFLARE_SITE_PROJECT` | variable | the marketing site's Pages project; default `heartpact-site` |
| `SITE_URL` | variable | the marketing site's public URL, e.g. `https://heartpact.me`; also add it to the API's `Frontend__Origin` |
| `PLAY_SERVICE_ACCOUNT_JSON` | secret | the JSON key of a Google Cloud service account invited in Play Console (below) |
| `PLAY_TRACK` | variable | default track: `internal` (default), `alpha`, `beta` or `production` |
| `PLAY_RELEASE_STATUS` | variable | `completed` (default) or `draft` while the Play app has never been published |
| `ANDROID_KEYSTORE_*`, `FIREBASE_*`, `GOOGLE_WEB_CLIENT_ID`, `PLAY_INTEGRITY_PROJECT_NUMBER` | secrets | as in `docs/secrets.md` |

Each job runs in a GitHub **environment** (`production-api`, `production-admin`, `production-website`, `production-android`), created on
the first run. To require someone's approval before a deploy, add required reviewers to that environment
(Settings → Environments).

### Railway (API and database)

1. Create a project with a PostgreSQL database and an empty service named `api` (or whatever `RAILWAY_SERVICE` says).
2. On the service, set the API's environment from `docs/secrets.md`. `DATABASE_URL` comes from the Postgres
   service as a reference variable. Also set `ForwardedHeaders__Enabled=true`, `Jwt__SigningKey`,
   `Admin__SecretKey`, and `Frontend__Origin` including `ADMIN_URL`.
3. Give the service a public domain, which becomes `API_URL`, and create a project token for `RAILWAY_TOKEN`.
4. If the service is also connected to this GitHub repository, turn that autodeploy off. Otherwise every push
   deploys twice, once without the pipeline's checks.

The build uses the `Dockerfile`, and `railway.json` sets the readiness check. The API updates its database schema
on start-up (`Database:EnsureCreated` is on in Production), so a deploy is also the migration. Schema changes are
written to be additive, so the old build keeps working while the new one starts.

**Roll back:** Railway → service → Deployments → the previous one → Redeploy.

### Cloudflare Pages (dashboard)

1. Create the project once: `npx wrangler pages project create heartpact-admin --production-branch main`.
2. Add a custom domain (e.g. `admin.heartpact.me`), which becomes `ADMIN_URL`. Consider putting Cloudflare Access
   in front of it as well.
3. `admin/public/_redirects` sends every path to the app, and `_headers` sets the security headers.

**Roll back:** Pages → the project → Deployments → an earlier one → Rollback.

### Google Play (app)

1. In Google Cloud, create a service account and a JSON key, which becomes `PLAY_SERVICE_ACCOUNT_JSON`. Enable the
   Google Play Android Developer API.
2. In Play Console → Users and permissions, invite the service account's email with **Release apps to testing
   tracks** (and **Release to production** if you'll use that track), plus **Manage orders and subscriptions**,
   which the API's billing needs as well.
3. Upload the very first bundle by hand in Play Console. Google requires that once. Download it from the
   `heartpact-release-aab` artifact of a Deploy run. Until the app is published, set `PLAY_RELEASE_STATUS=draft`.

Every run also attaches `heartpact-release-apk`: the same production build as an APK, signed with the release key,
to install directly (Actions → the Deploy run → Artifacts). To build one without touching the Play track, run Deploy
by hand with only Android ticked and no `PLAY_SERVICE_ACCOUNT_JSON`, or ignore the upload.

Each build is numbered by the pipeline: version code `100000 + run × 10 + attempt`, name `0.1.<run>`. A re-run
counts up, as Play requires. Release notes are the commit's first line.

**Roll back:** Play Console → the track → halt the release, or promote an earlier one.

## Doing it by hand

```bash
echo "$(git rev-parse HEAD)" > version.txt   # optional: lets /health report the commit
npx @railway/cli up --ci --service api       # with RAILWAY_TOKEN set
cd admin && VITE_API_URL=https://api.heartpact.me npm run build && npx wrangler pages deploy dist --project-name heartpact-admin --branch main
```

Don't commit `version.txt`. It isn't in `.gitignore` on purpose, because `railway up` skips ignored files.

# Secrets and settings

Nothing secret lives in git. The Android app reads its build settings from environment variables, which GitHub
Actions fills from repository secrets (Settings → Secrets and variables → Actions). The API reads its settings from
environment variables on the host that runs it, using `__` for nesting (`Push__Provider` is `Push:Provider`).

## GitHub repository secrets (Android builds)

| Secret | Used for | Where to get it |
|---|---|---|
| `FIREBASE_PROJECT_ID` | push | Firebase console → Project settings → General: Project ID |
| `FIREBASE_APP_ID` | push | Project settings → Your apps → the Android app (`app.heartpact`): App ID, `1:…:android:…` |
| `FIREBASE_API_KEY` | push | Project settings → the Android app: Web API key (also `current_key` in `google-services.json`) |
| `FIREBASE_SENDER_ID` | push | Project settings → Cloud Messaging: Sender ID (`project_number`) |
| `MAPS_API_KEY` | the location map | Google Cloud console → APIs & Services → Credentials: an API key with "Maps SDK for Android" enabled, restricted to the app's package and signing certificate. Without it the app draws its own map of the two of you |
| `ANDROID_KEYSTORE_BASE64` | signed release bundle | `base64 -w0 release.jks` of your upload keystore |
| `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | signed release bundle | chosen when the keystore was made |
| `HEARTPACT_RELEASE_API_URL` | release builds | optional; defaults to `https://api.heartpact.app/` |

Without the Firebase secrets the app still builds and works: it polls instead of receiving pushes. Without the
keystore secrets the `release` job is skipped. Locally, put the same names in `~/.gradle/gradle.properties` as
`heartpact.FIREBASE_APP_ID=…`, or export them before running Gradle.

The workflow is `.github/workflows/android.yml`: build, unit and screenshot tests and lint on every push; the app's
client against a real API (PostgreSQL service, an S3 stand-in for photos); and, on `main` or by hand, a signed
App Bundle uploaded as a build artifact.

## API host environment

| Variable | Needed for | Notes |
|---|---|---|
| `DATABASE_URL` (or `Database__ConnectionString`, or `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD`) | everything | |
| `Jwt__SigningKey` | sign-in | long random string; production refuses the default |
| `Frontend__Origin` | the web app | CORS, comma-separated |
| `Push__Provider` = `fcm`, `Push__FcmProjectId`, `Push__FcmCredentialsJson` | push | the service account JSON itself (Firebase → Project settings → Service accounts → Generate new private key) |
| `Media__ServiceUrl`, `Media__Bucket`, `Media__AccessKey`, `Media__SecretKey`, `Media__Region` | shared album | any S3-compatible store: Cloudflare R2 (`Region=auto`), AWS S3, MinIO |
| `Food__AnthropicApiKey` (or `ANTHROPIC_API_KEY`) | food photo estimates | without it the app logs meals by hand |
| `Billing__Provider` = `googleplay`, `Billing__GooglePlayPackageName`, `Billing__GooglePlayCredentialsJson`, `Billing__RtdnSecret` | Heartpact Plus | see `docs/billing.md` |
| `ForwardedHeaders__Enabled` = `true` | behind a proxy (Railway) | |

If the API is deployed from GitHub Actions, add these as secrets there and pass them as `env:` to the deploy step;
otherwise set them in the host's dashboard.

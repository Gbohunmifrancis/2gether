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
| `GOOGLE_WEB_CLIENT_ID` | "Continue with Google" | Google Cloud console → APIs & Services → Credentials: the **Web application** OAuth client id (`…apps.googleusercontent.com`). Also create an **Android** OAuth client for package `app.heartpact` with your signing certificate's SHA-1, or Google refuses the sign-in. Without it the button is hidden |
| `PLAY_INTEGRITY_PROJECT_NUMBER` | proving a finished dare came from the genuine app | the **number** of the Google Cloud project linked in Play Console → App integrity. Without it no token is sent. See `docs/exercise-checks.md` |
| `ANDROID_KEYSTORE_BASE64` | signed release bundle | `base64 -w0 release.jks` of your upload keystore |
| `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | signed release bundle | chosen when the keystore was made |
| `HEARTPACT_RELEASE_API_URL` | release builds | optional; defaults to the `API_URL` variable, else `https://api.heartpact.app/` |
| `PLAY_SERVICE_ACCOUNT_JSON` | uploading to Google Play | see `docs/deploy.md` |
| `RAILWAY_TOKEN`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` | deploying the API and dashboard | see `docs/deploy.md`, with the `API_URL`, `ADMIN_URL` and other variables |

Without the Firebase secrets the app still builds and works: it polls instead of receiving pushes. Without the
keystore secrets the Deploy workflow's Android job is skipped. Locally, put the same names in `~/.gradle/gradle.properties` as
`heartpact.FIREBASE_APP_ID=…`, or export them before running Gradle.

`.github/workflows/android.yml` runs build, unit and screenshot tests, and lint on every push. It also tests the
app's client against a real API (a PostgreSQL service and an S3 stand-in for photos). Signed bundles and uploads to
Google Play happen in `.github/workflows/deploy.yml` (see `docs/deploy.md`).

## API host environment

| Variable | Needed for | Notes |
|---|---|---|
| `DATABASE_URL` (or `Database__ConnectionString`, or `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD`) | everything | |
| `Jwt__SigningKey` | sign-in | long random string; production refuses the default |
| `Frontend__Origin` | the web app and admin dashboard | CORS, comma-separated: include the dashboard's origin |
| `Push__Provider` = `fcm`, `Push__FcmProjectId`, `Push__FcmCredentialsJson` | push | the service account JSON itself (Firebase → Project settings → Service accounts → Generate new private key) |
| `Media__ServiceUrl`, `Media__Bucket`, `Media__AccessKey`, `Media__SecretKey`, `Media__Region` | shared album | any S3-compatible store: Cloudflare R2 (`Region=auto`), AWS S3, MinIO |
| `Email__Provider` = `smtp`, `Email__Host`, `Email__Port` (587), `Email__User`, `Email__Password`, `Email__From` (`Email__FromName`, `Email__StartTls` optional) | password reset codes | any SMTP service: Amazon SES, Postmark, Mailgun, Resend, SendGrid, Google Workspace. `Email__StartTls=false` for implicit TLS on 465. Without it "Forgot password?" says reset isn't set up |
| `Auth__GoogleClientIds` | Google sign-in | the same web client id as `GOOGLE_WEB_CLIENT_ID` (comma-separate several). Without it the server answers 503 to Google sign-in |
| `Food__AnthropicApiKey` (or `ANTHROPIC_API_KEY`) | food photo estimates | without it the app logs meals by hand |
| `Billing__Provider` = `googleplay`, `Billing__GooglePlayPackageName`, `Billing__GooglePlayCredentialsJson`, `Billing__RtdnSecret` | Heartpact Plus | see `docs/billing.md` |
| `Integrity__Provider` = `playIntegrity` (`Integrity__PackageName`, `Integrity__CredentialsJson` optional) | flagging finished dares from a modified app or tampered phone | reuses the billing package and service account unless set. Turn on after a build with `PLAY_INTEGRITY_PROJECT_NUMBER` ships. See `docs/exercise-checks.md` |
| `ForwardedHeaders__Enabled` = `true` | behind a proxy (Railway) | |
| `Admin__Emails` | the admin dashboard | who may sign in, comma-separated; each signs up in the app first, then enrolls an authenticator. See `docs/admin.md` |
| `Admin__MonthlyPrices__<productId>`, `Admin__Currency`, `Admin__TokenHours` | the admin dashboard | list prices for the revenue estimate (monthly equivalent), their currency (default `USD`), admin session hours (default 4) |
| `Admin__SecretKey` (and `Admin__PreviousSecretKey` while changing it) | the admin dashboard | encrypts admins' authenticator secrets; a long random string. See `docs/admin.md` |

## Admin dashboard build

| Variable | Notes |
|---|---|
| `VITE_API_URL` | the API's public URL, read when `admin/` is built (`npm run build`). Not secret: it ends up in the page |

If the API is deployed from GitHub Actions, add these as secrets there and pass them as `env:` to the deploy step;
otherwise set them in the host's dashboard.

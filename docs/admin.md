# Admin dashboard

`admin/` is the web dashboard for the people who run Heartpact: analytics across every user, Google Play
subscriptions and complimentary Plus, account operations, and an audit log of everything an admin does. It is a
Vite + React single-page app using TanStack Router (typed routes and search params), TanStack Query (server state)
and TanStack Table, and it talks only to the API's `/api/admin/*` endpoints.

## What admins can and can't see

Admins see **counts, account metadata and when things happened, with names**: email, name, sign-up and
last-active dates, sign-in methods, couple state, devices, store subscriptions, how many dares, meals, photos and
answers someone has, their journey (each day's kinds of activity, with exercise names, game types and products), the
live feed ("Kayla & Austin linked up", "João did the dare · squats, set by Leticia"), top couples by name, the world
view by account country, and Heart to heart scores as distributions. They never see what couples wrote,
photographed, drew or logged: no photos, captions, answers, messages, drawings, meals, cycle or pregnancy details, or
location. The API has no admin endpoint that returns any of it. The Privacy Policy (`website/privacy/`, section 5)
describes this to users; change it when what admins see changes.

## Signing in

1. The person signs up in the app like anyone else (an email and password account).
2. Their email goes in `Admin__Emails` on the API (comma-separated). The list is re-checked on every request, so
   removing an email locks that admin out at once.
3. Their first sign-in on the dashboard shows a QR code: they scan it with an authenticator app (Google
   Authenticator, 1Password, Authy…) and enter the six-digit code. From then on every sign-in needs email,
   password and a fresh code. A code works once.
4. The admin session lasts `Admin__TokenHours` (default 168, a week; between 0.25 and 168) and is kept in the
   browser's `localStorage`: closing the tab doesn't sign out, Sign out does. Admin tokens use their own audience; app tokens don't work on
   the admin API and admin tokens don't work in the app.

A lost phone: another admin opens System → Admins → **Reset authenticator**. That admin is signed out and enrolls
again at their next sign-in. Nobody can reset their own.

### A fresh code for destructive actions

Disabling or deleting an account, refunding, revoking or stopping a subscription and resetting another admin's
authenticator also need a **fresh authenticator code**, sent as the `X-Admin-Code` header. It is checked like a sign-in
code: it works once (the same "last used step" as sign-in, so the code you just signed in with doesn't count again), and
it is used up before the action runs, so a copied session token alone can't do any of these. One code covers five minutes
of such actions from the same session (`StepUpGrace`, in memory), so a few in a row ask once. Giving a couple Plus,
marking an account internal and switching a feature need only the signed-in session. Without a code the API answers `403 admin.step_up_required`; with a wrong or used one,
`403 admin.step_up_invalid` (audited as `admin.step_up_failed`). Five wrong codes in 15 minutes lock step-up for that
admin for a while (`429`). The dashboard asks for the code in a dialog and retries with it.

Every sign-in failure says the same thing ("Email or password is incorrect"), whether the account doesn't exist, isn't
an admin, or is disabled. Attempts are rate-limited per email (5 per 15 minutes) and per IP (20 per 15 minutes).

## Pages

| Page | What it does |
|---|---|
| Overview | People, linked couples, active today and couples on Plus, counting up, with the week before where there's a fair comparison; the live pulse (online now, dares ringing, on camera, hearts, games, sign-ups, links and Plus today); active people, sign-ups and couples linked per day; the live feed; devices and app versions |
| Live | The live feed as stickers sliding in, with Maya and Theo acting out the latest moment (a couple linking, Plus, a dare done, an alert); the pulse; filters by kind; the sounds |
| Alerts | What the server is worried about right now (sign-up waves, food scan floods, payment trouble, failing push, late or failing workers, album storage), alerts raised while you've been looking, and the rules |
| Engagement | Dares completed vs missed (and by exercise), meals logged and food scans, photos shared, questions answered, games finished, how many use location, cycle and pregnancy |
| Funnel | People who signed up in the window and how far they got, as a heart jar filling level by level: invited, linked, first dare set and done, a whole heart, back in week 2, Plus; step conversion and median times |
| Retention | Couples by the week they linked and the share active each week after, as soft heat squares with the weighted average on top |
| Rhythm | Weekday × hour blobs of everything, dares done or alarms, shifted into your own time zone |
| Dares | Per exercise: set, done, called off, missed, time to start, time on camera, how sure the camera was, failed checks; early starts; couples by current streak |
| Games | Started, finished and left per game; the Heart to heart duel's skill scores as small histograms; duels, ties and the usual winning margin in hearts |
| Growth | Renewals coming up, renewed and lapsed, renewal rate, the path from free to Plus (limits hit on the way), median days to Plus, Plus by country |
| Revenue | Estimated monthly revenue, paying and complimentary couples, subscriptions started and ended, active by plan and state |
| World | A map of where people are (the country on their account, never their location): bubbles sized by people, coloured by how many of their couples are on Plus; the top countries |
| Top couples | The couples making the most hearts, as a sticker wall: their hearts, dares, games, streak and Plus |
| Users | Search by email, name, user id or couple id; filter active, disabled, not in a couple or internal; who's online now |
| A user | Account, couple and partner, activity counts, their journey (each day's counts as a sticker strip), devices, subscriptions, grants and admin history. Disable or re-enable, sign out everywhere, email a reset code, mark as internal, give the couple Plus, delete the account |
| Payments | Every Play subscription: sync with Google, stop renewal, refund the latest payment, or revoke (refund and end now). Card (Bachs) subscriptions, marked "Card": sync with Bachs and stop renewal (Bachs cancels at the period end); refunds and ending one now are done in the Bachs dashboard, and the API refuses them with `409 admin.bachs_dashboard`. Complimentary Plus grants, with end |
| Audit log | Every admin sign-in (and failure) and action, who, when, on what, from which IP. Kept two years (`AdminAuditSweepWorker`, daily), as the privacy policy says |
| Health | Probes (database, storage, push, email, food scans) with latency; background workers as beating hearts that go still when late and red when failing; queues; album storage; food scan cost; an honest list of what nothing monitors |
| Features | Switches that change every app without a release. Today: **Christmas theme**, which turns the festive look on for every app (within about a minute), and **Card payments in the app** (`bachsInApp`, Google Play billing choice). See `docs/feature-flags.md` |
| System | Which services are configured, push and clean-up queues, list prices, the **Billing check** (below), and the admins |

Analytics windows are 7, 30, 90 or 365 days; filters, pages and windows are in the URL, so a view can be shared.
Every chart leaves out internal (test and team) accounts and the admins, and couples with one of them in. "Active"
means the person did something that day: started or completed a dare, logged a meal, synced a photo, answered a
question, finished or played a game, or signed in. Rates that can't be computed show "—".

Analytics windows are 7, 30, 90 or 365 days; filters, pages and windows are in the URL, so a view can be shared. Every
chart has a table view (the toggle at its top right), and every animation stops when the system asks for reduced motion.

**Internal accounts.** Mark test and team accounts as internal on their page: they get a badge everywhere and are left
out of every count and chart (admins are always left out).

**Live.** The header greets you and shows who's online, a heart that beats once per live event, the alerts bell and the
command palette (Cmd/Ctrl+K: jump to a page, find someone by email, name or id, or do a quick action). The live feed is a
Server-Sent Events stream read with `fetch` (so it carries the bearer token); it reconnects after 1, 2, 5, 10, then every
30 seconds, and polls `analytics/pulse` every minute meanwhile. Sounds (off until you turn them on, remembered in the
browser): a two-note chime when a couple links, a tick for a dare done, a warm "ka-ching" for Plus, a low tone for alerts.

**Fresh codes for destructive actions.** Disabling, deleting, refunds, revocations, stopping renewal, giving Plus,
resetting another admin's authenticator, marking internal and switching a feature ask for a fresh authenticator code (a padlock dialog); the
dashboard sends it as `X-Admin-Code` and retries. A deployed dashboard on another origin needs the API's CORS to allow
that header.

Every action that changes an account or money asks for confirmation, and most need a **reason**, which goes into
the audit log. Deleting an account also needs the account's email typed out. It erases everything, exactly as the
person deleting their own account would, but it doesn't stop a Google Play subscription: stop or revoke that
first (the dashboard warns when one is still renewing). A renewing card (Bachs) subscription is cancelled in Bachs,
immediately and without a refund, before the account goes; if Bachs can't be reached nothing is deleted (503).

Revenue is an estimate: renewing subscriptions × the list price in `Admin__MonthlyPrices__<productId>` (a yearly
plan's price divided by 12). It ignores Google's fee, tax, trials and currency; Play Console is the record.

## Authenticator secrets at rest

Each admin's authenticator secret is encrypted (AES-256-GCM) with a key from the host environment, so a copy
of the database alone doesn't give away anyone's second factor. The key is `Admin__SecretKey`, or one derived
from `Jwt__SigningKey` when that isn't set. Secrets from before encryption, or under an older key, are
re-encrypted at start-up.

To change the key, set the new `Admin__SecretKey` and put the old one in `Admin__PreviousSecretKey` (or leave it
out if the old key was the JWT-derived one, which is always tried last). Restart, then remove the previous key
after a start-up. If neither key opens a secret, that admin's sign-in is refused, never re-enrolled on a password
alone, and another admin resets their authenticator. Changing `Jwt__SigningKey` without setting
`Admin__SecretKey` has the same effect, so set a dedicated `Admin__SecretKey` in production.

## Settings (API host)

| Variable | Meaning |
|---|---|
| `Admin__Emails` | Who may sign in, comma-separated. Empty: nobody |
| `Admin__TokenHours` | Admin session length in hours, default 168 (a week), at most 168 |
| `Admin__MonthlyPrices__heartpact_plus_monthly`, `Admin__MonthlyPrices__heartpact_plus_yearly` | Monthly list price per product, for the revenue estimate |
| `Admin__Currency` | Currency of those prices (and of the food scan cost), default `USD` |
| `Admin__FoodScanPricePerMillionInput`, `Admin__FoodScanPricePerMillionOutput` | Food photo estimate price per million input and output tokens, for the cost on System → Health. Without both, the cost shows "—" |
| `Admin__StorageBudgetGb` | Album storage budget in GB (10^9 bytes): an alert is raised at 80% and turns red at 100%. Unset: no storage alert |
| `Admin__SecretKey` | Encrypts admins' authenticator secrets in the database. Any long random string. Without it, a key derived from `Jwt__SigningKey` is used |
| `Admin__PreviousSecretKey` | The key before the last change, so existing secrets still open; they are re-encrypted under the new key at start-up |
| `Frontend__Origin` | Add the dashboard's origin (for example `https://admin.heartpact.me`) to this comma-separated CORS list |

Refunds and revocations call Google Play, so they need `Billing__Provider=googleplay` and a service account with
"Manage orders and subscriptions" (see `docs/billing.md`).

## Running it locally

```bash
# 1. The API, with an admin and fake billing (see AGENTS.md for the database)
Database__EnsureCreated=true ASPNETCORE_ENVIRONMENT=Development ASPNETCORE_URLS=http://localhost:5135 \
Admin__Emails=ops@heartpact.me Admin__MonthlyPrices__heartpact_plus_monthly=4.99 \
Billing__Provider=fake Email__Provider=log RateLimits__Enabled=false \
dotnet run --project src/Twogether.Api --no-launch-profile

# 2. Some data, and the admin enrolled: prints the authenticator secret and a token
python3 admin/scripts/seed.py http://localhost:5135

# 3. The dashboard on http://localhost:5173 (proxies /api to the API)
cd admin && npm ci && npm run dev
```

Add the printed `totpSecret` to an authenticator app, or compute codes from it. Running the seed again needs
`ADMIN_TOTP_SECRET=<that secret>`, since the admin is already enrolled.

Checks: `npm run format:check`, `npm run lint`, `npm run typecheck`, `npm test` (unit tests, plus the real router
against a mocked API: sign-in and enrollment, reasons and typed confirmation, sign-out on 401) and `npm run build`.
`npm run screens` signs in through the form and saves a screenshot of every page to `admin/screens/`.

**Demo mode, no backend:** `npm run dev:demo` (http://localhost:5174) runs the dashboard against a pretend API in the
browser: made-up but realistic data for every endpoint, a live stream with an event every few seconds and the odd alert,
and step-up codes. Sign in with "Try the demo" (or any email, password and six-digit code; `000000` is always refused).
`npm run screens:demo` starts it itself and saves every page, light and dark, desk and phone widths, plus a few frames
caught mid-animation, to `admin/screens/demo/`. A normal build leaves the demo out.

CI is `.github/workflows/admin.yml`: those checks, then the production build against a real API and PostgreSQL,
seeded, with every page screenshotted (the `admin-screens` artifact).

## Deploying

The Deploy workflow builds it and publishes it to Cloudflare Pages on every change to `admin/` on `main` (see
`docs/deploy.md`). The build is static files (`npm run build` → `admin/dist`). Build with `VITE_API_URL=https://your-api` and host
anywhere that can serve a single-page app: Cloudflare Pages, Netlify, S3 + CloudFront, or the same host as the API.
`public/_redirects` sends every path to `index.html`, and `public/_headers` sets a strict Content Security Policy
(scripts only from the dashboard itself, no framing), `no-referrer` and `no-store`; both are read by Cloudflare
Pages and Netlify. Elsewhere, set the same headers on the host.

Put it on its own hostname, add that origin to `Frontend__Origin`, and consider putting it behind your host's
access control (Cloudflare Access, an IP allowlist) as well: the sign-in is strong, but an admin URL nobody else
can reach is stronger.

## Billing check

System → **Billing check** answers "why does the app say Plus isn't on sale?" (`GET api/admin/billing/check?country=NG`).
It runs only when an admin presses **Run check** (it asks Google Play and Bachs each time, caches nothing, and is audited
as `billing.checked` with the country and the number of problems); after a first run, picking another country runs it
again for that country. It is store configuration only, nothing about any person.

- **Hints** at the top, as a checklist: what stops Plus selling (✗), things to do, notes, and what's fine, in plain
  words with where to click in Play Console or Bachs. The hints are computed on the server (`BillingCheckHints`).
- **Google Play**: the package, whether the service account is set and the provider, then one row per
  `Billing__ProductIds` entry with ✓/✗ for exists, has an active base plan and on sale in the chosen country (with its
  price there), each base plan (state, renewing or prepaid, period, how many countries, a few prices) and the offers.
  Subscriptions Play Console has that aren't in `Billing__ProductIds` are listed as a likely id typo. A failed read
  shows Google's HTTP status and one-word reason with what to do (401/403: the service account has no access in Play
  Console; 404: no such app or no subscriptions; the API switched off in Google Cloud; a broken key).
- **Card payments (Bachs)**: live or sandbox (from the key), the settings that aren't set (names only), the
  `bachsInApp` flag, and each plan's product: exists, active, price, billing period, trial.

Each call to Google or Bachs has 15 seconds; a failure is a field of the answer, never a 500. The billing service account
needs access to the app in Play Console (View app information, plus View financial data and Manage orders and
subscriptions for purchases). See `docs/billing.md`, "Why Plus isn't on
sale".

## Live, health and alerts

- **Live** (`analytics/pulse`, `analytics/live/events`): the pulse is today's counts and the last 24 hours of events; the
  stream is Server-Sent Events over a normal authenticated `GET` (read with `fetch`, not `EventSource`). The API polls
  the database every 3 s, sends each event once by id, pings every 20 s, re-checks every minute that the admin is still
  an admin, and ends when the token expires. It starts a minute back, so the client drops repeats of `recent` by id.
  "Online now" is people whose last request was in the last 5 minutes or who have the app open (a live connection).
- **Health** (`system/health`): database and storage round trips, whether push, email and food estimates are set up
  (and degraded when a fifth of pushes or half the scans failed in the last hour), every background worker's last run
  and error, and the queues the workers drain. Workers report into memory, so this is the answering instance's view
  since its last start; "late" is three missed intervals (at least 30 s), "failing" is a last loop that errored.
- **Alerts** (`alerts`): sign-up wave, food-scan flood, payment trouble, push failing, a late or failing worker, album
  storage near its budget. Each is raised once an hour at most, stays listed for that hour, and appears on the live
  stream as an `alert` event. They are computed on request (at most every 30 s) and held in memory: nothing pages
  anyone, and an alert is only noticed while someone has the dashboard open. `notMonitored` on the health page lists
  what nothing watches yet.
- Deleted accounts leave one row in `account_deletions` (when, and whether it was an internal account; nothing else),
  so the live feed can say an account was deleted.

## Known limits

- The first enrollment trusts whoever first signs in with the admin's password. Enroll each new admin promptly
  after adding their email.
- One role: every admin can do everything. Every action is audited.

# Admin dashboard

`admin/` is the web dashboard for the people who run Heartpact: analytics across every user, Google Play
subscriptions and complimentary Plus, account operations, and an audit log of everything an admin does. It is a
Vite + React single-page app using TanStack Router (typed routes and search params), TanStack Query (server state)
and TanStack Table, and it talks only to the API's `/api/admin/*` endpoints.

## What admins can and can't see

Admins see **counts and account metadata**: email, name, sign-up and last-active dates, sign-in methods, couple
state, devices, store subscriptions, and how many dares, meals, photos and answers someone has. They never see
what couples wrote, photographed or logged: no photos, captions, answers, messages, meals, cycle or pregnancy
details, or location. The API has no admin endpoint that returns any of it.

## Signing in

1. The person signs up in the app like anyone else (an email and password account).
2. Their email goes in `Admin__Emails` on the API (comma-separated). The list is re-checked on every request, so
   removing an email locks that admin out at once.
3. Their first sign-in on the dashboard shows a QR code: they scan it with an authenticator app (Google
   Authenticator, 1Password, Authy…) and enter the six-digit code. From then on every sign-in needs email,
   password and a fresh code. A code works once.
4. The admin session lasts `Admin__TokenHours` (default 4, between 0.25 and 12) and lives in the tab's
   `sessionStorage`: closing the tab signs out. Admin tokens use their own audience; app tokens don't work on
   the admin API and admin tokens don't work in the app.

A lost phone: another admin opens System → Admins → **Reset authenticator**. That admin is signed out and enrolls
again at their next sign-in. Nobody can reset their own.

Every sign-in failure says the same thing ("Email or password is incorrect"), whether the account doesn't exist, isn't
an admin, or is disabled. Attempts are rate-limited per email (5 per 15 minutes) and per IP (20 per 15 minutes).

## Pages

| Page | What it does |
|---|---|
| Overview | People, linked couples, daily/weekly/monthly active, couples on Plus; active people, sign-ups and couples linked per day; devices and app versions |
| Engagement | Dares completed vs missed (and by exercise), meals logged and food scans, photos shared, questions answered, games finished, how many use location, cycle and pregnancy |
| Revenue | Estimated monthly revenue, paying and complimentary couples, subscriptions started and ended, active by plan and state |
| Users | Search by email, name, user id or couple id; filter active, disabled or not in a couple |
| A user | Account, couple and partner, activity counts, devices, subscriptions, grants and admin history. Disable or re-enable, sign out everywhere, email a reset code, give the couple Plus, delete the account |
| Payments | Every Play subscription: sync with Google, stop renewal, refund the latest payment, or revoke (refund and end now). Complimentary Plus grants, with end |
| Audit log | Every admin sign-in (and failure) and action, who, when, on what, from which IP |
| System | Which services are configured, push and clean-up queues, list prices, and the admins |

Analytics windows are 7, 30, 90 or 365 days; filters, pages and windows are in the URL, so a view can be shared.

Every action that changes an account or money asks for confirmation, and most need a **reason**, which goes into
the audit log. Deleting an account also needs the account's email typed out. It erases everything, exactly as the
person deleting their own account would, but it doesn't stop a Google Play subscription: stop or revoke that
first (the dashboard warns when one is still renewing).

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
| `Admin__TokenHours` | Admin session length in hours, default 4 |
| `Admin__MonthlyPrices__heartpact_plus_monthly`, `Admin__MonthlyPrices__heartpact_plus_yearly` | Monthly list price per product, for the revenue estimate |
| `Admin__Currency` | Currency of those prices, default `USD` |
| `Admin__SecretKey` | Encrypts admins' authenticator secrets in the database. Any long random string. Without it, a key derived from `Jwt__SigningKey` is used |
| `Admin__PreviousSecretKey` | The key before the last change, so existing secrets still open; they are re-encrypted under the new key at start-up |
| `Frontend__Origin` | Add the dashboard's origin (for example `https://admin.heartpact.app`) to this comma-separated CORS list |

Refunds and revocations call Google Play, so they need `Billing__Provider=googleplay` and a service account with
"Manage orders and subscriptions" (see `docs/billing.md`).

## Running it locally

```bash
# 1. The API, with an admin and fake billing (see AGENTS.md for the database)
Database__EnsureCreated=true ASPNETCORE_ENVIRONMENT=Development ASPNETCORE_URLS=http://localhost:5135 \
Admin__Emails=ops@heartpact.app Admin__MonthlyPrices__heartpact_plus_monthly=4.99 \
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

## Known limits

- The first enrollment trusts whoever first signs in with the admin's password. Enroll each new admin promptly
  after adding their email.
- One role: every admin can do everything. Every action is audited.

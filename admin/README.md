# Heartpact admin

The dashboard for running Heartpact: analytics, users, payments and the audit log. Vite + React, TanStack Router,
Query and Table. Read `../docs/admin.md` for what it does, how admins sign in, settings and deployment.

```bash
npm ci
npm run dev        # http://localhost:5173, proxies /api to http://localhost:5135
npm run dev:demo   # http://localhost:5174, no backend: made-up data, a live stream, any code signs in
npm test           # unit and router tests
npm run lint && npm run typecheck && npm run format:check
npm run build      # dist/, with VITE_API_URL set
```

`scripts/seed.py` fills a local API with couples and enrolls an admin; `npm run screens` screenshots every page.
`npm run screens:demo` screenshots every page in demo mode (light and dark, 1440 and 390 wide, plus mid-animation frames)
into `screens/demo/`, starting its own demo server.

Where things are: `src/api` (client with step-up retry, the live SSE client, types and queries), `src/live` (one live
connection for the console), `src/components` (shell and header, command palette, step-up dialog, charts, art: the mark,
the mascot, Maya and Theo), `src/pages`, `src/lib` (formatting, time-zone shift, palette search, sounds, countries) and
`src/demo` (the pretend API). Styles: `src/styles.css` (tokens, base) and `src/console.css` (the console's pieces and
animations, all off under reduced motion).

## Demo data

`scripts/seed_demo.py` gives a local API 90 days of believable life to design and review against: about 240 people
from 27 countries (sign-ups rising, a launch spike, quieter weekends), couples linking, a few breakups and deleted
accounts, dares done, missed and called off at real hours, meals and food scans, daily questions, games (Heart to
heart duels with skill scores), cycle tracking, location sharing, photos, Plus subscriptions in every state and
complimentary Plus, with retention that decays. Three team accounts are marked internal.

```bash
python3 admin/scripts/seed_demo.py --reset      # wipes every table's data (schema stays), then seeds; ~2 minutes
python3 admin/scripts/seed_demo.py --dry-run    # only prints what it would make
```

Development only: the API started as below (fake billing, log email, no rate limits, `ops@heartpact.app` as admin)
and psql to its database (`--psql`, default the dev database on `/var/lib/postgresql/heartpact-dev:5544`). It acts
through the real API, then moves each row back to when it happened with SQL, keeping timestamps consistent; food
scans and photos, which the API can't make without a model key or storage, are inserted as plain rows. `--seed`
picks another story, `--people` the size. It ends with row counts, consistency checks and the admin's
authenticator secret and token; every demo account's password is `longenough`. The script's docstring has the
details and what it leaves out.

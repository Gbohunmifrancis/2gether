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

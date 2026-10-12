# Heartpact admin

The dashboard for running Heartpact: analytics, users, payments and the audit log. Vite + React, TanStack Router,
Query and Table. Read `../docs/admin.md` for what it does, how admins sign in, settings and deployment.

```bash
npm ci
npm run dev        # http://localhost:5173, proxies /api to http://localhost:5135
npm test           # unit and router tests
npm run lint && npm run typecheck && npm run format:check
npm run build      # dist/, with VITE_API_URL set
```

`scripts/seed.py` fills a local API with couples and enrolls an admin; `npm run screens` screenshots every page.

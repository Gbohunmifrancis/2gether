# Marketing website

`website/` is heartpact.app: the home page that sends people to Google Play, the Privacy Policy, the Terms of Use,
and the account deletion page Google Play requires. It is plain HTML, CSS and a little JavaScript, with no build step
and nothing loaded from other sites (fonts and images are served from `website/assets/`).

| Path | What |
|---|---|
| `index.html` | The home page (design direction C, "The Fold") |
| `privacy/`, `terms/` | Drafts written from what the code does. **Have a lawyer review them**, and fill the `[COMPANY NAME]`, `[POSTAL ADDRESS]`, `[COUNTRY]` and other bracketed placeholders, before launch. Keep them true to the code: when a feature or the admin console changes what is collected or who sees it, update them. The app opens them in the phone's language (`LegalPages` in `android/.../ui/components/Legal.kt`) from sign-up and from Us |
| `delete-account/` | Signs in with email and password and calls `POST /api/account/delete`, the same call the app makes. A renewing Plus subscription is shown first, as in the app. Google sign-in accounts are asked to email support |
| `404.html` | Not found |
| `_headers` | Security headers, including a strict Content-Security-Policy (scripts, styles and fonts only from the site; API calls only to the API) |
| `assets/config.js` | The API's address for the deletion page; the deploy job writes the real one |

The prices on the home page ($5.99 a month, $39.99 a year) must match the Plus base plans in Play Console; change
both together. The contact address throughout is `support@heartpact.app`.

## Motion

The home page moves the way the reference video does: the two halves open from the seam and the heart drops in and
sticks, the headlines rise out of their lines, little cards float in, the statement lights up word by word as it
scrolls, the rep counter counts up with its bars, the light panel rises over the dark one, and the closing lines
meet from both sides.

All of it is optional. `assets/boot.js` turns it on only when JavaScript runs and the visitor hasn't asked for
reduced motion (`prefers-reduced-motion`); otherwise every element is simply there. `assets/site.js` uses one
`IntersectionObserver` for reveals and one `requestAnimationFrame` loop for the scroll effects.

## Running it locally

```bash
cd website && python3 -m http.server 8088   # then open http://127.0.0.1:8088
```

To try the deletion page against a local API, point `assets/config.js` at `http://localhost:5135` (don't commit it)
and start the API with `Frontend__Origin=http://127.0.0.1:8088`.

## Deploying

The Deploy workflow's `website` job publishes `website/` to Cloudflare Pages whenever it changes on `main`
(see `docs/deploy.md`). It writes `API_URL` into `assets/config.js` and into the CSP's `connect-src`, uploads, then
checks the live site serves this build with its headers, and warns if the API doesn't allow the site's origin.

1. Create the Pages project once: `npx wrangler pages project create heartpact-site --production-branch main`.
2. Add the custom domain (`heartpact.app`) and set the `SITE_URL` variable to it.
3. Add the site's origin to `Frontend__Origin` on the API (comma-separated, next to the dashboard's), or the
   deletion page can't sign in.
4. In Play Console, use `SITE_URL/privacy/` as the privacy policy and `SITE_URL/delete-account/` as the account
   deletion URL.

It uses the same `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` as the dashboard; `CLOUDFLARE_SITE_PROJECT`
names the project (default `heartpact-site`).

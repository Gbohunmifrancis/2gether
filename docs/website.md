# Marketing website

`website/` is heartpact.me: the home page that sends people to Google Play, the Privacy Policy, the Terms of Use,
the account deletion page Google Play requires, and Heartpact Plus by card (`/plus/`). It is plain HTML, CSS and a
little JavaScript, with no build step and nothing loaded from other sites (fonts and images are served from
`website/assets/`), except Google's sign-in script on the Plus page.

| Path | What |
|---|---|
| `index.html` | The home page (design direction C, "The Fold") |
| `privacy/`, `terms/` | Drafts written from what the code does. **Have a lawyer review them** before launch; every placeholder is filled in (`build.py fill` replaces one in every language). Keep them true to the code: when a feature or the admin console changes what is collected or who sees it, update them. The app opens them in the phone's language (`LegalPages` in `android/.../ui/components/Legal.kt`) from sign-up and from Us |
| `delete-account/` | Signs in with email and password and calls `POST /api/account/delete`, the same call the app makes. A renewing Plus subscription is shown first, as in the app. Google sign-in accounts are asked to email support |
| `plus/` | Heartpact Plus by card, through Bachs (below) |
| `plus/thanks/` | Where Bachs' checkout returns after paying: waits for the server to switch Plus on |
| `404.html` | Not found |
| `_headers` | Security headers, including a strict Content-Security-Policy (scripts, styles and fonts only from the site; API calls only to the API). `/plus/*` (and its translations) swaps in a policy that also allows Google Identity Services (`accounts.google.com/gsi/`: the script, its button frame, its calls and styles) |
| `assets/account.js` | Signing in with email and password or a Google ID token, shared by the deletion and Plus pages |
| `assets/config.js` | The API's address (for the deletion and Plus pages and the Christmas switch); the deploy job writes the real one |
| `assets/festive.js` | Turns on the Christmas look when the admin flag is on (below) |

The prices on the home page ($7.99 a month, $39.99 a year, with a 7-day free trial on the yearly plan) must match the
Plus base plans and the yearly plan's trial offer in Play Console, and the Bachs prices on `/plus/`; change them together. Why these prices:
`docs/research/pricing-2026.md`. The contact address throughout is `support@heartpact.me`.

## Heartpact Plus on the web

`/plus/` sells Plus by card through Bachs (bachs.io; the server side is in `docs/billing.md`). The home page's Plans
section links to it.

1. **Sign in** with email and password (`POST /api/auth/login`), or "Signed up with Google?": Google Identity Services
   (`accounts.google.com/gsi/client`, loaded by `assets/plus.js` only while the sign-in form shows, and only on this
   page) hands over an ID token for `POST /api/auth/google`. The web never creates an account: without a date of birth
   the server answers `422 auth.profile_required`, and the page asks the person to sign up in the app first. The
   token is kept in `sessionStorage` (`hp-session`) for this tab only, so the thanks page can use it; "Sign out" or
   closing the tab drops it.
2. **The plan** (`GET /api/billing/entitlement`, and `GET /api/couple` for the partner's name): already Plus says who
   it covers and when it renews; a Bachs subscription the person bought gets "Manage billing" (`POST
   /api/billing/bachs/portal`, then Bachs' customer portal), a Google Play one points to Google Play. On Free, with
   `webCheckout` true, the yearly (7 days free) and monthly cards `POST /api/billing/bachs/checkout {plan, from: "web"}`
   and go to the `checkoutUrl`; with it false the page says card payment isn't available yet. `billing.bachs_unavailable`,
   `billing.unknown_plan`, `billing.already_plus` and `billing.no_bachs_customer` each have their own sentence.
3. **`/plus/thanks/`**: Bachs returns here after paying (cancelling returns to `/plus/`). Arriving proves nothing:
   the server grants Plus from Bachs' webhook, so the page asks for the entitlement every 3 seconds for up to 90,
   then says Plus is on, or that it's still waiting (with "Check again"), or, without the tab's session, asks to sign in
   again.

The prices on the cards ($7.99 a month, $39.99 a year with 7 days free) are written in the page, like the home page's.

## Motion

The home page moves the way the reference video does: the two halves open from the seam and the heart drops in and
sticks, the headlines rise out of their lines, little cards float in, the statement lights up word by word as it
scrolls, the rep counter counts up with its bars, the light panel rises over the dark one, and the closing lines
meet from both sides.

All of it is optional. `assets/boot.js` turns it on only when JavaScript runs and the visitor hasn't asked for
reduced motion (`prefers-reduced-motion`); otherwise every element is simply there. `assets/site.js` uses one
`IntersectionObserver` for reveals and one `requestAnimationFrame` loop for the scroll effects.

## Christmas

While the admin dashboard's Christmas flag is on (`docs/feature-flags.md`), the site dresses up like the app: ivory
ground, cranberry and pine halves, fairy lights along the top of the header, a Santa hat on the logo, and light
snowfall. Every page loads `assets/config.js` and `assets/festive.js` in its head (the translated pages get them
from `i18n/build.py build`). `festive.js` calls `GET {API}/api/config` with a 2.5 second timeout, keeps the answer in
`sessionStorage` (`hp-christmas`) for the visit, and adds `class="christmas"` to `<html>` when it's `true`; the
`html.christmas` rules at the end of `assets/site.css` do the rest, without moving anything. A failed or slow call,
or no JavaScript, leaves the site as it always is. The snow only falls without `prefers-reduced-motion`. The call
needs nothing new in the CSP (`connect-src` already allows the API) but does need the site's origin in
`Frontend__Origin`, as the deletion page does. There is no public override: to preview, run
`sessionStorage.setItem("hp-christmas", "1")` in the browser console and reload (`"0"` or closing the tab undoes it).

## Running it locally

```bash
cd website && python3 -m http.server 8088   # then open http://localhost:8088
```

To try the deletion or Plus pages against a local API, point `assets/config.js` at `http://localhost:5135` (don't commit it)
and start the API with `Frontend__Origin=http://localhost:8088`.

## Deploying

The Deploy workflow's `website` job publishes `website/` to Cloudflare Pages whenever it changes on `main`
(see `docs/deploy.md`). It writes `API_URL` into `assets/config.js` and into the CSP's `connect-src`, uploads, then
checks the live site serves this build with its headers (and one policy allowing Google sign-in on `/plus/`), and warns
if the API doesn't allow the site's origin.

1. Create the Pages project once: `npx wrangler pages project create heartpact-site --production-branch main`.
2. Add the custom domain (`heartpact.me`) and set the `SITE_URL` variable to it.
3. Add the site's origin to `Frontend__Origin` on the API (comma-separated, next to the dashboard's), or the
   deletion and Plus pages can't sign in.
4. In Google Cloud (APIs & Services, Credentials), open the **web** OAuth client (the one in `GOOGLE_WEB_CLIENT_ID` and
   `Auth__GoogleClientIds`) and add `https://heartpact.me` (the `SITE_URL`) under **Authorized JavaScript origins**,
   or "Continue with Google" on `/plus/` fails. Add `http://localhost:8088` too to try it locally.
5. In Play Console, use `SITE_URL/privacy/` as the privacy policy and `SITE_URL/delete-account/` as the account
   deletion URL.

It uses the same `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` as the dashboard; `CLOUDFLARE_SITE_PROJECT`
names the project (default `heartpact-site`).

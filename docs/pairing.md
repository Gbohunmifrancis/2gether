# Signing up and linking

The first thing a new couple sees: making an account, then finding each other. The app's side is `android/app/src/main/java/app/heartpact/ui/auth` and `ui/pair`; the API's is `AuthController` and `CoupleController`.

## Signing up and in

- Sign up has two steps. Step 1 is the account: name, email and password (eight characters or more; the strength bar is only a hint). Step 2 is about you: date of birth (18 and over, checked on the phone and by the server's `AgeRules`), country and gender. Back from step 2 keeps everything typed. If the server answers `auth.email_exists`, the app goes back to step 1 and shows the reason by the email field.
- The country is filled in from the SIM's country, then the mobile network's, then the phone's language setting (`Countries.pick`), and can be changed. Nothing is looked up online.
- With Google, the first call to `POST api/auth/google` signs in an existing account. For a new account it answers `422 auth.profile_required`, and the app shows step 2 alone, plus the name only if Google sent none.
- Sign in offers "Continue with Google", then "or", then email and password. A wrong password always gets the same `401 auth.invalid_credentials`. Telling someone "this account uses Google" would reveal which emails have accounts, so the app adds a hint for everyone instead: "Signed up with Google? Use Continue with Google above."
- After a password sign-up or sign-in, Credential Manager offers to save the password. The app cancels autofill's own save prompt at the same moment, so the person is asked only once.

## Invites

| Call | Who | What |
|---|---|---|
| `POST api/couple/invites` | signed in, not linked | A new six-digit code and a 48-hex-character link token, valid for 48 hours. It revokes the previous code. Limited to 20 an hour per account, every call counting (`429 rate_limited`). |
| `POST api/couple/invites/accept {code?, token?}` | signed in | Links the two accounts. Wrong codes are limited (`docs/abuse-limits.md`); links are not. Sends `CoupleConnected {userId, displayName}` and a `coupleConnected` notification to the inviter. |
| `POST api/couple/invites/preview {token}` | anyone | `{inviterFirstName, expiresAtUtc}` for a usable link, otherwise `404 invite.invalid`. Only a link's token works here, never a code, and only the first word of the display name comes back. |

The phone keeps the last invite it made (DataStore, per account) and shows it again until its last minute. Opening the pair screen again doesn't revoke a code that was already sent. A new code is made only when the last one has expired, or when the person taps "Get a new code". Under an hour before it expires, the invite card counts down ("expires in 12 minutes").

## Ways to link

- **Code:** the partner types it into six boxes. The sixth digit sends it. When the boxes are focused or tapped, the clipboard is read once, and if it holds a code or an invite link the app offers "Paste 482 031". Android 13 and later show their own notice when an app reads the clipboard.
- **Share:** the message is one line about Heartpact, then the link on a line of its own (so WhatsApp makes it tappable in every language, Arabic too), then the code.
- **QR code:** the pair screen shows the invite link as a QR code, made on the phone with ZXing core. "Scan their code" reads a partner's QR code with CameraX and ZXing. Frames are read and let go, never stored. A scanned link joins exactly as an opened link does. A QR code that isn't a Heartpact invite gets a short note, and the scanner keeps looking.
- **Link:** `https://heartpact.me/invite/{token}` opens the app (the `autoVerify` intent filter in the manifest).
  - **Signed out:** the token is stored on disk (`SessionStore.savePendingInvite`) and kept through signing up or in, even if the app is closed meanwhile. Welcome, sign-up and sign-in show "Maya invited you". A first visit with a link skips the onboarding tour. Once signed in, the app goes straight to pairing and joins at once, showing "Joining Maya…".
  - **Signed in and not linked:** the pair screen asks "Join Maya?" first. This is also the case when the person had chosen "Connect later" (the pair screen opens over Home).
  - **Already linked:** the link is dropped.

## The moment you're linked

The person waiting with the code sees the partner arrive at once. The couple hub's `CoupleConnected` reaches the inviter's user group, and the `coupleConnected` push does the same when the hub is down. The poll every four seconds stays as the fallback. When the partner joins this way, or after the person's own code, link or scan goes through, the app shows the celebration. The two halves walk in from either side and become the whole heart. There is a soft haptic tap and the level-up chime at its quieter volume, through the app's one gate (`ClearedSound`). Then "You're linked with {name}", and the app moves on after about three seconds, or sooner on a tap. With animations off, the whole heart shows at once and the app moves on after a moment. On Home, "Connect later" users get the partner's arrival through the same event (`AppViewModel.checkLinked`).

## Not yet

- The app link needs `https://heartpact.me/.well-known/assetlinks.json` with the Play app-signing certificate's SHA-256 fingerprint before Android will verify it. The same file lets saved passwords be shared between the app and heartpact.me (the manifest's `asset_statements` already points at it). The website has no `/invite/` page yet. Until both exist, a link tapped on a phone without the app, or opened before verification, lands on the website's 404 page. The code in the share message always works. A phone's own camera scanning the QR code opens the same link.
- The code boxes take six digits. `Invites:CodeDigits` can go up to ten once they take more.

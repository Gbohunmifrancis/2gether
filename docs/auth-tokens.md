# Access tokens and couple changes

Access tokens are JWTs that last seven days (`Jwt:AccessTokenMinutes`). Each token carries the user id and a `couple_id` claim from the moment it was issued. A breakup, a new partner, or a deleted account all happen without issuing new tokens, so the server never trusts the claim on its own.

## REST

On every request, `JwtBearerEvents.OnTokenValidated` loads the user's current `CoupleId`. This is a single primary-key lookup.

- If the user no longer exists, the request fails with `401`.
- Otherwise the token's `couple_id` claim is replaced with the stored value. If the user is not in a couple, the claim is removed.

Every controller reads the couple through `ICurrentUser`, so they all see the current couple, never the one baked into the token:

- **After a breakup**, an old token reads no chat, games or album from the old couple, and cannot edit its messages.
- **After a new link**, the same old token works in the new couple.

## SignalR

A hub connection is authenticated once, when it connects, and keeps those claims and its groups for as long as it stays open. To cover that, `CoupleMembershipHubFilter` runs before every hub call:

- It compares the connection's couple with the stored one.
- If they differ, or the account is gone, it closes the connection and throws `HubException`.
- The client reconnects. That runs the REST check above, so it rejoins only the current couple's groups.

## Breakup side effects

A breakup also:
- ends the couple's open games (waiting, in progress or paused) as abandoned, so the round timer stops advancing them;
- sends `GameEnded` to the old couple group;
- sends `CoupleEnded` to both partners, including the other devices of the person who ended it.

## Password reset and Google sign-in

- `POST api/auth/password/forgot {email}` emails a six-digit code. The answer is `202 auth.reset_sent` whether or
  not the account exists, so it cannot be used to find accounts; at most 3 codes per address and 10 per network
  address an hour. 503 `auth.email_unavailable` when `Email` is not configured.
- Only the code's hash (bound to the user) is stored, in `password_reset_codes`. A code lasts 15 minutes, allows 5
  tries and works once; asking again retires the previous one.
- `POST api/auth/password/reset {email, code, newPassword}` sets the password, marks the email verified, and signs in.
  It sets `users.SessionsValidFromUtc`: `OnTokenValidated` rejects any token issued before it, and every open hub
  connection of that user is closed, so a reset ends every other session. Their location sharing stops with them, as
  it does for the admin console's sign-out everywhere and disable. Tokens carry whole seconds, so the cutoff is the
  reset's second; a wrong or expired code is `400 auth.invalid_reset_code`.
- `POST api/auth/google {idToken, dateOfBirth?, gender?, displayName?}` verifies a Google ID token (signature, expiry,
  audience in `Auth:GoogleClientIds`) and requires a Google-verified email. It signs in the account linked to that
  Google id, or links the account with the same email, or creates one. Creating needs a date of birth: without
  it the answer is `422 auth.profile_required` (with `email` and `name` from Google), and the app asks and calls
  again. A Google account different from the one already linked to the email is `409 auth.google_mismatch`.


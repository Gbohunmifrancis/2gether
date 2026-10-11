# Abuse limits

Sign-in, sign-up, invite codes and the account-deletion password check all limit **failures**, counted in memory per key over a sliding window. A correct password or code never counts toward any limit. When a budget is used up, the API answers `429` with a `Retry-After` header and the body `{ "code": "rate_limited" }`.

| What | Budget | Key |
|---|---|---|
| Wrong password | 10 per 15 min | email (unknown emails too, so the limit reveals nothing) |
| Wrong password | 30 per 15 min | client address |
| Sign-up | 10 per hour, every attempt counts | client address |
| Wrong invite code | 5 per 15 min | account |
| Wrong invite code | 20 per hour | client address |
| Wrong invite code | 200 per hour | everyone together |
| Wrong password when deleting the account | 5 per 15 min | account |

## Invites

Invite codes are short enough to guess, so the limits above apply to them, along with a few other defences:

- **Every request that carries a code counts as a code attempt,** even if it also carries a link token. Otherwise a junk token would let guesses through uncounted.
- **Invite links are never limited.** They carry 192 random bits, so they keep working for everyone even while codes are paused.
- **The global budget catches guessing spread across many accounts and addresses.** When it runs out, codes pause for up to an hour and an error is logged: "possible guessing attack".
- **Invites now last 48 hours** instead of 7 days. Fewer live codes means fewer to hit.
- **`Invites:CodeDigits`** (6 to 10, default 6) sets the code length. The current web screen accepts only six digits. Once the Android code field accepts eight, set it to 8: each extra digit makes guessing ten times harder. Codes may be typed with spaces or dashes.

## Settings

- **`ForwardedHeaders:Enabled`** must be `true` behind Railway or any other reverse proxy.
  - Without it, every request appears to come from the proxy, so all users share one per-address budget. Production logs a warning when it is off.
  - When it is on, the API trusts only the address that the nearest proxy appended.
  - Do not turn it on when the API is exposed directly, because clients could then spoof their address.
- **`RateLimits:Enabled`** defaults to `true`. Turn it off only for local load or smoke tests. Production refuses to start with it off.

## Known limits

- **Counts are kept per process.** With several API instances, each enforces its own budget. Move them to Postgres or Redis before scaling out.
- **An account's sign-in budget can be spent by someone else.** Ten wrong guesses lock its owner out for up to 15 minutes. That is the cost of stopping password guessing against a single account.

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

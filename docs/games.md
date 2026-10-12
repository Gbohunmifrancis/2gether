# Games: backend contract

A couple has at most one open game. One partner invites (`POST api/game/sessions {gameType, letter?}`), the other
accepts (`POST .../{id}/accept`), a shared five-second countdown runs, then moves go to
`POST .../{id}/actions {expectedStateVersion, action, value?, index?}`. Either partner can end a game, or decline or
cancel an invite, with `POST .../{id}/abandon`. `GET api/game/sessions` returns the couple's last 30 games, newest
first.

Every response and the couple hub's `GameStarted`, `GameStateChanged` and `GameCompleted` events carry the whole
session. `stateJson` is a JSON string of the public state (nothing hidden from the other player leaks into it);
`stateVersion` goes up by one on every change, and a move against an older version is `409 game.version_conflict`:
reload and try again. `GameInvitation`, `GameCountdown` and `GameEnded` say "refetch". The game hub (`/hubs/game`)
is not needed: REST plus the couple hub covers everything.

## Clocks

- **I call on** is played against the clock: 15 seconds to answer once one player has submitted, 20 to judge. The
  timeout worker closes rounds that run out, and a late move is treated as the timeout.
- **Same answer** (`coupleQuiz`, and the other quiz-style types) and **memory match** are turn by turn and have no
  turn deadline (`deadlineUtc` is null): a couple may take turns hours apart.
- An invite nobody answers ends after a day, and a turn-by-turn game left alone for a week ends, so a forgotten game
  never blocks a new one. Both send `GameEnded`.

## The games

- **Same answer**: 20 prompts. Whoever's turn it is answers; when both have, matching answers (trimmed, any case)
  give each partner a point. Cooperative: the score is how many you matched.
- **Memory match**: 12 cards, 6 pairs. A matching pair scores and the same player goes again; a miss passes the turn.
  After a miss, `lastPair` shows both cards (index and value) to both players until the next flip.
- **I call on**: 3 rounds of Name, Place, Thing and Food starting with a letter. The inviter may choose the first
  round's letter (`letter`); later rounds draw at random. Answers a checker can't confirm go to the partner to judge
  (`vet`). A unique answer scores 2, a shared one 1 each.

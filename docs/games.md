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

- **Same answer**: 10 prompts about the two of you ("What's our song?"), drawn from its own bank when the game is
  invited (`SameAnswerBank`). Whoever's turn it is answers; when both have, matching answers (trimmed, any case)
  give each partner a point. Cooperative: the score is how many you matched. Games started with trivia prompts
  before the bank existed carry on with them.
- **Guess my answer**: 8 rounds; the subject alternates, the first player first. The subject answers a question about
  themselves (`answer`, `index` 0–3), then the other guesses (`guess`, `index`). The answer is the subject's secret
  until the guess is in; `last` then shows both to both. A right guess is a point for the guesser.
- **Would you rather**: 10 rounds, both at once (`pick`, `index` 0 for `a`, 1 for `b`). `picked` says who has picked
  and `myPick` is your own; both picks show in `last` once both are in. Matching picks give each partner a point.
- **Memory match**: 12 cards, 6 pairs. A matching pair scores and the same player goes again; a miss passes the turn.
  After a miss, `lastPair` shows both cards (index and value) to both players until the next flip.
- **I call on**: 3 rounds of Name, Place, Thing and Food starting with a letter. The inviter may choose the first
  round's letter (`letter`); later rounds draw at random. Answers a checker can't confirm go to the partner to judge
  (`vet`). A unique answer scores 2, a shared one 1 each.
- **Draw and guess** (`handwriting`): 6 rounds, the drawer alternates (round 0: the first player). Each round's word
  comes from the setup (a global bank written with `Loc.N`, or a Naija one) and is the drawer's alone (`word` is null
  for everyone else until the round ends; then `lastRound` shows it, the outcome and the drawing to both). Both may
  move at once. The drawer sends `stroke` (`value` `{"points":[[x,y],...],"width":w}`: coordinates and width as
  fractions of the canvas, 1 to 200 points, width 0.001 to 0.1, nothing else; at most 250 strokes on the canvas),
  `undo`, `clear` and `pass`; the guesser sends `guess` (any case or accents, a leading "a"/"an"/"the" ignored, in
  English or their own language). A right guess gives both a point; three wrong ones or a pass end the round with
  none. Strokes and guesses cross, so the app queues its moves and resends one that gets `game.version_conflict`.

# Adding a game

## How a game works

- **Rules:** one class per game implementing `IGameRules` (`src/Twogether.Application/Features/Games/`), registered in
  `GameEngine.Registered`. `Start` makes the first state from the two player ids and the setup; `Apply` takes one
  move (`action`, optional `value` string and `index` int) and returns the next state, whose turn it is, an optional
  deadline, and whether the game is over. A move that isn't allowed throws `InvalidOperationException` with a
  `Loc.T("...")` message: the app shows it as is.
- **Turns:** by default only `CurrentTurnUserId` may move. A game where both answer at once sets `AnyoneMayAct`.
  A game with a clock sets `Timed`; when its deadline passes the server plays `GameEngine.TimeoutAction` for it (the
  timeout worker, or the next move that arrives late). Turn-by-turn games without a clock may be played hours apart.
- **Secrets:** `ToPublicState(state, viewer)` returns what that partner may see. The word to draw is the drawer's
  alone; an answer stays hidden from the partner until both are in. A null viewer sees no secrets. Updates go to each
  partner separately (`GameEvents`), each with their own view and in their own language, so content written with
  `Loc.T` reaches each partner translated.
- **Setup:** `GameSetupFactory` builds the setup when a game is invited: always the `region`, plus anything chosen
  or fetched up front (I call on's letter, trivia questions). The app sees only the region and the letter
  (`GameRegion.PublicSetup`); the rest stays on the server.
- **Scores:** `Summarise` gives each player's score and the rounds played; the result is kept for the couple's game
  history. A heart is a point.

## Nigerian couples

A couple plays the Nigerian versions when either partner's country is Nigeria (`GameRegion.For`, region `"ng"`;
everyone else is `"global"`). Nigerian versions swap content, not rules: Naija trivia (music, Nollywood, Super Eagles,
food, history, places, Pidgin), I call on with Nigerian names, places, foods and animals, memory cards with Naija
pictures, prompts that mention jollof, owambe, danfo and so on. Keep it warm and inclusive across regions and
ethnic groups; no politics, religion or tribal jokes.

## The line-up

| Game (`GameType`) | Who moves | What happens | Score |
|---|---|---|---|
| Same answer (`coupleQuiz`) | in turn, no clock | Both answer the same question about the two of them; answers are compared. | a heart each per match |
| Guess my answer (`guessMyAnswer`) | in turn, no clock | One answers a question about themselves from four choices, the other guesses. Swap each round. | the guesser, per right guess |
| Would you rather (`wouldYouRather`) | both at once | Both pick A or B; picks are revealed together. | a heart each per match |
| Trivia (`trivia`) | both at once | Ten multiple-choice questions; both answer, then the right answer shows. Global: Open Trivia DB. Nigeria: Naija trivia. | a point per right answer |
| Memory match (`memoryMatch`) | in turn | Twelve cards, six pairs. | a point per pair |
| I call on (`iCallOn`) | both at once, timed | A letter and four categories, 15 seconds; partners vet unknown words. | 2 unique, 1 shared |
| Truth or dare (`truthOrDare`) | in turn, no clock | Pick truth or dare, get a couple-safe prompt, the partner marks it done or skipped. | a point per done |
| Word dash (`wordDash`) | in turn, timed | Word chain: each word starts with the last letter of the one before, no repeats, 20 seconds a turn. A refused word may be retried while the time lasts. First to five. | the other partner, when time runs out |
| Draw and guess (`handwriting`) | both at once, no clock | One draws a secret word, the other has three guesses. Swap each round, six rounds. | a heart each, when guessed |
| Tic-tac-toe (`ticTacToe`) | in turn | Classic 3×3; best of five. | a point per game won |
| Connect four (`connectFour`) | in turn | 7×6, drop discs, four in a row. | a point per game won |

## Text

Every word a person reads goes through `Loc.T`/`Loc.Plural` on the server and `tr`/`trn` in the app
(docs/translation.md). Content banks (questions, prompts, words) are written in English with `Loc.N("...")` and shown
with `Loc.T(text)` in `ToPublicState`, so each partner reads them in their own language. Naija content is English and
Pidgin by design and isn't marked for translation.

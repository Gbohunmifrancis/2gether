# Games: backend contract

A couple has at most one open game (two invites at the same moment open one: the second gets `409
game.active_session`, and the app reloads to show the first). One partner invites (`POST api/game/sessions {gameType, letter?}`), the other
accepts (`POST .../{id}/accept`), a shared five-second countdown runs, then moves go to
`POST .../{id}/actions {expectedStateVersion, action, value?, index?}`. Either partner can end a game, or decline or
cancel an invite, with `POST .../{id}/abandon`. `GET api/game/sessions` returns the couple's last 30 games, newest
first. `GET api/game/region` says whose versions the couple plays (`{"region":"ng"}` or `"global"`); Nigerian couples'
game list also links to After Round 1 on Google Play. A game that finished in the last 15 minutes stays on screen with its final board until it's closed.

Every response and the couple hub's `GameStarted`, `GameStateChanged` and `GameCompleted` events carry the whole
session. `stateJson` is a JSON string of the public state (nothing hidden from the other player leaks into it);
`stateVersion` goes up by one on every change, and a move against an older version is `409 game.version_conflict`:
reload and try again (the app does that by itself for a pick in Same answer, Would you rather and Trivia that still
stands on the new version, and for I call on's answers and verdicts) (except in the games whose moves may cross,
Tic-tac-toe and Spot the difference: there a move is judged against the board as it is when it arrives, and `409` only
comes from two writes at the same instant). `GameInvitation`, `GameCountdown` and `GameEnded` say "refetch". An accept
or an end that crosses another change is `409` too (`game.not_waiting`, `game.version_conflict`), never an error.

Pushes: an invite tells the partner; an invite taken back sends nothing (it just leaves their screen); a declined
invite tells whoever sent it ("Not right now"); a game ended part way tells the partner. In a game played turn by turn
with no clock (Guess my answer, Memory match, Connect four, Snakes and ladders), the turn
passing to a partner who isn't in the app (no couple hub connection) sends one "Your turn" (`gameTurn`), and no
other until they've read it or six hours have passed. Every game push opens the games screen. The game hub (`/hubs/game`) is not needed: REST plus the couple hub
covers everything, including Tic-tac-toe's live ink (`Ink`, below).

The app's Today tab shows a card near the top while the couple's open game waits on you (`TodayGames.waiting`): the
partner's invite ("Theo invited you to play" the game, with Play and Not now, answered right there; Play then opens the
game for its countdown), your turn in a game played in turn without a clock (Guess my answer, Memory match, Connect four,
Snakes and ladders, Truth or dare: `currentTurnUserId` is you), or your move in one played at once without a clock where
the state says you haven't moved this round (Same answer, Would you rather, Trivia, Heart to heart). Games on a clock or
played live (I call on, Word dash, Spot the difference, Tic-tac-toe, Draw and guess) never show there. It follows the
same couple hub events as the games screen (no connection of its own), and reloads on resume and when the hub reconnects.

## Clocks

- **I call on** is played against the clock: 15 seconds to answer once one player has submitted, 20 to judge. The
  timeout worker closes rounds that run out, and a late move is treated as the timeout. Answers aren't lost to the
  clock: while a player types, the app reports what's typed to the couple hub (`ReportDraft(sessionId, round,
  answersJson)`, never stored), and when the round runs out those drafts count as that player's answers, partial or
  not; a `submit` that arrives just after the deadline counts the same way. The partner sees how far the other has
  got (`GameProgress` `{sessionId, userId, round, filled, total}`).
- **Spot the difference** gives each picture 90 seconds, then a 3-second beat before the next picture; the timeout
  worker plays both.
- **Same answer** (`coupleQuiz`, and the other quiz-style types), **memory match**, **Heart to heart** and **Snakes
  and ladders** have no turn deadline (`deadlineUtc` is null): a couple may take turns hours apart.
- **Tic-tac-toe** has no turns and no move clock. Two timers are the server's, both on its own clock (the time a
  move arrives, never the phone's): each pen's cooldown, and the 1.5-second beat between games, which is the
  session's `deadlineUtc` while a finished board is up (the timeout worker then starts the next game).
- An invite nobody answers ends after a day, and a turn-by-turn game left alone for a week ends, so a forgotten game
  never blocks a new one. Both send `GameEnded`.

## The games

- **Same answer**: 10 prompts about the two of you ("What's our song?"), drawn from its own bank when the game is
  invited (`SameAnswerBank`; every prompt is about "us", never "you" alone). Both answer at once (`answer`,
  `value` the words, up to 200 characters), once each. A prompt starting "Who" (`kind` `who`, else `text`) is
  answered by picking one of you: `value` is that partner's user id (an older app's words still count). Until both are
  in, each sees only who has answered (`answered`) and their own answer (`mine`); then `last` shows the prompt and
  both answers (`answers`, by player) to both. Answers match when they say the same thing: any case, accents,
  punctuation, spacing or a leading article ("the", "our", "la"...), or one letter apart in words of five letters or
  more (`SameAnswerRules.Same`). A match is a point each. Cooperative: the score is how many you matched. Games
  started with trivia prompts before the bank existed carry on with them.
  - **"That counts!"**: answers that didn't match but mean the same to you ("pizza", "pepperoni pizza") either
    partner may count as a match: `counts`, `index` the round (`round` in `last`/`history`), no `value`. Any round
    revealed and not matched may be counted, during the game or after it while the finished game is still on screen
    (`GameController.FinishedGameAmendWindow`, 15 minutes after it ends, and only until the couple's next game is
    invited; the session stays `completed` with the same end time and only its `stateVersion` goes up). It gives both
    the point a match gives, once: counting a round already counted changes nothing (two taps that cross make one
    point each), and it can't be undone. A round not yet revealed is refused ("You can count a question once you've
    both answered it."), so is one that matched on its own ("Those answers already match."). The round then reads
    `match: true` (older apps show its heart), with `accepted: true` and `acceptedBy` (who counted it); rounds from
    before read `accepted: false`. `Summarise` and the recap count it. The app offers it on the reveal of the last
    round and on each unmatched row of the finished game's recap; the stamp ("Counted!") and the hearts land on both
    phones from the `GameStateChanged`/`GameCompleted` that follows. A `409` sends it again while the round is still
    there to count.
- **Guess my answer**: 8 rounds; the subject alternates, the first player first. The subject answers a question about
  themselves (`answer`, `index` 0–3), then the other guesses (`guess`, `index`). The answer is the subject's secret
  until the guess is in; `last` then shows both to both. A right guess is a point for the guesser.
- **Would you rather**: 10 rounds, both at once (`pick`, `index` 0 for `a`, 1 for `b`). `picked` says who has picked
  and `myPick` is your own; both picks show in `last` once both are in. Matching picks give each partner a point.
- **Trivia**: 10 questions, both at once (`answer`, `index` 0-3). `answered` says who is in and `myAnswer` is your
  own; once both are, `reveal` shows the right answer and both picks. Global couples who both read English get Open
  Trivia DB (easy and medium only), topped up from the built-in bank; if either partner reads another language they
  get the built-in bank (`GlobalTrivia`, `Loc.N`), so each reads it in their own language. Nigerian couples get Naija trivia.
- **Recaps**: Same answer, Guess my answer, Would you rather and Trivia also keep `history`, every round played so
  far as `last`/`reveal` shows it, for the round-by-round recap under a finished game. Nothing in it is secret: a
  round enters it only once both are in. Games from before it was kept have an empty one.
- **Fresh content**: when one of those four is invited, the setup avoids the prompts and questions of the couple's
  last three games of the same kind while the bank has others (`FreshPick`), and never repeats one within a game.
- **Memory match**: 12 cards, 6 pairs. A matching pair scores and the same player goes again; a miss passes the turn.
  After either, `lastPair` shows both cards (index and value) to both players until the next flip: a match is
  celebrated on both phones, a miss stays face up.
- **I call on**: 3 rounds of Name, Place, Thing and Food starting with a letter. The inviter may choose the first
  round's letter (`letter`); later rounds draw at random. Answers a checker can't confirm go to the partner to judge
  (`vet`). A unique answer scores 2, a shared one 1 each. Categories travel as English keys (`Name`, `Place`, `Thing`,
  `Food`; `Animal` for Nigerian couples) in answers and verdicts; the app shows each as its own translated phrase
  (`GamePlay.categoryField`). Answers are compared without accents, case or extra spaces ("Éric" starts with E,
  "Zürich" and "zurich" are the same answer), kept to one line of 40 characters, and a later round never repeats a
  letter already played. The online checker confirms a name or a place by looking it up; a food or an animal only when
  the dictionary describes it as one (any real word is a thing), so "bicycle" for a food goes to the partner to judge.
  Verdicts from both partners cross all the time, so the app sends one that gets `game.version_conflict` again while
  that answer still waits for it.
- **Draw and guess** (`handwriting`): 6 rounds, the drawer alternates (round 0: the first player). Each round's word
  comes from the setup (a global bank written with `Loc.N`, or a Naija one) and is the drawer's alone (`word` is null
  for everyone else until the round ends; then `lastRound` shows it, the outcome and the drawing to both). Both may
  move at once. The drawer sends `stroke` (`value` `{"points":[[x,y],...],"width":w}`: coordinates and width as
  fractions of the canvas, 1 to 200 points, width 0.001 to 0.1, nothing else; at most 250 strokes on the canvas),
  `undo`, `clear` and `pass`; the guesser sends `guess` (any case or accents, a leading "a"/"an"/"the" ignored, a
  plural "s" or "es" either way, in English or their own language). A right guess gives both a point; three wrong
  ones or a pass end the round with none. Strokes and guesses cross, so the app queues its moves and resends one that
  gets `game.version_conflict`; with no connection it keeps them and tries again, checking first whether a move whose
  answer was lost had landed, so nothing is drawn or undone twice.
- **Tic-tac-toe** (`ticTacToe`), at once: a match of up to five games, first to three wins; the couple's first player
  (`players[0]`) is X, the other O. Both mark squares whenever they like (`AnyoneMayAct`; `place`, `index` the square
  0–8 row by row) and a square goes to whoever's move reaches the server first: a claim on a taken square is
  `422 game.invalid_action` ("That square is taken."), which the app shows as a "Too slow!" bounce, never as an error.
  After each of your own marks your pen refills for **650 ms** (`cooldownMs` in the state); the server refuses a mark
  less than 550 ms after your last (100 ms forgiven for network jitter: the phone waits the full 650 ms from sending),
  and a refused mark costs no cooldown. `pens` holds each player's newest square and when it landed. Moves may cross
  in flight, so a stale `expectedStateVersion` is fine; `value` names the game the move was made on,
  `{"game":n,"strokes":[[[x,y],...],...]}` (the hand-drawn mark: up to 3 strokes of 1 to 64 points, coordinates
  fractions of the square, nothing else; a tap sends a ready-made pen X or O), so a move that crossed the end of a
  game is refused (or, if it arrived as the late timeout, dropped) instead of landing on the next board. `ink` adds a
  stroke or two to your newest mark within 2 seconds of placing it (the second line of an X), up to 3 in all, with no
  cooldown. `marks` holds each square's strokes (null when empty, or for a square placed without strokes). Moves are
  played one at a time, so the first line made ends the game (two lines made by the same mark are both drawn in
  `line`); a full board is a draw. The finished board stays up as `gameOver` with `nextGameAtUtc` 1.5 s later; then
  the next game starts on a fresh board, by the timeout worker or by the first move for it (up to 250 ms early,
  since phone clocks differ). A move that arrives after the deadline is played as the timeout with its square and
  value, so it still lands on the fresh board when it was made for it. Turn-by-turn matches from before read the
  same (the state is a superset of the grid match's); one still open carries on under these rules.
  - **Live ink**: while a finger draws on a square, the app sends the couple hub
    `Ink(sessionId, cell, stroke, points)`: `cell` 0–8 (-1 clears the sender's ink), `stroke` a number the phone
    counts up per stroke, `points` up to 32 x,y pairs (64 numbers from 0 to 1, fractions of the square), only the
    points new since the last message, at most every 50 ms (20 a second). The hub relays it to the partner only, as
    `GameInk` `{sessionId, userId, cell, stroke, points}`, for the caller's own couple's Tic-tac-toe in progress after
    its countdown; anything else (another couple's game, one not in progress, another game type, a malformed message,
    more than 30 a second from one person) is dropped, and the call returns false. It is never stored and never in
    `stateJson`: the mark itself still goes in with `place`. The partner's phone shows the stroke in the drawer's
    colour and fades it 1.5 s after the last message, or swaps it for the mark when the square is taken. Without a
    hub connection everything still works; the stroke just isn't live.
- **Heart to heart** (`skillDuel`): five skill rounds (Heart skills, `ui/play`), the same for both: the setup draws a
  seed and from it five of the nine kinds (`FairShare`, `FillTheHeart`, `OtherHalf`, `Heartbeat`, `KissTheCenter`,
  `PromiseRing`, `CupidsAim`, `SlowKiss`, `DrawAHeart`, the app's `SkillKind` names, `SkillDuelRules.Kinds`), none
  twice, each with its own seed, so both get the same shape, flight or target. Duels always play each game at its
  first level, so two phones at different practice levels play the same round. An app older than a kind can't play
  that round (it shows it but can't open it), so a new kind goes out in the app before the server draws it. Both play at once, in any order, on their own time (no clock). The phone scores a round 0–100 and sends
  `score` (`index` the round 0–4, `value` `{"score": n}`, a whole number; each round once). Scores may cross in flight
  (`MovesMayCross`): each is one partner's own round, so a stale `expectedStateVersion` is fine. The score counts the
  moment it's made: leaving the round while its score counts up still sends it. Each round in `rounds`
  shows `kind`, `seed`, `mine`, and `played` (the partner has played it); the partner's score (`partner`), the
  round's `winner` and `tie` show only once you've played that round too. A round goes to the higher score, a tie is a
  heart each (`hearts`, counted over rounds both have played). The duel goes to whoever has more hearts. Once both
  have played all five, `totals` (points), `winner`, `decidedBy` (`hearts`; `points` when the hearts were level and
  the higher total broke the tie; null for a tie), `tie` (level on hearts and on points: `winner` is null),
  `margin` (the difference in hearts) and `pointMargin` (the difference in points) show. The hearts are the game's
  scores in the history, and a finished duel declares its winner, so a duel won on points with level hearts is
  still recorded as that partner's win; a tie declares none.
- **Spot the difference** (`spotTheDifference`): 3 pictures, 7 differences each, both partners on the same picture at
  the same time. The setup draws a seed and from it three different picture kinds (`Picnic`, `Bedroom`, `Kitchen`,
  `Beach`, `Garden`, `NightSky`, `Cafe`, `LivingRoom`, the app's `SceneKind` names), each with its own seed; the phones
  build both pictures and the differences from the kind and seed (`SpotScenes`, pure arithmetic, so both phones agree),
  and the server never sees a picture. A player who finds one sends `spot` (`index` 0–6, which difference; `value`
  `{"scene":n}`, the picture it was found on, so a spot that crossed the end of a picture is refused, "That picture is
  over.", instead of landing on the next; a phone that sends no value still plays). Spots may cross in flight: each is
  judged on arrival, whatever `expectedStateVersion` it names. The first claim scores; a later claim of the same one is refused ("Your partner spotted that one first."), and the app shows
  that kindly. A player's spots must be at least 300 ms apart (server time); wrong taps never reach the server (the
  app pauses tapping for 1.5 seconds). When all seven are found, or after 90 seconds (the rest are then revealed,
  `timedOut`), `phase` is `between` for 3 seconds (`deadlineUtc`), then the next picture starts; spots in between are
  refused. After the third picture, more spots wins (`winner`; `tie` when equal). The state shows `scene`, `kind`,
  `seed` and `found` (`{index, by}`) for the picture in play, `scenes` for every picture so far, and `scores`;
  each player's spots are their score in the history.
- **Snakes and ladders** (`snakesAndLadders`), in turn, no clock: a 10×10 board, 1 to 100, rows running left to right
  then right to left. Both tokens start off the board at 0. The server flips a coin at the start (`first`) for who
  rolls first; then `turn` (and the session's `currentTurnUserId`) says whose roll it is. The only move is `roll` (no
  `value` or `index`): the server rolls a die with its secure RNG and plays everything that follows, so the phone
  never decides an outcome.
  - **Moving:** the token hops one square per pip (from 0, a 1 lands on square 1). You must land on 100 exactly: an
    overshoot bounces back by the excess (97 and a 5: 98, 99, 100, 99, 98). Reaching 100 wins at once.
  - **Sixes:** a 6 rolls again (`extraTurn`); a third 6 in a row sends you back to the square the turn started on and
    passes the turn (`threeSixes`). A 6 that reaches 100 just wins.
  - **The landing square**, once (nothing chains): a ladder's foot climbs to its top; a snake's head slides to its
    tail, unless you carry a hug shield, which cancels that one snake and is used up (`shield`); a heart square:
    **Love boost** +3, **Heartbreak** back 5, **Hug shield** gives you a shield (one at a time; it shows on your token
    and in `shields`), **Kiss & swap** trades places with your partner if they're ahead (`swap`), and is just a kiss
    if you're ahead or level (`kiss`). That's the only catch-up in the game: no hidden rubber-banding. A swap and
    three sixes never set off the square you end up on; a bounce does (bouncing back onto a snake's head still slides).
  - **The board** comes from the setup's seed (`Layout`), so each game has its own: 7 to 9 ladders and 7 to 9 snakes
    of mixed lengths (at least a one-row and a three-row-or-more of each, a snake head in the last row, a ladder in
    the first two), and seven heart squares (two swaps, two boosts, two shields, a heartbreak). Every board is
    `Valid`: ladders go up and snakes down by a row or more; nothing starts or ends on 1 or 100; no two things share
    a square, so no ladder ends on a snake and no snake on a ladder (no chains, no loops); a boost or a heartbreak
    lands on a plain square. It must also be fair: the server plays 400 games of one player on it, and a board whose
    average falls outside 26 to 42 rolls to reach 100 is drawn again (a plain board takes about 34). The board travels
    in the state (`board`: `ladders` and `snakes` as `{from, to}`, `hearts` as `{square, kind}` with `kind` `swap`,
    `boost`, `shield` or `heartbreak`).
  - **`last`** tells the latest roll in full, so both phones replay the same animation:
    `{move, player, roll, from, steps, events, to, extraTurn, sixes}`. `move` counts every roll from 1 (the app plays
    a move once, and none it opened on); `steps` are the squares hopped one by one, bounce included; `events` are, in
    order, `{kind, from, to}` with `kind` one of `ladder`, `snake`, `shield` (a snake cancelled, `from` = `to` = its
    head), `hug` (a shield gained), `boost`, `heartbreak`, `swap` (you go to `to`, your partner to `from`), `kiss`,
    `bounce` (`from` 100), `threeSixes` (back to `to`) and `win`; `to` is where the token ends.
  - The state also has `positions`, `stats` per player (`rolls`, `ladders`, `snakes`, `sixes`, `swaps`, `saves`),
    `sixes` (in a row this turn), `moves` and `winner`. Nothing is secret. The history keeps each player's final
    square as their score, the rolls taken as the rounds, and the winner.

# Adding a game

## How a game works

- **Rules:** one class per game implementing `IGameRules` (`src/Twogether.Application/Features/Games/`), registered in
  `GameEngine.Registered`. `Start` makes the first state from the two player ids and the setup; `Apply` takes one
  move (`action`, optional `value` string and `index` int) and returns the next state, whose turn it is, an optional
  deadline, and whether the game is over. A move that isn't allowed throws `InvalidOperationException` with a
  `Loc.T("...")` message: the app shows it as is.
- **Turns:** by default only `CurrentTurnUserId` may move. A game where both answer at once sets `AnyoneMayAct`; one
  whose moves may cross in flight and be judged on arrival also sets `MovesMayCross` (a stale version isn't refused).
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
- **Older apps:** a game type the app doesn't know reads as `GameType.Unknown` (the session's default, so the list
  still loads): it shows "update the app" with the usual "End this game", and is never offered.

## Nigerian couples

A couple plays the Nigerian versions when either partner's country is Nigeria (`GameRegion.For`, region `"ng"`;
everyone else is `"global"`). Nigerian versions swap content, not rules: Naija trivia (music, Nollywood, Super Eagles,
food, history, places, Pidgin), I call on with Nigerian names, places, foods and animals, memory cards with Naija
pictures, prompts that mention jollof, owambe, danfo and so on. Keep it warm and inclusive across regions and
ethnic groups; no politics, religion or tribal jokes.

## The line-up

| Game (`GameType`) | Who moves | What happens | Score |
|---|---|---|---|
| Same answer (`coupleQuiz`) | both at once, no clock | Both answer the same question about the two of them, in a word or two or by picking one of you; both answers show, then the next. | a heart each per match |
| Guess my answer (`guessMyAnswer`) | in turn, no clock | One answers a question about themselves from four choices, the other guesses. Swap each round. | the guesser, per right guess |
| Would you rather (`wouldYouRather`) | both at once | Both pick A or B; picks are revealed together. | a heart each per match |
| Trivia (`trivia`) | both at once | Ten multiple-choice questions; both answer, then the right answer shows. Global: Open Trivia DB. Nigeria: Naija trivia. | a point per right answer |
| Memory match (`memoryMatch`) | in turn | Twelve cards, six pairs. | a point per pair |
| I call on (`iCallOn`) | both at once, timed | A letter and four categories, 15 seconds; partners vet unknown words. | 2 unique, 1 shared |
| Truth or dare (`truthOrDare`) | in turn, no clock | Pick truth or dare (`choose`), get a couple-safe prompt, the partner marks it done or skipped (`judge`). Nobody is pushed: the one with the prompt may `swap` it for another of the same kind once a turn, or `pass` on it (no point, `last.passed`), without asking. | a point per done |
| Word dash (`wordDash`) | in turn, timed | Word chain: each word starts with the last letter of the one before, no repeats, 20 seconds a turn. English words, A to Z: accents are dropped ("café" plays as "cafe") and hyphens closed up ("puff-puff"). A refused word may be retried while the time lasts. First to five. | the other partner, when time runs out |
| Draw and guess (`handwriting`) | both at once, no clock | One draws a secret word, the other has three guesses. Swap each round, six rounds. | a heart each, when guessed |
| Tic-tac-toe (`ticTacToe`) | both at once | 3×3, drawn by hand; first to a square gets it, a 650 ms pen cooldown, the next game 1.5 s after each one; best of five. | a point per game won |
| Connect four (`connectFour`) | in turn | 7×6, drop discs, four in a row. | a point per game won |
| Spot the difference (`spotTheDifference`) | both at once, timed | The same two pictures on both phones; tap a difference first to claim it. Three pictures, seven differences each, 90 seconds a picture. | a point per spot; most spots wins |
| Heart to heart (`skillDuel`) | both at once, no clock | The same five skill rounds for both (cut it even, pour to the line, draw the other half, tap the beat, kiss the center, draw a ring, shoot an arrow through a floating heart, hold a kiss for the time shown, draw a heart), each scored 0–100; scores show side by side once both have played a round. | a heart per round won (a tie a heart each); the higher total wins the duel |
| Snakes and ladders (`snakesAndLadders`) | in turn, no clock | Roll the server's die, climb ladders, slide down snakes, land on heart squares (kiss & swap, love boost, hug shield, heartbreak); a 6 rolls again, three 6s go back; land on 100 exactly. Each game has its own fair board. | first to 100 wins; each player's final square is kept |

## Text

Every word a person reads goes through `Loc.T`/`Loc.Plural` on the server and `tr`/`trn` in the app
(docs/translation.md). Content banks (questions, prompts, words) are written in English with `Loc.N("...")` and shown
with `Loc.T(text)` in `ToPublicState`, so each partner reads them in their own language. Naija content is English and
Pidgin by design and isn't marked for translation.

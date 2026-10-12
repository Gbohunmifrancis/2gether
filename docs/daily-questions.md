# Daily questions: backend contract

Route prefix `api/questions`, authorized, needs a linked couple (`400 questions.no_couple`).

- One question per couple per local day, created on first read (unique index, so simultaneous first reads share a row).
- The bank (`DailyQuestionCatalog`, 80 questions in nine categories, nothing explicit) is walked in a per-couple
  order derived from a hash of couple id and key. No repeats until the bank is exhausted; then the question used
  longest ago returns. Keys are permanent; retiring a question shows a placeholder on old days.
- **Answer to unlock**: the partner's answer is returned only after the caller has answered. Before that the caller
  only sees `partnerAnswered: true`.
- **Streak**: consecutive days on which both answered, ending today, or yesterday while today is still open.
- Dates are the caller's local day, accepted only within one day of UTC today, so nobody opens tomorrow early. Answers
  are accepted for questions dated from UTC yesterday onward (`409 questions.closed` otherwise), so streaks cannot be backfilled.

| Method | Route | Body | Returns |
|---|---|---|---|
| GET | `today?date=yyyy-MM-dd` | | `DailyQuestionDto` |
| PUT | `{questionId}/answer` | `{ body }` (1..1000 chars) | `DailyQuestionDto`; first answer notifies the partner, later calls edit in place |
| GET | `history?days=30&date=` | | `DailyQuestionHistoryDto` (days 1..90, newest first, reveal rule applied per day) |

Realtime: `DailyQuestionAnswered` (`{ dailyQuestionId, localDate, userId, bothAnswered }`) to the couple group on a
first answer. It carries no answer text. Notifications: `dailyQuestionAnswered` with "Your turn" or "You can both see your answers".

Couple flashcards are not a separate backend: the existing `GuessMyAnswer` and `CoupleQuiz` games already cover the
"how well do you know me" loop through the game engine and hubs.

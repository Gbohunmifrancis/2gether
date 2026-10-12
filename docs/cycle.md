# Cycle: backend contract

Only the partner who owns the cycle (`cycleOwner`, claimed with `POST api/cycle/claim`) logs it. The other partner
sees what the owner shares: `none`, `predictionsOnly` or `fullDetail` (predictions plus the logs).

## Predictions (`GET api/cycle/prediction?date=`, `GET api/cycle/shared?date=`)

A calendar estimate from the last period start and the owner's averages, in `Twogether.Core.Cycle.CyclePrediction`:

- An old last-period date rolls forward whole cycles, so the forecast always describes the cycle that contains today:
  `cycleStart`, `cycleDay` (day 1 is the first day of the period), `nextPeriodStart`, and `periodEnd` (the last day of
  the next period).
- Ovulation is taken as 14 days before the next period (the luteal phase varies little); the fertile window is the five
  days before ovulation and the day itself: `fertileWindowStart` to `fertileWindowEnd`.
- `date` is the caller's own calendar date (yyyy-MM-dd); within a day of UTC it is used, otherwise UTC today.
- During an active pregnancy the owner's predictions are null (see `docs/pregnancy.md`).

These are estimates for planning, not medical advice or contraception; the app says so.

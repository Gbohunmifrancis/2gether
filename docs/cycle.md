# Cycle: backend contract

Only the partner who owns the cycle (`cycleOwner`) logs it. A woman becomes the owner on her own at sign-up or sign-in
unless her partner already is; `POST api/cycle/claim` does the same by hand. The other partner sees what the owner
shares: `none`, `predictionsOnly` or `fullDetail` (predictions plus the logs).

## Sharing

- A new profile shares `none`. Claiming never changes a level that was already chosen; only `PUT api/cycle/profile`
  does, and the app asks for it at setup.
- The share level is chosen for one partner. A breakup, a new link, and the partner's account deletion all set it back
  to `none` (`CycleSharing.StopSharingAsync`), so a new partner never inherits it. Breakups from before that left
  the level as it was; on boot, `DatabaseSchemaUpgrade.StopCycleSharingAfterOldBreakupsSql` sets it to `none` for
  anyone not in an active couple whose profile hasn't been saved since a linked couple of theirs ended. A level chosen
  after the breakup, or by someone in an active couple, is left alone.
- At `fullDetail` the partner sees the last 120 logs dated on or after the day the couple linked, never earlier ones.
  Shared logs carry the date, flow, symptoms, mood and notes, not `hadSex` or `protectionUsed`.
- When claiming isn't possible because the partner owns the calendar, claim answers `409 cycle.unavailable` without
  saying why, and the app never offers it: whoever is shown nothing sees the same screen, whatever the reason.

## Predictions (`GET api/cycle/prediction?date=`, `GET api/cycle/shared?date=`)

A calendar estimate from the last period start and the owner's averages, in `Twogether.Core.Cycle`:

- The last period start comes from the logs (`CyclePeriods.LastStart`): light, medium or heavy flow after at least five
  days without any starts a period, and the profile's `lastPeriodStartDate` counts as a start too (the fallback when
  nothing is logged). A start less than half a cycle (and at least the period length plus five days) after the one
  before is the same period or mid-cycle bleeding, so the earlier start stands. Flow over 400 days old is not read.
  It comes back as `lastPeriodStart`. The averages are what the owner set; they are not learned from the logs.
- For an owner who logs flow (flow logged this cycle or the one before), a period that is due and not logged is late:
  the forecast stays on that cycle (`cycleDay` 29, 30…) and `daysLate` counts from the due day (0 on the day). After a
  whole cycle late, or for an owner who doesn't log, an old start rolls forward whole cycles, so the forecast describes
  the cycle that contains today. The app draws a rolled-forward period as predicted, not as one that happened.
  A partner shown predictions or everything gets the same `daysLate` as the owner and the app says so by name
  ("Maya's period is 3 days late"), on the cycle screen and the Us row; shown nothing, they get nothing. Lateness
  never sends the partner a notification.
- `cycleStart`, `cycleDay` (day 1 is the first day of the period), `nextPeriodStart`, and `periodEnd` (the last day of
  the next period).
- Ovulation is taken as 14 days before the next period (the luteal phase varies little); the fertile window is the five
  days before ovulation and the day itself, `fertileWindowStart` to `fertileWindowEnd`, never before `cycleStart`
  (short cycles).
- `date` is the caller's own calendar date (yyyy-MM-dd); within a day of UTC it is used, otherwise UTC today.
- During an active pregnancy the owner's predictions are null. The partner's shared predictions are null only when the
  pregnancy is shared with this couple; a private one keeps the old roll-forward and never shows `daysLate` (see
  `docs/pregnancy.md`).

## Validation

- `PUT api/cycle/profile`: cycle 15 to 60 days and period 1 to 14 (`cycle.invalid_profile`), a known share level
  (`cycle.invalid_profile`), and a last period start no later than today anywhere on Earth (UTC+14) and no more than a
  year back (`cycle.invalid_date`).
- `PUT api/cycle/logs/{date}`: the same date bounds (`cycle.invalid_date`), a known flow and mood (`cycle.invalid_log`),
  notes and symptoms within their limits. Two first saves of the same day that race end as one row: the loser updates it.

These are estimates for planning, not medical advice or contraception; the app says so on both partners' screens.

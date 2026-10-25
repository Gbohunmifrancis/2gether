# Pregnancy mode: backend contract

Route prefix `api/pregnancy`, authorized. Health data (GDPR Article 9): informational only, not medical advice.

## Ownership and visibility

- A pregnancy belongs to the person who is pregnant. Only they can change, end or delete it, or record kick sessions.
- Their partner sees it, its appointments and its kick sessions only while it is active, `shareWithPartner` is on
  (default on) and they are in the couple it was started in. Anything the caller may not see answers 404, so its
  existence never leaks.
- The partner can add appointments while it is shared, and edit or delete the ones they added. The owner can edit and
  delete any appointment. The app offers Delete only where the server allows it.
- At most one active pregnancy per person (filtered unique index). Ending stores no reason and stops sharing: an ended
  pregnancy can't be changed, so it could never be hidden again otherwise. The owner still sees it (`includeEnded`)
  and can delete it.
- Delete removes the pregnancy, its appointments and its kick sessions (database cascade). The app offers it on an
  active pregnancy and on ended ones, after a confirmation.
- While a pregnancy is active, the owner's period predictions return nulls. The partner's shared view of those
  predictions pauses only when the pregnancy is shared with that couple, so a private pregnancy is never hinted at.
- An account export holds the appointments of the person's own pregnancies, and the ones they added to a partner's
  only while they can still see that pregnancy. Deleting an account keeps the appointments the person added to
  someone else's pregnancy, with their text erased (title "Appointment", no place, no notes).

## Endpoints

| Method | Route | Body | Notes |
|---|---|---|---|
| GET | `?date=&includeEnded=false` | | active pregnancies I can see, with week, day, trimester, days to go, progress and next appointment; `includeEnded` adds my own ended ones |
| POST | `` | `{ dueDate?, lastPeriodStart?, shareWithPartner }` | one of the two dates; due = last period + 280 days; 409 `pregnancy.already_active` |
| PUT | `{id}` | `{ dueDate, shareWithPartner }` | owner only; an unchanged due date is not checked again, so a pregnancy past its due date can still be hidden |
| POST | `{id}/end` | | owner only; stops sharing |
| DELETE | `{id}` | | owner only, active or ended; deletes everything under it |
| GET/POST | `{id}/appointments` | `{ title, atUtc, location?, notes? }` | owner or sharing partner |
| PUT/DELETE | `appointments/{appointmentId}` | | owner, or the sharing partner for appointments they added |
| GET | `{id}/kicks?days=7` | | sessions, newest first |
| POST | `{id}/kicks` | | owner only; starts a session and closes any abandoned one |
| PUT | `kicks/{sessionId}` | `{ count, end }` | owner only; absolute count that only goes up, so retries are safe; sessions cap at 3 hours |

Validation codes: `pregnancy.invalid_due_date` (a new due date more than 43 weeks ahead or 3 weeks past),
`pregnancy.invalid_appointment`, `pregnancy.invalid_kicks`, `pregnancy.ended`, `pregnancy.session_closed`.

## Realtime and notifications

`PregnancyChanged` (`{ pregnancyId, change }`, change one of `started`, `updated`, `hidden`, `ended`, `deleted`,
`appointments`, `kicks`) goes to the owner and, when the partner can or could see it, to the partner. It carries no
health data; clients refetch.

Sharing a pregnancy also creates a `pregnancyUpdated` notification for the partner (data `{ pregnancyId }`). Hiding,
ending or deleting the pregnancy deletes that notification, so it leaves the partner's inbox and a push not sent yet
is never sent.

# Pregnancy mode: backend contract

Route prefix `api/pregnancy`, authorized. Health data (GDPR Article 9): informational only, not medical advice.

## Ownership and visibility

- A pregnancy belongs to the person who is pregnant. Only they can change, end or delete it, or record kick sessions.
- Their partner sees it, its appointments and its kick sessions only while `shareWithPartner` is on (default on)
  and the couple is still linked. Anything the caller may not see answers 404, so its existence never leaks.
- The partner can add, edit and delete appointments while it is shared.
- At most one active pregnancy per person (filtered unique index). Ending stores no reason.
- Delete removes the pregnancy, its appointments and its kick sessions (database cascade).
- While a pregnancy is active, the owner's period predictions return nulls. The partner's shared view of those
  predictions pauses only when the pregnancy is shared too, so a private pregnancy is never hinted at.

## Endpoints

| Method | Route | Body | Notes |
|---|---|---|---|
| GET | `?date=` | | active pregnancies I can see, with week, day, trimester, days to go, progress and next appointment |
| POST | `` | `{ dueDate?, lastPeriodStart?, shareWithPartner }` | one of the two dates; due = last period + 280 days; 409 `pregnancy.already_active` |
| PUT | `{id}` | `{ dueDate, shareWithPartner }` | owner only |
| POST | `{id}/end` | | owner only |
| DELETE | `{id}` | | owner only; deletes everything under it |
| GET/POST | `{id}/appointments` | `{ title, atUtc, location?, notes? }` | owner or sharing partner |
| PUT/DELETE | `appointments/{appointmentId}` | | owner or sharing partner |
| GET | `{id}/kicks?days=7` | | sessions, newest first |
| POST | `{id}/kicks` | | owner only; starts a session and closes any abandoned one |
| PUT | `kicks/{sessionId}` | `{ count, end }` | owner only; absolute count that only goes up, so retries are safe; sessions cap at 3 hours |

Validation codes: `pregnancy.invalid_due_date` (due date more than 43 weeks ahead or 3 weeks past),
`pregnancy.invalid_appointment`, `pregnancy.invalid_kicks`, `pregnancy.ended`, `pregnancy.session_closed`.

## Realtime

`PregnancyChanged` (`{ pregnancyId, change }`, change one of `started`, `updated`, `hidden`, `ended`, `deleted`,
`appointments`, `kicks`) goes to the owner and, when the partner can or could see it, to the partner. It carries no
health data; clients refetch. Sharing a pregnancy also creates a `pregnancyUpdated` notification for the partner.

# Food log and photo calorie estimates: backend contract

Route prefix `api/food`, all endpoints `[Authorize]`. `UserId` and `CoupleId` come from the token only.
Reads work for an unlinked user (they see their own data and no partner); writes need an active couple
(`400 food.no_couple`). Dates on the wire are `yyyy-MM-dd` in the user's own local day; the client picks the date.

## Data

- `food_entries`: one meal or snack per row, owned by `UserId`. `Calories` is what the user accepted. When the
  entry came from a scan it also carries `CaloriesLow`/`CaloriesHigh`, `Confidence` and `ScanId`; a hand edit that
  moves `Calories` outside the range drops the range. Check constraints keep calories non-negative and the range ordered.
- `nutrition_targets`: one row per user, always looked up by user id (the stored couple id is historical) (`DailyCalories` 800..6000, optional macro targets) plus `ShareLevel`
  (`none` / `totalsOnly` / `fullDetail`, default `totalsOnly`), which is what the partner may see.
- `food_scans`: audit row per photo estimate call. Never stores the photo; stores the model, token counts, latency,
  success or error code, and the accepted estimate as JSON. Drives the per-user daily quota, which comes from the couple's plan (Free 3, Plus 20 by default; see
  `docs/billing.md`), counted per UTC day, failed calls included because they were billed.
  The row is written as pending (`errorCode` `food.scan_pending`) before the photo is sent, with the count and the
  insert done in one step per person (a PostgreSQL advisory lock), so parallel requests cannot all pass the limit.
  Every scan that got that far counts for the day however it ends: success, a model error, `food.not_food`, an
  unexpected error (`food.scan_error`) or the phone giving up (`food.scan_cancelled`; a row left pending was abandoned).

Totals (`FoodTotalsDto`) also carry `caloriesLow` and `caloriesHigh`: each entry's range added up, with an entry that
has no range counted at its own calories at both ends. A partner who shares totals only therefore still shows an
honest range, without their meals.

## Endpoints

| Method | Route | Body | Returns | Notes |
|---|---|---|---|---|
| GET | `targets` | | `NutritionTargetDto` | defaults when nothing saved |
| PUT | `targets` | `UpsertNutritionTargetRequest` | `NutritionTargetDto` | 422 `food.invalid_target`, `food.invalid_macros`, `food.invalid_share_level` |
| GET | `day?date=` | | `FoodDayDto` | my entries, totals, remaining; `partner` filtered by their share level; `scansUsedToday` / `scanLimitPerDay` |
| POST | `entries` | `CreateFoodEntryRequest` | `FoodEntryDto` | optional `scanId` links a successful scan of mine; 422 `food.scan_not_found` otherwise. Optional `portionFactor` (0.25–3, scans only, else 422 `food.invalid_portion`) says the plate was smaller or larger than the photo: the scan's range scales with it (low rounded down, high up) before the accepted calories are checked against it |
| PUT | `entries/{id}` | `UpdateFoodEntryRequest` | `FoodEntryDto` | own entries only (404 otherwise) |
| DELETE | `entries/{id}` | | 204 | own entries only |
| GET | `history?days=14` | | `FoodHistoryDto` | per-day totals for me and, when shared, my partner; days clamped 1..90 |

A partner only ever sees entries logged while linked to the current couple. My own entries are always mine, whichever couple they were logged under.
| POST | `scans` | `FoodScanRequest` | `FoodScanResultDto` | see below |

`POST scans` order of checks: 503 `food.estimation_unavailable` when no API key is configured; 422 `food.image_type`,
`food.image_invalid`, `food.image_too_large` (default 5 MB, checked before decoding); 429 `food.scan_limit`; then the
model call. Failures map to 400 `food.estimation_failed` / `food.estimation_refused` / `food.estimation_unreadable`,
or 502 `food.estimation_busy` / `food.estimation_timeout` when it is worth retrying. 422 `food.not_food` when the
model says the photo is not food. Estimates are clamped server side and always satisfy
`caloriesLow <= calories <= caloriesHigh`; treat them as ±30 to 40 percent, never as medical guidance.

## Realtime

`FoodDayUpdated` (`FoodDayUpdatedDto`: `userId`, `localDate`, `totals`, `shareLevel`) goes to the couple group after
any entry change, unless the user's share level is `none`.

## Configuration

`Food:Model` (default `claude-opus-5-5`), `Food:AnthropicApiKey` or the `ANTHROPIC_API_KEY` environment variable,
`Food:MaxImageBytes`, `Food:TimeoutSeconds`. The scan limit is a plan limit (`Billing:Free:FoodScansPerDay`,
`Billing:Plus:FoodScansPerDay`). The estimator uses the official Anthropic C# SDK
with a schema-constrained JSON output (`FoodEstimateParser.JsonSchema`) at low effort. Its client is made by
`AnthropicClients`, as the AI features' is (`docs/ai.md`): the same key and model, and the SDK's retries with backoff
on 429 and 5xx.

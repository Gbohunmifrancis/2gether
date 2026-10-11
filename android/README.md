# Heartpact for Android

Kotlin and Jetpack Compose. The design source is `design/heartpact-v3` and `DESIGN.md`; the API is the .NET app in `src/`.

## Build

Needs JDK 17+ and the Android SDK (platform 36). Point Gradle at the SDK with `ANDROID_HOME` or `local.properties` (`sdk.dir=...`).

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests, API client tests, screenshot tests
./gradlew lintDebug
```

Screenshots of every screen and the splash frames: `./gradlew testDebugUnitTest -Proborazzi.test.record=true`, written to `app/build/screens/`.

Against a real API (each test registers two throwaway accounts and links them; start the API with `RateLimits__Enabled=false` so sign-ups are not throttled):

```bash
HEARTPACT_API_URL=http://localhost:5135/ ./gradlew testDebugUnitTest --tests '*LiveApiTest*'
```

The album test needs the API started with S3-compatible storage (`Media__ServiceUrl`, `Media__Bucket`, `Media__AccessKey`, `Media__SecretKey`, `Media__Region`); MinIO works, and so does `moto_server` for a quick local run. Without storage it is skipped.

## Configuration

Push (Firebase), release signing and the release API URL come from environment variables or `~/.gradle/gradle.properties`; see `docs/secrets.md`. CI is `.github/workflows/android.yml`.

`gradle.properties` sets the API base URL per build type: debug uses `http://10.0.2.2:5135/` (the host machine from the emulator), release `https://api.heartpact.app/`. Debug builds allow plain HTTP to the emulator host and localhost only.

## What is wired

- Walk-together splash (`ui/splash`): the halves walk in, meet and become the mark. A pure function of time, so frames are tested; tap to skip; with animations off it shows the last frame.
- Onboarding (three pages), welcome, sign up and sign in against `api/auth`.
- Pairing against `api/couple`: invite code and link, share and copy, enter a partner's code, accept an invite link (`https://heartpact.app/invite/{token}`), and moving on when the partner joins.
- The five-tab home shell: Today, Pacts, Food, Us and Album.
- **Dares** (`ui/today`, `alarm`, `exercise`, `ui/camera`):
  - Today shows my next or ringing dare, my partner's, the week of stickers, and a card when the phone isn't ready to ring (notifications, exact alarms, full-screen).
  - New dare: exercise, reps or seconds, daily time, a 10/20/30-minute window, sent to `POST api/exercise/goals` for the partner.
  - The alarm: the phone sets exact alarms at each of my goals' `nextDueAtUtc` (re-armed on every refresh, boot, clock or time zone change and update). When one fires, an insistent alarm-channel notification opens the full-screen alarm over the lock screen. It always offers "Silence on this phone"; the dare stays open on the server until its deadline. The alarm confirms the occurrence with the server and checks every few seconds, so a partner's call-off stops it.
  - Ask to call it off (`ask-off`), and on the partner's Today, "Call it off together" (`cancel`).
  - "Do it now": a dare due later today can be done early (`start-early`); it counts for the day and that day's alarm is cancelled.
  - Pacts (`ui/pacts`): every dare split by who it's for, with a 7-day sticker strip, the 30-day heart count, and stopping a dare you set.
  - Rep counting on the device: CameraX frames, MediaPipe's pose landmarker (`pose_landmarker_lite.task`, downloaded at build time into `app/src/main/assets`), and `exercise/RepCounter`, which turns each exercise into one signal with rest and active zones. Frames never leave the phone. The finish waits for the server's minimum time, then `start` and `complete` go to the API.
- **Food** (`ui/food`, `api/food`): today's calories as a range (one segment per meal, a dashed box from the low end to the high end, the daily aim beside it), the meals logged, and the partner's day as far as they share it (nothing, a range of totals, or every meal). "Snap a meal" opens the camera (or the photo picker); the photo is shrunk to 1600 px and sent once for an estimate, which comes back as a range with macros. Smaller / As shown / Larger scales the range (the server checks it with `portionFactor`), the meal is guessed from the clock, and one tap logs it. Meals can be logged, changed or deleted by hand, and the aim and sharing are set from the aim beside the bar. If the server has no estimator, or today's estimates are used up, it says so and offers logging by hand.
- **Us** (`ui/us`): the two of you and how long you've been together; today's question (`api/questions`: your partner's answer opens only once yours is in, with earlier days below); notification settings (categories and quiet hours, saved as you change them, with the phone's time zone; the dare alarm always rings); sign out, end the pact (`api/couple/breakup`) and delete the account.
  - Cycle (`ui/cycle`, `api/cycle`): the day of the cycle, a strip of the whole cycle (period, estimated fertile days, today, the predicted next period), today's flow, symptoms, mood and notes (saved as they change; flow after a dry stretch moves the last period start), and what the partner sees (nothing, predictions, everything). First use asks for the last period and usual lengths. The partner sees the shared prediction, and the logs only at "everything".
  - Pregnancy (`ui/pregnancy`, `api/pregnancy`): week and due date, the 40-week strip, sharing, appointments either partner can add, and kick counting (tap per kick, saved as it goes). Cycle predictions pause while it's on. Signing out, an expired session, an ended pact or a partner's breakup push all clear the phone's alarms.
- **Album, offline first** (`album`, `ui/album`), like a chat app's media:
  - "Add photos" opens the system photo picker. Each photo is copied into app-private storage with a small JPEG thumbnail (EXIF date and orientation kept) and badged with a phone: it is only on this phone, and the partner sees nothing yet.
  - Nothing uploads by itself. Tapping Sync opens a sheet: how many and how large, Wi-Fi or mobile data, and what the album holds afterwards. Sync now hands the job to WorkManager, which keeps it going with a progress notification after the app is left and resumes when the network returns.
  - The sync runs in batches of 50 (`POST sync`), PUTs bytes and thumbnails straight to the signed storage links on a client without the sign-in token, then confirms (`POST sync/complete`), marking the last batch `finished` so the partner hears once. Each photo carries its own id, so a retried batch never adds it twice. Refused photos (too large, album full) stay on the phone with the reason and a "Try again".
  - The shared grid pages through `api/memories`, badges each photo with the half of the heart that added it, and shows "On this day" from earlier years. If the server has no photo storage, photos still wait safely on the phone.
  - Once a day, if photos are waiting, a quiet notification says so and opens the Album. Signing out clears the waiting photos so they can never sync into another account's album.
- **Push** (`push/`): Firebase Cloud Messaging set up from build settings, not `google-services.json`. The token is registered with `PUT api/devices` on every start and on rotation, and unregistered on sign-out. Every server notification arrives as data: an `exerciseDue` push rings the alarm if the phone's own exact alarm didn't (for example after a lost alarm or a wrong clock); the album nudge waits for Wi-Fi; everything else shows on the `general` channel, or silently on `quiet` during quiet hours. Repeats are dropped by `notificationId`, and an open Today or Album refreshes at once. With push on, Today's safety-net check slows from 15 seconds to a minute.
- **Live** (`live/LiveHub`): the couple hub (SignalR) is open while the app is on screen or a location share runs, and reconnects with backoff. Its events refresh the screens they concern (dares, food, today's question, pregnancy, album, the pact ending) and carry the partner's live rep count onto Today while their dare rings; the camera reports each rep as it counts.
- **Location** (`location/`, `ui/map`): mutual and always visible, as `docs/location-sharing.md` requires. Sharing starts only when the person taps Share: a foreground service with a "Sharing your location" notification and a Stop button sends a fix about every 30 s or 25 m through the hub, with no background location permission and no history on the phone. Us shows a map card (distance and age, Pause or Resume); the map screen (mockup 13) offers 1 hour, until tomorrow, or stop. With `MAPS_API_KEY` it is a Google map; without, the app draws both of you by real distance and direction.
- Each partner's side of the heart comes from the API (`members[].side`), so colours stay fixed on both phones.

## Layout

- `data/`: Retrofit API, `ApiResult` (keeps the API's `{code, message}`), DataStore session, repositories, `AppContainer`.
- `ui/theme`: tokens from DESIGN.md (day and night), the Sora/Figtree ramp.
- `ui/graphics`: the geometric heart, stickers, the Kept Promise mark, and the two characters ported from `design/heartpact-v3/mascots.js`.
- `album/`: the local queue (`AlbumQueue`, an index file written atomically), `PhotoImporter`, `SyncEngine`, the sync and reminder workers, notifications.
- `ui/components`, `ui/onboarding`, `ui/auth`, `ui/pair`, `ui/home`, `ui/splash`, `ui/today`, `ui/pacts`, `ui/album`, `ui/AppNav.kt`.
- `tools/icon_paths.py` regenerates the launcher icon layers (the tick is cut into the geometry, since vector drawables have no masks).

## Not yet

Games; videos, captions and deleting shared photos in the album; password reset and Google sign-in (the API has neither yet).

The rep counter is tuned on synthetic poses; thresholds need checking on real phones and bodies before release. Release APKs are large (about 55 MB) because MediaPipe ships native code for every ABI: publish an App Bundle so Play delivers one ABI per phone.

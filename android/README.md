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

Against a real API (registers two throwaway accounts and links them):

```bash
HEARTPACT_API_URL=http://localhost:5135/ ./gradlew testDebugUnitTest --tests '*LiveApiTest*'
```

## Configuration

`gradle.properties` sets the API base URL per build type: debug uses `http://10.0.2.2:5135/` (the host machine from the emulator), release `https://api.heartpact.app/`. Debug builds allow plain HTTP to the emulator host and localhost only.

## What is wired

- Walk-together splash (`ui/splash`): the halves walk in, meet and become the mark. A pure function of time, so frames are tested; tap to skip; with animations off it shows the last frame.
- Onboarding (three pages), welcome, sign up and sign in against `api/auth`.
- Pairing against `api/couple`: invite code and link, share and copy, enter a partner's code, accept an invite link (`https://heartpact.app/invite/{token}`), and moving on when the partner joins.
- The five-tab home shell. Today is live; the other tabs arrive feature by feature.
- **Dares** (`ui/today`, `alarm`, `exercise`, `ui/camera`):
  - Today shows my next or ringing dare, my partner's, the week of stickers, and a card when the phone isn't ready to ring (notifications, exact alarms, full-screen).
  - New dare: exercise, reps or seconds, daily time, a 10/20/30-minute window, sent to `POST api/exercise/goals` for the partner.
  - The alarm: the phone sets exact alarms at each of my goals' `nextDueAtUtc` (re-armed on every refresh, boot, clock or time zone change and update). When one fires, an insistent alarm-channel notification opens the full-screen alarm over the lock screen. It always offers "Silence on this phone"; the dare stays open on the server until its deadline. The alarm confirms the occurrence with the server and checks every few seconds, so a partner's call-off stops it.
  - Ask to call it off (`ask-off`), and on the partner's Today, "Call it off together" (`cancel`).
  - Rep counting on the device: CameraX frames, MediaPipe's pose landmarker (`pose_landmarker_lite.task`, downloaded at build time into `app/src/main/assets`), and `exercise/RepCounter`, which turns each exercise into one signal with rest and active zones. Frames never leave the phone. The finish waits for the server's minimum time, then `start` and `complete` go to the API.
- Each partner's side of the heart comes from the API (`members[].side`), so colours stay fixed on both phones.

## Layout

- `data/`: Retrofit API, `ApiResult` (keeps the API's `{code, message}`), DataStore session, repositories, `AppContainer`.
- `ui/theme`: tokens from DESIGN.md (day and night), the Sora/Figtree ramp.
- `ui/graphics`: the geometric heart, stickers, the Kept Promise mark, and the two characters ported from `design/heartpact-v3/mascots.js`.
- `ui/components`, `ui/onboarding`, `ui/auth`, `ui/pair`, `ui/home`, `ui/splash`, `ui/AppNav.kt`.
- `tools/icon_paths.py` regenerates the launcher icon layers (the tick is cut into the geometry, since vector drawables have no masks).

## Not yet

The Pacts, Food, Us and Album tabs; offline photo sync (`docs/memories.md`); push (FCM: until it arrives, Today checks in every 15 seconds and the partner sees a call-off request when they open the app); SignalR live updates, including live rep progress for the partner; doing a dare before it rings (the API only starts an occurrence once it is ringing); password reset and Google sign-in (the API has neither yet).

The rep counter is tuned on synthetic poses; thresholds need checking on real phones and bodies before release. Release APKs are large (about 55 MB) because MediaPipe ships native code for every ABI: publish an App Bundle so Play delivers one ABI per phone.

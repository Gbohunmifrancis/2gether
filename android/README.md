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
- The five-tab home shell. Tab content arrives feature by feature.
- Each partner's side of the heart comes from the API (`members[].side`), so colours stay fixed on both phones.

## Layout

- `data/`: Retrofit API, `ApiResult` (keeps the API's `{code, message}`), DataStore session, repositories, `AppContainer`.
- `ui/theme`: tokens from DESIGN.md (day and night), the Sora/Figtree ramp.
- `ui/graphics`: the geometric heart, stickers, the Kept Promise mark, and the two characters ported from `design/heartpact-v3/mascots.js`.
- `ui/components`, `ui/onboarding`, `ui/auth`, `ui/pair`, `ui/home`, `ui/splash`, `ui/AppNav.kt`.
- `tools/icon_paths.py` regenerates the launcher icon layers (the tick is cut into the geometry, since vector drawables have no masks).

## Not yet

The Today, Pacts, Food, Us and Album features; the exercise alarm and camera rep counting; offline photo sync (`docs/memories.md`); push (FCM); SignalR live updates; password reset and Google sign-in (the API has neither yet).

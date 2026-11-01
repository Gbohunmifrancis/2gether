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

`gradle.properties` sets the API base URL per build type: debug uses `http://10.0.2.2:5135/` (the host machine from the emulator), release `https://api.heartpact.me/`. Debug builds allow plain HTTP to the emulator host and localhost only.

## What is wired

- 3D icons (`ui/graphics/Icon3D.kt`, made in `design/rive-icons`): the five tabs, and the feature rows on Us (today's question, cycle, Plus, games, pregnancy, notifications, location). The selected tab plays in Rive (`app.rive:rive-android`), popping when chosen and bobbing while it stays selected; the other tabs and the rows are stills (`res/drawable-nodpi/icon3d_*.webp`), so only one icon animates at a time. `ProvideRive` in `MainActivity` loads the file once on one Rive worker; under Robolectric, in previews and with animations off it isn't loaded and everything shows the stills.
- Walk-together splash (`ui/splash`): the halves walk in, meet and become the mark; the mark opens its eyes, its heart beats twice and it blinks. A pure function of time, so frames are tested; tap to skip; with animations off it shows the last frame, silently.
  - On a phone it plays in 3D from Rive (artboard `splash` in `res/raw/heartpact_icons.riv`, built in `design/rive-icons` on the same timeline): the two halves hop in with their faces, lean together, join, the tick draws on, the heart beats and blinks. The sound and clock start with Rive's first frame; if the file isn't loaded or drawn within 450 ms each (or in tests), the drawn splash plays instead.
  - Its sound (`res/raw/splash_love.ogg`, 11 KB) is made from scratch by `tools/splash_sound.py` on the same timeline: two soft boops as the halves meet, a music-box arpeggio as the tick cuts in, two heartbeats. It plays only when the ringer is on (not silent or vibrate), on the system sounds volume; a tap stops it. It plays in full the very first time it's heard on the phone, then at a quarter of that (`splashVolume`).
- Live logo (`ui/graphics/LiveLogo.kt`): the mark with a face, playing a routine of tricks by where it is: it drops in and bounces, double-bounces, pulls its halves apart and claps them together, dances, somersaults, and puffs out hearts. Tap it and it somersaults into a burst of hearts. Welcome: happy. Sign in: waves with a wink, and turns worried (shivers, gulps) after an error. Sign up and reset password: happy or hello, worried on errors. Pairing: glancing about, leaning, hopping on the spot. Tablet rail: breathing, a hop or a clap now and then, asleep (nodding off) from 11 pm to 6 am. The layout is the mark's own box; jumps and hearts draw beyond it. Still when animations are off, and in screenshot tests (`LocalStillLogo`); `GraphicsShotsTest` renders every mood and a frame-by-frame strip of the tricks.
- Google button (`ui/auth/GoogleButton.kt`): Google's own sign-in button style (the official "G" from Google's branding pack, `drawable-nodpi/google_g.png`), labelled "Continue with Google", "Sign up with Google" or "Sign in with Google" by screen.
- Onboarding (three pages), welcome, sign up and sign in against `api/auth`.
- Forgot password (an emailed six-digit code, then a new password; it signs out every other session) and "Continue with Google" through Credential Manager when the build has `GOOGLE_WEB_CLIENT_ID`; a new Google account is asked only for a birthday and gender.
- Pairing against `api/couple`: invite code and link, share and copy, enter a partner's code, accept an invite link (`https://heartpact.me/invite/{token}`), and moving on when the partner joins.
- The five-tab home shell: Today, Pacts, Food, Us and Album.
- **Dares** (`ui/today`, `alarm`, `exercise`, `ui/camera`):
  - Today shows my next or ringing dare, my partner's, the week of stickers, and a card when the phone isn't ready to ring (notifications, exact alarms, full-screen). The first time I have a dare and exact alarms are off (the default from Android 14), it explains why and offers the system's "Alarms & reminders" page, once. Today also saves the phone's time zone with the notification settings when it changed.
  - New dare: exercise, reps or seconds, daily time, a 10/20/30-minute window, sent to `POST api/exercise/goals` for the partner. The time is read in the partner's saved time zone; when that keeps a different clock, the screen says so and what time it is there.
  - The alarm: the phone sets exact alarms at each of my goals' `nextDueAtUtc` (re-armed on every refresh, boot, clock or time zone change and update). When one fires, an insistent alarm-channel notification opens the full-screen alarm over the lock screen. It always offers "Silence on this phone", on the screen too while it is still loading and on the camera (for a dare done early, from its due time); the dare stays open on the server until its deadline. The alarm confirms the occurrence with the server and checks every few seconds, so a partner's call-off stops it. A second dare ringing switches the screen to it (or, mid-workout, waits until this one is done), and the one left waits its turn. Without exact alarms, the server's `exerciseDue` push rings on time, also for a dare started early and left.
  - The finish: if saving fails (offline, the server busy), the counted reps are kept and it retries by itself, waiting a little longer each time, with "Try again" to send at once.
  - Ask to call it off (`ask-off`), and on the partner's Today, "Call it off together" (`cancel`).
  - "Do it now": a dare due later today can be done early (`start-early`); it counts for the day and that day's alarm is cancelled.
  - Pacts (`ui/pacts`): every dare split by who it's for, with a 7-day sticker strip, the 30-day heart count, and stopping a dare you set.
  - Rep counting on the device: CameraX frames, MediaPipe's pose landmarker (`pose_landmarker_lite.task`, downloaded at build time into `app/src/main/assets`), and `exercise/RepCounter`, which turns each exercise into one signal with rest and active zones. Frames never leave the phone. The finish waits for the server's minimum time, then `start` and `complete` go to the API.
- **Food** (`ui/food`, `api/food`): today's calories as a range (one segment per meal, a dashed box from the low end to the high end, the daily aim beside it), the meals logged, and the partner's day as far as they share it (nothing, a range of totals, or every meal). "Snap a meal" opens the camera (or the photo picker); the photo is shrunk to 1600 px and sent once for an estimate, which comes back as a range with macros. Smaller / As shown / Larger scales the range (the server checks it with `portionFactor`), the meal is guessed from the clock, and one tap logs it. Meals can be logged, changed or deleted by hand, and the aim and sharing are set from the aim beside the bar. If the server has no estimator, or today's estimates are used up, it says so and offers logging by hand.
- **Us** (`ui/us`): the two of you and how long you've been together; today's question (`api/questions`: your partner's answer opens only once yours is in, with earlier days below); notification settings (categories and quiet hours, saved as you change them, with the phone's time zone; the dare alarm always rings); sign out, end the pact (`api/couple/breakup`) and delete the account.
  - Cycle (`ui/cycle`, `api/cycle`): drawn like the cycle-ring period trackers people know. A dial of the cycle's days from day 1 at the top (counter-clockwise right to left): the period in period red, a predicted period dotted, the predicted fertile window in teal with ovulation (its last day) marked, today a knob with its day number; in the middle "Day 12" over "Period in 16 days", or "Period day 2", or "Period may be late". Under it the month calendar with the same marks (a predicted period outlined and dashed, today ringed), a legend that labels every prediction as one, and the owner taps a day to log it (`CyclePlan.ring`, `marks`, `month`, `dial`). Then flow, symptoms, mood and notes for today or any of the last 60 days (saved as they change; the server moves the last period start to logged flow, and a due period that isn't logged shows as late), and what the partner sees (nothing, predictions, everything). First use asks for the last period, usual lengths and sharing (nothing until chosen); "Cycle settings" changes them later. The partner sees the shared prediction, and the logs only at "everything", with the same "not contraception" line.
  - Pregnancy (`ui/pregnancy`, `api/pregnancy`): week and due date, the 40-week strip, sharing, appointments either partner can add (the partner deletes only their own), kick counting (tap per kick, saved as it goes), and deleting a pregnancy, also after it ended. Cycle predictions pause while it's on. Signing out, an expired session, an ended pact or a partner's breakup push all clear the phone's alarms.
- **Album, offline first** (`album`, `ui/album`), like a chat app's media:
  - "Add photos" opens the system photo picker. Each photo is copied into app-private storage with a small JPEG thumbnail (EXIF date and orientation kept) and badged with a phone: it is only on this phone, and the partner sees nothing yet.
  - Nothing uploads by itself. Tapping Sync opens a sheet: how many and how large, Wi-Fi or mobile data, and what the album holds afterwards. Sync now hands the job to WorkManager, which keeps it going with a progress notification after the app is left and resumes when the network returns.
  - The sync runs in batches of 50 (`POST sync`), PUTs bytes and thumbnails straight to the signed storage links on a client without the sign-in token, then confirms (`POST sync/complete`), marking the last batch `finished` so the partner hears once. Each photo carries its own id, so a retried batch never adds it twice. Refused photos (too large, album full) stay on the phone with the reason and a "Try again".
  - The shared grid pages through `api/memories`, badges each photo with the half of the heart that added it, and shows "On this day" from earlier years. If the server has no photo storage, photos still wait safely on the phone.
  - Once a day, if photos are waiting, a quiet notification says so and opens the Album. Signing out clears the waiting photos so they can never sync into another account's album.
- **Push** (`push/`): Firebase Cloud Messaging set up from build settings, not `google-services.json`. The token is registered with `PUT api/devices` on every start and on rotation, and unregistered on sign-out. Every server notification arrives as data: an `exerciseDue` push rings the alarm if the phone's own exact alarm didn't (for example after a lost alarm or a wrong clock); the album nudge waits for Wi-Fi; everything else is drawn by the app (`PushNotifier`) with actions done from the shade (reply and heart for a message, cheer for a dare done, let off or keep ringing for a call-off, play, watch live), the partner's picture in a chat-style conversation, grouping, on the `partner` channel for what the partner waits on, `general` for the rest, or silently on `quiet` during quiet hours (docs/push-notifications.md).
- **In-app banners** (`ui/components/InAppBanner.kt`, `push/InApp.kt`): what arrives while the app is open (a push in the foreground, the hub's `NotificationCreated`) drops in from the top on a spring with the Heartpact character or heart for the kind of notice, pulses once with a soft tap, opens its target when tapped, goes up when swiped up and by itself after 5 seconds; several queue and play in turn. Never over the splash, the alarm or the camera; switched-off categories never show; with animations off it only fades. Repeats are dropped by `notificationId`, and an open Today or Album refreshes at once. With push on, Today's safety-net check slows from 15 seconds to a minute.
- **Live** (`live/LiveHub`): the couple hub (SignalR) is open while the app is on screen or a location share runs, and reconnects with backoff. Its events refresh the screens they concern (dares, food, today's question, pregnancy, album, the pact ending) and carry the partner's live rep count onto Today while their dare rings; the camera reports each rep as it counts.
- **Location** (`location/`, `ui/map`): mutual and always visible, as `docs/location-sharing.md` requires. Sharing starts only when the person taps Share: a foreground service with a "Sharing your location" notification and a Stop button sends a fix about every 30 s or 25 m through the hub, with no background location permission and no history on the phone. It needs notifications on (the notification is how sharing stays visible; swiping it away brings it back), starts sharing with an explicit call, and sends a heartbeat every 4 minutes so the server's 10-minute lease doesn't lapse while the phone is still; while paused it turns location off and says "Paused until". Stop (the notification, the app, signing out) goes to `POST api/location/stop`, retried by WorkManager when offline; signing out stops this phone's service first, even offline. A share stopped elsewhere (signed out on another phone, a password reset, the admin console) stops this phone too: the hub's `LocationSharingChanged` with this person's `off`, or a dropped fix answered with it, makes the service check at once, and the heartbeat's `off` ends it and its notification. Us shows a map card (distance and age, Pause or Resume); the map screen (mockup 13) offers 1 hour, until tomorrow, or stop. The map is MapLibre Native on OpenFreeMap vector tiles (no API key), restyled at runtime in `ui/map` to a soft pastel Snap Map look with 3D buildings, pitched 55 degrees and slowly swaying (a navy map with glowing roads at night); each of you is your profile picture in a ring of your colour with a name pill, a pulsing halo while the fix is live and how old it is ("now", "2 min ago"), gliding between fixes. Fly-in to both of you, "frame both" and "center on me" buttons. If the style cannot load (no network and nothing cached), the app draws both of you by real distance and direction instead.
- **Play together** (`ui/games`, `docs/games.md`): invite your partner to Same answer, Memory match or I call on (with an optional first letter); they accept or decline from the invite, a shared countdown starts both phones, and every move goes through REST while the couple hub keeps both boards in step (a stale move is reloaded, never applied twice). Memory match shows both cards of a miss; I call on has the round timer and a judging step for answers the server can't confirm. The last result and recent games show on the picker.
  - How to play (`GameRules.kt`): every couple game has a short rules sheet (the goal, how a turn goes, winning, one tip, checked against the server's rules). It opens by itself the first time each person opens that game on the phone (while it waits to start, or once it's on in a game without a clock), and from the "?" in the game's top bar, or "How to play" under the picked game, any time.
  - The hub (`GamesHub.kt`) is two grids of wobbly pastel blobs, each game with its own line icon: "Play together" (live couple games, Heart to heart first) and "Heart skills" (solo practice). Picking one gives it a dashed pen ring and a bounce; under the grid are its line and Invite or Play with your best score.
  - Every game moves (`GameLife.kt`, `ui/play/PlayKit.kt`): Maya and Theo on the scoreboard hop and burst hearts when they score, memory cards turn over in 3D, Connect four discs drop and bounce, the pen strikes through a winning line, and every result is counted up, circled in pen pink and stamped ("You won!", "In sync!"). Haptics on every move; still frames when animations are off.
  - The sound of a level cleared or a game won (`level_up`, Snakes and ladders' chime at 100) plays at 40% of the others and through one gate for the whole app, so it never stacks (`ClearedSound`).
  - **Heart skills** (`ui/play/skills`): six tiny tests of precision, scored 0 to 100 with one honest note: Fair share (split a heart cookie, a chocolate bar or a pizza with one swipe), Fill the heart (pour to a line that hides), Other half (draw across the fold), Heartbeat (tap on beat eight in the quiet), Kiss the center and Promise ring (one-stroke circle). Best scores stay on the phone.
  - **Heart to heart** (`SkillDuelBoard.kt`, `skillDuel`): the same five skill rounds for both partners, each on their own time; a round's two scores reveal side by side, stamped and with a heart for the winner, once both have played it. More hearts win the duel; level on hearts, the higher total of points breaks the tie, and the result says so only then ("Level on hearts, won on points").
  - **Spot the difference** (`SpotTheDifferenceBoard.kt`, `spotTheDifference`): three pictures, seven differences each, both partners hunting at once. The pictures are drawn on the phone from the server's kind and seed (`SpotScenes.kt` builds them with pure arithmetic so both phones agree; `SpotArt.kt` draws the sticker props and rooms): a picnic, a bedroom, a kitchen, a beach, a garden, rooftops at night, a café table and a living room. The first to tap a difference gets a pen ring in their colour with their initial on both pictures; a wrong tap is an x and a 1.5-second pause.
  - **Snakes and ladders** (`SnakesAndLaddersBoard.kt`, `snakesAndLadders`): a sticker board of pastel rows (`SnakesArt.kt`) with wooden ladders tied with a pink bow, friendly swaying pastel snakes with faces, heart squares (kiss & swap, love boost, hug shield, heartbreak), and Maya and Theo as the tokens (sharing a square, their halves make one heart). The die is a real 3D cube with a heart for the 1: tap it (or hold to shake, and let go) and it tumbles at once, landing on the server's number when the answer comes. Every roll replays from the state's `last` on both phones, once (`SnakesReplay.kt`, pure): hops square by square with a tick, a climb rung by rung, a slide down the snake's curve while it gulps and laughs, tokens flying past each other in a swap, "Oh no!" and "Up the ladder!" bubbles, hearts at 100. Soft sounds from `tools/snakes_sounds.py` (ringer on only); with animations off the board just shows where everyone is.
- **AI features** (`ui/ai`, `data/AiModels.kt`, `docs/ai.md`), each shown only when `GET api/ai/status` says the server has it on:
  - New dare: "Suggest a dare" asks for three ideas for the partner; tapping one fills the exercise, target and time, all still editable.
  - The coach's note: under Heart complete, and on the partner's done card on Today ("The coach's note to Theo"), with soft
    shimmer lines while it is written; it hides itself when there is none. `CoachLoader` asks again while another phone is writing it.
  - Us: "Your week together", this week's recap card at the top (Sunday evening; the `weeklyRecap` push opens Us and
    refreshes it), with a full view of the recap and its numbers; and "Plan something": mood, budget, in or out and an
    optional city, then three date ideas and two games (a tap opens the games).
- Each partner's side of the heart comes from the API (`members[].side`), so colours stay fixed on both phones.

## Layout

- `data/`: Retrofit API, `ApiResult` (keeps the API's `{code, message}`), DataStore session, repositories, `AppContainer`.
- `ui/theme`: tokens from DESIGN.md (day and night), the Sora/Figtree ramp.
- `ui/graphics`: the geometric heart, stickers, the Kept Promise mark, and the two characters ported from `design/heartpact-v3/mascots.js`.
- `album/`: the local queue (`AlbumQueue`, an index file written atomically), `PhotoImporter`, `SyncEngine`, the sync and reminder workers, notifications.
- `ui/components`, `ui/onboarding`, `ui/auth`, `ui/pair`, `ui/home`, `ui/splash`, `ui/today`, `ui/pacts`, `ui/album`, `ui/AppNav.kt`.
- `tools/icon_paths.py` regenerates the launcher icon layers (the tick is cut into the geometry, since vector drawables have no masks).

## Tablets

The app adapts to the width of its window (`ui/components/Adaptive.kt`), using Material's window size classes:

- **Phones (under 600 dp):** the bottom navigation bar, one column.
- **Tablets, phones on their side and split screen (600 dp and up):** a navigation rail down the start side instead (the
  right side in Arabic). Single-column screens keep a readable width (640 dp), centred on the page (`ReadableScreen`,
  `Modifier.readable()`). Whatever paints the screen's background still reaches both edges.
- **Room for two panes (840 dp of content and up, tablets lying down):**
  - Today, Food and Us split into two panes (`AdaptivePage`): what matters now on the left, the rest on the right. On
    a phone the same sections come in the same order, one under the other.
  - Pacts and the list of games lay their cards out in columns (`CardColumns`), and the album grid fits as many photos
    across as there's room for.
- **The map and the food scan:** the map or photo goes edge to edge, with the sheet at a readable width over it.
- **The camera:** it has its own side-by-side layout when the window is wider than tall.

`StoreShotsTest` renders the screens on 7-inch and 10-inch tablets, upright and lying down (`build/screens/store-tablet*`).

## Not yet

The rep counter is tuned on synthetic poses; thresholds need checking on real phones and bodies before release. Release APKs are large (about 55 MB) because MediaPipe ships native code for every ABI: publish an App Bundle so Play delivers one ABI per phone.

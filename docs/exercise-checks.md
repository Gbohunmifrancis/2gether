# Form and cheat checks on camera

The camera counts reps on the phone with MediaPipe Pose Landmarker Lite (open source, Apache 2.0, 5.6 MB, no
frames leave the phone). On top of the count, the app judges each rep's form and watches for cheating. What it
notices goes to the server with the finish, and the partner sees it on Today and in the "Goal done" notification.

The checks are informational. A rep with bad form doesn't count, but nothing here stops a finished dare from
counting: the partner decides what to make of a flag.

## Measuring: in space, smoothed, fitted to the person

- **In space.** MediaPipe gives each pose twice: in the picture (for drawing and framing) and as world landmarks, in
  metres around the hips. Every angle, length and slope is measured on the world landmarks when there are some
  (`Pose.at`), so a knee bends as far seen from the front or at 45 degrees as side-on; picture-only poses fall back to
  the old measurements. What the fixed camera sees move (the hips sinking in a squat or a lunge) is still read in the
  picture.
- **Smoothed.** Each joint goes through a One Euro filter on frame time (`exercise/Smoothing.kt`) before anything is
  measured or drawn: still, the model's jitter disappears; moving fast, it hardly lags, so the bottom of a quick rep
  isn't shaved off. After a gap of 0.6 s it starts afresh.
- **Fitted to the person.** While the coach holds someone in the start position (the ready hold and the countdown),
  it reads their own start value (the median of the frames): standing knee angle, top elbow angle, lying hip angle,
  and so on. At "Go!" the counter's rest edge moves to just inside it and the active edge a set depth beyond, each kept
  within bounds (`Zones`): soft knees that never read straight can still finish a squat, and very straight legs never
  make a quarter squat count. The start itself is accepted from the loosest rest edge the bounds allow.

## Getting set: the voice coach

Nothing counts until the person is in the exercise's start position (`exercise/PoseCoach.kt`, spoken by
`ui/camera/VoiceCoach.kt`). The coach works through, in order: someone in the picture, the whole body in view (the joints
the counter needs), turned the right way, and the start pose. Measured across the picture alone, a front-on squat's
knees come toward the camera and barely bend on screen, so front-on squats never reached the counting zone; measured
in space they do, so squats, lunges and the wall sit may face any way (side-on still when a pose has no world
landmarks). Push-ups, planks, sit-ups, glute bridges, mountain climbers and burpees are side-on; arm raises, jumping
jacks, high knees and the side plank face the camera. "Turned", in space: the share of the line between the shoulders
that lies across the picture (side-on at most 0.71, about 45 degrees; facing at least 0.5, with the trunk across the
picture too, so a side plank lying head-first toward the phone doesn't pass); across the picture: shoulder width over
torso length, side-on at most 0.5, facing at least 0.35.

Each instruction is shown in the middle of the picture and said out loud once it has held for 0.7 s, then again every
6 s while it isn't followed. Held in position for 0.6 s: "Perfect. Hold it there.", then "3, 2, 1, Go!". Leaving the
position for more than 0.7 s during the countdown goes back to getting set. Only after "Go!" does the rep counter see
frames, so check-ins and the minimum time start from a real start. While counting it says each rep (every fifth of
high knees and mountain climbers, which a voice can't keep up with; a hold every ten seconds), each new form tip, "Done! Well done." at the target, and asks the person back if they are out of the picture
for 2.5 s.

The voice is the phone's own text-to-speech, in the app's language; with no voice for that language it stays quiet and
the words stay on screen. The speaker button turns it off and on, remembered on the phone. The manifest declares the
`TTS_SERVICE` query, without which Android 11+ hides the speech engine from the app.

## Form: reps that don't count

`android/.../exercise/RepCounter.kt` and `Form.kt`. Each fault leaves a tip in the sheet for three seconds.

| Fault | When | Tip (squat, for example) |
|---|---|---|
| Shallow | went most of the way (45 percent toward the bottom) and came back | "Go lower: hips down toward knee height." |
| Not all the way | started the next rep without getting back to the start | "Stand all the way up between squats." |
| Posture | squat: trunk leaning more than 60 degrees from upright at the bottom. Push-up: shoulder, hip and ankle out of line by more than 30 degrees | "Keep your chest up as you go down." |
| Too fast | one rep quicker than 60 percent of the exercise's minimum pace (the server's `MinSecondsPerUnit`) | "Slow down: that one was too quick to count." |
| Plank | the line from shoulders to ankles breaks after at least a second held: the clock stops and the tip says which way to move the hips | "Lift your hips into a straight line." |
| Position | the body isn't where the exercise puts it, so the frame moves nothing (see below); a tip only, not a fault count | "Get down into a push-up: body level, hands under your shoulders." |

Every exercise has its own tip for each fault. Posture is only judged when the camera can see the joints it needs.

Moving one joint can fake the signal, so the counter also checks the body's position. The limits are loose on
purpose, for real bodies and camera angles; they only rule out the cheats:

| Exercise | Required | Rules out |
|---|---|---|
| Squat | at the bottom, the hips sink at least 0.1 leg lengths below where they were standing (a real squat to 105 degrees sinks about 0.2) | sitting in a chair swinging a leg; standing on one leg bending the other back |
| Push-up | the shoulder-to-feet line slopes 50 degrees or less (to the hips when the feet are out of view); at the top, the hands are below the shoulders by half an arm or more | standing side-on bending the elbows; lying flat, face down or on the back |
| Plank | as before, plus an elbow or hand below the shoulder by half an upper arm or more | lying flat, face down or on the back (and with it a glute bridge for the hips-up check-in) |
| Sit-up | the knee no more than half a thigh below the hip | standing forward bends |
| Lunge | at the bottom both knees bend (the straighter one is the signal), the feet are 0.45 leg lengths apart along the floor, and the hips sink 0.1 leg lengths | a squat with the feet side by side; standing on one leg swinging the other back |
| Glute bridge | lying: the trunk 50 degrees or more from upright, the shoulders not raised above the hips by more than a third of the trunk, the knees bent to 135 degrees or less | a standing hip hinge; lying flat with straight legs |
| Mountain climbers | a high plank: the trunk within 40 degrees of level, the hands under the shoulders by half an arm; each leg counted on its own, so legs crossing mid-air both count | high knees standing up |
| Burpee | a rep counts only if the bottom reaches a plank (shoulders to ankles straight to 150 degrees, within 35 of level), and the top is standing with straight knees | squat thrusts without kicking back ("Kick your feet back into a straight plank.") |
| Wall sit | the knees 60 to 125 degrees, the hips within half a thigh above or a third below the knees, the back within 30 degrees of upright | standing. A chair looks the same to the camera; the check-ins and the partner are what's left |
| Side plank | shoulders, hips and ankles straight to 155 degrees, sloping 10 to 45 degrees from level, shoulders stacked, the lower forearm or hand under the lower shoulder | lying on the side; hips on the floor ("Lift your hips into a straight line.") |

## Cheating

| Check | How | Where |
|---|---|---|
| Surprise check-ins | at a random moment in the middle of the set (one for a set expected under 40 seconds, two otherwise, never in the first 5 seconds) the screen asks for the left hand or the right, picked at random: that wrist above its shoulder for a second, with the other hand not up (a clip of arm raises lifts both). A recording, playing or paused, can't follow a cue it didn't know. On the front camera the picture is mirrored before detection, which swaps the model's left and right, so the check swaps them back. Plank and mountain climbers, where the hands hold the body up, ask for hips up and back into line; the side plank for a hip dip (down toward the floor and back into line). Six seconds to do it; out of frame counts as missed. No new check-in opens once the target is reached | `exercise/Checks.kt`, `ChallengeRunner` |
| Phone moving | the gyroscope (or linear accelerometer) while the camera counts. A rep counted while a quarter or more of the readings show the phone clearly moving is reported; a single nudge isn't | `ui/camera/PhoneMotion.kt`, `MotionLog` |
| Out of frame | seconds out of view after first appearing | `SessionChecks` |
| Genuine app | Google Play Integrity: the app asks Google for a token bound to this occurrence, the count and the six check numbers (`SHA-256("heartpact:exercise:<id>:<count>:<formFaults>:<fastReps>:<passed>:<missed>:<phoneMoved>:<outOfFrame>")`), so a genuine token can't carry made-up numbers; the server has Google decode it and requires the request hash, package, a token under 10 minutes old, `PLAY_RECOGNIZED`, and `MEETS_DEVICE_INTEGRITY` (or strong) | `data/PlayIntegrity.kt`; server `PlayIntegrityRules`, `PlayIntegrityVerifier` |

## Watching live

The partner can watch a dare on camera as a stick figure (docs/couple-goals.md, Watching live): only the 13 joints,
the count, the phase and a cue code leave the phone, and the server relays them without storing them. It is not a
check: the checks above are what goes with the finish.

## What the partner sees

The server stores the counts on the occurrence (`FormFaults`, `FastReps`, `ChallengesPassed`, `ChallengesMissed`,
`PhoneMovedReps`, `OutOfFrameSeconds`, `Integrity`) and returns them as `checks` with a list of `flags`:

| Flag | When |
|---|---|
| `missedCheckIn` | any check-in missed |
| `phoneMoved` | 2 or more reps counted with the phone moving |
| `leftFrame` | 20 seconds or more out of view |
| `unverifiedApp` | Play Integrity failed, or is set up and the app sent no token |

With a flag, the partner's notification says "Goal done, worth a look" and lists them, and Today shows
"Worth a look: …". Without one, Today says how many reps didn't count, or that every check-in passed.

## Setting up Play Integrity

1. Play Console → the app → App integrity → Play Integrity API: link a Google Cloud project, and enable the
   Play Integrity API in that project.
2. App build: set `PLAY_INTEGRITY_PROJECT_NUMBER` to that project's **number** (Cloud console → project
   settings). Without it the app sends no token.
3. API host: `Integrity__Provider=playIntegrity`. It uses `Billing__GooglePlayPackageName` and
   `Billing__GooglePlayCredentialsJson` unless `Integrity__PackageName` / `Integrity__CredentialsJson` are set. The
   service account must belong to the linked Cloud project.

Turn the server side on only once a build with the project number is out: until then every finish would be
`missing` and flagged. `Integrity__Provider=fake` (development and CI only, refused in Production) passes the
token `fake.pass` and fails any other. Google being unreachable records `notChecked`, never a flag.

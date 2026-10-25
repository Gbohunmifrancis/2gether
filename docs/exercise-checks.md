# Form and cheat checks on camera

The camera counts reps on the phone with MediaPipe Pose Landmarker Lite (open source, Apache 2.0, 5.6 MB, no
frames leave the phone). On top of the count, the app judges each rep's form and watches for cheating. What it
notices goes to the server with the finish, and the partner sees it on Today and in the "Goal done" notification.

The checks are informational. A rep with bad form doesn't count, but nothing here stops a finished dare from
counting: the partner decides what to make of a flag.

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

## Cheating

| Check | How | Where |
|---|---|---|
| Surprise check-ins | at a random moment in the middle of the set (one for a set expected under 40 seconds, two otherwise, never in the first 5 seconds) the screen asks for the left hand or the right, picked at random: that wrist above its shoulder for a second, with the other hand not up (a clip of arm raises lifts both). A recording, playing or paused, can't follow a cue it didn't know. On the front camera the picture is mirrored before detection, which swaps the model's left and right, so the check swaps them back. Plank, where the hands hold the body up, asks for hips up and back into line. Six seconds to do it; out of frame counts as missed. No new check-in opens once the target is reached | `exercise/Checks.kt`, `ChallengeRunner` |
| Phone moving | the gyroscope (or linear accelerometer) while the camera counts. A rep counted while a quarter or more of the readings show the phone clearly moving is reported; a single nudge isn't | `ui/camera/PhoneMotion.kt`, `MotionLog` |
| Out of frame | seconds out of view after first appearing | `SessionChecks` |
| Genuine app | Google Play Integrity: the app asks Google for a token bound to this occurrence, the count and the six check numbers (`SHA-256("heartpact:exercise:<id>:<count>:<formFaults>:<fastReps>:<passed>:<missed>:<phoneMoved>:<outOfFrame>")`), so a genuine token can't carry made-up numbers; the server has Google decode it and requires the request hash, package, a token under 10 minutes old, `PLAY_RECOGNIZED`, and `MEETS_DEVICE_INTEGRITY` (or strong) | `data/PlayIntegrity.kt`; server `PlayIntegrityRules`, `PlayIntegrityVerifier` |

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

# Regional images

The website's workout screen shows someone people can see themselves in. There is one version per region, each
built from the same mockup (`design/heartpact-v3/screens/06d-workout-<region>.html`), with the pose lines drawn over
the person and the exercise matched to what they're doing.

| Region | Photo | Exercise on screen | Site image |
|---|---|---|---|
| Africa (sub-Saharan) | Pexels 8173430, squat in a living room | 15 squats | `workout-africa.webp` |
| Middle East and North Africa | Pexels 8736746, woman in a hijab, boxing gym | 15 arm raises | `workout-mena.webp` |
| Latin America | Pexels 8173450, standing in a living room | 20 jumping jacks | `workout-latam.webp` |
| East and Southeast Asia | Pexels 3768700, kettlebell squat (cropped from a group class) | 15 squats | `workout-eastasia.webp` |
| South Asia | **Not made yet**: shows the default until a photo is added (prompts below) | | `workout-southasia.webp` |
| Everywhere else | Unsplash 1518310383802, arm raises (`06b-workout.html`) | 15 arm raises | `workout.webp` |

Pexels and Unsplash photos are free for commercial use and need no credit; each source is recorded in
`design/heartpact-v3/assets/PROVENANCE.json`. Don't use a photo in a way that portrays its model negatively.

How each surface picks a region:

- **Website:** from the visitor's time zone (`Intl.DateTimeFormat().resolvedOptions().timeZone`), worked out by
  `website/assets/boot.js` before anything loads and swapped in by `site.js`, so only one image is downloaded. Nothing is sent anywhere and no location is looked up. Without JavaScript, or for a time
  zone we don't map, it shows the default. Add `?region=africa` (or `mena`, `latam`, `eastasia`, `southasia`,
  `default`) to a link to force one, for ads aimed at a region or for checking.
- **App:** from the country chosen at sign-up (email and Google), stored on the user and returned as `countryCode`
  and `region` on `AuthUserDto`. The picker starts on the phone's best guess (SIM country, then mobile network,
  then the language setting), read on the phone only. People who signed up before it was asked can set it under
  **Us → Country**, and anyone can change it there. The server maps country to region with `ContentRegions` in
  `src/Twogether.Core/Users/ContentRegions.cs`, so the app and server-side content agree. Today the server also
  uses the country to tune food photo estimates to local dishes.

## Adding a region's photo

1. Crop the photo to 780 × 1190 (portrait, ratio 0.655) as `design/heartpact-v3/assets/camera-<region>.jpg`. Leave
   the top 30 % free of the face if you can (the title sits there), and keep the feet above the bottom 7 %
   (the count sheet overlaps it).
2. Copy one of the `06d-workout-*.html` screens, point it at the photo, and set the joints (shoulders, elbows,
   wrists, hips, knees, ankles) in the mockup's 390 × 595 coordinates, the exercise title, count and cue.
3. `node render.js 06d` in `design/heartpact-v3`, then export the PNG at 620 px wide as
   `website/assets/img/workout-<region>.webp` (WebP, quality 80).
4. Add the source to `PROVENANCE.json`. For a new region, add it to `READY` and `ALT` in `website/assets/site.js` (South Asia: just `READY` and `ALT`, the time zones are already mapped).

## South Asia: prompts for an image generator

No suitable free photo was found, so here are prompts for any photorealistic image generator. Generate several
and pick one where the hands, feet and face look natural, then follow the steps above. Don't use a real person's
name or likeness.

**Main prompt (arm raises, matching the default screen):**

> Photorealistic photo, portrait orientation 2:3. A South Asian woman in her late twenties, from India, with long
> dark hair in a low ponytail, warm brown skin, wearing plain dark navy leggings and a fitted maroon athletic
> t-shirt, no logos. She stands barefoot on a yoga mat in a bright, lived-in Indian apartment living room: a low
> wooden sofa with patterned cushions, a dhurrie rug, potted plants, framed family photos, sheer curtains with soft
> morning daylight. She faces the camera directly, mid arm-raise: both arms straight up above her head, palms
> facing in, relaxed and focused, slight smile. Whole body in frame from above her hands to below her feet, with
> clear floor visible under her feet and empty wall above her hands. Shot at chest height from a phone on a tripod
> about 2.5 m away, 28 mm lens, f/4, natural colour, sharp focus on her, background softly out of focus.
> Editorial lifestyle photography, realistic skin texture.

**Alternative (squat):**

> Photorealistic photo, portrait orientation 2:3. A South Asian man in his early thirties, from Pakistan or India,
> short black hair and a trimmed beard, wearing a plain grey t-shirt and black track pants, no logos. He is
> mid-squat in a bright Indian living room (wooden furniture, patterned rug, plants, window light), facing the
> camera, thighs parallel to the floor, arms stretched forward for balance, back straight. Whole body in frame,
> head to toe, with floor space under his feet and wall space above his head. Eye-level phone camera on a tripod
> about 2.5 m away, 28 mm lens, natural daylight, realistic, editorial lifestyle photography.

**Couple version (for future marketing):**

> Photorealistic photo, landscape 3:2. A South Asian couple in their late twenties doing a home workout together
> in a bright, modern Indian living room, both facing a phone on a tripod: she is doing arm raises, he is
> squatting, both laughing. Casual plain activewear, no logos. Warm morning daylight, plants, patterned rug.
> Natural, candid, realistic.

**Avoid (negative prompt, where supported):** extra or missing fingers, distorted hands or feet, blurred face,
text, watermark, logos, gym equipment brand names, heavy make-up, studio backdrop, cropped feet, cropped hands.

# Twogether mobile pivot: viability, monetization and naming research

Research date: 10 October 2026. Target: Android-first launch on Google Play, iOS later.
Legend: **[R]** reported by a company, filing or primary press. **[E]** third-party estimate or our derivation. **[S]** self-reported marketing claim.

## 1. The idea in one paragraph

A private app for one couple that combines (1) partner-assigned exercise challenges verified by on-device computer vision, (2) an escalating alarm when a challenge is missed, (3) shared fitness and diet goals with photo calorie estimation, (4) cycle tracking and a pregnancy mode, (5) mutual live location, (6) couple flashcards, daily questions and streaks, and (7) a shared memories album with photo import.

## 2. Verdict

**Viable as a niche subscription business, not as a venture-scale one, and only if the alarm feature is redesigned.**

- The couples-app wedge is real but historically weakly monetized. The category leader Paired earns a few million dollars a year [E]; nobody else in the category has disclosed revenue. No incumbent combines couples + fitness + cycle + location, so the positioning is uncontested.
- The money in this feature set sits in adjacent categories: Flo (about 5M paid subscribers) [R], Life360 (3.2M paying circles, $159M quarterly revenue) [R], Cal AI ($30M+ annual revenue in under two years, acquired by MyFitnessPal) [R], Alarmy ($32M revenue in 2025 at a 40%+ operating margin) [R]. The pitch "all of these, for the two of us" is credible, but each of those companies won with a single sharp feature first.
- Android-first costs roughly 3x in conversion versus iOS at the median (0.9% versus 2.6% download-to-paid) and doubles involuntary churn (31% of Play cancellations are billing failures versus 14% on the App Store) [R]. Launching on Play is fine for validation; the revenue plan must assume iOS follows within 6 to 9 months.
- The headline feature as specified ("phone rings for 30 minutes until both partners log in and agree to stop") is the one item likely to fail Google Play review and to be read as coercive. It is fixable: the device owner must always be able to silence the device locally, and the two-partner agreement should govern streak credit and "challenge completed" status, not the audio.
- Paid user acquisition does not pay back at median Android conversion (about $220 cost per paying couple against about $40 first-year net revenue per couple) [E]. Growth has to come from the built-in partner invite loop, creator marketing, and App Store launch later.

## 3. Market and competitor evidence

### 3.1 Couples apps

| App | What it is | Scale | Revenue signal | Price | Source |
|---|---|---|---|---|---|
| Paired (UK) | Daily questions, quizzes, coaching | 8M to 12M downloads [S]; 1M+ Play installs [R tier] | ~$200K/mo US iOS + ~$100K/mo US Play, undated Sensor Tower snapshot [E] | $14.99/mo, $79.99/yr per couple; one subscription covers both partners | [App Store](https://apps.apple.com/jp/app/id1469609343), [Sensor Tower iOS](https://app.sensortower.com/overview/1469609343?country=US), [Sensor Tower Play](https://app.sensortower.com/overview/com.getpaired.app?country=US), [Paired FAQ](https://www.paired.com/frequently-asked-questions) |
| Paired funding | | $4.6M total raised [R] | | | [TechCrunch 2021](https://techcrunch.com/2021/05/27/paired-pulls-in-3-6m-to-encourage-more-couples-to-get-cosy-with-app-based-relationship-care) |
| Lasting (Talkspace) | Marriage counseling content | 3M+ downloads [S]; 500K+ Play installs [R tier] | Not disclosed | $11.99/mo, $79.99/yr, covers both partners; B2B codes to 10,000+ therapists | [BusinessWire](https://www.businesswire.com/news/home/20201112005199/en), [Lasting for therapists](https://getlasting.com/therapy-practice) |
| Between (Korea) | Private couple messenger | 26M+ downloads, 1M+ DAU at 2018 acquisition [R]; no figures since | Not disclosed | Free with ads, Plus $2.99/mo | [Asiae](https://view.asiae.co.kr/article/2018071707025962957) |
| Couply (Toronto) | Quizzes, AI coach | 400K+ couples, 144 countries (Feb 2024) [S] | Not disclosed; raised ~C$300K | $69.99/yr for both partners [E] | [BusinessWire 2024](https://www.businesswire.com/news/home/20240212068143/en/%C2%A0%C2%A0Couply-Creates-Worlds-First-AI-Personalized-Relationship-Coach), [BetaKit](https://betakit.com/former-wattpad-employees-close-300000-to-build-relationship-improvement-app-couply/) |
| Agapé (YC) | Daily questions | 500K+ Play installs [R tier] | ~$50K/mo, stale snapshot [E]; raised $1.5M to $2.9M | $9.99 to $14.99/mo, $199.99 lifetime | [Play](https://play.google.com/store/apps/details?id=com.getdailyagape&hl=en_US), [Preqin](https://www.preqin.com/data/profile/asset/agape-app--inc/534632) |
| Evergreen | Questions, quizzes | ~52K App Store ratings | None found | $9.99/mo, ~$49.99/yr, hard paywall at onboarding | [App Store](https://apps.apple.com/us/app/evergreen-relationship-growth/id1573360122) |
| Love Nudge | 5 Love Languages | 1M+ Play installs [R tier] | Not disclosed | Free + IAP | [AppBrain](https://www.appbrain.com/dev/Love+Language+Brand/) |
| Cupla (NZ) | Shared couple calendar | 1M+ downloads [S] | Not disclosed; raised ~$250K to $460K | Freemium | [Cupla facts](https://cupla.app/facts/) |
| Flamingo Cards | Question card game | Small | None found | $4.49/week, $19.99 Premium Pass | [App Store](https://apps.apple.com/us/app/flamingo-cards-couple-games/id1537890432) |

Read-across: 78% of couples apps use subscriptions at $10 to $15 a month [E, low-reliability vendor stat]. Only Paired has a credible revenue signal. A market-research firm sizes "relationship apps for couples" at ~$2B in 2024 growing to $5.77B by 2033 [E, opaque methodology]; the bottom-up view (leader at a few $M/yr) says the monetized segment is far smaller ([Business Research Insights](https://www.businessresearchinsights.com/market-reports/relationship-apps-for-couples-market-117629)).

### 3.2 Couples + fitness: nobody has won it yet

Indie launches in 2025 and 2026 show builders see the demand, but none has traction: Sweatmates (weekly goal, photo log, loser buys dinner), Sweathearts: Couples Fitness, Couple Glow (workouts + nutrition + mood, couple code, streaks), Fitlove, Workout Crew, RunTogether, GymBuddy75. All are iOS-only, none uses camera rep counting or partner-enforced alarms ([Sweatmates](https://apps.apple.com/us/app/-/id6756000479), [Couple Glow](https://apps.apple.com/app/id6768482914)).

Commitment and accountability apps: StickK (free, $23.7M pledged by 2016) [R, dated]; Beeminder (~$460K revenue in 2023, flat) [E]; Forfeit (~$1M raised, no traction data) [E]; Pact/GymPact shut down in 2017 after an FTC settlement of $948,788 in refunds over billing [R] ([MobiHealthNews](https://www.mobihealthnews.com/news/khosla-backed-fitness-startup-pact-shuts-down), [Latka on Beeminder](https://getlatka.com/companies/beeminder)). Lesson: accountability mechanics engage, but billing and verification have to be beyond reproach.

### 3.3 Adjacent categories that prove each feature can pay

| Feature analogue | Company | Latest figures | Source |
|---|---|---|---|
| Photo calorie | Cal AI | >15M downloads, >$30M annual revenue in under 2 years; $2.49/mo or $29.99/yr; ~250 retained creators; acquired by MyFitnessPal (announced 2 Mar 2026) [R] | [TechCrunch](https://techcrunch.com/2026/03/02/myfitnesspal-has-acquired-cal-ai-the-viral-calorie-app-built-by-teens/), [CNBC](https://www.cnbc.com/2025/09/06/cal-ai-how-a-teenage-ceo-built-a-fast-growing-calorie-tracking-app.html) |
| Location sharing | Life360 | Q2 2026 revenue $159.0M (+38% YoY), 3.2M paying circles, 95.8M MAU [R]. Life360 bought the couples app "Couple" in 2016 and shut it in 2019 | [Life360 Q2 2026 8-K](https://www.sec.gov/Archives/edgar/data/0001581760/000158176026000142/life360incq226mediarelea.htm), [Wikipedia: Couple app](https://en.wikipedia.org/wiki/Couple_(app)) |
| Cycle tracking | Flo Health | $200M+ Series C at >$1B valuation (2024); ~70M MAU, ~5M paid [R] | [Fierce Healthcare](https://www.fiercehealthcare.com/digital-health/fertility-and-period-tracking-app-flo-health-bank-200m-reach-unicorn-status) |
| Cycle tracking | Clue | 1M paid subscribers (May 2025), doubled in a year [R] | [Clue](https://helloclue.com/articles/about-clue/clue-reaches-1-million-paid-subscribers-cementing-position-as-leading-data-driven-women-s-health-intelligence-platform) |
| Diet tracking | MyFitnessPal | ~$150M annual EBITDA; sale explored at >$1B (Apr 2026) [R] | [Athletech / Reuters](https://athletechnews.com/fitness-and-health-app-myfitnesspal-explores-sale-sources-say/) |
| Escalating alarm | Alarmy (Delight Room) | 2025 revenue ₩46B (~$32M), operating profit ₩20B; 100M+ downloads, 7M+ MAU; premium ~$59.99/yr [R] | [VentureSquare](https://www.venturesquare.net/1032467) |
| CV rep counting | Onyx, Zenia, Kemtai, Kaia | Onyx acquired by Cure.fit (2021), Zenia acquired by Zing Coach, Kemtai raised ~$5.5M, Kaia $26M for clinical use. Pattern: rep counting has been an acqui-hire feature, not a standalone business | [VentureBeat](https://venturebeat.com/business/cure-fit-acquires-ai-body-tracker-onyx-to-enable-two-way-workout-videos), [Silicon Canals](https://siliconcanals.com/?p=50583) |

Health & fitness overall: 3.96B installs and a record $4.5B in in-app revenue in 2025, over half from the US [E, Sensor Tower] ([Sensor Tower](https://sensortower.com/blog/state-of-mobile-health-and-fitness-in-2025)).

### 3.4 Demand signals for the headline feature

Direct evidence is weak; indirect evidence is decent.

- TikTok trends: "hot couple walks", partner lifting tags with 5.3M views, individual couple workout videos at 45M views ([Bustle](https://www.bustle.com/wellness/hot-couple-walks-tiktok), [MyProtein](https://us.myprotein.com/thezone/health-and-wellness/tiktok-workout-trends-for-2023/)).
- Mission alarms monetize (Alarmy above; Erly, Pokito, Do It Clock as indies).
- Fajr.club uses "trusted accountability partners" for wake-ups; a Product Hunt commenter flagged partner burnout when streaks keep failing. This is the exact risk in the "until both agree" design ([hunted.space](https://hunted.space/product/fajr-club)).
- No Reddit threads were found asking for partner-controlled workout alarms. r/AccountabilityPartner shows steady demand for human check-ins, not apps.
- No reliable search-volume figure for "couple fitness app" was found. Brand term "paired app" is ~4.9K searches a month; paired.com traffic is ~119K visits a month and declining ([Ahrefs](https://ahrefs.com/websites/paired.com)).

### 3.5 How couples apps die

- Avocado (ex-Googlers, VC-backed) shut in 2017: no sustainable monetization ([Dealroom](https://app.dealroom.co/companies/avocado_apps)).
- Couple/Pair (YC) was absorbed into Life360 and shut in 2019: couples became a feature of a family app.
- LoveByte, Tuned (Meta), Pathshare, Zeta (couples banking, 2025) all discontinued.
- Between stagnated after its 2018 acquisition despite 26M downloads: big free base, unclear monetization.
- Dominant one- to three-star review theme on Paired: hard paywall ("one teaser question a day"), "both have to pay" confusion, trial-to-annual rollover charges, lost pairing or sync ([review analysis](https://unstar.app/blog/paired-lasting-love-nudge-evergreen-cupla-couples-apps-ranked-2026), [JustUseApp](https://justuseapp.com/en/app/1469609343/paired-relationship-couples/reviews)).
- Structural: two-sided activation means every churn event is doubled. Health & fitness has the worst first-renewal retention of any category (30.3%) [R, Adapty].

## 4. Technical feasibility and Google Play policy

### 4.1 Risk register

| Feature | Feasibility (1 to 5) | Policy risk (1 to 5) | What to do |
|---|---|---|---|
| CV rep counting | 4 | 1 | MediaPipe Pose Landmarker or ML Kit on-device; whitelist about six exercises; diagonal camera guidance; range-of-motion thresholds; send partner a short clip as the anti-cheat |
| 30-minute alarm until both agree | 2 | 5 | Redesign: local stop always available; partner agreement gates credit, not audio; SCHEDULE_EXACT_ALARM with user grant; full-screen intent requested at runtime; cap ringing at a few minutes and escalate through notifications |
| Live location | 4 | 3 | Mutual-only sharing, visible indicator and foreground-service notification, one-tap pause, foreground-only default with opt-in "always"; Play background-location declaration with video |
| Photo calories | 4 | 2 | Gemini Flash-Lite or Passio; show ranges not points; nutrition declaration and not-a-medical-device disclaimer |
| Cycle and pregnancy | 5 | 3 | Client-side encryption, no analytics on cycle data, delete-everything control; skip Health Connect in v1 |
| Memories import | 4 | 2 | Android Photo Picker first; Google Photos Picker API later if needed |
| Streaks, questions, flashcards | 5 | 1 | Nothing beyond the Data safety form |
| Distribution | 4 | 3 | Organization account or 12 testers for 14 days; file all declarations before closed testing |

### 4.2 Computer-vision rep counting

- MediaPipe Pose Landmarker (33 landmarks, live-stream mode with tracking) and ML Kit Pose Detection (same topology, ~30 fps base model on a Pixel 4, single person, face must be visible) both run fully on-device at no cost ([MediaPipe](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android), [ML Kit](https://developers.google.com/ml-kit/vision/pose-detection)). MoveNet Lightning runs at roughly 17 to 40 fps on a 2020 mid-range phone [E from Google's Pixel 5 figures] ([TensorFlow blog](https://blog.tensorflow.org/2021/08/pose-estimation-and-classification-on-edge-devices-with-MoveNet-and-TensorFlow-Lite.html)).
- Google's own guide counts push-ups and squats with a pose classifier plus a state machine; a 2023 paper counted squats, push-ups and pull-ups on-phone at 98.9% in live tests ([ML Kit classification guide](https://developers.google.com/ml-kit/vision/pose-detection/classifying-poses), [arXiv](https://arxiv.org/abs/2308.02420)).
- Camera placement decides accuracy: a 2026 JMIR study of 44 participants found push-ups need diagonal close or mid views, squats work best at diagonal or front, 180 to 200 cm away ([JMIR mHealth](https://mhealth.jmir.org/2026/1/e82412/)). Reliable: squats, push-ups, jumping jacks, lunges, sit-ups, pull-ups. Unreliable: planks, calf raises, holds, floor exercises viewed head-on, anything with the face hidden.
- Cheating: partial reps, someone else performing, a video replayed at the camera. Mitigations: angle-threshold state machines, random mid-set prompts, a liveness pose, and a short clip sent to the partner (the social check is the strongest control).
- Stack: Google's official Flutter MediaPipe package has no vision support yet; React Native VisionCamera v5 frame processing works but community pose plugins are individually maintained. The pose loop should be a native Kotlin (later Swift) module whichever framework hosts the UI ([flutter-mediapipe](https://github.com/google/flutter-mediapipe), [VisionCamera](https://visioncamera.margelo.com/docs/guides/frame-processors)). A PWA cannot do the alarm or background location, so a native Android shell is required regardless. The existing .NET API and SignalR backend can stay.

### 4.3 The alarm feature is the policy problem

- Exact alarms: apps targeting API 33+ have SCHEDULE_EXACT_ALARM denied by default on Android 14+; the user must grant it and can revoke it. USE_EXACT_ALARM is reserved for apps whose core function is an alarm clock or calendar and Play rejects other uses ([Android alarms](https://developer.android.com/develop/background-work/services/alarms), [Android 14 changes](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)).
- Full-screen intents: since January 2025 Play revokes the default full-screen-intent grant for any app that is not an alarm or calling app; others must send the user to a settings screen, and if denied the alarm becomes a 60-second heads-up notification ([Play policy](https://support.google.com/googleplay/android-developer/answer/13392821)).
- Foreground services: a 30-minute ringing session needs a foreground service of type specialUse with a Play Console declaration and demo video. Play's Device and Network Abuse policy requires foreground services to be stoppable by the user and forbids full-screen intents that force interaction ([FGS types](https://developer.android.com/develop/background-work/services/fg-service-types), [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/9888379)).
- Do Not Disturb: use the alarm audio stream and a channel that bypasses DND; bypass only works if the user's DND policy allows it.
- OEMs: Samsung puts unused apps to sleep after about 3 days and "alarms will not work anymore"; Xiaomi needs undocumented autostart steps ([dontkillmyapp](https://dontkillmyapp.com/samsung)).
- The "cannot be silenced by the device holder" design conflicts with three policies: Device and Network Abuse ("can be terminated or stopped by the user"), Google's Unwanted Software principles, and the harassment rule in Restricted Content. A phone its holder cannot silence is the textbook coercive-control pattern, and reviewers will read it that way ([Unwanted Software](https://www.google.com/about/unwanted-software-policy.html), [Restricted Content](https://support.google.com/googleplay/android-developer/answer/9878810)). Alarmy's mission alarms stop locally once the mission is done; nothing documents a mode where a third party must consent.
- iOS later: AlarmKit (iOS 26) lets third-party alarms break through Silent and Focus after user authorization; Critical Alerts entitlement is approved only for health and safety apps ([AlarmKit](https://developer.apple.com/documentation/alarmkit)).

Recommended redesign that keeps the emotional hook: the assigned partner's phone rings in alarm mode for a capped window (say 2 to 5 minutes, user-configurable), repeats on a schedule they chose themselves when accepting the challenge, and is always dismissable locally. What the partner cannot do alone is mark the challenge done: that needs either the camera-verified reps or the other partner's explicit "let it go" tap. Streaks, forfeits and the shared scoreboard carry the accountability instead of the speaker.

### 4.4 Location, health data and photos

- Background location needs Play's declaration (one feature only, 30-second video, prominent disclosure using the word "location" before the runtime prompt) and must deliver "significant user benefit". Life360's Play build declares exactly ACCESS_BACKGROUND_LOCATION plus FOREGROUND_SERVICE_LOCATION ([Play location policy](https://support.google.com/googleplay/android-developer/answer/9799150), [Exodus report](https://reports.exodus-privacy.eu.org/en/reports/538637)).
- Stalkerware line: monitoring apps are allowed only for parental or enterprise use and "cannot be used to track anyone else (a spouse, for example) even with their knowledge and permission". A mutual, symmetric, consented feature sits under the location policy instead, so the design must make that unambiguous: both share or neither, visible indicator, one-tap pause, no hidden mode, no "check on your partner" marketing ([Malware policy](https://support.google.com/googleplay/android-developer/answer/9888380)).
- Health: the Health Content and Services policy applies to any app with health features; privacy policy must be a live non-PDF URL; non-device health apps must say they are not a medical device. The Health apps declaration (mandatory since Aug 2024, closed tracks included) has Period Tracking, Nutrition and Weight Management, and Activity and Fitness categories; pregnancy falls under Reproductive and Sexual Health ([Health policy](https://support.google.com/googleplay/android-developer/answer/16679511), [Health declaration](https://support.google.com/googleplay/android-developer/answer/14738291)).
- Cycle and pregnancy data is GDPR Article 9 special-category data (explicit consent, DPIA). Flo added Anonymous Mode after Dobbs so it could not tie cycle data to a person on legal request; Flo also settled with the FTC in 2021 for sharing data with Facebook and Google. For a two-user app, client-side encryption with keys only on the couple's devices is the cleanest answer and lets the Data safety form treat the data as end-to-end encrypted ([Healthcare Dive](https://healthcaredive.com/news/flo-anonymous-mode-period-tracker-app-abortion-roe/631926)).
- Google Photos: since 31 March 2025 the Library API only returns app-created content and shared-album methods are gone. Import now means the Picker API (user manually selects, OAuth verification required) or, simpler, the Android Photo Picker with multi-select and upload to your own storage ([Photos API updates](https://developers.google.com/photos/support/updates), [Photo Picker](https://developer.android.com/training/data-storage/shared/photopicker)).
- Photo calories: GPT-4o and Claude 3.5 Sonnet scored ~36% mean absolute percentage error on a 52-photo study, Gemini 1.5 Pro 64%, with systematic under-estimation of large portions. Treat it as a playful ±30 to 40% estimate, show ranges, never medical guidance ([PMC study](https://pmc.ncbi.nlm.nih.gov/articles/PMC12513282/)). Cost is negligible: about $0.001 per scan on Gemini 2.5 Flash, $0.0003 on Flash-Lite [E from [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing)]; Passio's dedicated API is roughly $0.01 to $0.075 per photo [E].

### 4.5 Play distribution in 2026

- Personal developer accounts created after November 2023 must run a closed test with 12 testers opted in for 14 continuous days before applying for production; an organization account (D-U-N-S number) is exempt ([Play testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465)).
- New apps and updates must target API 36 (Android 16) from 31 August 2026 ([target API](https://support.google.com/googleplay/android-developer/answer/11926878)).
- Mandatory forms for this app: Data safety, Health apps declaration, foreground service types and full-screen intent declaration, background location declaration, privacy policy URL, account deletion URL.
- Fees: in the US, UK and EEA from 30 June 2026, auto-renewing subscriptions pay a 10% service fee plus 5% Play Billing fee; elsewhere 15% on subscriptions until the global rollout. Net effect is about 15% either way ([Play service fees](https://support.google.com/googleplay/android-developer/answer/112622), [lower fees page](https://support.google.com/googleplay/android-developer/answer/16954621)). After Epic v. Google, US apps may use alternative payments and external links; Google intends to charge a fee on link-outs but has not published one ([Play US policy](https://support.google.com/googleplay/android-developer/answer/15582165)).

## 5. Monetization on Google Play

### 5.1 Benchmarks that set expectations

| Metric | Figure | Source |
|---|---|---|
| Download-to-paid at day 35, median | Android 0.9% vs iOS 2.6% [R] | [RevenueCat](https://www.revenuecat.com/blog/engineering/android-paywall-gap) |
| Revenue per install at day 60, global median | Google Play $0.14 vs App Store $0.38 [R] | [RevenueCat SOSA 2025](https://www.revenuecat.com/state-of-subscription-apps-2025) |
| Health & fitness trial-to-paid | 37.7% to 42.2% median [R] | [RevenueCat](https://www.revenuecat.com/state-of-subscription-apps), [Adapty](https://adapty.io/blog/health-fitness-app-subscription-benchmarks/) |
| Health & fitness first-renewal retention | 30.3%, worst of all categories [R] | [Adapty](https://adapty.io/state-of-in-app-subscriptions/) |
| Year-one retention by plan | yearly 44.1%, monthly 17.0%, weekly 3.4% [R] | [RevenueCat SOSA 2025](https://www.revenuecat.com/state-of-subscription-apps-2025) |
| Hard paywall vs freemium, day-35 conversion | 10.7% vs 2.1% [R] | [RevenueCat 2026](https://www.revenuecat.com/blog/growth/subscription-app-trends-benchmarks-2026) |
| Billing-failure cancellations | Google Play 31% vs App Store 14% [R] | same |
| Android share of subscription revenue (Adapty dataset) | 15% [R] | [Adapty 2026](https://adapty.io/blog/mobile-app-monetization-2026/) |
| Lifestyle category 12-month install LTV | $0.70 (7th of 8 categories); top 10% of apps take 97.9% of revenue [R] | [Adapty Lifestyle](https://adapty.io/blog/lifestyle-app-subscription-benchmarks/) |
| New apps reaching $10K/mo within two years | 4.6% [R] | [RevenueCat SOSA 2026](https://www.revenuecat.com/state-of-subscription-apps) |
| Android health & fitness cost per install | ~$1 to $3 [E, aggregators] | [Linkrunner](https://linkrunner.io/tools/cost-per-install-benchmark), [DigitalApplied](https://www.digitalapplied.com/blog/mobile-app-marketing-statistics-2026-install-data) |

### 5.2 Recommended model

1. **One subscription per couple**, priced per couple, $49.99 to $69.99 a year with a 7-day trial on the annual plan only, $9.99 to $12.99 monthly. This is what Paired, Lasting and Couply do. Play has no shared-subscription feature and Family Library excludes subscriptions, so the backend grants the partner's entitlement once accounts are linked. State on the paywall that one purchase covers both, and block the second partner from buying when the couple is already entitled ([Play Family Library](https://support.google.com/googleplay/answer/7007852?hl=en)).
2. **Keep the two-player loop free**: daily question, streak, partner answers, and the camera-verified challenge. Gate depth: history, question and flashcard packs, AI calorie scans beyond a daily cap, cycle insights, pregnancy content, location history.
3. **Consumable AI-scan credits** as the only pay-per-use item. At about $0.001 per scan, 90 scans a month costs under $0.11 per couple, under 3% of net ARPU; abuse caps matter more than margin.
4. **One-time packs** for flashcard decks and question sets (Flamingo sells a $19.99 Premium Pass; one-time purchases are 26% of Lifestyle revenue) [R].
5. **No ads**. A couple seeing one interstitial per session earns about $0.70 a month [E], far below subscription net, and ad SDKs next to cycle, pregnancy and location data are a reputational risk. Between is the only major couples app that runs ads.
6. **B2B codes later**: Lasting sells bulk codes to 10,000+ therapists at up to 75% off. Couples therapists, premarital courses and fertility clinics are the obvious channels once content is proven. Play allows up to 10,000 one-time subscription promo codes per quarter per product ([Play promo codes](https://support.google.com/googleplay/android-developer/answer/6321495?hl=en)).
7. **Billing hygiene**: configure grace period and account hold, in-app billing-failure messages, and clear trial-to-annual messaging. Trial rollover charges are the top billing complaint in Paired, Evergreen and Flamingo reviews. Pact died on billing complaints.

### 5.3 Twelve-month scenario, Android only

All inputs are choices anchored to the cited benchmarks; treat as orders of magnitude.

| Input | Pessimistic | Base | Optimistic |
|---|---|---|---|
| Installs per month | 2,000 | 8,000 | 25,000 (requires paid or creator marketing) |
| Install-to-paid | 0.5% | 0.9% (Android median) | 2.0% |
| New paying couples per month | 10 | 72 | 500 |
| Annual to monthly mix | 65:35 | 65:35 | 65:35 |
| Prices per couple | $39.99/yr, $9.99/mo | $49.99/yr, $9.99/mo | $59.99/yr, $12.99/mo |
| Monthly-plan churn per month | 20% | 15% | 12% |
| Play fee and refunds | 15% + 4.7% | 15% + 4.7% | 15% + 4.7% |
| Paying couples at month 12 | ~94 | ~706 | ~5,040 |
| Net MRR at month 12 | ~$340 | ~$3,060 | ~$27,800 |

Paid acquisition check: at $2 per install and 0.9% conversion, one paying couple costs about $220 against about $40 of first-year net revenue. Paid UA on Android does not pay back at median conversion; it only works with hard-paywall conversion (10.7%) or organic growth through the partner invite. The optimistic case therefore depends on creator marketing in the Cal AI style (250 retained micro-creators at mid six figures a month) or on an iOS launch.

### 5.4 Pitfalls specific to this app

- Half the ARPU by construction: one subscription covers two installs.
- Seasonality is weaker than assumed: dating apps see a January peak and a February dip; the fitness and diet features fit New Year better than Valentine's ([Apptopia](https://apptopia.com/en/blog/valentines-day-provides-modest-increase-in-dating-app-demand/)).
- Gating the two-player loop kills retention (the dominant complaint across Paired, Evergreen and Love Nudge reviews).
- Partner burnout: if challenges keep failing, the assigning partner becomes the nag. Add a weekly cap on assigned challenges and a "pause accountability" mode.

## 6. Recommended scope for a first release

Ship the sharp feature first, as Cal AI, Alarmy, Flo and Life360 each did.

1. **Phase 1 (validation, Play closed test)**: couple pairing, partner-assigned challenge with camera-verified reps for six exercises, the redesigned alarm, streaks and scoreboard, daily question. Nothing else. Measure weekly active couples and challenge completion rate.
2. **Phase 2**: photo calorie scan with daily cap, shared fitness and diet goals, memories via Photo Picker.
3. **Phase 3**: mutual location with the full declaration set, cycle tracking with client-side encryption, pregnancy mode.
4. **Phase 4**: iOS using AlarmKit, B2B codes.

Decisions to make now: organization developer account (avoids the 12-tester gate), native Kotlin shell versus React Native or Flutter with a native pose module, and whether the alarm redesign above preserves enough of the original hook for you.

## 7. Name research

Method: for each candidate, the Google Play search results page and the App Store search API were queried for apps whose title contains the name, then web searches for trademark and company hits, then RDAP lookups for .com and .app. This is a screening pass, not a legal clearance. Before committing, run exact and phonetic searches on USPTO, EUIPO, UK IPO and WIPO Global Brand Database in classes 9, 42 and 44, and check social handles.

### 7.1 Why the current name has to go

"Twogether" is crowded on both stores: at least seven Play apps carry the name, including "Twogether: Couple Game & Dares", "Twogether: Couples & Pet", "TwoGether: Couples Planner App", "Twogether: Couple Games & Date", a Twogether dating app, and an iOS date-planning app for couples launched in November 2025 with an AI assistant ([Product Hunt](https://www.producthunt.com/products/twogether/launches/twogether), [Twogether Matrimony](https://apps.apple.com/us/app/-/id6755220471)). Several of these are direct category competitors, which is the worst case for store search, ASO and trademark confusion. "2gether" has the same problem plus the generic word "together".

### 7.2 Finalists

| Name | Meaning | Play title matches | App Store title matches | Web trademark hits | Domains | Notes |
|---|---|---|---|---|---|---|
| **Heartpact** | Heart + pact: the promise one partner makes to the other; fits the partner-set challenge exactly | 0 of 30 results | 0 (nearest: Hearts card games) | None found | heartpact.app free; .com registered | Strongest fit to the product. Top recommendation |
| **Twinpact** | Twin + pact, or twin impact | 0 of 30 | 0 (1 result, unrelated) | None found | .app and .com registered | Clean, slightly less warm than Heartpact |
| **Vowfit** | Vow + fit | 0 of 10 | 0 | None found | vowfit.app free; .com registered | "Vow" skews married; good if that is the target |
| **Yokely** | From "yoked": joined as a pair, and gym slang for strong | 0 of 30 | 0 (nearest: Yokee Music) | None found | yokely.app free; .com registered | "Yoke: Couples Check-in" and "Yoked - AI Fitness Coach" exist on Play; similar-sounding neighbours raise confusion risk |
| **Usward** | Toward "us" | 0 of 30 | 0 (1 result, unrelated) | None found | .app and .com registered | Clean and abstract; weakest at explaining the product |
| **Duowell** | Duo + wellness | 0 of 30 | 0 (2 results, unrelated) | None found | .app and .com registered | Near names "Duwell", "DoWell" on Play |
| **Lovesprint** | Love + sprint | 0 of 30 | 0 | None found | lovesprint.app free; .com registered | Generic feel; "Love"-prefixed marks are numerous |
| **Bothly** | Both + ly | 0 of 25 | 0 | None found | .app and .com registered | Clean, low meaning |

### 7.3 Rejected candidates and why

| Name | Reason |
|---|---|
| Sweatheart / Sweatmates | "Sweathearts: Couples Fitness" already on the App Store; "Sweatmates" is a live couples fitness app; iFIT litigates its SWEAT marks ([FashionUnited](https://fashionunited.uk/news/business/victoria-s-secret-sued-by-owner-of-sweat-trademark/2021111859427)) |
| Pairwell, Pairfit, Pairpact, Pairly, Pairup, Pairfect | Pair Networks has sued multiple app makers over "Pair" marks, including the couples app Pair ([TechCrunch](https://techcrunch.com/?p=546820), [Domain Name Wire](https://domainnamewire.com/2013/04/30/pair-networks-sues-pairshare-for-trademark-infringement/)); Pairly already has seven Play apps, several for couples |
| Twofold | 13 App Store apps, including "Twofold: Couples Goals & Plans" and "Twofold: Couples"; 8 on Play |
| Tandemly | "Tandemly: Built for Couples" on both stores |
| Pactly | "Pactly: Diet Accountability" on Play, same category |
| Coupla | "AI Couples Therapy - Coupla" on the App Store; Cupla (1M+ downloads) is a phonetic match |
| Duofy | "duofi: couples budget tracker" is a homophone in the same category |
| Knotfit | Clean on both stores, but The Knot (wedding brand) is an aggressive mark holder in the couples space |
| Duetly | "Duetly: Dual Camera Recorder" on the App Store |
| Yoked | "Yoked - AI Fitness Coach" on Play |
| Twoly, Duoly, Vowly, Amorly, Couplify, Heartsync, Couplet, Twiin | Existing couples or games apps with the same title on one or both stores |
| Fitmate, Wefit, Movemate, Spotme, Spotr, Bindly | Heavily used generic names (9 to 12 title matches each) |

### 7.4 Recommendation

Go with **Heartpact**, store title "Heartpact: Couple Goals & Fitness" (Play allows 30 characters), domain heartpact.app, with Twinpact and Vowfit as fallbacks if clearance fails. Check whether heartpact.com is parked and purchasable, file a trademark application in your home jurisdiction in classes 9 and 42 before the public launch, and reserve the handle on Instagram and TikTok on the same day.

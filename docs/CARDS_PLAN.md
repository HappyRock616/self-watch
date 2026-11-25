# Body and Activity cards: review and plan

Status: built on branch `cards` (Workstream A, 2026-11-25): small and large forms, one default layout per tab, the goal cards, Daily balance, Weight goal and the catalog (cards for data no device sends yet say "Your devices don't record this"). Owner: HappyRock (final card picks are his).
Branch `cards-finish` (2026-11-25): Day clock, Live (live heart rate and steps while on screen, strap named), Weather (the relay's stored forecast), the device cards D1–D4 (home Body, listed under "Device cards" in Edit cards; Solar charging and Room climate wait for data) and Food groups (diary lines keep the food's category, schema 3).

**Rule for the tabs (HappyRock):**
- **Body** = passive signals you can't directly control: sleep, HRV, resting heart rate, stress, energy
  level, breathing, blood oxygen, body measurements.
- **Activity** = things you do: movement, workouts, training, fitness results.
- **Food** keeps food, water and calories in vs out.

**Scope: every device Gadgetbridge supports** (watches, bands, rings, scales, blood pressure monitors, glucose meters, bike sensors, thermometers), not only Garmin. Every card from Gadgetbridge's dashboard and charts, and from Garmin Connect, is in the catalog as an optional card. You add,
remove and reorder them with Edit cards (already built). A card for data your watch doesn't record says so
and is hidden from the Add list unless "Show all" is on. Names are our own (no Garmin trademarks such as
Body Battery, Training Readiness, HRV Status, Pulse Ox, Endurance Score, Hill Score, Sleep Coach).

Sources: SW = Self Watch today, GC = Garmin Connect, GB = Gadgetbridge (28 dashboard widgets plus its chart screens and device capability flags), P = Pulse.

**Defaults follow your devices (auto-detect):** a default card only shows when one of your devices records that data. Pairing a device adds its cards: a scale adds Weight and Body fat, a blood pressure monitor adds Blood pressure, a glucose meter adds Blood glucose, a ring with temperature adds Temperature, a bike sensor adds Cycling. Removing the device hides them again (the data stays).
Default = on the tab out of the box (when your devices record it); everything else is optional.

## Decisions (HappyRock, 2026-11-24)

1. **8 cards per tab by default: 1–2 large and 4–6 small** (may change later).
   - **Small** = one block, half the screen width: one number, a label, a tiny trend or status color.
   - **Large** = full width: a chart or the details (stages, timeline, list).
   - Every card comes in both sizes; Edit cards switches a card between small and large.
2. **Every card can go on either page** (Body or Activity). The tables below only set each card's home and defaults.
3. **Readiness lives on Body** (like the energy level).
4. **Body composition** (the scale numbers: body fat, muscle, lean mass, bone, body water, BMI, resting calories)
   stays as **separate cards, like Gadgetbridge**.
5. **Food cards can go on Body and Activity too**, but none are there by default.
6. **No goal presets for cards** (dropped). One default layout for everyone, adjusted only by which data your
   devices record. Code impact: `CardPresets` becomes one list per tab; the onboarding goal step must stop
   saying "It picks the cards on Body, Activity and Food".
7. **Weather card: yes.** Self Watch already forwards weather from a weather app on the phone (for example
   Breezy Weather) to the watch; the weather app does the internet part, so the card uses the same forecast
   and Self Watch itself needs no internet for it. Only without a weather app would Self Watch fetch weather
   itself (an internet switch in the Privacy center, off by default), the way Pulse uses Open-Meteo.

### Default layout (2026-11-24, after "goals as the main display")

Goals are the main display: the first large card on each tab shows every goal for that tab at a glance, each as
a ring or bar with "done / goal". Per-metric goals stay (set in Goals); only the main-goal *card presets* are gone.

**Body**

| Slot | Card | Shows |
|---|---|---|
| Large 1 | **Body today** (new) | Calories eaten / target, Calories burned / goal, Water / goal, Energy level now (0–100), Sleep last night / goal, Weight / goal |
| Large 2 | Sleep | Last night: hours, stages, score |
| Small | HRV | Overnight vs your usual range |
| Small | Resting heart rate | Today vs your usual |
| Small | Stress | Today's average, rest time |
| Small | Readiness | Ready to train today |
| Small | Body signals | Skin temperature, breathing, resting HR vs usual |
| Small | Blood oxygen | Last night |

**Activity**

| Slot | Card | Shows |
|---|---|---|
| Large 1 | **Activity goals** (new) | Steps today / goal, Active minutes this week / goal (vigorous count double), Active calories today / goal, Workouts this week / goal, Active days this week / goal, Distance today / goal, Floors / goal (if the watch has a barometer) |
| Small | Weekly effort (was Training load) | How hard you trained this week, from heart-rate zones |
| Small | VO2 max | Latest and trend |
| Small | Heart rate recovery | After the last workout |
| Small | Workout time this week (was Weekly volume) | Workout time and distance this week |
| Last (tall) | **Recent activities** | List of the last 5 workouts, each with sport icon, name, date, and summary stats (duration, distance, pace or speed, average heart rate, calories). Tap one: the full workout screen (summary, route map, heart rate, pace/speed and elevation charts, laps, heart-rate zones, export). "All activities" opens the full list, filterable by sport |

The tall list takes the room of two small tiles (Strength and Easy and hard move to optional). It sits last, after the small cards (2026-11-25); saved layouts get it moved to the end once.
In "All activities", picking a sport shows a trend dashboard for that sport above its workouts (layers toggled like the other charts, remembered per sport; it can be removed per sport and brought back from the menu). A workout can be trimmed (start and end) without losing the original file.

**Food** (the Daily balance card replaces today's fixed calories-left header; meals follow the cards)

| Slot | Card | Shows |
|---|---|---|
| Large 1 (fixed top) | **Daily balance** (new, Cronometer-style) | Energy: calories eaten, burned (resting + active) and left, as a ring. Macro bars against targets: protein, carbs, fat, with the share of calories each gives against a balanced range. Fat broken down: saturated, mono, poly, trans. Fiber; sodium and added sugar against limits. Tap a bar for the foods behind it |
| Large 2 | **Weight goal** (new) | Recommended calories a day to reach your goal weight by your goal date, and how it changes over time; weight trend with the projected line to goal; BMI now and at goal |
| Small | Water | Glasses today / goal, one tap adds a glass |
| Small | Protein per meal | Meals with about 20–40 g |

How the recommendation works (to confirm): burn = the watch's measured resting + active calories (BMR formula when
there's no watch data); daily target = burn − the deficit needed to reach the goal by the date, capped at a safe
rate (about 0.5–1% of body weight a week) and never below a safe minimum; it re-adjusts each week from the actual
weight trend, so the target follows what really happens.

If your devices don't record a default card's data, the next one fills its place: Body: Weight, Breathing rate,
Sleep score; Activity: Steps, Distance, Today timeline.

## Body tab (passive)

| # | Card | Shows | From | On by default |
|---|---|---|---|---|
| 0 | Body today | Goals at a glance: calories eaten, calories burned, water, energy level, sleep, weight | new | Default (large) |
| 1 | Energy level | 0–100 reserve today, charged by sleep, drained by stress and activity | SW GC GB P | In Body today; own card optional |
| 2 | Sleep | Last night vs 7–9 h, stages | SW GC GB P | Default |
| 3 | Sleep score | Score and what lowered it | GC GB | Default |
| 4 | HRV | Overnight HRV vs your usual range | SW GC GB P | Default |
| 5 | Resting heart rate | 4-week trend vs usual | SW GC GB | Default |
| 6 | Stress | All day, rest time | SW GC GB P | Default |
| 7 | Body signals | Skin temperature, resting HR, breathing vs usual (health check) | SW (GC Health Status) | Default |
| 8 | Weight | Trend and goal | SW GC GB | Default |
| 9 | Heart rate | Now, today's low, high, resting | SW GC GB P | Optional |
| 10 | Heart rate trends | Resting and average, 30 days | SW | Optional |
| 11 | Blood oxygen | Last night and 30 nights | SW GC GB P | Optional |
| 12 | Breathing rate | Overnight vs usual | SW GC GB P | Optional |
| 13 | Temperature | Skin or body temperature, overnight change vs usual (watches, rings, Zepp, Huawei) | GC GB | Optional; auto with a device that records it |
| 14 | Sleep need | Tonight's suggested sleep from recent sleep and activity | GC | Optional |
| 15 | Naps | Today's naps | GC | Optional |
| 16 | Morning summary | Sleep, HRV and energy in one line each morning | GC | Optional |
| 17 | Recovery | Sleep, resting HR and HRV together | SW | Optional |
| 17a | Readiness | Ready to train today: sleep, HRV, recent load, recovery (moved from Activity) | GC GB | Optional |
| 18 | Health check | Last 2-minute check: HR, HRV, blood oxygen, breathing, stress | GC | Optional |
| 19 | Fitness age | Body age from fitness, resting HR and activity | GC | Optional |
| 20 | Body fat | Trend from scale or entries | SW GC GB | Optional |
| 21 | Muscle mass | Muscle and skeletal muscle trend (scales) | GC GB | Optional |
| 21a | Lean mass | Fat-free mass trend (scales) | GB | Optional |
| 22 | Body water | Trend | GC GB | Optional |
| 23 | Bone mass | Trend | GC | Optional |
| 24 | BMI | Value and band (also shown on Weight goal) | GC GB | Optional, any tab |
| 25 | Resting calories (BMR) | Calories at rest | GB | Optional |
| 26 | Measurements | Waist, hips | SW | Optional |
| 27 | Waist to height | Waist vs half your height | SW | Optional |
| 28 | Blood pressure | Latest and trend (monitors such as Beurer, some watches) | GC GB | Optional; auto with a monitor |
| 29 | ECG | Last recording (Garmin, Huawei, Withings ScanWatch) | GC GB | Optional |
| 30 | Cycle tracking | Period, cycle phase, pregnancy | GC | Optional |
| 31 | Jet lag | Body clock vs destination time | GC | Optional |
| 32 | Blood glucose | Readings from a glucose meter (OneTouch) or monitor | GC GB | Optional; auto with a meter |

## Activity tab (things you do)

| # | Card | Shows | From | On by default |
|---|---|---|---|---|
| 0 | Activity goals | Every activity goal at a glance: steps, active minutes per week, active calories, workouts, active days, distance, floors | new (GB Goals, GC, P) | Default (large) |
| 1 | Today | Steps, active calories, active minutes rings | SW P | Optional (Activity goals covers it) |
| 2 | Steps | Today vs target, live when connected | SW GC GB P | Default |
| 3 | Today timeline | Steps by hour with heart rate on top | SW | Default |
| 4 | Intensity minutes | This week, vigorous counts double | SW GC GB P | Default |
| 5 | Recent activities | Latest workouts | SW GC GB P | Default |
| 6 | Heart rate recovery | How fast HR drops after a workout | SW | Default |
| 7 | VO2 max | Running (and cycling) VO2 max trend | GC GB | Default |
| 8 | Weekly effort (was Training load) | How hard you trained this week, from heart-rate zones (Self Watch works it out when the watch doesn't) | SW GC GB | Default |
| 10 | Training status | Productive, maintaining, overreaching, detraining | GC | Optional |
| 11 | Recovery time | Hours until recovered from the last hard effort | GC | Optional |
| 12 | Last activity | Big card for the latest workout | GC GB | Optional |
| 13 | Day clock | 24-hour circle of sleep, rest and activity | GB | Optional |
| 14 | Distance | Today, last 7 days | SW GC GB P | Optional |
| 15 | Active time | Minutes moving today | GB P | Optional |
| 16 | Active calories | Today | SW GC GB | Optional |
| 17 | Calories burned | Active plus resting today | SW GC GB P | Optional |
| 18 | Floors climbed | Today vs goal (watches with a barometer) | GC | Optional |
| 19 | Vigorous minutes | Hard effort vs 75 a week (moved from Body) | SW | Optional |
| 20 | PAI | Weekly activity score (Amazfit / Zepp devices) | GB | Optional; auto on those devices |
| 21 | Active days | Days this week meeting a seventh of the weekly goal | SW | Optional |
| 22 | Strength | Strength and balance days this week | SW | Optional |
| 23 | Easy and hard | Share of workout time at an easy effort | SW | Optional |
| 24 | Workout time this week (was Weekly volume) | Workout time and distance this week, long-session check | SW | Optional |
| 25 | Trends | Distance, minutes, steps, calories over time | SW GC P | Optional |
| 26 | Race times | Predicted 5K, 10K, half, marathon | GC | Optional |
| 27 | Endurance | Endurance fitness score | GC | Optional |
| 28 | Hill fitness | Climbing strength and endurance | GC | Optional |
| 29 | Lactate threshold | Threshold pace and heart rate (chest strap) | GC | Optional |
| 30 | Cycling power | FTP and power curve (power meter) | GC | Optional |
| 31 | Heat and altitude | Acclimation | GC | Optional |
| 32 | Personal records | Fastest and longest efforts | GC | Optional |
| 33 | Streaks | Days in a row meeting your goals | P | Optional |
| 34 | Weekly review | Last week vs the week before | GC P | Optional |
| 35 | Achievements | Badges for milestones (on the phone, no social) | GC P | Optional |
| 36 | Weather | Today's weather for planning a workout (needs internet, off by default) | P | Optional |
| 37 | Suggested workout | Today's workout from a training plan | GC | Optional, later |
| 37a | Sport progress | One sport over time (pick it: running, cycling, walking, swimming, strength...): pace or speed, distance, heart rate at the same pace, longest and fastest, sessions per week. Same view opens from "All activities" filtered by sport | new (GC activity trends, GB) | Optional |
| 38 | Live | Heart rate and steps right now while connected | GB | Optional |
| 39 | Speed zones | Time in each speed zone (bands that record it) | GB | Optional |
| 40 | Cycling | Speed, cadence and distance from a bike sensor | GB | Optional; auto with a bike sensor |
| 41 | Elevation | Altitude and climb today (devices with a barometer) | GB | Optional |

## Devices tab cards (device data, not health)

| # | Card | Shows | From |
|---|---|---|---|
| D1 | Battery | Battery level history for each device (watch, ring, band, earbuds) | GB |
| D2 | Solar charging | Solar intake (solar watches) | GB |
| D3 | Room climate | Temperature and humidity from a Bluetooth thermometer (Xiaomi Mijia) | GB |
| D4 | Last sync | When each device last sent data, and what | SW |

## Food tab cards (new or changed)

| Card | Shows | From |
|---|---|---|
| Daily balance | Calories eaten, burned and left; protein, carbs, fat against targets; fat types; fiber, sodium, added sugar | new (Cronometer's diary targets, MyFitnessPal's goal page) |
| Weight goal | Recommended daily calories to reach goal weight by date, re-adjusted weekly; weight trend and projection; BMI | new |
| Food groups | Fruits, vegetables, grains, protein foods, dairy against a balanced plate (from the food categories in database v2) | new, optional |
| Water, Protein per meal, Sodium, Fiber, Total sugar, Protein per kg, Carbs per kg, Workout fuel, Fuel for training, Logging days | as today | SW |

## Moves and merges

- **Out of Body:** Steps → Activity (duplicate of Activity "Steps"); Vigorous minutes → Activity; Protein and
  macros → Food (duplicate of Food "Protein and macros"); Calories (food in vs out) → Food.
- **Food keeps:** Water (GC and GB hydration), Calories in vs out, the food cards.
- GB "Goals" = Activity "Today"; GB "Intensity Minutes / PAI" = Intensity minutes, with PAI as its own card.
- Gadgetbridge chart screens (sleep, HR, stress, temperature, weight...) map to each card's detail screen.

## Device support (from Gadgetbridge's capability flags)

| Data | Device families in Gadgetbridge |
|---|---|
| Steps, distance, sleep, heart rate | Almost every watch, band and ring |
| Sleep stages (REM, awake), sleep score | Garmin, Zepp/Amazfit, Huawei, Xiaomi, rings |
| Stress, HRV, blood oxygen, breathing | Garmin, Zepp/Amazfit, Huawei, Xiaomi, some rings |
| Energy level (reserve 0–100) | Garmin |
| PAI | Zepp/Amazfit |
| Training load, readiness, status, race times, VO2 max | Garmin (model dependent) |
| Temperature (skin/body) | Zepp OS, Huawei, Xiaomi Watch S1 Pro, Colmi and Yawell rings |
| ECG | Garmin, Huawei, Withings ScanWatch |
| Weight and body composition (fat, muscle, skeletal muscle, bone, water, lean mass) | Mi Body Composition and Mi Smart Scale, 1byone, QN, Viatom F8, generic Bluetooth scales |
| Blood pressure | Beurer BM59/BM69 and other monitors, some watches |
| Blood glucose | OneTouch meters |
| Cycling speed and cadence | Bluetooth cycling sensors, iTech Active 3 |
| Speed zones | iTech Active 3 |
| Water | Watches that log hydration |
| Battery, solar charging | All devices; solar Garmin models |
| Room temperature, humidity | Xiaomi Mijia thermometers |

Model limits still apply: the vívoactive 5, for example, doesn't record training load, training status, race
predictions or cycling VO2 max (Self Watch's own Weekly effort card still works from heart-rate zones).
Self Watch reads each device's capabilities, as Gadgetbridge does, to decide which cards to offer.

## Questions for HappyRock

Answered 2026-11-24 (see Decisions). Open:
1. Which cards should be large by default once you've seen them (the table above is a starting point)?
2. Food tab default (proposal, no goals): Protein and macros (large); Water, Sodium, Fiber (small).
3. Does the main goal stay for anything else (protein question in onboarding, calorie target), or go entirely?

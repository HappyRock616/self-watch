# WP-15 cards (Body, Activity and Food card stacks)

Lane C (Screens). Depends on: core-db (settings, change bus), WP-09 components, WP-10 tab screens. Phase 1 (this package's framework and the move of today's content into cards) is built; phase 2 is the card catalog below, built in parallel by one developer per tab.

## Goal

Body, Activity and Food are each an editable stack of cards. The main goal picked in onboarding (or in Me › Goals) chooses each tab's default cards and their order; the user can reorder, hide and show cards on every tab, and reset a tab to the defaults for their goal. Adding a card is a small, self-contained change: one Kotlin file, one layout, one line in the tab's list, lines in the presets table and a test.

## How it works

| Piece | Where | What it does |
|---|---|---|
| `CardTab` | `app/.../cards/Card.kt` | `BODY`, `ACTIVITY`, `FOOD`; names each tab's settings keys |
| `Card<T>` | `cards/Card.kt` | One card type: stable `id`, `tab`, title and one-line summary (string resources), `tables` that reload it, `suspend fun load(env: CardEnv): T` and `createView(host, parent): CardView<T>` |
| `CardView<T>` | `cards/Card.kt` | The card's view: `root` plus `bind(data: T)` |
| `CardEnv` | `cards/Card.kt` | What a loader reads: `db`, `clock`, `day` (today on Body and Activity, the diary day shown on Food), `units` |
| `CardHost` | `cards/Card.kt` | What a view may do: `open(screen)`, `openTab(tab)`, `launch { }` (work tied to the screen), `fragment`, `context` |
| `BodyCards`, `ActivityCards`, `FoodCards` | `body/cards/`, `activity/cards/`, `food/cards/` | Each tab's list of card types (its registry). Separate files so three developers never edit the same list |
| `CardRegistry` | `cards/CardRegistry.kt` | `of(tab)` and `byId(tab, id)` over the three lists |
| `CardPresets` | `cards/CardPresets.kt` | **The one table** of default cards per main goal per tab, most important first |
| `CardStore` | `cards/CardStore.kt` | Reads and saves the main goal and each tab's order; merge rules; migration of the old Body keys |
| `CardStack` | `cards/CardStack.kt` | Shows a tab's visible cards in a `LinearLayout` in the saved order and keeps them fresh |
| `EditCardsFragment` | `cards/EditCardsFragment.kt` | Edit cards for one tab (`newInstance(tab)`): drag handles, switches, TalkBack "Move up/down", "Reset to defaults for my goal" |
| `MainGoal`, `MainGoalPicker`, `MainGoalSwitchDialog` | `cards/` | The nine goals; the radio list; the one "Switch cards?" question |

**Loading.** `CardStack` builds each visible card's view once per screen, then runs one coroutine per visible card while the screen is started: it calls `load` on an IO thread when the card appears, whenever one of the card's `tables` changes (`db.changes`), and when the day or the units change, then `bind`s the result on the main thread. Cards load independently; a slow or failing card never holds up the others (a failure is logged and the card keeps what it showed). Hidden cards are never built or loaded. Reordering moves the existing views.

**Tabs.**
- **Body:** the watch chip and date stay in the toolbar; everything below is cards.
- **Activity:** the streak chip stays in the toolbar; everything below is cards. The Trends card's range (Week · Month · Year · All) is the setting `activity.trend_range`, so the Activity menu's "Range" and the card's buttons agree.
- **Food:** the day switcher and the **calories-left header stay fixed at the top and are never cards** (it is the headline number; users object when it moves). The Food cards are a strip between the header and the meals; the meals and fasting stay below and work as before. Food cards load for the diary day shown.

**Each tab's menu** has "Edit cards". **Changing the main goal** in Me › Goals asks once ("Switch cards?") and, on yes, resets all three tabs to the goal's defaults in one write. First-run onboarding applies the goal's cards to all three tabs without asking (there is nothing of the user's to replace); if setup runs again over cards the user saved, the same single question is asked.

## Settings keys

| Key | Value |
|---|---|
| `goal.main` | Main goal id (`weight_loss`, `weight_management`, `build_muscle`, `athletic`, `endurance`, `heart_health`, `stress_wellbeing`, `active_aging`, `general_health`); absent = general health |
| `cards.<tab>` | Visible card ids of that tab, in order, comma list (`cards.body`, `cards.activity`, `cards.food`). Absent = the main goal's set. Empty = every card hidden |
| `cards.<tab>.known` | Every card id of that tab the app knew when `cards.<tab>` was saved |
| `cards.<tab>.preset` | The main goal the tab was last reset to |
| `activity.trend_range` | Trends card range: `week`, `month`, `year` or `all`; absent = month |
| `body.cards`, `body.cards.known`, `body.cards.preset` | **Old** Body keys. The first read of the Body cards moves them to `cards.body*` and deletes them (when both exist, the new keys win) |

**Merge rules** (`CardStore.resolve`, same on every tab):
1. Ids that are unknown (removed cards, cards of another tab, ids from a newer version) are ignored; repeats count once.
2. A card type added in an app update (not in `cards.<tab>.known`) appears at the end if the user's goal's preset has it, otherwise it starts hidden.
3. A card the user hid (known, not listed) never comes back on its own.

## How to add a card

Example: a Food card "Sodium" (catalog C25).

1. **Card file:** `app/src/main/kotlin/app/selfwatch/food/cards/SodiumCard.kt`:
   ```kotlin
   object SodiumCard : Card<SodiumCard.Data>("sodium", CardTab.FOOD, R.string.card_sodium, R.string.card_sodium_summary) {
       data class Data(val sodiumMg: Int?, val limitMg: Int)
       override val tables: Set<Table> = setOf(Table.DIARY, Table.GOAL)
       override suspend fun load(env: CardEnv): Data = Data(env.db.diary.dayTotals(env.day).sodiumMg, 2_300)
       override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)
       private class Holder(parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_food_sodium)) {
           override fun bind(data: Data) { /* fill the views; show an EmptyState when there is no data */ }
       }
   }
   ```
   The id is stored on phones: pick it once, lower case with underscores, and never change it. Keep the arithmetic in a pure function (or the tab's loader) so it can be unit tested.
2. **Layout:** `res/layout/card_food_sodium.xml`, root a `SwCard` with style `Widget.SelfWatch.TabCard` and an id (`@+id/sodium_card`). An `EmptyState` for "nothing yet" (wording rules below). Content description on the root, 48 dp touch targets, works at 200% font.
3. **Strings:** `card_<id>` (name) and `card_<id>_summary` (one line for Edit cards) plus the card's own text, in the tab's strings file: `values/strings_cards_body.xml`, `strings_cards_activity.xml` or `strings_cards_food.xml`.
4. **Registry:** add the object to `FoodCards.ALL` (or `BodyCards`, `ActivityCards`). The order there is the order of hidden cards in Edit cards.
5. **Presets:** add it to the goals that should show it in `CardPresets` (Food table), at its place in the order. Existing users get it at the end of their tab only if their goal's preset has it (merge rule 2).
6. **Tests:**
   - A loader test (`app/src/test/.../food/cards/SodiumCardTest.kt`): seed the database, call `SodiumCard.load(CardEnv(db, FakeClock(), day, Units.METRIC))`, check the numbers, including the empty case.
   - Add the card's root id to `rootIds` in `BodyScreensSmokeTest` (the test fails until you do). The smoke test then shows every registered card of every tab with the demo data, in portrait and in landscape at 2× font.
   - `CardFrameworkTest` already checks that every preset uses only registered cards of the right tab and that ids are unique; update its spot checks if you change the table.
7. **Taps:** a card opens its detail with `host.open(SomeDetailFragment())` or another tab with `host.openTab(Tab.X)`. Writes go through `host.launch { ... }`.

New data a card needs (a query, a new column) follows the module's own rules (WP-03 for the database). A card that needs input the app does not collect yet is marked "later, needs owner OK" below and is not built until HappyRock agrees.

## Phase 1 cards (built)

| Tab | Id | Card |
|---|---|---|
| Body | `energy` | Calories in vs out today and the 7-day bars; opens the calorie explainer |
| Body | `macros` | Protein (against the goal or 1.6 g/kg of body weight), carbs and fat today; opens Food |
| Body | `steps` | Steps and active minutes against the goals; opens Activity |
| Body | `intensity` | Vigorous minutes, last 7 days, against 75; opens Activity |
| Body | `weight` | Weight, 7- and 90-day trend change, goal, sparkline; opens Weight (with the energy-balance overlay) |
| Body | `measurements` | Waist, hips and body fat with 30-day change; opens Measurements |
| Body | `recovery` | Sleep, resting heart rate and HRV side by side; open Sleep and Heart |
| Body | `spo2` | Last night's blood oxygen and 30 nights; opens Sleep |
| Activity | `today_rings` | Steps, active minutes and active kcal rings against the goals |
| Activity | `trends` | Week · Month · Year · All bars with chips for distance, active minutes, steps and active kcal |
| Activity | `workouts` | The latest 5 workouts; "All" opens the list |
| Food | `macros_today` | Protein, carbs and fat for the day shown (same view as Body's macros card); opens Goals |
| Food | `water` | Glass squares for the day shown; tap to add a glass, tap the last full one to take it back |

Phase 1 defaults (in `CardPresets` now): Body keeps the stacks it had; Activity shows all three cards for every goal (workouts first for build muscle, athletic and endurance); Food shows water for every goal and macros first for lose weight, build muscle, athletic, endurance and stay active.

## Card catalog (phase 2)

Thirty cards cover all nine goals; each goal gets six to eight of them in its own order and with its own target. Targets are first-launch defaults; every card can be edited or hidden. "Computed" means Self Watch derives the number itself. Items marked **[unverified]** were not re-checked against the source.

| ID | Card | Tab | Planned id | Data used | Default target | Source |
|---|---|---|---|---|---|---|
| C1 | Calories left | Food (fixed header, not a card) and Body `energy` | (header) | Food log; target from goal | Loss: maintenance − 500 to 750 kcal (UK about − 600). Maintain: maintenance. Gain: maintenance + 10–20%. Computed | [AHA/ACC/TOS 2013](https://pmc.ncbi.nlm.nih.gov/articles/PMC5819889/); [NHS 12-week plan](https://sites.southglos.gov.uk/one-you/wp-content/uploads/sites/414/2025/03/NHS-12-week-weight-loss-plan.pdf); [Iraki 2019](https://dx.doi.org/10.3390/sports7070154) |
| C2 | Weight trend (smoothed, raw days as faint dots) | Body | `weight` (exists; extend) | Weight log | Loss 0.5–1 kg/week; % lost against a 5–10% first goal **[5–10% wording unverified]**. Maintain: ±3% band. Gain: +0.25–0.5% body weight/week. Computed | [CDC](https://www.cdc.gov/healthy-weight-growth/losing-weight/index.html); [ACSM 2009 summary](https://obesitymedicine.org/blog/how-much-physical-activity-is-needed-for-weight-loss-weight-loss-maintenance-and-weight-gain-prevention/); [Iraki 2019](https://dx.doi.org/10.3390/sports7070154) |
| C3 | Energy balance, 7-day | Body | `energy` (exists; extend) | Food kcal − active + resting kcal | Near 0 for maintenance. Labeled "estimate". Computed | [Hall 2011](https://pubmed.ncbi.nlm.nih.gov/21872751/) |
| C4 | Logging days this week | Food | `logging_days` | Diary or scale entries | No official number. Weighing daily to weekly beats less often | [Burke 2011](https://lonestarcenters.com/wp-content/uploads/2025/11/2011-burke-self-monitoring-and-weight-loss.pdf) |
| C5 | Waist-to-height ratio (with waist) | Body | `waist_height` | Waist + height | Under 0.5. 0.5–0.59 increased risk, 0.6+ high risk. Waist alternatives: US 102/88 cm, UK 94/80 cm (90 cm for South Asian men). Computed | [NICE NG246](https://www.nice.org.uk/guidance/ng246/chapter/Identifying-and-assessing-overweight-obesity-and-central-adiposity); [NHLBI](https://www.nhlbi.nih.gov/files/docs/guidelines/ob_gdlns.pdf) |
| C6 | Body fat % trend | Body | `body_fat` | Scale or manual entry | No official target; trend only | NHLBI, NICE and WHO give none |
| C7 | Protein g/kg/day | Food | `protein_per_kg` | Food log ÷ weight | Loss ≥1.2 (≥1.6 if training). Muscle 1.6 (1.4–2.0). Athletic and endurance 1.2–2.0. 65+ 1.0–1.2. General: US 1.2–1.6 (contested). Computed | [Rev Endocr Metab Disord 2025](https://link.springer.com/article/10.1007/s11154-025-09968-3); [ISSN 2017](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5477153/); [PROT-AGE](https://www.jamda.com/article/s1525-8610(13)00326-5/fulltext); [Dietary Guidelines](https://www.dietaryguidelines.gov/) |
| C8 | Protein per meal | Food | `protein_per_meal` | Food log by meal | Meals reaching 0.25–0.4 g/kg (about 20–40 g), every 3–4 h. Computed | [ISSN 2017](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5477153/) |
| C9 | Carbs g/kg/day, auto band | Food | `carbs_per_kg` | Food log ÷ weight; band from 7-day training time | Under 1 h/day → 3–5; about 1 h/day → 5–7; 1–3 h/day → 6–10; over 4 h/day → 8–12. Computed | [AND/DC/ACSM 2016](http://drugfreesport.org.za/wp-content/uploads/2018/04/Position-stand-on-Nutrition-Athletic-Performance-ACSM-2016-1.pdf) |
| C10 | Intensity minutes this week (vigorous counts double) | Activity | `intensity_minutes` | Watch activity minutes | 150. Loss/maintenance: 250+. 300 for extra benefit | [WHO 2020](https://pmc.ncbi.nlm.nih.gov/articles/PMC7719906/); [ACSM 2009 summary](https://obesitymedicine.org/blog/how-much-physical-activity-is-needed-for-weight-loss-weight-loss-maintenance-and-weight-gain-prevention/) |
| C11 | Steps today | Activity | `steps_today` | Watch | 7,000 (60+: 6,000–8,000; under 60 the benefit levels off at 8,000–10,000). No official body sets a step target | [Ding 2025](https://www.thelancet.com/journals/lanpub/article/PIIS2468-2667(25)00164-1/fulltext); [Paluch 2022](https://www.thelancet.com/pdfs/journals/lanpub/PIIS2468-2667(21)00302-9.pdf) |
| C12 | Active kcal today | Activity | `active_kcal` | Watch | About 400 kcal/day, as reported by long-term maintainers (NWCR) | [Wyatt 2002](https://onlinelibrary.wiley.com/doi/full/10.1038/oby.2002.13) |
| C13 | Strength (and balance) sessions this week | Activity | `strength_sessions` | Workout types | 2 or more. 65+: multicomponent balance and strength on 3+ days | [WHO 2020](https://pmc.ncbi.nlm.nih.gov/articles/PMC7719906/) |
| C14 | Weekly sets per muscle group | Activity | `weekly_sets` | Strength sets from the watch, if readable | About 10+ per muscle group. **Later, needs owner OK** (needs set data the app does not read yet) | [ACSM 2026](https://acsm.org/science-spotlight-acsm-releases-new-position-stand-on-resistance-training/) |
| C15 | Active days this week | Activity | `active_days` | Watch | No official number: "most days", "some is better than none" | [CDC older adults](https://www.cdc.gov/physical-activity-basics/guidelines/older-adults.html) |
| C16 | Sleep duration (stages as detail) | Body | `sleep` | Watch | 7–9 h (athletes 7–9 h, set individually) | [AHA Life's Essential 8](https://www.heart.org/en/healthy-living/healthy-lifestyle/lifes-essential-8); [Walsh 2021](https://www.sportgeneeskunde.com/wp-content/uploads/Br-J-Sports-Med-2021-Walsh-consensus-statement-sleep-and-the-athlete.pdf) |
| C17 | Overnight HRV vs your baseline | Body | `hrv_baseline` | Overnight HRV, 7-day average | Inside your own normal band; no population target | Plews and Buchheit's baseline method |
| C18 | Resting HR trend | Body | `resting_hr` | Watch, 4-week rolling | Lower or stable vs baseline. Each +10 bpm goes with about 9% higher mortality | [Zhang 2016, CMAJ](https://pubmed.ncbi.nlm.nih.gov/26598376/) |
| C19 | Weekly training load | Activity | `training_load` | **HR-zone minutes** (minutes in each heart-rate zone × zone weight) | Steady progression; no acute:chronic ratio. Computed. (Session RPE × minutes is the validated alternative but needs an RPE prompt: later, needs owner OK) | [Haddad 2017](https://www.frontiersin.org/journals/neuroscience/articles/10.3389/fnins.2017.00612/full); [Impellizzeri 2020](https://journals.humankinetics.com/downloadpdf/journals/ijspp/15/6/article-p907.xml) |
| C20 | Weekly volume + long-session check | Activity | `weekly_volume` | Workout distance and time | Flag a session more than 10% longer than your longest in the last 30 days. Computed | [Frandsen 2025, BJSM](https://findresearcher.sdu.dk/ws/files/295073070/1203.full.pdf) |
| C21 | Easy/hard split | Activity | `easy_hard` | Workout HR zones | About 75–80% easy. Computed | [PMC11679080](https://pmc.ncbi.nlm.nih.gov/articles/PMC11679080/) |
| C22 | Workout fuelling | Food | `workout_fuel` | Food logged around workouts over 60–90 min | 30–60 g carbs/h; up to 90 g/h beyond 2.5 h. Computed | [AND/DC/ACSM 2016](http://drugfreesport.org.za/wp-content/uploads/2018/04/Position-stand-on-Nutrition-Athletic-Performance-ACSM-2016-1.pdf) |
| C23 | Sweat loss / hydration | Food | `sweat_loss` | Weight before and after a workout + water log | Lose under 2% of body weight per session; then drink 1.25–1.5 L per kg lost. Computed. **Later, needs owner OK** (needs before/after weigh-ins) | same |
| C24 | Fuel balance (soft flag) | Food | `fuel_balance` | (kcal eaten − active kcal) ÷ fat-free mass | Flag a 7-day average under about 30 kcal/kg FFM as "may be under-fuelling". Never a diagnosis. Computed | [IOC REDs 2023](https://stillmed.olympics.com/media/Documents/Athletes/Medical-Scientific/Consensus-Statements/REDs/BJSM-IOC-consensus-statement-on-Relative-Energy-Deficiency-in-Sport-REDs.pdf) |
| C25 | Sodium | Food | `sodium` | Food log | Under 2,300 mg (AHA ideal 1,500 **[unverified]**; WHO under 2,000 **[unverified]**) | [Dietary Guidelines](https://www.dietaryguidelines.gov/) |
| C26 | Fiber | Food | `fibre` | Food log | 14 g per 1,000 kcal (about 25–34 g); UK 30 g **[unverified]**. Computed | [Dietary Guidelines](https://www.dietaryguidelines.gov/) |
| C27 | Total sugar (swap-in) | Food | `total_sugar` | Food log | Label it "total sugar"; added-sugar limits cannot be measured | [ACC on the 2025–2030 Dietary Guidelines](https://www.acc.org/latest-in-cardiology/articles/2026/01/27/16/22/how-do-the-2025-2030-dietary-guidelines-for-americans-measure-up-for-cardiovascular-health) |
| C28 | Body stress & rest time | Body | `body_stress` | Watch stress score | Context only; no target | [Rosenbach 2025](https://onlinelibrary.wiley.com/doi/full/10.1002/smi.70125) |
| C29 | Body signals | Body | `body_signals` | Skin temperature delta, resting HR and respiration as deviations from baseline | Flag when 2–3 rise together. Computed | [TemPredict](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8891385/) |
| C30 | How do you feel? (new input) | Body | `feel` | Manual 1–5 rating | No target; fills the gap between body stress and felt stress. **Later, needs owner OK** (new input) | [JMIR Hum Factors 2022](https://humanfactors.jmir.org/2022/3/e33754) |

**Swap-ins outside the defaults:** water (Food `water`, built; EFSA 2.0/2.5 L **[unverified]**), SpO2 (Body `spo2`, built; single value with a "not medical" note), macros (Food `macros_today` and Body `macros`, built), workouts and trends (Activity, built), aerobic decoupling (needs per-second HR and pace streams that may not be readable), VO2 max (on the watch, not yet read), and manual blood pressure, cholesterol and HbA1c cards.

**Later, needs owner OK** (inputs the app lacks): C30 "How do you feel?" (1–5 check-in), O8 (alcohol tags in the diary), session RPE after workouts (for an RPE-based C19; C19 ships on HR-zone minutes), C14 weekly sets (strength set data over Bluetooth), C23 sweat loss (before/after weigh-ins).

## Default stacks per goal and tab (phase 2 target)

The pattern: weight goals lead with food and scale cards, training goals with fuel per kg and recovery, health goals with minutes, steps and sleep. Lists are in order; cards already built are kept where they fit so nothing disappears. Phase 1 defaults stay until each card exists; when a card lands, its developer moves the table toward these rows.

| Goal | Body | Activity | Food (below the fixed calories-left header) |
|---|---|---|---|
| Lose weight | `weight` (C2), `energy` (C3), `waist_height` (C5), `sleep` (C16), `measurements` | `intensity_minutes` (C10), `steps_today` (C11), `today_rings`, `trends`, `workouts` | `protein_per_kg` (C7, ≥1.2), `logging_days` (C4), `water`, `macros_today` |
| Weight management | `weight` (C2, ±3% band), `energy` (C3), `waist_height` (C5), `sleep` (C16) | `intensity_minutes` (C10, 250–300), `active_kcal` (C12), `steps_today` (C11), `today_rings`, `trends`, `workouts` | `logging_days` (C4), `water`, `macros_today` |
| Build muscle | `weight` (C2, +0.25–0.5%/week), `waist_height` (C5), `body_fat` (C6), `sleep` (C16), `energy` | `strength_sessions` (C13), `workouts`, `intensity_minutes`, `trends` | `protein_per_kg` (C7, 1.6), `protein_per_meal` (C8), `macros_today`, `water` |
| Athletic performance | `hrv_baseline` (C17), `sleep` (C16), `resting_hr` (C18), `weight` (C2), `body_fat` (C6) | `training_load` (C19), `workouts`, `easy_hard` (C21), `trends` | `protein_per_kg` (C7), `carbs_per_kg` (C9), `fuel_balance` (C24), `water` (C23 later) |
| Endurance training | `hrv_baseline` (C17), `sleep` (C16), `resting_hr` (C18) | `weekly_volume` (C20), `easy_hard` (C21), `training_load` (C19), `workouts`, `trends` | `carbs_per_kg` (C9), `workout_fuel` (C22), `protein_per_kg` (C7), `water` |
| Heart health | `resting_hr` (C18), `sleep` (C16), `waist_height` (C5, BMI beneath), `hrv_baseline` (C17), `weight` | `intensity_minutes` (C10, 150), `steps_today` (C11), `today_rings`, `trends` | `sodium` (C25), `fibre` (C26), `water` |
| Stress and wellbeing | `sleep` (C16), `hrv_baseline` (C17), `body_stress` (C28), `resting_hr` (C18), `body_signals` (C29) (C30 later) | `steps_today` (C11), `intensity_minutes` (C10), `today_rings` | `water` |
| Stay active | `sleep` (C16), `weight` (C2, prompt on unplanned loss), `resting_hr` (C18) | `steps_today` (C11), `intensity_minutes` (C10), `strength_sessions` (C13, 3 if 65+), `active_days` (C15), `today_rings` | `protein_per_kg` (C7, 1.0–1.2), `water` |
| General health (default) | `sleep` (C16), `waist_height` (C5), `resting_hr` (C18), `weight`, `energy` | `intensity_minutes` (C10), `steps_today` (C11), `strength_sessions` (C13), `today_rings`, `trends`, `workouts` | `sodium` (C25), `fibre` (C26), `water` |

## Overlays (combined charts)

Only relationships rated Strong or Moderate share an axis; each uses the lag the evidence shows, smooths noisy series, and shows HRV, resting HR and skin temperature as deviations from the user's own baseline. Overlays live on detail screens (O1 is built on the Weight screen), not as cards.

| ID | Overlay | Rating | Lag / window | Goals | Key caveat |
|---|---|---|---|---|---|
| O1 | 7-day rolling energy balance vs smoothed weight trend (raw days as faint dots) | Strong ([Hall 2011](https://pubmed.ncbi.nlm.nih.gov/21872751/)) | Weeks | Lose, Maintain, Muscle, General | The 7,700 kcal/kg rule overstates long-run loss ([Church 2009](https://journals.plos.org/plosone/article?id=10.1371%2Fjournal.pone.0004515)); label kcal→kg as an estimate and prefer an energy use learned from the weight trend |
| O2 | Sleep (night N) vs calories (day N+1) | Strong: +385 kcal ([Al Khatib 2017](https://www.nature.com/articles/ejcn2016201)), +253 ([Zhu 2019](https://pubmed.ncbi.nlm.nih.gov/30870662/)), +204 at ≤5.5 h | Next day | Lose, Maintain, General | Big individual spread, −261 to +679 kcal ([PMC5188476](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5188476/)) |
| O3 | Weekly protein g/kg (1.6 line, strength sessions marked) vs body fat % / waist trend | Strong: 49 RCTs, levels off at 1.62 g/kg ([Morton 2018](http://breathe-edu-downloads.s3.amazonaws.com/Morton-2018.pdf)) | Weeks–months | Muscle | Small average gain; scale body fat is noisy; only alongside strength training |
| O4 | Daily training load / intensity minutes (bars) vs next-morning HRV 7-day average + resting HR, with baseline band | Moderate ([PMC12787763](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12787763/)) | Next morning; 1–4 weeks | Athletic, Endurance | Single days mislead; lower HRV is not always fatigue |
| O5 | Weekly exercise minutes vs weekly average sleep | Moderate, small effects ([Kredlow 2015](https://www.semanticscholar.org/paper/The-effects-of-physical-activity-on-sleep:-a-review-Kredlow-Capozzoli/6cd98e5376cdfce983964bea91e7dca5603dff39)) | Weekly | Stress, Aging, Athletic, General | Nightly overlays mostly show noise |
| O6 | Weekly intensity minutes vs 4-week rolling resting HR | Moderate: about −4 bpm with training in an RCT ([IJERPH 2022](https://www.mdpi.com/1660-4601/19/17/10463)) | 4+ weeks ([Buchheit 2014](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC3936188/)) | Heart, Endurance, Aging | Show over 3–6 months |
| O7 | Body signals: skin temperature delta + resting HR + respiration vs baseline | Moderate ([TemPredict](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8891385/)); luteal phase raises skin temperature up to 0.5 °C ([Zhu 2021](https://www.jmir.org/2021/6/e20710)) | Same night | Stress, General | No validation for this watch's skin temperature; never diagnostic; optional cycle-phase band |
| O8 | Alcohol vs overnight HRV / resting HR / sleep | Moderate–strong: dose-dependent over 5.1M person-days ([PLOS Digit Health](https://journals.plos.org/digitalhealth/article?id=10.1371%2Fjournal.pdig.0001284)) | Same night | Heart, Stress | **Later, needs owner OK:** needs alcohol tags in the diary |
| A1 | Annotation, not a line: "high-carb day yesterday, likely water" on weight jumps | Moderate (glycogen holds about 3 g water per g) | 1–3 days | Lose, Maintain | Varies by person |

**Kept apart on purpose:** watch stress score vs HRV (circular: the score is computed from HR and HRV, [bioRxiv 2025](https://www.biorxiv.org/content/10.1101/2025.01.06.630177v1.full.pdf)); stress score vs sleep (score unvalidated); sodium vs next-day weight (contested, [NephJC on Rakova 2017](http://www.nephjc.com/news/2017/5/31/spookysodium)); active kcal as a 1:1 offset to food (energy use plateaus, [Pontzer 2016](https://www.cell.com/current-biology/pdfExtended/S0960-9822(15)01577-8); [Curr Nutr Rep 2023](https://pubmed.ncbi.nlm.nih.gov/36933180/)); logged water vs workout HR (the log does not measure hydration, [Adams 2014](https://www.researchgate.net/publication/277306972_Influence_of_Dehydration_on_Heart_Rate_During_Exercise_in_the_Heat_A_Systematic_Review)); SpO2 vs anything (wrist readings below 95% when the reference was ≥98%, [UCL CinC 2023](https://discovery.ucl.ac.uk/10186376/1/CinC2023-237.pdf)); short sleep vs body composition (one 10-person RCT, [Nedeltcheva 2010](https://pubmed.ncbi.nlm.nih.gov/20921542/)); acute:chronic workload ratio ([Impellizzeri 2021](https://www.researchgate.net/publication/347442902_What_Role_Do_Chronic_Workloads_Play_in_the_Acute_to_Chronic_Workload_Ratio_Time_to_Dismiss_ACWR_and_Its_Underlying_Theory)); steps vs health outcomes (better as one target, [Paluch 2022](https://www.thelancet.com/journals/lanpub/article/PIIS2468-2667(21)00302-9/fulltext)); raw daily weight vs daily calories (water swings swamp the signal, [Hall model appendix](https://www.niddk.nih.gov/-/media/Files/BWP/Hall_Lancet_Web_Appendix.pdf)).

## Display and wording rules for every card

| Topic | Use | Avoid |
|---|---|---|
| Stress score | "Body stress", "rest time", "your body was working hard" | "Stress level", anxiety, anything about mood |
| HRV / resting HR | "Within your usual range", "lower than your usual"; 7-day average vs own band | "Bad HRV", population scores |
| Logging | "4 days logged this week, nice", "pick up where you left off" | Broken-streak alarms, red failure states |
| Weight | Trend first; "daily weight swings with water" | Leading with the raw daily number |
| Body signals | "Several signals are up; take it easy" | "You may be ill", fever or ovulation claims |
| Fuel balance | "You may be under-fuelling for your training" | Diagnoses or named syndromes |
| Steps / minutes | "Every extra 1,000 steps helps", "some is better than none" ([WHO 2020](https://pmc.ncbi.nlm.nih.gov/articles/PMC7719906/), [CDC](https://www.cdc.gov/physical-activity-basics/guidelines/older-adults.html)) | Pass/fail framing |
| Sugar | "Total sugar" | "Added sugar" (not measured) |
| Energy maths | "Estimated", "about" | Exact kg predictions from kcal |
| SpO2, sleep stages, body fat % | A single value or long trend with a "not medical" note | Day-level conclusions |

Obsessive logging is the most-cited harm among young app users ([JMIR mHealth 2019](https://mhealth.jmir.org/2019/6/e14239/)), and device stress is not felt stress ([JMIR Hum Factors 2022](https://humanfactors.jmir.org/2022/3/e33754)): cards describe the body and never judge the user.

## Definition of done (per card)

1. Card object, layout, strings, registry entry and preset rows as above; KDoc on the object.
2. Loader test with data and with an empty database; the smoke test shows it with demo data (portrait, and landscape at 2× font).
3. Empty state that says what will appear and how; content description; 48 dp targets.
4. Only the tables it reads in `tables`; no work on the main thread; no new library.
5. `./gradlew build` passes; the APK stays within the size budget.

# Food UI review and plan (food database v2)

Status: review and plan only, nothing implemented. 2026-11-24. Owner: HappyRock. Food cards stay HappyRock's
pick (card picks PDF); this plan only lists new card options.

## 1. What the app has today

| Screen | Today |
|---|---|
| Food tab (`FoodFragment`) | Day switcher; "kcal left" header with "Goal − Food + Activity"; the Food cards the user picked; one card per meal (entries with amount and kcal, "+ Add food", "Copy yesterday's lunch"); fasting card |
| Add food (`AddFoodActivity`) | Search field + Scan; tabs Recent · Favorites · My foods · Recipes; rows "name [source pill] / brand · serving · kcal" with a one-tap "+" (last serving); Quick add and New food buttons |
| Food detail (`FoodDetailSheet`) | Title "Add food"; name, brand · source · data year, star; serving chips; amount stepper; meal chips; one line "150 kcal · protein 3 g · fat 1 g · carbs 30 g"; "Add to Lunch"; edit as custom / delete |
| Quick add | kcal, protein, fat, carbs, optional name, meal |
| Custom food (`FoodEditorActivity`) | name, brand, per serving or per 100 g, serving label and grams, kcal, protein, fat, sat fat, carbs, sugar, fiber, sodium, cholesterol, barcode |

## 2. Does it make sense?

The flow is right and already matches the best of both apps: meal → Add food → search, scan or tabs → tap a
row → serving, amount, meal → Add. Self Watch is ahead in places: the one-tap "+" with the last serving,
"Copy yesterday's lunch" offered on the meal, the source pill on every row (Cronometer's idea), and Undo
everywhere.

What doesn't hold up once v2 adds 20 nutrients, diet flags and allergens:

1. **The food screen shows almost nothing about the food.** One line of kcal, protein, fat and carbs.
   No fiber, sugar or sodium (already stored), no nutrition facts, no "% of my goal", no diet or allergen
   info. Both reference apps show the full facts with a share of the daily target under the inputs.
2. **Serving chips don't scale.** USDA foods now have up to 8 portions plus "100 g" and "g": a cloud of
   chips. MyFitnessPal uses one "Serving size" row that opens a list; Cronometer one unit dropdown.
3. **The sheet title says "Add food"** (same as the screen behind it) instead of the food's name.
4. **Meal chips on every add** even when the meal is already chosen ("Add to Lunch"). One "Meal: Lunch"
   row is enough.
5. **No way to see a day's nutrition beyond the cards.** Cronometer's best feature is the day's targets as
   bars, with "tap a food to see its share" and "tap a bar to see the top foods". MyFitnessPal has the same
   idea as a Nutrition page with day and week views. Self Watch has only the cards.
6. **Meals show kcal only.** MyFitnessPal shows the meal's carbs, fat and protein under each meal.
7. **Custom food and Quick add can't hold v2 data** (trans fat, potassium, diet flags, allergens).
8. **Unknown vs zero.** Packaged and restaurant foods often lack vitamins and minerals. A day total that
   silently skips them looks complete when it isn't. Cronometer warns that branded foods are "a snapshot".

## 3. Best of each app to use

| From | Use | Where |
|---|---|---|
| MyFitnessPal | One "Serving size" row + "Number of servings" field | Food detail |
| MyFitnessPal | Macro split with calories in the middle, then "% of daily goals" | Food detail |
| MyFitnessPal | Carbs · fat · protein line under each meal | Food tab |
| MyFitnessPal | Nutrition page with day / week toggle | Day nutrition |
| Cronometer | Full nutrient list grouped (Energy, Fats, Carbs, Protein, Minerals, Vitamins) with % target bars | Food detail (folded), Day nutrition |
| Cronometer | Data source and how complete the food's data is ("12 of 22 nutrients") | Food detail |
| Cronometer | Consumed ↔ Remaining toggle on the targets | Day nutrition, header |
| Cronometer | Tap a diary food → bars show only that food's share | Food tab |
| Cronometer | Tap a target bar → top contributing foods that day | Day nutrition |
| Cronometer | Targets from the official intake guidelines (DRI), editable | Goals |

Skip: MyFitnessPal's ads and paywalled macro views; Cronometer's dense "everything at once" screens. Self
Watch keeps its rule: minimal first view, more on tap, strong color cues.

## 4. Plan

### 4.1 Food detail sheet (phase 1, ships with database v2)

Top to bottom:
1. **Title = food name.** Under it: brand · source · data year, and a small completeness note when the
   food is missing nutrients ("Vitamins not listed for this product").
2. **Diet line** (only what's known): badges "Vegan", "Vegetarian", "Gluten-free" (label) or "Naturally
   gluten-free"; allergens "Contains: milk, soy". A badge turns **red** when it conflicts with the user's
   diet ("Contains gluten" for a gluten-free user). Unknown shows nothing, never a false "free". Tap →
   "From the ingredient list. Always check the label."
3. **Serving size** (one row, opens a list: named servings with grams, then 100 g, g, oz) and
   **Number of servings** (number field, plus quick ½ · 1 · 1½ · 2).
4. **Meal** (one row, preselected; tap to change).
5. **Calories and macro split**: big kcal, a three-part bar or ring (carbs / fat / protein, % of the food's
   calories), grams under each.
6. **% of my day**: 4 bars (calories, protein, carbs, fat) showing what this amount adds, against the goal.
   Diet users get their own: heart-healthy adds sodium and saturated fat; athlete shows protein per meal.
7. **Nutrition facts** (folded, "Show all nutrients"): grouped list with % of daily target, units, "—" for
   unknown.
8. **Add to Lunch** (filled), then Edit as custom / Delete.

### 4.2 Food tab (phase 1)

- Meal header: kcal plus a small "C 52 g · F 18 g · P 31 g" line.
- Header "kcal left": tap to switch to "eaten" (Cronometer's Consumed ↔ Remaining).
- Long-press or select a diary food: the macro card (and the Day nutrition bars) show only that food's share.
- Opening **Day nutrition**: from the header's ⓘ / a "Nutrition" button and from the menu.

### 4.3 Add food (phase 1)

- Rows keep "name [source] / brand · serving · kcal"; add a conflict badge only when the food breaks the
  user's diet (red "Contains gluten", "Not vegan"). No badge clutter otherwise.
- Search results with the new search (everyday name first, brand mode for chains); for brand mode a small
  header "McDonald's menu".
- Filter chips under the search field only for users with a diet set: "Vegan", "Gluten-free", "No
  milk"... (hide foods known to conflict; unknown foods stay, marked "?").

### 4.4 Day nutrition screen (phase 2, new)

1. Day / Week toggle and the day switcher.
2. **Macro targets**: calories, protein, carbs, fat as bars, Consumed ↔ Remaining.
3. **Your targets** (from the diet setting): e.g. heart-healthy → sodium, saturated fat, fiber, potassium,
   added sugar; athlete → protein per kg, protein per meal, carbs per kg; vegan → B12, iron, calcium,
   vitamin D (where data exists).
4. **All nutrients** grouped, % of target bars; colors: under / on target / over a limit (limits like sodium,
   saturated fat, added sugar turn amber then red when exceeded, not green).
5. **Tap a bar** → top foods for that nutrient today.
6. **Completeness line** per nutrient when some foods lack it: "Potassium known for 6 of 9 foods".

### 4.5 Diet setting and targets (phase 3)

- Me → Goals → **Diet**: none / high protein / heart-healthy / vegetarian / vegan / gluten-free (more than
  one allowed) and **Allergens** (the 9 in the database).
- Targets: official intake guidelines by age and sex from the profile, editable; presets per diet
  (heart-healthy: sodium ≤ 1,500–2,300 mg, saturated fat < 6–10% of kcal, added sugar < 10% of kcal,
  fiber 14 g per 1,000 kcal; athlete: protein 1.2–2.0 g/kg). Numbers to be confirmed against the current
  guidelines before shipping.
- Offer, don't force: after two weeks with no meat logged, a gentle "Set vegetarian?" (HappyRock's
  auto-detect preference).

### 4.6 Custom food and Quick add (phase 3)

- Custom food: "More nutrients" fold with the v2 nutrients; diet checkboxes (vegan, vegetarian,
  gluten-free) and allergen checkboxes; category picker; extra servings (Cronometer's "+ serving size").
- Quick add stays four numbers (fast is the point).

### 4.7 New Food card options (for HappyRock's picks)

Saturated fat, Potassium, Added sugar, Heart-healthy (sodium + saturated fat + fiber), Micronutrients
(share of targets met, with completeness), Diet check (foods today that conflict with the diet).

## 5. Questions for HappyRock

1. Default on the header and bars: "left" (MyFitnessPal) or "eaten"?
2. Macro split on the food screen: bar or ring?
3. Diet badges on search rows: only conflicts (proposed) or always?
4. Day nutrition: include the week view in phase 2, or later?
5. Which nutrients go in "Your targets" for each diet (list in 4.4 is a starting point)?

Sources: [Cronometer: Add a food](https://support.cronometer.com/hc/en-us/articles/360018193011-Add-a-Food),
[Cronometer: Mobile daily report](https://support.cronometer.com/hc/en-us/articles/32689033683220-Mobile-Daily-Report),
[Cronometer: Macronutrient breakdown](https://support.cronometer.com/hc/en-us/articles/31119712060308-Macronutrient-Breakdown),
[MyFitnessPal: Today tab](https://support.myfitnesspal.com/hc/en-us/articles/39985611667341-Introducing-the-brand-new-Today-tab),
[MyFitnessPal: Nutrient dashboard](https://support.myfitnesspal.com/hc/en-us/articles/360032272532-Customize-your-Nutrient-Dashboard-with-MyFitnessPal-Premium).

# Prior art: calorie counting apps

Researched 2026-11-22 for the food tracker in Self Watch (see [DESIGN.md](DESIGN.md)).

**Rule: take inspiration only, never copy.** Don't use their names, logos, icons, colors, wording, screen layouts as a whole, or database content. These notes describe general patterns (search bar, tabs, rings) that are common across the category.

**AI features are left out on purpose** (photo or meal scanning, voice logging, AI coaching). Self Watch will have none.

## Apps reviewed and why

- **MyFitnessPal:** the largest by downloads.
- **Lose It!:** the most beginner-friendly, and strong on streaks and badges.
- **Cronometer:** the most accurate data and the best privacy of the three.

By downloads, Yazio is #2 worldwide and FatSecret has more installs than Cronometer. FatSecret is worth a later look because its barcode scanning is still free.

## Source quality

- Lose It's help center and parts of MyFitnessPal's support site would not load, so some official wording comes from search snippets.
- Many 2026 reviews are blogs run by competing apps.
- The screen layouts are partly from memory and could not be checked against 2026 screenshots. Treat them as approximate.

## 1. Profiles

| | MyFitnessPal | Lose It! | Cronometer |
|---|---|---|---|
| Price | Free; Premium $19.99/mo or $79.99/yr; Premium+ $24.99/mo or $99.99/yr | Free; Premium $79.99/yr (up from $39.99); lifetime $299.99 | Free; Gold $10.99/mo or $59.99/yr |
| Ads on free tier | Yes, including full-screen | Yes | Yes |
| Account required | Yes | Yes | Yes |
| Privacy | 2018 breach (~150M accounts); policy allows sharing with ad partners | Policy reportedly says it may sell sensitive data (Texas notice); unverified | Best of the three: doesn't sell personal data; free CSV export |
| Food data | Crowdsourced, 14–20M entries, known accuracy problems | Crowdsourced plus verified | ~1M lab-analyzed or verified foods, up to 95 nutrients; weak on restaurants |
| Garmin | Yes; its calorie "adjustment" confuses users | Premium only | Free; imports many health metrics |

## 2. Feature matrix

F = free, P = paid, ? = not confirmed.

| Feature | MyFitnessPal | Lose It! | Cronometer | **Self Watch** |
|---|---|---|---|---|
| Barcode scan | P | P (new accounts) | F | **F; offline once the food database is installed** |
| Food search | F | F | F | **F; offline once the food database is installed** |
| Recent, frequent, favorites | F | F | F | **F** |
| Copy meal or previous day | F (one meal) | F | F? | **F** |
| Saved meals | F | F | F | **F, in the recipe book** |
| Recipe builder | F | F? | F | **F, with recipe import and export** |
| Recipe import from a web page | Disputed | P? | P | Later |
| Quick-add calories | F (macros P) | F | F? | **F** |
| Custom foods | F | F | F | **F** |
| Restaurant and chain foods | Large, crowdsourced | Yes | Limited | **96 chains (2017–18 data), user corrections** |
| Serving sizes | Yes | Yes | Yes | **Yes, grams always available** |
| Custom meal slots | F (up to 6) | F | F? | **F** |
| Macro goals in grams | P | P | F | **F** |
| Micronutrients | Partly P | P | F | **F, as far as the data allows** |
| Net carbs | P | P? | F | **F** |
| Intermittent fasting | P | P | P | **F** |
| Water | F | Goal P | F | **F** (also from the watch) |
| Weight log and charts | F | F | F (charts 7 days) | **F** |
| Body measurements | F? | P | Partly P | **F** |
| Watch calories | F (confusing) | P | F | **F, direct from the watch, with a clear formula** |
| Steps | F | P | F | **F, direct from the watch** |
| Meal planning | P+ | P | No | Later |
| Grocery list | P+ | No? | No | Later |
| Reminders | F | F | F | **F** |
| Streaks and badges | Streak counter | Strong | Minimal | Optional, quiet |
| Social / community | Yes | Yes | Forums | No |
| Reports and charts | Basic F | Partly P | 7 days F | **F, any range** |
| Data export | **P** | F (web only) | F (web only) | **F, from the phone: CSV, JSON, FIT, GPX, TCX, backup** |
| Data import | No? | No (official) | No CSV import? | **F: all three, plus Garmin Connect, Gadgetbridge, FIT, GPX, TCX** |
| Works offline | Recent foods only | Partial | Mostly online | **Fully, once the food database is installed** |
| Watch app | Apple Watch, Wear OS | Apple Watch | Apple Watch | **Garmin first; other brands ported later** |
| Account | Required | Required | Required | **None** |
| Ads | Yes | Yes | Yes | **None** |

## 3. What users like and dislike

**MyFitnessPal**
- **Liked:** the biggest database and the most integrations.
- **Disliked:**
  - barcode scanning moved behind the paywall (the #1 complaint and the main reason people leave),
  - the 2026 redesign added taps,
  - full-screen ads,
  - sync failures and crashes,
  - duplicate and inaccurate entries.

**Lose It!**
- **Liked:** simple, friendly, and streaks help people keep going.
- **Disliked:**
  - barcode scanning quietly made paid,
  - the price doubled,
  - the free tier is calories only,
  - weaker accuracy.

**Cronometer**
- **Liked:** accurate data, micronutrients free, barcode scanning and integrations free, free export.
- **Disliked:**
  - the slowest, densest logging,
  - weak restaurant and non-US coverage,
  - ads,
  - charts limited to 7 days on the free tier.

## 4. Screen patterns (approximate)

**Add-food screen.** All three put a search field at the top with a barcode button next to it or just under it, and tabs underneath.
- MyFitnessPal: All / My Meals / My Recipes / My Foods.
- Lose It!: My Foods / Meals / Recipes.
- Cronometer: All / Favorites / Common / Custom.

Before you type, they show history:
- MyFitnessPal: a list sortable by recent, frequent or A–Z, with a one-tap "+" on each row.
- Lose It!: meals from the past few weeks, where you tick items to re-log them.
- Cronometer: recent and frequent foods with a source tag on each row.

**Day view.**
- MyFitnessPal: a "Goal − Food + Exercise = Remaining" strip, then meal sections with an "Add food" link and a "…" menu, then exercise with the watch adjustment row.
- Lose It!: a calorie ring with three chosen nutrients, meal sections, and an "Add yesterday's [meal]" shortcut.
- Cronometer: three circles (eaten, burned, left), a swipeable banner of macro and nutrient bars, and a mixed list of food, exercise, measurements and notes.

**Serving entry.** All three use a serving-unit picker plus an amount, with a live nutrition preview underneath.

## 5. Gaps Self Watch can fill

1. **No account, private by default, no ads.** All three require an account, keep your diary on their servers, and pay for the free tier with ads.
2. **Truly offline.** None of them can search or scan reliably offline. A local `foods.db` beats all three.
3. **Nothing behind a paywall.** Barcode scanning, gram macros, net carbs, fasting, water goals, export and long-range charts are each paid somewhere.
4. **Fastest logging.**
   - one-tap re-log of a recent food with its last serving,
   - yesterday's meal offered inline,
   - add several foods at once,
   - quick-add calories in two taps,
   - sensible default servings.
5. **Trust in the data.** Show the source and year on every food, merge duplicates, and give restaurant items a "data from" date.
6. **Clear watch-calorie maths.** "Goal − Food + Activity (from watch) = Remaining", with one rule explained on screen. Self Watch reads the watch directly, so there's no confusing adjustment line.
7. **Free, complete import and export.** Bring history in from all three apps, Garmin Connect and Gadgetbridge; take every entry out as CSV, JSON, workout files or a full backup, from the phone.
8. **Calories left on the watch,** sent through Self Watch's own watch messaging. None of these apps send food data back to a Garmin watch.

## Main sources

- https://www.myfitnesspal.com/premium
- https://support.myfitnesspal.com/hc/en-us/articles/360032273352-Data-Export-FAQs
- https://support.myfitnesspal.com/hc/en-us/articles/360032624771
- https://community.myfitnesspal.com/en/discussion/10835692/garmin-connect-calorie-adjustment-confusing
- https://loseit.zendesk.com/hc/en-us/articles/51906523474708 (pricing tiers, via search snippet)
- https://loseit.zendesk.com/hc/en-us/articles/47649133343380-Using-the-Log-Header-in-Lose-It
- https://cronometer.com/gold/
- https://cronometer.com/blog/food-search-updated/
- https://support.cronometer.com/hc/en-us/articles/360060616191-Energy-Summary
- https://support.cronometer.com/hc/en-us/articles/16957825757204-Garmin
- https://www.cnbc.com/2018/03/29/under-armour-stock-falls-after-company-admits-data-breach.html
- https://unstar.app/blog/is-myfitnesspal-premium-worth-it-paywall-app-reviews-2026
- https://macros.gg/articles/loseit-barcode-scanner (competitor-run blog)
- https://www.statista.com/statistics/1660940/top-fitness-and-sport-apps-downloads/

**Not confirmed:**
- Lose It's database size and whether its recipe import is paid;
- whether Lose It! and Cronometer have Wear OS apps;
- whether MyFitnessPal's recipe import became paid in May 2026;
- home screen widget support in each app;
- how Cronometer behaves offline.

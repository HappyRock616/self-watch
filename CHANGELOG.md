# Changelog

## 0.0.3

- Sleep: a night that the watch hands over in two parts (before and after a sync at midnight) is now one night. It starts when you fell asleep, not at midnight, counts all of it, and belongs to the day you wake up. Sleep stages are read from every watch file, not only the sleep file. The watch's original sleep files are kept on the phone for 14 days so an odd night can be checked.
- Fresh data while you look at the app: Self Watch syncs when you open it (if the last sync is over 5 minutes old) and every 15 minutes while it stays open. No need to tap Sync now.
- Stuck link guard: if syncs keep finishing with no new watch data for 4 hours during the day, Self Watch reconnects to the watch by itself (at most every 2 hours), instead of needing a phone restart.
- Intensity minutes now match Garmin Connect: only the minutes the watch itself credits as moderate (count 1) or vigorous (count 2) exercise, rated from heart rate. Before, ordinary walking around counted too. Past days show 0 because the old figures could not be corrected.
- Watch pairing: the pairing list shows Garmin watches by name. Nameless Garmin devices nearby no longer show up as hex addresses. If the watch is not in the list, Self Watch explains how to put it in pairing mode (Pair phone on the watch).

## 0.0.2

A big redesign of every page, a much better food database, and a new Devices page.

- Cards: every card comes in a small (half-width) and a large size, and any card can go on any tab. One clean default layout per tab that only shows what your devices record. Goals are the main display: Body today and Activity goals show every goal at a glance.
- Body: Body today (calories eaten and burned, water, energy level, sleep, weight against your goals), Sleep, HRV, resting heart rate, stress, readiness, body signals and blood oxygen, plus an optional catalog covering what Gadgetbridge and Garmin Connect offer (body composition, blood pressure, temperature, naps, battery, last sync and more).
- Activity: Activity goals, weekly effort, workout time this week, VO2 max (running and cycling, read from the watch), heart-rate recovery, Day clock, Live heart rate and Weather. Recent activities lists your latest workouts with full stats.
- Workouts: pick a sport in All activities for a trends dashboard (heart rate, calories, distance, pace, heart-rate recovery, VO2 max and more, each switchable), and trim a workout from the start or end when you forgot to stop the watch. Pace shows as minutes and seconds.
- Food: Daily balance (calories eaten, burned and left; protein, carbs and fat against balanced ranges; fat types, fiber, sodium, added sugar) and a Weight goal card that works out safe daily calories to reach your goal weight by your date. Tap a meal for its nutrition summary; remove foods by swipe or the (−) button; recent foods per meal with one-tap add.
- Food database v2: about 266,000 foods with trans, mono and poly fat, added sugar, potassium, calcium, iron and vitamins A, C and D, plus diet flags and allergens. Search shows the everyday food first ("carrot", "coke", "big mac", "mcdonalds"). It downloads automatically on Wi-Fi the first time you add food.
- Diet and allergens: set vegan, vegetarian, gluten-free, heart-healthy or high protein and your allergens; foods show badges, clashes turn red, and Day nutrition shows your targets by day or week. Targets can be edited.
- Devices: one card per device (watch, strap, scale); connect several at once, remove a device, or hold for 3 seconds to reset its data (workouts are kept). A connected heart-rate strap always wins over the watch.
- Weight loss: pick a goal weight and date and the app fills in a safe calorie goal.
- Privacy: nothing is sent unless you allow it, except the food database download, watch app web requests (for watch apps such as TCS), the local phone-app link and GPS help for the watch. Barcode lookups start off. Permissions are buttons that open the right Android setting.
- Units: everything follows your Metric or Imperial choice in Me › Settings; water counts 12 fl oz (350 ml) glasses.
- Watch battery: no layer retries the Bluetooth link faster than every 5 seconds.

## 0.0.1

First build.

- Color themes: pick one of five themes (Turquoise, Purple, Orange, Yellow, Forest, the same as the Just Go 5K watch app) in Me › Settings; it colors every screen and chart, and changes apply at once.
- Body: your own stack of cards (calories in vs out, protein and macros, steps, vigorous minutes, weight, measurements, recovery, blood oxygen), reorder or hide them; the default set follows your main goal.
- Activity: steps, active minutes, streaks and trends; workouts with route, heart rate and splits. Its cards can be reordered or hidden too.
- Food: the cards between calories left and the meals (water, protein and macros) can be reordered or hidden. Changing your main goal can switch the cards on Body, Activity and Food in one go.
- Sleep: stages, heart rate, HRV, breathing and SpO2.
- Food diary: search, barcode scanning with the built-in camera, custom foods, meals and recipes. Copy a day or one meal to another day (with undo), rename, reorder, add or remove meals, and export a day as CSV.
- Recipe book: import recipe files, export the whole book, filter by tag.
- The + button logs a glass of water or starts and ends a fast in one tap.
- Garmin watch pairing and sync over Bluetooth, notifications, and phone-app ↔ watch-app messaging.
- Import from Gadgetbridge, Garmin Connect exports, FIT/GPX/TCX and calorie-counting apps; export to CSV, JSON, GPX, TCX and FIT.
- Backups (optionally encrypted) and Health Connect sync (Android 14+).
- Privacy center with a switch for each internet feature and a log of every request.
- Help built into the app (works offline), Bluetooth help with quick fixes, and Export activities: every workout in a date range as GPX, FIT or TCX in one zip.

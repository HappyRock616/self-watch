// Section: app layout, screens, menus, code structure. Included by build.js.
module.exports = function layoutSection(ctx) {
  const { c, H1, H2, P, B, N, Note, table, gap, Paragraph, TextRun, ImageRun, AlignmentType, fs, path, nextNum } = ctx;
  const mono = (t) => new Paragraph({ spacing: { after: 0 }, children: [new TextRun({ text: t, font: "Consolas", size: 16 })] });

  c.push(H1("10. App layout"));
  c.push(P("Five bottom tabs. **Body is the home screen, and physical activity comes first.** A **+** button sits on Body and Food. Full details and wireframe source are in docs/APP_LAYOUT.md and docs/layout/.", { keepNext: true }));
  const img = path.join(__dirname, "..", "layout", "wireframes-2rows.png");
  const b = fs.readFileSync(img); const w = b.readUInt32BE(16), h = b.readUInt32BE(20);
  c.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 60 }, children: [new ImageRun({ type: "png", data: b, transformation: { width: 540, height: Math.round(540 * h / w) }, altText: { title: "Wireframes", description: "Self Watch wireframes: Body, Activity, Food diary, Add food, Sleep overlay, Devices, Me", name: "wireframes" } })] }));
  c.push(Note("Wireframes show layout only. Colors use the Self Watch palette; numbers are sample data."));

  c.push(H2("Tabs"));
  c.push(table(["Tab", "Purpose"], [
    ["Body (home)", "Calories in vs out (today and 7 days), weight trend against goal, body measurements (waist, hips, body fat), recovery (sleep, resting HR, HRV), watch status"],
    ["Activity", "Steps, active minutes and active calories rings; long trends over weeks, months and a year (walking distance, steps, active minutes) with toggle chips; recent activities and workouts; streak"],
    ["Food", "Food diary by day: meals, inline \"add yesterday's meal\", water, fasting"],
    ["Devices", "Watch and gadgets: sync, find, watch apps and their settings, notifications, alarms, linked phone apps"],
    ["Me", "Privacy center, goals, profile, data (import, export, backup), Health Connect, food data, appearance, settings, about"],
  ], [1600, 7760]));
  c.push(P("**+ sheet:** Scan barcode · Search food · Quick add calories · Log weight · Log measurements (waist, hips, chest, body fat) · Log water · Start or stop fast."));

  c.push(H2("Screens"));
  c.push(table(["Screen", "Shows", "Main actions"], [
    ["Add food", "Search with Scan; tabs Recent · Favorites · My foods · Recipes; source tag on each row", "One-tap re-log of the last serving; food detail; quick add; copy from menu board"],
    ["Barcode scan", "Camera view (CameraX + ZXing)", "Found: food detail. Not found: Open Food Facts lookup if allowed, then custom-food form"],
    ["Food detail", "Serving picker (named servings and grams), amount, nutrient preview, source and data year", "Add to meal, favorite, edit as custom food"],
    ["Custom food / meal", "Name, brand, servings, nutrients; meals hold several foods", "Save, delete"],
    ["Recipe book / recipe", "Recipes and saved meals with calories per serving, search and tags; a recipe shows scalable servings, ingredients with their matched foods, nutrition per serving and steps", "Log a serving, fix a match, create, edit, import, export"],
    ["Measurements", "Waist, hips, chest, arms, thighs, body fat, each with history and trend; custom measurements allowed", "Log, edit, add a custom measurement"],
    ["Sleep detail", "Hypnogram with HR, HRV, breathing, SpO2, movement and temperature overlays; Night · Week · Month", "Toggle lines, scrub the chart, change range"],
    ["Heart / Activity detail", "HR, resting HR, HRV and stress overlaid; or steps, intensity minutes and active calories, with calories eaten", "Same chart controls"],
    ["Weight and body", "Weight with trend line and goal; body fat, BMI, measurements", "Log weight, edit goal"],
    ["Workouts", "Workout list; detail with charts and the GPS route drawn from its points (no map tiles, so no network)", "Open, export GPX (default), FIT or TCX"],
    ["Watch apps / settings", "Installed Connect IQ apps; settings editor from the app's settings definition", "Open on watch, install from file, delete, save settings, restore saved copy"],
    ["Linked phone apps", "Phone apps allowed to message watch apps", "Allow, revoke"],
    ["Privacy center / Network activity", "Requests today, per-feature firewall switches, permissions; full request log", "Toggle features, clear or export log"],
    ["Goals / Profile", "Calories (automatic or manual), macros in grams, steps, weight, water, fasting; height, birth year, sex for BMR, units follow the phone", "Save"],
    ["Settings", "Connection mode (always connected, or sync only), food tracking on or off, units, notifications", "—"],
    ["Import", "Pick a file (Gadgetbridge or Pulse backup, Garmin Connect export, FIT / GPX / TCX, MyFitnessPal, Lose It!, Cronometer, recipe files, food lists, Self Watch backup); preview with detected dates and units", "Confirm, change units or date format, undo a past import"],
    ["Export", "Self Watch backup; CSV (Cronometer layout) or JSON; Garmin body CSV or FIT weight file; activities as GPX, FIT or TCX (one, or all in a date range); recipe book; date range", "Export to a file or share"],
    ["Data / Food data", "Import, export, backup and restore, automatic backup and workout export, Health Connect; food database installed or not, version and size", "Open each; download food data (Wi-Fi only), load from file, remove"],
    ["Onboarding", "Welcome and promise → profile → goals → pair watch → bring your history (import) → permissions (only what's needed) → food data (optional)", "Skip any step"],
  ], [2000, 4200, 3160]));

  c.push(H2("Menus (top-bar overflow)"));
  c.push(table(["Screen", "Menu items"], [
    ["Body", "Edit cards · Sync now"],
    ["Activity", "Range · Activity goals · Export activities (GPX, FIT, TCX)"],
    ["Food diary", "Go to date · Copy day · Recipe book · Meal names · Export day"],
    ["Add food", "Create custom food · Create recipe · Create saved meal · Scan settings"],
    ["Recipe book", "Import recipes · Export recipe book · Tags"],
    ["Body detail screens", "Range · Compare with previous period · Export data · Chart settings"],
    ["Devices", "Pair new device · Device order · Bluetooth help"],
    ["Watch apps", "Install from file · Refresh list"],
    ["Me", "Help · About"],
  ], [2400, 6960]));

  c.push(P("**Code structure:** see section 11."));
};

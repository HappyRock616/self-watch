const fs = require("fs");
const path = require("path");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, WidthType, ShadingType,
  BorderStyle, AlignmentType, LevelFormat, Footer, PageNumber, ImageRun, HeadingLevel, PageBreak,
} = require("docx");

const GREEN = "1F6F5C", GREY = "777777", LINE = "C9D3CF", FONT = "Calibri";
const SHOTS = path.join(__dirname, "shots");
const OUT = process.argv[2];

// ---------- helpers ----------
function runs(text, base = {}) {
  // **bold** support
  const out = [];
  text.split(/(\*\*[^*]+\*\*)/).forEach((part) => {
    if (!part) return;
    if (part.startsWith("**")) out.push(new TextRun({ ...base, text: part.slice(2, -2), bold: true }));
    else out.push(new TextRun({ ...base, text: part }));
  });
  return out;
}
const P = (t, o = {}) => new Paragraph({ spacing: { after: 120 }, ...o, children: runs(t, o.run || {}) });
const H1 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun(t)] });
const H2 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun(t)] });
const B = (t, level = 0) => new Paragraph({ numbering: { reference: "bullets", level }, spacing: { after: 60 }, children: runs(t) });
let numInst = 0, lastWasNum = false;
const N = (t) => new Paragraph({ numbering: { reference: "numbers", level: 0, instance: numInst }, spacing: { after: 60 }, children: runs(t) });
const Note = (t) => new Paragraph({ spacing: { after: 120 }, children: runs(t, { italics: true, color: GREY, size: 19 }) });
const PB = () => new Paragraph({ children: [new PageBreak()] });

const border = { style: BorderStyle.SINGLE, size: 4, color: LINE };
const borders = { top: border, bottom: border, left: border, right: border };
const margins = { top: 60, bottom: 60, left: 100, right: 100 };

function table(headers, rows, widths, opts = {}) {
  const total = widths.reduce((a, b) => a + b, 0);
  const cell = (text, w, head, shade) => new TableCell({
    width: { size: w, type: WidthType.DXA }, borders, margins,
    shading: head ? { fill: GREEN, type: ShadingType.CLEAR, color: "auto" } : shade ? { fill: shade, type: ShadingType.CLEAR, color: "auto" } : undefined,
    children: String(text).split("\n").map((line) => new Paragraph({
      spacing: { after: 0 },
      children: runs(line, head ? { bold: true, color: "FFFFFF", size: 19 } : { size: 19 }),
    })),
  });
  return new Table({
    width: { size: total, type: WidthType.DXA }, columnWidths: widths,
    rows: [
      new TableRow({ tableHeader: true, children: headers.map((h, i) => cell(h, widths[i], true)) }),
      ...rows.map((r) => new TableRow({ cantSplit: true, children: r.map((c, i) => cell(c, widths[i], false, opts.shadeLast && i === r.length - 1 ? "E8F2EF" : null)) })),
    ],
  });
}
const gap = () => new Paragraph({ spacing: { after: 120 }, children: [] });

function jpgSize(file) {
  const b = fs.readFileSync(file); let i = 2;
  while (i < b.length) {
    if (b[i] !== 0xff) { i++; continue; }
    const m = b[i + 1];
    if (m >= 0xc0 && m <= 0xc3) return { h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7) };
    i += 2 + b.readUInt16BE(i + 2);
  }
}
// grid of screenshots with captions, `cols` per row, fixed image width in px (96 dpi)
function shotGrid(items, cols = 4) {
  const colW = Math.floor(9360 / cols);
  const imgW = Math.round((colW / 1440) * 96) - 14;
  const none = { style: BorderStyle.NONE, size: 0, color: "FFFFFF" };
  const nb = { top: none, bottom: none, left: none, right: none };
  const rows = [];
  for (let i = 0; i < items.length; i += cols) {
    const slice = items.slice(i, i + cols);
    while (slice.length < cols) slice.push(null);
    rows.push(new TableRow({
      cantSplit: true,
      children: slice.map((it) => new TableCell({
        width: { size: colW, type: WidthType.DXA }, borders: nb, margins: { top: 40, bottom: 80, left: 60, right: 60 },
        children: it ? (() => {
          const f = path.join(SHOTS, it.file + ".jpg"); const s = jpgSize(f);
          return [
            new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 40 }, children: [new ImageRun({ type: "jpg", data: fs.readFileSync(f), transformation: { width: imgW, height: Math.round(imgW * s.h / s.w) }, altText: { title: it.title, description: it.title, name: it.file } })] }),
            new Paragraph({ spacing: { after: 20 }, children: [new TextRun({ text: it.title, bold: true, size: 17, color: GREEN })] }),
            new Paragraph({ spacing: { after: 0 }, children: [new TextRun({ text: it.note, size: 17 })] }),
          ];
        })() : [new Paragraph({ children: [] })],
      })),
    }));
  }
  return new Table({ width: { size: colW * cols, type: WidthType.DXA }, columnWidths: Array(cols).fill(colW), rows });
}

// ---------- content ----------
const c = [];
c.push(new Paragraph({ spacing: { after: 60 }, children: [new ImageRun({ type: "png", data: fs.readFileSync(path.join(__dirname, "..", "..", "branding", "icon", "png", "self-watch-icon-192.png")), transformation: { width: 72, height: 72 }, altText: { title: "Self Watch icon", description: "Self Watch app icon", name: "icon" } })] }));
c.push(new Paragraph({ style: "Title", children: [new TextRun("Self Watch")] }));
c.push(P("A private health and food tracker, built from the ground up  ·  Design outline and background research  ·  November 2026  ·  Draft v0.9", { run: { color: GREY } }));
c.push(P("**Top rule: size and efficiency of the final product come first.** When two options are close, pick the smaller and faster one, even if it is more work to build. Private, free, no ads, no account and no AI features."));
c.push(P("**v0.4 change (HappyRock, 22 Nov 2026): Self Watch is a ground-up build, not a fork of Gadgetbridge.** It combines the best parts of every reference app into one new app. It works fully without a food database, and Tony's foods.db plugs in later. **Gadgetbridge, Pulse and Internet Helper are references, not code sources** (HappyRock, v0.5): we read them to learn how things work, especially the Garmin protocol, then write our own code. Libraries are fine for standard plumbing; everything that makes Self Watch what it is gets written for it. It will be a large codebase, and that's accepted."));

c.push(H1("Slogans"));
c.push(P("Core idea: **my body, my data.** Slogans kept so far:"));
["Your body. Your data. Your business.", "Know thyself. Tell no one.", "Measured by you. Seen by no one.", "Your pulse is nobody's business.", "Tracked by you, for you."].forEach((t) => c.push(B("**" + t + "**")));
c.push(P("**Name: Self Watch.** Screened by web search only (not a legal clearance); it is close to descriptive, so run a USPTO search before publishing."));

c.push(H1("Naming and branding rules"));
c.push(B("Self Watch has its own name and app ID, so it installs alongside Gadgetbridge and isn't mistaken for it. Don't use the Gadgetbridge or Pulse names or branding."));
c.push(B("No code, artwork or fonts are copied from the references. Thank Gadgetbridge, Pulse and Internet Helper as references in the About screen and README."));
c.push(B("The same applies to competitors in this document: no use of their names, logos, colours, screen designs or wording in the product."));

// 1
c.push(H1("1. What Self Watch is"));
c.push(P("A new Android app, built from the ground up for GrapheneOS and F-Droid users. No Google services, no account, no ads, no AI features. One app for:"));
numInst++; c.push(N("**Body:** weight, body measurements (waist, hips, body fat), and calories in vs out."));
c.push(N("**Activity:** steps, active minutes, workouts and long trends, read straight from the watch. Physical activity comes first."));
c.push(N("**Food:** a diary with search, barcode scanning, custom foods and saved meals. It works on day one without a food database."));
c.push(N("**Watch:** pairing, sync, notifications, Connect IQ app install and settings, and two-way messaging between phone apps and watch apps."));
c.push(N("**Import and export** in the formats the reference apps use, so people can move in and out freely."));
c.push(P("**How the reference apps are used.** All Self Watch code is written for Self Watch. Gadgetbridge, Pulse and Internet Helper are open source, so we can read exactly how they talk to a Garmin watch or read FIT files, then write our own version. Closed apps (Garmin Connect, MyFitnessPal, Lose It!, Cronometer) are inspiration for features only."));
c.push(table(["Reference (Garmin Apps\\GadgetbridgeFork)", "Version", "What we learn from it"], [
  ["Gadgetbridge", "master 160beab41 (21 Nov 2026), ~900k lines", "How the Garmin protocol works (~24k lines of their code); which Garmin models exist and what each supports; Bluetooth connection handling (~11k); the FIT file format; GPX export; the Garmin Connect export layout; Health Connect sync (~6k)"],
  ["Pulse", "4ef13c310 (24 Oct 2026)", "Ideas for the Today screen, streaks, weekly review and widgets"],
  ["Internet Helper", "master b9be971 (21 Nov 2026), ~1.6k lines", "What a network helper must offer: HTTP now; Wi-Fi, FTP and UDP when a device needs them"],
], [2600, 2400, 4360]));
c.push(gap());
c.push(P("**Scale:** Gadgetbridge's Garmin and Bluetooth code alone is about 50,000 lines; Self Watch will be a large codebase written from scratch, and that's accepted. **Devices: Garmin first** (193 models in Gadgetbridge). Smart scales and other brands are added one family at a time, as people need them, with Gadgetbridge as the reference. This is the main trade-off of a ground-up build: Gadgetbridge's 670 models don't all arrive on day one."));

// 2
c.push(H1("2. Best of each reference app"));
c.push(table(["From", "What Self Watch takes"], [
  ["Garmin Connect", "Watch-app install and settings with proper labels; two-way messaging between phone apps and watch apps; one daily view of body, activity and sleep; long-range trends"],
  ["Gadgetbridge", "Local-only data and no account; the Garmin protocol, FIT handling, exports and imports; an Intent interface for other apps; a firewall for each feature"],
  ["Pulse", "A Today-style home screen with a goal ring, streaks, a weekly review, widgets, and themes with accent colours"],
  ["MyFitnessPal", "Large searchable food list; one-line food rows with name, serving and calories; weekly macro chart"],
  ["Lose It!", "Simple day log with a budget ring; \"add yesterday's meal\"; streaks and badges; an encouraging weight-goal view"],
  ["Cronometer", "Source tag on every food; nutrient targets as percentage bars; free barcode scanning; free, complete export"],
  ["Internet Helper", "The network engines, behind a firewall and a request log the user can read"],
  ["**Self Watch's own**", "Overlay charts with toggle chips (for example sleep stages with heart rate, HRV and breathing); calories left shown on the watch; works fully offline; nothing paywalled; free import and export from the phone"],
], [2000, 7360], { shadeLast: false }));

// 3
c.push(H1("3. Key decisions"));
c.push(table(["Topic", "Decision", "Why"], [
  ["Language", "**Kotlin** for all code.", "Android's Bluetooth, notifications, services and Health Connect only work from Java or Kotlin. A Rust app would still need the Java runtime plus a Rust library per processor type, so it would be bigger, not smaller."],
  ["Rust", "None, unless profiling shows a real slow spot.", "SQLite already does the heavy work. Battery goes on the radio and wakeups, not computing."],
  ["Screens", "Android Views. No Jetpack Compose.", "Compose adds a few MB."],
  ["Oldest Android", "**Android 8.0** (minSdk 26); target the newest.", "Modern date and time code is built in from 8.0, so no extra compatibility library. Covers nearly every phone in use."],
  ["Structure", "One app module plus small library modules (section 11). **No Hilt, no database mapping library, no chart library.**", "Each library adds size and build time. A custom chart view gives the overlay charts their own look."],
  ["Libraries", "Standard plumbing only: AndroidX core, AppCompat, Fragment, RecyclerView and Lifecycle; Kotlin coroutines; Material Components; protobuf-lite (Garmin protocol); CameraX and ZXing (barcodes); Health Connect client. Anything more needs a reason.", "All open source, no Google Play Services, fine for F-Droid."],
  ["Build size", "Code and resource shrinking. **No native libraries, so one small download works on every phone.**", ""],
  ["Background", "**Only the watch connection runs all the time,** in its own small process. Food, import and export and screens load only when opened. Mode: always connected (default) or sync only; a Food tracking switch can hide food entirely.", "Battery and memory. Garmin Connect and Gadgetbridge also keep an always-on connection; Self Watch keeps that part small."],
  ["Data on the phone", "One writable selfwatch.db for everything the user owns, plus a read-only foods.db that can be replaced. Android's built-in SQLite.", "Food data updates never touch the user's records."],
  ["Food data", "**Optional and pluggable.** Downloaded compressed after install, or loaded from a file. FTS4 search.", "The app works without it; food updates don't need an app release."],
  ["Food data build", "On the PC, by Tony: DuckDB, Parquet files between steps; DuckDB writes the final SQLite file.", "Fast with very large CSV and JSON files."],
  ["One app", "Internet access built in; no separate helper app.", "One install."],
  ["License", "**AGPL-3.0** (recommended); the client kit for other apps **Apache-2.0**.", "Our code is our own, but we study AGPL code closely; the same license removes any doubt if parts end up similar. A permissive client kit lets any app use it."],
], [1700, 3900, 3760]));

// 4
c.push(H1("4. Watch messaging"));
c.push(B("**Both directions:** phone app to watch app, and watch app to phone app, written from scratch with Gadgetbridge's watch-app communicator as the reference. Gadgetbridge itself only delivers messages for its two built-in watch apps."));
c.push(B("**For other apps:** a small client library or plain Intents. Self Watch also accepts Gadgetbridge's SEND_WATCH_APP_MESSAGE Intent, so apps already written for Gadgetbridge work unchanged."));
c.push(B("**Controls:** a permission for each phone app; delivery confirmation; an \"is the app installed on the watch?\" check; a way to ask the watch to open the app."));
c.push(B("**Limit:** about 14 fragments per message, so payloads must stay compact."));
c.push(B("**First users:** Third Coast Sailing (fallback link), and the food tracker's \"calories left\" on the watch."));
c.push(H2("How other phone apps connect (compared)"));
c.push(table(["Approach", "How it works", "Good", "Limits"], [
  ["Garmin's phone toolkit", "Closed library inside the phone app; talks only to the Garmin Connect app", "Both directions; delivery result; installed check; open app; lists, maps, strings and numbers", "Needs Garmin Connect; Garmin's license (may only be used to build Connect IQ apps)"],
  ["Gadgetbridge Intent", "Broadcast Intent SEND_WATCH_APP_MESSAGE with JSON or text", "Simple; open source", "Phone to watch only (replies dropped); no delivery result; one switch per watch lets any app send"],
  ["Gadgetbridge PebbleKit support", "Answers the old Pebble app's broadcast Intents", "Existing Pebble apps work unchanged", "Only possible because Pebble's toolkit used open broadcasts and Pebble's app was gone"],
  ["Internet Helper", "Separate app with bound services guarded by a permission the user grants", "Strong, checkable security model", "Not watch messaging"],
  ["**Self Watch link**", "Bound service inside Self Watch plus a small client kit; also accepts Gadgetbridge's Intent", "Both directions; delivery result; installed check; open app; per-app approval; same message types as Garmin's", "Apps written for Garmin's toolkit need a small change"],
], [1700, 2700, 2700, 2260]));
c.push(gap());
c.push(P("**Verdict on posing as Garmin Connect: no.** Garmin's toolkit is closed and built to find Garmin Connect's own app (its errors include \"Garmin Connect not installed\"). Unchanged apps would only work if Self Watch installed under Garmin's app name: impersonation, impossible alongside the real Garmin Connect, and against store rules. Working out the toolkit's private interface would mean studying Garmin's closed code, which its license doesn't allow, so it wasn't opened. PebbleKit support worked for Gadgetbridge only because Pebble used open broadcasts."));
c.push(P("**What Self Watch does instead:**", { keepNext: true, spacing: { after: 60 } }));
numInst++; c.push(N("**The client kit mirrors the shape of Garmin's toolkit** under Self Watch's own names: connect, list watches, check the watch app is installed, send with a delivery result, receive, open the app, with the same message types. A companion app can support both with a small switch; porting is mostly renaming."));
c.push(N("**The client kit is Apache-2.0,** so any app, open or closed, can use it without changing its own license."));
c.push(N("**Gadgetbridge's Intent keeps working,** and Self Watch adds the replies Gadgetbridge drops."));
c.push(N("**Security from Internet Helper's model:** a bound service plus a per-app approval screen, stronger than one switch per watch."));
c.push(N("A short sample shows one companion app talking to both Garmin Connect and Self Watch."));
c.push(H2("Watch app install and settings (Connect IQ app parity)"));
c.push(B("**Gadgetbridge shows how** (behind an experimental switch): list installed Connect IQ apps, delete them, install .prg files, and read and write each app's settings. Self Watch writes its own version of each."));
c.push(B("**Self Watch adds:** app management on by default (installed-apps list with icons, install from a file, delete, open on the watch); a proper settings editor that reads the settings definition file the Connect IQ compiler writes next to each .prg, so users see real labels, dropdowns and ranges, with raw keys as the fallback; and a saved copy of each app's settings for restoring after a reinstall. Test first with Just Go 5K and the TCS Companion."));
c.push(B("**Not possible:** browsing or installing from the Connect IQ Store. It needs a Garmin account and Garmin's servers, which conflicts with the no-account promise. Users can still use Garmin's app for Store installs; a curated list of open-source watch apps could come later."));

// 5
c.push(H1("5. Body and food"));
c.push(B("**Body:** weight (typed in, or from the watch or a scale), goal and trend line, body measurements (waist, hips, chest, arms, thighs, body fat, plus custom ones), BMI, and calories in vs out."));
c.push(B("**Food:** search as you type; barcode scan with Open Food Facts lookup when a code isn't found locally; recent foods, favourites, custom foods, a recipe book and quick-add; a \"copy from the menu board\" form; a daily budget of calories eaten against calories burned from the watch; calories left shown on the watch."));
c.push(H2("How the food database plugs in later"));
numInst++; c.push(N("**The app works without foods.db.** On day one, food comes from My foods (custom foods, saved meals, history) and from Open Food Facts online lookups, if the user allows them."));
c.push(N("All lookups go through one interface (FoodSource) with three providers, searched in order: **My foods, then foods.db (when installed), then Open Food Facts online (when allowed).**"));
c.push(N("With foods.db installed, search and barcode lookups work offline, instantly."));
c.push(N("The app reads the file's schema version and only opens files it understands; anything else gets a clear message, never a crash."));
c.push(N("Two ways to install it: download (Wi-Fi only, checksum checked) or **Load from file**, for people who want no network use at all."));
c.push(N("Diary entries copy the calories and nutrients they used, so replacing or removing foods.db never changes past days."));
c.push(N("**The foods.db layout below is the contract between Tony and the app.**"));
c.push(H2("Recipe book"));
c.push(P("One place for **recipes** (a dish with ingredients, servings and steps) and **saved meals** (foods logged together, like \"my usual breakfast\")."));
c.push(B("**Log a serving** of any recipe in one tap; the diary copies its calories and nutrients like any other food."));
c.push(B("**Nutrition per serving:** if the recipe states its own nutrition (most recipe files and websites do), Self Watch uses it. Otherwise each ingredient line (\"2 cups flour\") is matched to a food from My foods, foods.db or Open Food Facts; the user confirms the matches and the totals are worked out from them. Lines that can't be matched are flagged, never guessed silently."));
c.push(B("Scale servings, edit, tag and search. **Import** from the common recipe formats and food lists; **export** in formats other recipe apps read (section 6)."));
c.push(B("**Starter recipe book (optional, Tony):** USDA's MyPlate Kitchen had 1,072 recipes, each with a USDA nutrition analysis. USDA retired the site on 7 Jan 2026, but the original pages survive in web archives. Most are US government works (public domain); some came from partner programs and may still be under copyright, so check each recipe's source before including it, and use the original USDA pages rather than third-party copies. Delivered like foods.db: optional, downloaded or loaded from a file."));

c.push(H2("Food database (Tony's part)"));
c.push(table(["Source", "Gives", "Barcodes", "How current", "License"], [
  ["Open Food Facts", "Packaged groceries, worldwide (~4.9M); a few restaurant items", "Yes", "Live", "ODbL"],
  ["USDA Branded Foods", "US packaged groceries reported by manufacturers", "Yes", "About twice a year (latest Oct 2026)", "Public domain"],
  ["USDA Foundation, SR Legacy, FNDDS", "Generic foods; SR Legacy \"Fast Foods\" has big-chain items (e.g. 45 McDonald's, 31 Burger King)", "No", "SR Legacy frozen since 2018", "Public domain"],
  ["NYC MenuStat (Historical)", "96 large US chains, about 65,000 menu items", "No", "2017–2018 only", "Free NYC open data"],
], [1900, 3100, 1000, 1800, 1560]));
c.push(P("**Merge rules:**", { spacing: { before: 120, after: 60 } }));
numInst++; c.push(N("**Barcodes:** digits only, check digit verified, padded to 14 digits (GTIN-14), stored as a number. Bad codes are dropped."));
c.push(N("**Groceries:** match Open Food Facts and USDA Branded on the barcode. Prefer USDA's nutrition numbers."));
c.push(N("**Restaurants:** match on chain plus fuzzy item name; keep the newer MenuStat entry. Record the data year."));
c.push(N("**Generic foods:** prefer Foundation, then SR Legacy, then FNDDS."));
c.push(N("**Remove:** no calories, impossible values, exact duplicates. Tony proposes trimming rules (US only, no recent scans, discontinued items) with the size effect of each; HappyRock approves."));
c.push(P("**foods.db layout** (summary; the full contract for Tony is docs/FOODDB_SPEC.md: file settings, column rules, text style, permanent ids, barcodes, update manifest, recipe book format, release checks, size target 20–30 MB compressed):", { spacing: { before: 120, after: 60 } }));
c.push(table(["Table", "Contents"], [
  ["meta", "Schema version, data version (YYYYMMDD), build date, sources, license (ODbL 1.0), attribution"],
  ["food", "Name, brand (or chain), extra search words, kind (generic / packaged / restaurant), source, data year, popularity, basis (per 100 g, per 100 ml or per item), and nutrients as whole numbers: kcal ×10, protein, fat, saturated fat, carbs, sugar, fibre (grams ×10), sodium and cholesterol (mg). Unknown = empty, not 0."],
  ["serving", "Named servings for each food, e.g. \"1 sandwich = 219 g\" (compact WITHOUT ROWID table)"],
  ["barcode", "GTIN-14 as a number → food (compact WITHOUT ROWID table)"],
  ["chain", "Restaurant chain names"],
  ["food_fts", "FTS4 search index over name, brand and search words. Reads text from food (no second copy), prefix search, accents ignored"],
], [1600, 7760]));
c.push(gap());
c.push(P("**Finishing steps:** optimise the search index, then ANALYZE, then VACUUM. The layout was test-loaded with sample rows: \"mcdonalds big\" finds Big Mac, \"creme\" finds Crème brûlée, and barcode lookup works."));
c.push(P("**Tony delivers each build:** foods.db.gz (gzip is built into Android, so no extra library; zstd would need a ~1 MB native library) with its SHA-256 checksum; a report (rows per source, barcode overlap, restaurant matches, rows removed by each rule, sizes before and after compression); and the build script."));
c.push(H2("selfwatch.db, updates and licenses"));
c.push(B("**selfwatch.db:** one writable database for everything the user owns: devices, watch data, workouts, body measurements, food diary, custom foods, recipe book, goals, water, fasting, watch-app settings copies, linked-app permissions and the network log. Whole numbers wherever possible; minute data in compact tables keyed by device and time; small daily summaries (weeks, months and years are added up from them) so year-long charts open fast. Workout FIT files are kept as received, so \"export original\" returns exactly what the watch recorded."));
c.push(B("**Updates:** the app compares data versions, downloads a newer foods.db.gz over Wi-Fi only, checks the checksum and swaps the file in one step. selfwatch.db is never touched."));
c.push(B("**Licenses:** app code AGPL-3.0 (recommended), all written for Self Watch. foods.db contains Open Food Facts data, so it's published under ODbL 1.0 with credit to Open Food Facts. No scraping of chain websites without a legal check."));

// 6 import / export
c.push(H1("6. Import and export"));
c.push(P("**Everything is free and done from the phone.** By contrast, MyFitnessPal's export is paid, and Lose It! and Cronometer export only from their websites. **None of the three can import a CSV file,** and Garmin Connect doesn't read from Health Connect. So Self Watch reads everyone's export files, and writes files each destination can actually use."));
c.push(P("**Export is a must (HappyRock):** every piece of data can leave the phone, free, in an open format."));
c.push(H2("Import (bring your history in)"));
c.push(table(["From", "File", "What comes in"], [
  ["Gadgetbridge or Pulse", "Their backup zip (database, settings and the watch's FIT files)", "Activity, sleep, heart rate, HRV, SpO2, stress, weight, workouts"],
  ["Garmin Connect", "The account's \"Export Your Data\" zip (layout learned from Gadgetbridge)", "Workouts and other FIT files, daily summaries, hydration"],
  ["Any sports app", "FIT, GPX or TCX files", "Workouts and GPS tracks"],
  ["MyFitnessPal", "Its export zip (Premium): nutrition, exercise and progress CSVs", "Diary by meal (calories, macros), exercise, weight"],
  ["Lose It!", "Its account export (free, from the website): food log, weights, exercise CSVs", "Diary by food, weight, exercise"],
  ["Cronometer", "Its CSV exports (website): servings, daily summary, exercises, biometrics", "Diary by food with nutrients, weight and body measurements, exercise"],
  ["Health Connect", "Read, with the user's permission", "Weight, body fat, nutrition, steps, sleep and exercise from other apps"],
  ["Self Watch", "Its own backup zip", "Everything"],
  ["Recipe sites and apps using the web standard (Nextcloud Cookbook, RecipeSage, Mealie)", "schema.org Recipe JSON (JSON-LD)", "Recipes with ingredients, steps and stated nutrition"],
  ["Paprika", ".paprikarecipes export (a zip of compressed JSON files)", "Recipes"],
  ["Mealie, Tandoor", "Their backup or export zip", "Recipes"],
  ["Meal-Master", ".mmf text files (a classic format with large free archives online)", "Recipes, usually without nutrition"],
  ["Cooklang", ".cook plain-text files (an open format)", "Recipes"],
  ["Food lists", "CSV, one food per row (name, brand, serving, grams, calories, protein, fat, carbs…)", "Custom foods"],
], [1900, 4100, 3360]));
c.push(gap());
c.push(B("Columns are matched by name, with known variants (for example Lose It! calls the meal column Type in some exports and Meal in others)."));
c.push(B("A preview shows the first rows with the detected date format and units; the user confirms before anything is saved (Garmin Connect's own import works the same way)."));
c.push(B("Rows already in Self Watch are skipped, so importing a file twice is safe. Every import is tagged and can be undone in one step."));
c.push(B("Imported diary entries keep the calories and nutrients as recorded; they don't need foods.db."));
c.push(B("The recipe formats are the most common ones the open-source recipe manager Tandoor imports; its 20+ importers are a good map of what people actually use."));
c.push(H2("Export (take your data anywhere)"));
c.push(table(["To", "Format", "Contents"], [
  ["A new phone", "**Self Watch backup zip:** selfwatch.db, settings and workout FIT files. Optional automatic backup to a folder on a schedule.", "Everything"],
  ["Strava, Komoot, Garmin Connect, map apps, any sports app", "**GPX for every activity with GPS** (the default): the track plus heart rate, cadence and temperature, in Garmin's GPX extension that most apps read. Also **original FIT** (exactly as recorded) and **TCX** for indoor activities with no track. One activity, or a date range as one zip; optional automatic GPX export of each new activity to a chosen folder.", "Activities and GPS tracks"],
  ["Garmin Connect (weight)", "**Garmin body CSV:** first line \"Body\", then the header Date,Time,Weight,BMI,Fat. A **FIT weight file** also carries muscle, bone and water mass (written with our own FIT writer, no Garmin SDK).", "Weight, BMI, body fat"],
  ["Spreadsheets and tools", "**CSV in Cronometer's layout,** the most complete of the three: servings, daily summary, exercises, biometrics and notes files; ISO dates (YYYY-MM-DD); units in column names. Also as JSON.", "Diary, daily totals, exercise, body measurements, notes"],
  ["Other recipe apps", "**Recipe book** as schema.org Recipe JSON in Nextcloud Cookbook's folder layout (one folder per recipe), which Tandoor, Mealie and Nextcloud can import. Custom foods as a CSV food list.", "Recipes, saved meals, custom foods"],
  ["MyFitnessPal, Lose It!, Cronometer, other health apps", "**Health Connect** (write, with permission): the live bridge to the calorie apps, which read weight, steps and exercise from it.", "Weight, body fat, nutrition, water, steps, sleep, heart rate, HRV, SpO2, exercise"],
], [2100, 4900, 2360]));
c.push(gap());
c.push(Note("Before writing the importers, collect one real export from each app (HappyRock's own or a volunteer's, with personal details removed) as test files. Most column headers come from secondary sources because the apps' help pages don't publish them. Confirmed: Garmin's body CSV layout, Lose It!'s food-log header (Date, Name, Icon, Type, Quantity, Units, Calories…, US-style dates), and each export's file names."));

// 7 internet
c.push(H1("7. Internet (one app, no helper)"));
c.push(P("Gadgetbridge deliberately has no internet permission; anything online goes through the separate Internet Helper app, which the user must install and allow. That gives users a guarantee they can check. Self Watch is one app, so it gives up that guarantee and earns the trust another way:"));
numInst++; c.push(N("**Everything off by default,** except the food data download and Open Food Facts barcode lookups, and both can be turned off."));
c.push(N("A firewall switch for each feature, and for each watch app's web requests."));
c.push(N("A **Network activity** screen listing every request: time, host, feature and bytes."));
c.push(N("No analytics, no crash reporting to servers, no ads, no third-party code that phones home."));
c.push(N("**Load from file** for the food database, so the network can stay off completely."));
c.push(table(["Internet Helper service", "What it can do", "In Self Watch"], [
  ["HTTP", "Send a request and return the response; optionally over a chosen Wi-Fi network", "**Built now:** food data download, Open Food Facts lookups, watch-app web requests, GPS assistance data for the watch"],
  ["FTP", "Connect, log in, list folders, upload, download, delete, create folders", "With the first device that needs it"],
  ["UDP", "Open a UDP socket to a host and port", "With the first device that needs it"],
  ["Wi-Fi (Android 10+)", "Read the current network, check Wi-Fi is on, join a named network (e.g. a watch or camera hotspot), disconnect", "With the first device that needs it"],
], [2000, 3900, 3460]));
c.push(gap());
c.push(P("Self Watch has its own network code inside the app, so it doesn't need Internet Helper's separate-app interfaces."));

// 8 research
c.push(PB());
c.push(H1("8. Background research: popular calorie counting apps"));
c.push(P("The three apps reviewed are MyFitnessPal (largest by downloads), Lose It! (most beginner-friendly, strong streaks and badges) and Cronometer (most accurate data, best privacy of the three). By downloads, Yazio is second worldwide and FatSecret has more installs than Cronometer; FatSecret still offers free barcode scanning. **AI features (photo meal scanning, voice logging, AI coaching) are left out on purpose.**"));
c.push(table(["App", "Price", "What people like", "Common complaints"], [
  ["MyFitnessPal", "Free with ads; Premium $19.99/mo or $79.99/yr; Premium+ $99.99/yr", "Biggest database (14–20M entries); most integrations (Garmin, Health Connect and 35+ others)", "Barcode scanning paywalled since 2022 (the #1 complaint); 2026 redesign added taps; full-screen ads; sync failures; inaccurate, duplicated entries; 2018 breach of ~150M accounts"],
  ["Lose It!", "Free with ads; Premium $79.99/yr (was $39.99); lifetime $299.99", "Simple and friendly; streaks and badges keep people going; \"add yesterday's meal\" shortcut", "Barcode scanning quietly paywalled for new accounts; price doubled; free tier is calories only; Garmin sync is paid"],
  ["Cronometer", "Free with ads; Gold $10.99/mo or $59.99/yr", "Lab-verified data; up to 95 nutrients free; free barcode scanning, device sync and export", "Slowest, densest logging; weak restaurant and non-US coverage; ads; free charts limited to 7 days"],
], [1400, 2200, 2700, 3060]));
c.push(gap());
c.push(Note("Prices and paywall changes are well sourced (official pricing pages, help centres, review analyses). Some reviews come from blogs run by competing apps; those claims are marked in PRIOR_ART.md."));

c.push(H2("Feature matrix"));
c.push(P("F = free, P = paid, ? = not confirmed. The last column is our target."));
c.push(table(["Feature", "MyFitnessPal", "Lose It!", "Cronometer", "Self Watch"], [
  ["Barcode scan", "P", "P (new accounts)", "F", "**F; offline once foods.db is installed**"],
  ["Food search", "F", "F", "F", "**F; offline once foods.db is installed**"],
  ["Recent, frequent, favourite foods", "F", "F", "F", "**F**"],
  ["Copy a meal or yesterday", "F (one meal)", "F", "F?", "**F**"],
  ["Saved meals and recipes", "F", "F / F?", "F", "**F, recipe book with import and export**"],
  ["Quick-add calories", "F (macros P)", "F", "F?", "**F**"],
  ["Restaurant chains", "Large, crowdsourced", "Yes", "Limited", "**96 chains, user corrections**"],
  ["Macro goals in grams", "P", "P", "F", "**F**"],
  ["Micronutrients", "Partly P", "P", "F", "**F (as data allows)**"],
  ["Net carbs", "P", "P?", "F", "**F**"],
  ["Intermittent fasting", "P", "P", "P", "**F**"],
  ["Water", "F", "Goal P", "F", "**F**"],
  ["Weight log and charts", "F", "F", "F (7-day charts)", "**F**"],
  ["Watch calories", "F, confusing", "P", "F", "**F, direct from the watch**"],
  ["Meal planning / grocery list", "Top paid tier", "P / No?", "No", "Later"],
  ["Reports and charts", "Basic F", "Partly P", "7 days F", "**F, any range**"],
  ["Data export", "P", "F (website only)", "F (website only)", "**F, from the phone: CSV, JSON, FIT, GPX, TCX, backup**"],
  ["Data import", "No?", "No (official)", "No CSV import?", "**F: all three, plus Garmin Connect, Gadgetbridge, FIT, GPX, TCX**"],
  ["Works offline", "Recent foods only", "Partial", "Mostly online", "**Fully, once foods.db is installed**"],
  ["Account required", "Yes", "Yes", "Yes", "**No**"],
  ["Ads", "Yes", "Yes", "Yes", "**None**"],
], [2360, 1750, 1750, 1750, 1750], { shadeLast: true }));

c.push(H2("Advertised features and UI: screenshots"));
c.push(Note("Images are the publishers' own Google Play store screenshots (US listing, captured 22 Nov 2026), reproduced here only for internal research and comparison. All trademarks and images belong to their owners; not for redistribution and not to be copied into the product. AI-feature screenshots are excluded."));
c.push(new Paragraph({ keepNext: true, spacing: { before: 120, after: 60 }, children: [new TextRun({ text: "MyFitnessPal", bold: true, color: GREEN, size: 22 })] }));
c.push(shotGrid([
  { file: "android_2", title: "Food list", note: "Each row: name, verified tick, calories and serving. Takeaway: show the key number and serving on one line." },
  { file: "android_8", title: "Barcode scan (paid)", note: "Scan result card with calorie ring and macro split. Our version is free and works offline." },
  { file: "android_7", title: "Meal plan and recipes (Premium+)", note: "Planner, groceries and recipe tabs with photos. A later feature for us." },
  { file: "android_11", title: "Weekly macro chart", note: "Stacked bars per day with average against goal. Clear and compact." },
]));
c.push(new Paragraph({ keepNext: true, spacing: { before: 200, after: 60 }, children: [new TextRun({ text: "Lose It!", bold: true, color: GREEN, size: 22 })] }));
c.push(shotGrid([
  { file: "loseit_4", title: "Day log", note: "Budget ring (food, left, exercise) above meal sections with item, amount and calories." },
  { file: "loseit_2", title: "Weight goals", note: "Trend chart with low, change, weekly rate and next milestone. Motivating framing." },
  { file: "loseit_7", title: "Macro dashboard", note: "Daily bars for the week and per-nutrient gauges (over / under)." },
  { file: "loseit_8", title: "Barcode scan (paid for new accounts)", note: "Scan result shows a full nutrition panel." },
]));
c.push(new Paragraph({ keepNext: true, spacing: { before: 200, after: 60 }, children: [new TextRun({ text: "Cronometer", bold: true, color: GREEN, size: 22 })] }));
c.push(shotGrid([
  { file: "gold_2", title: "Diary with target bars", note: "Coloured target bars on top; meals, foods, exercise and water mixed in one timeline." },
  { file: "gold_4", title: "Serving entry", note: "Amount, serving size, time and group; data source and nutrient count; calorie split ring." },
  { file: "gold_3", title: "Daily report (free)", note: "Up to 95 nutrients against targets as percentage bars." },
  { file: "gold_13", title: "Food search", note: "Category tabs (all, favourites, custom, common, brands, restaurants) and a source tag on each row." },
]));

c.push(H2("Gaps Self Watch can fill"));
numInst++; c.push(N("**No account, private by default, no ads.** All three need an account, keep the diary on their servers and fund the free tier with ads."));
c.push(N("**Truly offline.** None can search or scan reliably offline; a local foods.db beats all three."));
c.push(N("**Nothing behind a paywall.** Barcode scanning, gram macros, net carbs, fasting, water goals, export and long-range charts are each paid somewhere."));
c.push(N("**Fastest logging.** One-tap re-log of a recent food with its last serving, yesterday's meal offered inline, add several foods at once, quick-add in two taps, sensible default servings."));
c.push(N("**Trust in the data.** Source and year on every food, duplicates merged, a \"data from\" date on restaurant items."));
c.push(N("**Clear watch-calorie maths.** Goal − Food + Activity (from watch) = Remaining, with the rule explained on screen."));
c.push(N("**Free, complete import and export.** Bring history in from all three apps, Garmin Connect and Gadgetbridge; take everything out as CSV, JSON, workout files or a full backup, from the phone."));
c.push(N("**Calories left on the watch,** sent through Self Watch's own watch messaging. None of these apps send food data back to a Garmin watch."));

// 9 checklist
c.push(H1("9. Feature checklist"));
c.push(table(["Priority", "Feature"], [
  ["Must", "Garmin pairing and sync: activity, sleep, heart rate, HRV, workouts, notifications"],
  ["Must", "Body home screen: calories in vs out, weight with goal and trend, measurements, recovery"],
  ["Must", "Food diary that works without foods.db: custom foods, saved meals, quick-add, recent and favourites, barcode scan with Open Food Facts lookup"],
  ["Must", "One-tap re-log of recent foods with the last serving; yesterday's meal offered inline; add several at once"],
  ["Must", "Daily budget: Goal − Food + Activity (from watch) = Remaining, explained on screen"],
  ["Must", "No account, no ads, no analytics; Network activity screen; firewall for each feature"],
  ["Must", "**Export everything, free:** backup, CSV, JSON, GPX for every GPS activity (plus FIT and TCX), Garmin body CSV, recipe book"],
  ["Must", "**Import backups:** restore a Self Watch backup; bring in Gadgetbridge or Pulse backups, the Garmin Connect account export, FIT / GPX / TCX, and MyFitnessPal, Lose It! and Cronometer exports"],
  ["Must", "**Import recipes:** schema.org recipe JSON, Paprika, Mealie, Tandoor, Meal-Master, Cooklang, and CSV food lists"],
  ["Must", "Recipe book: recipes and saved meals, log a serving, nutrition per serving"],
  ["Should", "foods.db plug-in: offline search and barcode lookup, download or load from file"],
  ["Should", "Watch-app messaging for other apps; Connect IQ app install and settings editor; calories left on the watch"],
  ["Should", "A starter recipe book (archived USDA MyPlate Kitchen, after a source check)"],
  ["Should", "Health Connect read and write; micronutrients as the data allows; net carbs; fasting timer; reminders"],
  ["Should", "Restaurant items for 96 chains with user corrections and a \"copy from the menu board\" form"],
  ["Later", "Smart scales and other device families; recipe import from a web page; meal planning; grocery list"],
], [1200, 8160]));

// 10 layout
require("./layout_part")({ c, H1, H2, P, B, N, Note, table, gap, Paragraph, TextRun, ImageRun, AlignmentType, fs, path, nextNum: () => ++numInst });

// 11 code plan
require("./code_part")({ c, H1, H2, P, B, N, Note, table, gap, Paragraph, TextRun, ImageRun, AlignmentType, fs, path, nextNum: () => ++numInst });

// 11 gadgetbridge reference
c.push(H1("12. Reference: what Gadgetbridge does"));
c.push(P("**Self Watch v1 covers these for Garmin watches;** the rest arrive as device families are added."));
c.push(table(["Area", "Capabilities (varies by device)"], [
  ["Phone to watch", "Notifications with replies, calls, contacts, music control, weather, alarms, find phone and find device, navigation directions, loyalty cards"],
  ["Health data", "Steps, activity, sleep, heart rate, HRV, SpO2, stress, temperature, blood pressure, ECG (some devices), VO2 max, training load, hydration, weight and body composition from scales"],
  ["Workouts", "Workouts with GPS tracks, GPX route install, exports, links to sports tracking apps"],
  ["Watch management", "Firmware and watch face install, watch app management (Connect IQ, Pebble, Bangle.js and others), per-device settings, home screen widgets"],
  ["Data", "Local database, automatic export, importing from Garmin Connect, Health Connect sync, Sleep as Android"],
  ["Automation", "Intent interface for other apps (sync, export, alarms, settings, watch app messages), PebbleKit compatibility"],
], [2000, 7360]));
c.push(gap());
c.push(P("**Devices:** about **670 models** in about **120 families** (gadgetbridge.org/gadgets). Largest groups: Garmin 193, Amazfit / Zepp about 65, Huawei / Honor about 46, Mi Band / Xiaomi about 29, Soundcore 28, Sony 22, Colmi 17. Beyond watches: earbuds, smart scales, heart-rate straps, bike computers, e-scooters, thermometers and more."));
c.push(P("**Order after Garmin (proposal):** smart scales (weight is central to Self Watch), then heart-rate straps, then whichever watch family users ask for most."));

// 12 pulse
c.push(H1("13. Reference: Pulse"));
c.push(P("Pulse is a Garmin-only fork of Gadgetbridge by a single developer (\"zoop\"), on F-Droid as cc.zachy.pulse. A copy of its source is in Garmin Apps/GadgetbridgeFork/Pulse (HEAD 4ef13c310, 24 Oct 2026)."));
c.push(table(["Question", "Finding"], [
  ["What code is in it?", "Gadgetbridge as of 28 Aug 2026, plus about 21,000 added lines (7,000 removed), almost all screens: a Today screen with a steps ring, Health, Sleep and Fitness tabs, streaks, achievements, a weekly review, goals, onboarding, widgets, a Quick Settings tile, themes with accent colours, two bundled fonts, and Open-Meteo weather. At least one commit is marked \"[AI assistance]\"."],
  ["Garmin connection code", "**Unchanged** from Gadgetbridge, and three months older. Gadgetbridge is the better reference for Garmin and Health Connect code."],
  ["Does it handle the data transfer we need?", "**No.** No watch-app messaging in either direction and no food tracking."],
  ["License", "Code AGPL-3.0; fonts SIL OFL 1.1; some icons CC BY-SA 4.0. Nothing is copied; don't use the Pulse name or branding."],
  ["Use in Self Watch", "UI ideas only (streak calendar, weekly review, widgets)."],
], [2300, 7060]));
c.push(P("**Partner with the developer?** Not a partnership (different goals, one maintainer), but a friendly contact later could be worthwhile. Any contact is your call; nothing has been sent."));

// 13
c.push(H1("14. Maintenance by agent"));
c.push(P("Tony runs nightly: it collects new issues, triages them and proposes fixes for the top ones. HappyRock approves or rejects every change. Because issue text is written by strangers and can try to steer the agent (prompt injection):"));
c.push(B("The agent runs in a sandbox and holds no signing keys or other secrets."));
c.push(B("It can only open pull requests, never push to the main branch. Only HappyRock merges."));
c.push(B("It treats issue text as information, never as instructions."));
c.push(B("It also watches Gadgetbridge for Garmin protocol fixes and proposes matching changes in Self Watch's own code."));

// 14
c.push(H1("15. Open questions"));
[
  "App ID (placeholder app.selfwatch), and a USPTO search for \"Self Watch\".",
  "Where the code lives (Codeberg or GitHub) and where foods.db.gz is published.",
  "Confirm FTS4 with accent-stripping works on Android 8.0's built-in SQLite.",
  "Real export files from MyFitnessPal, Lose It!, Cronometer and Garmin Connect, as importer test files.",
  "Tony's trimming rules and the resulting size.",
  "Starter recipe book: check each MyPlate Kitchen recipe's source (US government work or partner) before including it.",
  "Is US-only restaurant coverage enough?",
  "Which device family to add after Garmin (proposal: smart scales).",
  "Confirm the build order in section 11 (Garmin connection first, then Body and Activity, then food).",
].forEach((t) => c.push(B(t)));

c.push(H2("Source projects"));
c.push(table(["Project", "Web address", "License"], [
  ["Gadgetbridge", "https://codeberg.org/Freeyourgadget/Gadgetbridge", "AGPL-3.0"],
  ["Internet Helper", "https://codeberg.org/Freeyourgadget/Internethelper", "AGPL-3.0"],
  ["Pulse", "https://github.com/zoop-dev/pulse  (F-Droid: https://f-droid.org/packages/cc.zachy.pulse/)", "AGPL-3.0"],
  ["Gadgetbridge website and device list", "https://gadgetbridge.org  ·  https://gadgetbridge.org/gadgets/", ""],
  ["Open Food Facts", "https://world.openfoodfacts.org  (data: https://world.openfoodfacts.org/data)", "ODbL 1.0"],
  ["USDA FoodData Central", "https://fdc.nal.usda.gov/download-datasets/", "Public domain"],
  ["NYC MenuStat (Historical)", "https://data.cityofnewyork.us/Health/DOHMH-MenuStat-Historical-/qgc5-ecnb", "NYC open data"],
], [2300, 5660, 1400]));
c.push(H2("Other sources"));
[
  "Gadgetbridge and Internet Helper source code, codeberg.org/Freeyourgadget (master, 21 Nov 2026); gadgetbridge.org/basics/features",
  "USDA FoodData Central, fdc.nal.usda.gov; NYC Open Data, DOHMH MenuStat (Historical); Open Food Facts, world.openfoodfacts.org",
  "myfitnesspal.com/premium; MyFitnessPal support (data export, barcode scanning, Health Connect FAQ); MyFitnessPal community (Garmin adjustment)",
  "Lose It! help centre (pricing tiers, log header, Garmin, how to download your data, Health Connect); cronometer.com/gold; Cronometer support (energy summary, Garmin, Health Connect)",
  "Garmin Connect: Garmin forums (weight import), markwilson.co.uk (Garmin body CSV layout, Mar 2026), Android Central (what Garmin shares with Health Connect); Lose It! food-log header from github.com/TraceApps/nutritrace pull 334",
  "Garmin Connect IQ Mobile SDK for Android: github.com/garmin/connectiq-android-sdk (samples), Maven com.garmin.connectiq:ciq-companion-app-sdk (Garmin license), developer.garmin.com/connect-iq/sdk (license terms); Garmin forums (companion apps go through Garmin Connect)",
  "Recipe formats: Tandoor import and export docs (docs.tandoor.dev); MyPlate Kitchen status and public-domain notes from myplate.food (independent site, not USDA)",
  "Google Play store listings for the three apps (screenshots); Statista app download rankings 2025; CNBC on the 2018 MyFitnessPal breach",
  "Review analyses: unstar.app (MyFitnessPal Play reviews 2026), macros.gg (Lose It! barcode paywall; competitor-run). Full list in docs/PRIOR_ART.md",
].forEach((t) => c.push(new Paragraph({ numbering: { reference: "bullets", level: 0 }, spacing: { after: 40 }, children: [new TextRun({ text: t, size: 18, color: "555555" })] })));

// ---------- document ----------
const doc = new Document({
  creator: "HappyRock",
  title: "Self Watch: design and research",
  styles: {
    default: { document: { run: { font: FONT, size: 22 } } },
    paragraphStyles: [
      { id: "Title", name: "Title", basedOn: "Normal", run: { font: FONT, size: 44, bold: true, color: GREEN }, paragraph: { spacing: { before: 0, after: 80 }, keepNext: true } },
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true, run: { font: FONT, size: 30, bold: true, color: GREEN }, paragraph: { spacing: { before: 320, after: 120 }, keepNext: true, outlineLevel: 0 } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true, run: { font: FONT, size: 24, bold: true, color: "333333" }, paragraph: { spacing: { before: 200, after: 80 }, keepNext: true, outlineLevel: 1 } },
    ],
  },
  numbering: {
    config: [
      { reference: "bullets", levels: [{ level: 0, format: LevelFormat.BULLET, text: "•", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 270 } } } }, { level: 1, format: LevelFormat.BULLET, text: "–", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 1080, hanging: 270 } } } }] },
      { reference: "numbers", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 300 } } } }] },
    ],
  },
  sections: [{
    properties: { page: { size: { width: 12240, height: 15840 }, margin: { top: 1080, bottom: 1080, left: 1440, right: 1440 } } },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.RIGHT, children: [new TextRun({ text: "Page ", color: GREY, size: 18 }), new TextRun({ children: [PageNumber.CURRENT], color: GREY, size: 18 })] })] }) },
    children: c,
  }],
});

Packer.toBuffer(doc).then((buf) => { fs.writeFileSync(OUT, buf); console.log("wrote", OUT, buf.length); });

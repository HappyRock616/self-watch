# Self Watch food data: spec and review for Tony

Version 1 (schema_version 1), 2026-11-22. **One document with everything Tony needs.** Part A says where the current build stands and what to do next. Part B is the full file format: the contract between Tony (who builds and maintains the food data) and the Self Watch app. If this file and DESIGN.md §6 disagree, this file wins. Changing anything marked **MUST** needs a new `schema_version`, agreed with the app side first.

Tony chooses the tools (DuckDB and Parquet are the current pipeline). The app only cares about the files delivered.

---

# Part A: review of the current build and to-do list

**Where it stands:**
- `calorie-app/foods.parquet`: 91 MB, **1,094,740 rows**, built by DuckDB 2.0.1 from `build_v3.py` / `build_v3.sql`.
- Raw and work files: 7.4 GB.
- Parquet is the right in-between format, so this is a good start. The app needs the SQLite file described in Part B.

**To do, in order:**

| # | Current build | Change to (see Part B) |
|---|---|---|
| 1 | One flat table | Separate tables: `meta`, `chain`, `food`, `serving`, `barcode`, `food_fts` (§3) |
| 2 | Nutrients are decimals (DOUBLE) | Whole numbers: `kcal_x10`, `*_x10` grams, `sodium_mg`, `cholesterol_mg`; unknown = NULL (§7) |
| 3 | `year` is text | `data_year` INTEGER |
| 4 | `barcode13` is text, one per row | `barcode` table: GTIN-14 as a number, check digit verified, several codes per food allowed (§8) |
| 5 | `serving_size` is one text string | `serving` table: label + `grams_x10`; seq 0 = default (§3, §4) |
| 6 | Chains only in `brand_raw` | `chain` table and `chain_id` on restaurant items |
| 7 | No `keywords`, `popularity` | Add both (§4, §7) |
| 8 | Text `item_id`, ids change between builds | Permanent integer ids plus an id register (§5) |
| 9 | Working columns left in | Drop `description_raw`, `name_n`, `brand_n`, `kind_1`, `basis_1`, `rn`, `mcd_bk_merged`, `has_per100g` |
| 10 | Raw capitals and marketing text in names | Text style rules (§4) |
| 11 | No search index or meta | FTS4 index, `meta` keys, file settings, release checks (§2, §9, §13) |
| 12 | `build_v3.py` ends with zstd | **gzip** (`foods.db.gz`) plus SHA-256 and manifest (§1, §9) |
| 13 | 1.09 million rows | Trim to a **20–30 MB** compressed download, ceiling 40 MB. Propose rules with the size effect of each; HappyRock approves (§12) |
| 14 | Paths fixed to a fixed absolute path, which doesn't exist here (files are in `Garmin Apps/SelfWatch/calorie-app`) | Base folder as an argument; scripts into the repo at `tools/fooddb/`; data stays out of git (§14) |

**Review of build 2** (`fooddb_full.db` and `tools/fooddb/fooddb_5k_sample.db`, 2026-11-23):

Great progress: 68,131 foods, 32,205 barcodes and 78 chains in 29 MB before compression, which should land near the 20–30 MB compressed target. To match Part B, fix these, most important first:

1. **Search index must be FTS4, not FTS5** (§2, §3). FTS5 is missing from Android 8's built-in SQLite, so the app can't open the file on older phones. Use the exact `CREATE VIRTUAL TABLE food_fts USING fts4(content="food", name, brand, keywords, prefix="2,3", tokenize=unicode61 "remove_diacritics=1")` and `INSERT INTO food_fts(food_fts) VALUES('rebuild')`.
2. **File stamps** (§2): `PRAGMA application_id = 1398228548` and `PRAGMA user_version = 1`. Both are 0 now. The app refuses files without them.
3. **`meta` must be the key/value table** (§3, §9): `meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)` with `schema_version`, `data_version`, `built_at`, `min_app_version`, `sources`, `license`, `attribution`, `row_counts`. The current per-source table can become the `sources` text.
4. **Column names and whole numbers in `food`** (§3, §7):
   - Rename: `kcal` → `kcal_x10` (×10), `protein`/`fat`/`carb`/`sat_fat`/`sugar`/`fiber` → `protein_x10`, `fat_x10`, `carb_x10`, `satfat_x10`, `sugar_x10`, `fibre_x10` (×10).
   - Sodium becomes `sodium_mg` (whole mg; the sample's 0.75 looks like grams); add `cholesterol_mg`.
   - All INTEGER, rounded half away from zero; unknown = NULL.
   - Add `keywords`. Drop `description`, the `serving` text and `id_register_key` from `food`; the register belongs in the build, not the shipped file.
5. **`barcode` columns** (§3, §8): `gtin INTEGER PRIMARY KEY, food_id INTEGER NOT NULL`, `WITHOUT ROWID`.
6. **`serving`**: `grams_x10` must be INTEGER. Labels should follow §4 ("1 cup", "1 bar"); many rows are "1 serving" now. Use the source's serving text where it has one.
7. **`data_year`**: the year the numbers are from, not the build year. MenuStat items are 2017–2018, not 2026.
8. **MenuStat source URL:** use the NYC Open Data page (`https://data.cityofnewyork.us/Health/DOHMH-MenuStat-Historical-/qgc5-ecnb`). menustat.com and menustat.org now point to an unrelated site.
9. Then run the §13 checks and send `foods-sample.db` in the final format.

**Deliver first:** a `foods-sample.db` (about 5,000 rows) in the final format, so the app side can start testing while the full trim is decided.

**Review of build 3** (`tools/fooddb/foods.db.gz`, data_version 20261123):

The format is now right: every §13 structural check passes, along with FTS4, the file stamps, `meta`, gzip, SHA-256 and the manifest. The content isn't ready yet. Fix these, most important first:

1. **Wrong nutrition on generic foods:** "Milk, WHOLE" appears many times with `kcal_x10 = 0` (it should be about 610). Check the USDA Foundation/SR Legacy join and spot-check staples against FDC.
2. **Barcodes:** down to 869 from build 2's 32,205. Find the step that drops them.
3. **Serving weights:** `grams_x10` is NULL on every row, and 49,171 labels are "1 serving". Use source serving sizes and §4 labels.
4. **Chain and brand spelling:**
   - "Mcdonalds", "Kfc", "Chickfila" and "Mcdonald'S" should be "McDonald's", "KFC" and "Chick-fil-A". Use the alias list.
   - 108 brands hold several names ("Kraft, Heinz"); pick one.
5. **Keywords cut off:** Big Mac has "cdonald cdonalds", so "mcdonalds big" finds nothing.
6. **Popularity is flat:** 45,169 foods share 900. Use the §7 formula idea.
7. **Mixed-case names:** "Milk CHOCOLATE Truffles, MILK CHOCOLATE". 1,539 names are all capitals.
8. **Duplicates:** 7,118 name + brand pairs appear more than once.
9. **§7 plausibility:** 395 rows have saturated fat above fat, and 93 have sugar above carbs + 10.
10. **Text decoding:** broken characters such as "Jus D�ananas".
11. **Sodium:** 11,943 rows have `sodium_mg = 0`. Check that unknown isn't being written as 0.
12. **Extra indexes:** drop the three not in §3.

**Also needed:**
- `build_report.md`;
- the id register;
- the parquet path as an argument;
- a sample from the same build;
- proposed trimming rules with their size effect (only 2,011 Open Food Facts items, some of them non-US).

**Spot checks for the next build:**
- "mcdonalds big" finds Big Mac.
- Whole milk is about 61 kcal per 100 g.
- At least 25,000 barcodes.
- At least 80% of packaged foods have a serving weight.

**Notes on your Connect IQ messaging findings** (not food data, but for you):
1. Don't reverse-engineer the closed Garmin SDK or the Garmin Connect app (your option "a"). Garmin's license forbids it, and Self Watch talks to the watch directly, so it isn't needed. Option "b" (a small test sender plus a Bluetooth sniffer) fits Self Watch's first coding milestone.
2. Newer watches such as the vívoactive 5 may carry watch-app messages over Garmin's shared multi-link service rather than the dedicated `6a4e8022-…` service; this gets confirmed on HappyRock's watch.
3. 266 bytes is the limit at the default packet size; plan for it.
4. A watch can't run a web server. TCS's `makeWebRequest("http://127.0.0.1:52741/tcs")` is carried out by the phone-side companion, and the TCS phone app answers it. Self Watch will support this.
5. The reference source paths are now under `SelfWatch/references/`.

---

# Part B: file format and style (the contract)

## 1. Files delivered for each release

| File | What it is |
|---|---|
| `foods.db.gz` | The food database (SQLite), gzip-compressed (level 9). **Not zstd, not xz.** |
| `foods.db.gz.sha256` | One line: the SHA-256 (lowercase hex) of `foods.db.gz`, two spaces, the file name |
| `foods-manifest.json` | Tells the app what's available (section 9) |
| `foods-sample.db` | About 5,000 rows taken from the full build, same schema, uncompressed. Goes in the app's `testdata/` for automated tests |
| `build_report.md` | Counts and sizes (section 11) |
| `recipes.db.gz`, `.sha256`, `recipes-manifest.json` | The optional starter recipe book (section 10), when ready |

## 2. SQLite file settings (MUST)

- SQLite 3, UTF-8 text encoding.
- `PRAGMA page_size = 4096;` set before the first table is created.
- `PRAGMA journal_mode = DELETE;` (no WAL files; the app opens it read-only).
- `PRAGMA application_id = 1398228548;` (hex `0x53574644`, "SWFD"), so the app can recognize the file.
- `PRAGMA user_version = 1;`, equal to `schema_version`.
- **Only features in Android 8.0's built-in SQLite (3.18):**
  - FTS4, not FTS5.
  - No generated columns, no `STRICT` tables, no window functions in views, no JSON functions.
  - `WITHOUT ROWID` is fine.
- No triggers, no views. Only the indexes listed below.
- Finish with: FTS `optimize`, `ANALYZE`, `VACUUM`, then `PRAGMA integrity_check` must return `ok`.

## 3. Tables (MUST)

```sql
CREATE TABLE meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
) WITHOUT ROWID;

CREATE TABLE chain (
  id    INTEGER PRIMARY KEY,      -- permanent (section 5)
  name  TEXT NOT NULL UNIQUE      -- display name: "McDonald's"
);

CREATE TABLE food (
  id             INTEGER PRIMARY KEY,   -- permanent (section 5)
  name           TEXT NOT NULL,         -- display name (section 4)
  brand          TEXT,                  -- brand, or chain name for restaurant items
  keywords       TEXT,                  -- extra search words (section 4)
  kind           INTEGER NOT NULL,      -- 0 generic, 1 packaged, 2 restaurant
  chain_id       INTEGER,               -- restaurant items only, else NULL
  source         INTEGER NOT NULL,      -- section 6
  data_year      INTEGER,               -- year the nutrition data is from, e.g. 2018
  popularity     INTEGER NOT NULL DEFAULT 0,  -- 0..1000, higher shows first (section 7)
  basis          INTEGER NOT NULL,      -- nutrients are per: 0 = 100 g, 1 = 100 ml, 2 = 1 item
  kcal_x10       INTEGER,               -- 219 kcal = 2190
  protein_x10    INTEGER,               -- grams x 10
  fat_x10        INTEGER,
  satfat_x10     INTEGER,
  carb_x10       INTEGER,               -- total carbohydrate
  sugar_x10      INTEGER,               -- total sugars
  fibre_x10      INTEGER,
  sodium_mg      INTEGER,               -- milligrams
  cholesterol_mg INTEGER                -- milligrams
);
CREATE INDEX food_chain ON food(chain_id) WHERE chain_id IS NOT NULL;

CREATE TABLE serving (
  food_id   INTEGER NOT NULL,     -- food.id
  seq       INTEGER NOT NULL,     -- 0 = default serving, then 1, 2, ... in display order
  label     TEXT NOT NULL,        -- "1 sandwich", "1 cup", "1 bar"
  grams_x10 INTEGER,              -- weight of this serving x 10; NULL if unknown
  PRIMARY KEY (food_id, seq)
) WITHOUT ROWID;

CREATE TABLE barcode (
  gtin    INTEGER PRIMARY KEY,    -- GTIN-14 as a number (section 8)
  food_id INTEGER NOT NULL
) WITHOUT ROWID;

CREATE VIRTUAL TABLE food_fts USING fts4(
  content="food", name, brand, keywords,
  prefix="2,3",
  tokenize=unicode61 "remove_diacritics=1"
);
-- after loading food:  INSERT INTO food_fts(food_fts) VALUES('rebuild');
```

Every `serving.food_id`, `barcode.food_id` and `food.chain_id` MUST point at an existing row.

## 4. Text style

**Names (`food.name`):** what a person would write on a shopping list or menu.
- Trim, and collapse repeated spaces. No line breaks, tabs or control characters.
- Text that is mostly capitals ("GREEK YOGURT PLAIN NONFAT") becomes title case ("Greek Yogurt Plain Nonfat"). Keep common short words lowercase in the middle ("and", "with", "of", "in"), and keep known acronyms in capitals (BBQ, USDA, PB&J).
- Don't repeat the brand at the start of the name if it's already in `brand` ("Fage Total 0%" with brand Fage becomes "Total 0%").
- No marketing text ("NEW!", "Family size", "Value pack") and no weights in the name. Weights belong in servings.
- Generic foods use USDA-style descriptions, shortened: "Apple, raw, with skin". Keep the comma order, which groups similar foods.
- At most **80 characters**. Shorten carefully; don't just cut.

**Brands and chains (`food.brand`, `chain.name`):**
- The brand's own spelling and capitals ("McDonald's", "Ben & Jerry's").
- One spelling per brand across all sources ("Kellogg's", not also "KELLOGGS" or "Kellogg Company"). Keep a brand-alias list in the build.
- At most **60 characters**. NULL when unknown; never empty text.

**Keywords (`food.keywords`):** helps search, never shown to users.
- Lowercase plain letters and digits, space-separated, no repeats.
- Add spellings people type: the brand without punctuation ("mcdonalds bk benandjerrys"), common short names ("pbj", "mac cheese"), and US/UK spellings ("yogurt yoghurt").
- Leave out words already in `name` or `brand`. At most **120 characters**. NULL if none.

**Serving labels:** an amount plus a unit or an item word, lowercase except proper names.
- Write it the way people say it: "1 cup", "2 tbsp", "1 medium", "1 slice", "1 sandwich", "1 can (12 fl oz)", "1 bar".
- Use these unit words only: g, ml, oz, fl oz, cup, tbsp, tsp, slice, piece, item, serving.
- Put the weight in `grams_x10`, not in the label, unless the label needs it to make sense ("1 can (12 fl oz)").

## 5. Permanent ids (MUST)

Users' favorites, recent foods and recipes point at `food.id` and `chain.id`. So:
- **The same food keeps the same id in every build.**
- **A removed id is never reused** for a different food.
- Tony keeps an **id register** (for example `ids.parquet`: source, source key → id) that survives between builds. Source keys: the FDC id for USDA, the product code for Open Food Facts, and a stable restaurant + item-name key for MenuStat.
- When two sources merge into one food, keep the older id, and record the other source key against it.

## 6. Codes

| `source` | Source |
|---|---|
| 1 | Open Food Facts |
| 2 | USDA Branded Foods |
| 3 | USDA Foundation Foods |
| 4 | USDA SR Legacy |
| 5 | USDA FNDDS (survey foods) |
| 6 | NYC MenuStat |

`kind`: 0 generic, 1 packaged, 2 restaurant. `basis`: 0 per 100 g, 1 per 100 ml, 2 per item (weight unknown). When sources merge, `source` is the one the **nutrition numbers** came from.

## 7. Numbers

- **Nutrients are per `basis`:**
  - Basis 0 or 1: per 100 g or 100 ml.
  - Basis 2: per item. Only use basis 2 when the item's weight isn't known; otherwise convert to per 100 g.
- **Whole numbers:** multiply by 10 and round half away from zero (`kcal_x10`, `*_x10` grams); round sodium and cholesterol to whole mg.
- **Energy:** kcal. If only kJ is given, kcal = kJ ÷ 4.184.
- **Unknown is NULL, never 0.** 0 means the label says zero.
- **Plausibility (per 100 g or ml; drop or fix a row that fails, and count it in the report):**
  - `kcal_x10` between 0 and 9000.
  - Each of protein, fat and carb between 0 and 1000 (100 g).
  - Protein + fat + carb at most 1050 (allowing for rounding).
  - `satfat_x10` ≤ `fat_x10`; `sugar_x10` ≤ `carb_x10` + 10.
  - Sodium at most 40,000 mg (that's salt itself).
  - Energy versus macros: if 4×protein + 9×fat + 4×carb is more than 50% and more than 50 kcal away from the stated kcal, flag it. Tony proposes whether to drop or keep.
- **`popularity` (0..1000):** Open Food Facts scan counts on a log scale; restaurant items by chain size; common generic foods (fruit, eggs, bread, milk, chicken) high. Tony proposes the formula; it only orders search results.
- **`data_year`:** the year the numbers are from (USDA publication year, MenuStat year, Open Food Facts last-modified year).

## 8. Barcodes (MUST)

1. Keep digits only.
2. Accept 8, 12, 13 or 14 digits (EAN-8, UPC-A, EAN-13, GTIN-14). Left-pad with zeros to 14 digits.
3. Check the GS1 check digit; **drop any code that fails.**
4. Store the 14 digits as an INTEGER in `barcode.gtin`.
5. If two foods claim the same code, keep the one with the better source (USDA Branded, then Open Food Facts), and count the conflict.
6. A food may have several barcodes (sizes and pack variants with the same nutrition).

The app normalizes scanned codes the same way before looking them up.

## 9. Meta keys and the update manifest

**`meta` (MUST, all text):**

| key | example |
|---|---|
| `schema_version` | `1` |
| `data_version` | `20261122` (YYYYMMDD of the build; must increase with every release) |
| `built_at` | `2026-11-22T21:30:00Z` |
| `min_app_version` | `1` (the lowest Self Watch app version that understands this file) |
| `sources` | `OFF 2026-11-20; USDA FDC 2026-10-29; MenuStat 2018` |
| `license` | `ODbL-1.0` |
| `attribution` | `Contains data from Open Food Facts (openfoodfacts.org), licensed ODbL 1.0; USDA FoodData Central (public domain); NYC MenuStat (NYC Open Data).` |
| `row_counts` | `food=123456;serving=234567;barcode=98765;chain=96` |

**`foods-manifest.json`** (published next to the file; the app downloads this first):
```json
{
  "schema_version": 1,
  "data_version": 20261122,
  "min_app_version": 1,
  "file": "foods.db.gz",
  "url": "https://<release location>/foods.db.gz",
  "sha256": "<lowercase hex>",
  "size_gz": 24123456,
  "size_db": 81234567,
  "rows": 123456,
  "built_at": "2026-11-22T21:30:00Z"
}
```
The app installs a file only if `schema_version` is one it supports, `data_version` is newer than its current file, and the SHA-256 matches.

## 10. Starter recipe book (`recipes.db`, optional)

The same SQLite settings as section 2, except `application_id = 1398231618` (hex `0x53575242`, "SWRB").

```sql
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
-- keys: schema_version, data_version, built_at, min_app_version, sources, license, attribution, row_counts

CREATE TABLE recipe (
  id             INTEGER PRIMARY KEY,   -- permanent
  name           TEXT NOT NULL,         -- "Black Bean Burgers"
  servings_x10   INTEGER NOT NULL,      -- 4 servings = 40
  serving_label  TEXT,                  -- "1 burger"
  prep_min       INTEGER,
  cook_min       INTEGER,
  steps          TEXT NOT NULL,         -- steps separated by a newline, no numbering
  source_name    TEXT NOT NULL,         -- "USDA MyPlate Kitchen"
  source_url     TEXT,                  -- the original page (archive link is fine)
  license        TEXT NOT NULL,         -- "Public domain (US government work)"
  kcal_x10       INTEGER,               -- nutrition PER SERVING, same rules as section 7
  protein_x10 INTEGER, fat_x10 INTEGER, satfat_x10 INTEGER, carb_x10 INTEGER,
  sugar_x10 INTEGER, fibre_x10 INTEGER, sodium_mg INTEGER, cholesterol_mg INTEGER
);

CREATE TABLE recipe_item (
  recipe_id  INTEGER NOT NULL,
  seq        INTEGER NOT NULL,          -- display order from 0
  line       TEXT NOT NULL,             -- the original ingredient line: "1 can (15 oz) black beans, drained"
  amount_x100 INTEGER,                  -- 1.5 = 150; NULL if none ("salt to taste")
  unit       TEXT,                      -- unit words as in section 4
  food_name  TEXT,                      -- "black beans"
  food_id    INTEGER,                   -- foods.db id if matched, else NULL
  grams_x10  INTEGER,                   -- weight if known
  PRIMARY KEY (recipe_id, seq)
) WITHOUT ROWID;

CREATE TABLE recipe_tag (
  recipe_id INTEGER NOT NULL,
  tag       TEXT NOT NULL,              -- lowercase: "breakfast", "vegetarian", "high protein"
  PRIMARY KEY (recipe_id, tag)
) WITHOUT ROWID;

CREATE VIRTUAL TABLE recipe_fts USING fts4(
  content="recipe", name, steps,
  prefix="2,3", tokenize=unicode61 "remove_diacritics=1"
);
```

Include only recipes whose source is confirmed public domain or openly licensed, and record that in `license`. For MyPlate Kitchen, check each recipe's stated source; partner recipes may still be under copyright.

## 11. Build report (`build_report.md`)

- Source versions and download dates.
- Rows read from each source, then rows after merging, then rows after each trimming rule (say what each rule removed).
- Barcode overlap between Open Food Facts and USDA Branded; codes dropped for bad check digits; barcode conflicts.
- Restaurant matches (MenuStat against SR Legacy and FNDDS) and chain count.
- Rows dropped or flagged by each plausibility check.
- Ids: new, kept and retired since the last build.
- Sizes: `foods.db`, `foods.db.gz`, and each table plus the search index (from `dbstat`, or by building without the index).
- Timings of the test queries in section 13.

## 12. Size

- **Target: `foods.db.gz` 20–30 MB. Ceiling: 40 MB.** Above the ceiling, propose more trimming.
- Trimming rules are proposed by Tony with the size effect of each, and approved by HappyRock. Ideas: US products only; drop Open Food Facts items with no or very few scans; drop items with no calories, or with no protein, fat and carbs; keep only the newest version of a product; shorten names.

## 13. Checks before every release (MUST pass)

Run these against `foods.db`. The app's automated tests run the same checks on `foods-sample.db`.

```sql
PRAGMA integrity_check;                                   -- 'ok'
PRAGMA application_id;                                    -- 1398228548
PRAGMA user_version;                                      -- 1
SELECT value FROM meta WHERE key='schema_version';        -- '1'
SELECT COUNT(*) FROM food WHERE name IS NULL OR TRIM(name)='' OR LENGTH(name)>80;      -- 0
SELECT COUNT(*) FROM food WHERE brand='' OR LENGTH(brand)>60;                          -- 0
SELECT COUNT(*) FROM food WHERE kind NOT IN (0,1,2) OR basis NOT IN (0,1,2)
                             OR source NOT BETWEEN 1 AND 6;                            -- 0
SELECT COUNT(*) FROM food WHERE kind=2 AND chain_id IS NULL;                           -- 0
SELECT COUNT(*) FROM food WHERE kcal_x10 IS NULL;                                      -- 0 (no food without calories)
SELECT COUNT(*) FROM food WHERE basis<2 AND (kcal_x10 NOT BETWEEN 0 AND 9000
   OR COALESCE(protein_x10,0)+COALESCE(fat_x10,0)+COALESCE(carb_x10,0) > 1050);        -- 0
SELECT COUNT(*) FROM serving s LEFT JOIN food f ON f.id=s.food_id WHERE f.id IS NULL;  -- 0
SELECT COUNT(*) FROM barcode b LEFT JOIN food f ON f.id=b.food_id WHERE f.id IS NULL;  -- 0
SELECT COUNT(*) FROM food f WHERE NOT EXISTS (SELECT 1 FROM serving s WHERE s.food_id=f.id AND s.seq=0); -- 0
SELECT COUNT(*) FROM barcode WHERE gtin < 10000000 OR gtin > 99999999999999;           -- 0
```

**Test queries the app runs** (each should take well under 50 ms on a PC; report timings):
```sql
-- search as you type ("mcdonalds big" -> each word as a prefix)
SELECT f.id, f.name, f.brand, f.kcal_x10, f.basis
FROM food_fts JOIN food f ON f.id = food_fts.docid
WHERE food_fts MATCH 'mcdonalds* big*'
ORDER BY f.popularity DESC LIMIT 50;

-- barcode scan
SELECT f.* FROM barcode b JOIN food f ON f.id = b.food_id WHERE b.gtin = 41196910759;

-- servings for a food
SELECT seq, label, grams_x10 FROM serving WHERE food_id = ? ORDER BY seq;
```
Expected results to spot-check: "mcdonalds big" finds Big Mac; "creme brulee" finds Crème brûlée (accents ignored); a known barcode finds its product.

## 14. Build style (Tony's choice, with these needs)

- One command rebuilds everything from the raw downloads. The base folder is an argument or setting, not a fixed path.
- Pin the DuckDB version, and record each source's version and date in `meta.sources`.
- Build scripts go in the Self Watch repo at `tools/fooddb/`. Raw downloads, work files and outputs stay out of git, in `calorie-app/`.
- Keep the id register and the brand-alias list with the scripts, since both are needed to repeat a build.

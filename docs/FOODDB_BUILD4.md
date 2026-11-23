# Food database, build 4: instructions for Tony

**This is the complete brief. You don't need to piece anything together from earlier reviews.**

The file format in `FOODDB_SPEC.md` Part B has not changed since it was written, and your build 3 already matches it. Build 3 passed every structural check. What's left are **data bugs inside your pipeline**, and most are one-line fixes. **Reuse everything you have.** Don't start over.

**Done means:** `python tools/fooddb/check_foods.py calorie-app/out/foods.db.gz` prints **all PASS**, and `... --sample calorie-app/out/foods-sample.db` also prints all PASS. Build 3 scores 27/49 today. Run the checker as often as you like; it's fast and uses only the Python standard library.

---

## 1. What to keep (it works)

- `build_db.py`'s SQLite writer: page size, FTS4, `application_id`, `user_version`, `meta`, gzip, SHA-256, manifest. All correct.
- `build_v3.sql`'s DuckDB extraction of FDC, Open Food Facts and MenuStat. The joins and the per-source CTEs are fine; only the spots in section 3 are wrong.
- `foods.parquet` as the in-between format. Re-run `build_v3.sql` with the fixes to make a new one, then run `build_db.py` on it.

## 2. The exact output structure (unchanged; copy this)

File settings, set in this order before creating tables:

```sql
PRAGMA page_size = 4096;
PRAGMA journal_mode = DELETE;
PRAGMA application_id = 1398228548;
PRAGMA user_version = 1;
```

Tables: these exactly, with **no other tables or indexes** (drop `idx_food_kcal`, `idx_food_name`, `idx_food_source_kind`):

```sql
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE chain (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
CREATE TABLE food (
  id INTEGER PRIMARY KEY, name TEXT NOT NULL, brand TEXT, keywords TEXT,
  kind INTEGER NOT NULL,            -- 0 generic, 1 packaged, 2 restaurant
  chain_id INTEGER,                 -- restaurant items only
  source INTEGER NOT NULL,          -- 1 OFF, 2 USDA Branded, 3 Foundation, 4 SR Legacy, 5 FNDDS, 6 MenuStat
  data_year INTEGER, popularity INTEGER NOT NULL DEFAULT 0,
  basis INTEGER NOT NULL,           -- 0 per 100 g, 1 per 100 ml, 2 per item
  kcal_x10 INTEGER, protein_x10 INTEGER, fat_x10 INTEGER, satfat_x10 INTEGER,
  carb_x10 INTEGER, sugar_x10 INTEGER, fibre_x10 INTEGER, sodium_mg INTEGER, cholesterol_mg INTEGER
);
CREATE INDEX food_chain ON food(chain_id) WHERE chain_id IS NOT NULL;
CREATE TABLE serving (food_id INTEGER NOT NULL, seq INTEGER NOT NULL, label TEXT NOT NULL, grams_x10 INTEGER,
                      PRIMARY KEY (food_id, seq)) WITHOUT ROWID;
CREATE TABLE barcode (gtin INTEGER PRIMARY KEY, food_id INTEGER NOT NULL) WITHOUT ROWID;
CREATE VIRTUAL TABLE food_fts USING fts4(content="food", name, brand, keywords, prefix="2,3",
                                         tokenize=unicode61 "remove_diacritics=1");
INSERT INTO food_fts(food_fts) VALUES('rebuild');
```

Finish with FTS `optimize`, `ANALYZE`, `VACUUM`. Values: nutrients ×10 rounded half away from zero, sodium and cholesterol in whole mg, **unknown = NULL, never 0**.

## 3. The bugs, where they are, and the fix

I traced each build 3 failure to its cause.

| # | Symptom in build 3 | Cause (file:line) | Fix |
|---|---|---|---|
| 1 | 135,640 USDA Branded foods have 0 kcal (156,464 zero-kcal rows in `foods.parquet`); staples like raw apple are missing | `build_v3.sql:48` reads only nutrient **2047** (Atwater general). Branded foods store energy as **1008** (Energy, kcal); Foundation uses 2047/2048. | `COALESCE(MAX(CASE WHEN nutrient_id=1008 ...), MAX(... 2047), MAX(... 2048), MAX(... 1062)/4.184)` (1062 = kJ). Don't turn missing into 0. |
| 2 | Barcodes fell to 869 (the parquet has 33,140) | `build_v3.sql:187-195` keeps the **last 13 digits** and only keeps codes matching `^(0[0-6])[0-9]{11}$`. That drops every 12-digit UPC-A, which is most of USDA's `gtin_upc`. `build_db.py` then loses more. | Follow §8: digits only, accept 8/12/13/14 digits, **left-pad to 14**, verify the GS1 check digit, store as INTEGER. No "US prefix" filter (use the country rule in section 5 for trimming instead). Expect tens of thousands. |
| 3 | MenuStat items: source is NULL, kcal is 0, and McDonald's Big Mac can't be found | `build_v3.sql:91` reads `Calories_100g`, which is empty for most items. MenuStat gives **calories per item** (`Calories`) plus serving size. Line 204 then sets `kcal = -999`, and the source code stays NULL. | Use `Calories` per item: `basis = 2`, `kind = 2`, `source = 6`. If `Serving_Size` + unit is in g, convert to per 100 g (`basis = 0`) and set the serving `grams_x10`. Keep the item name in `name`. |
| 4 | `serving.grams_x10` is NULL on every row; 49,171 labels say "1 serving" | The parquet **has** `serving_size` (1,026,739 rows) and `serving_unit`, but `build_db.py` writes only `"1 serving"` / `"1 item"`. | Carry them through. USDA Branded: `serving_size` + `serving_size_unit` (g → grams_x10 = size×10; ml → treat 1 ml ≈ 1 g only for drinks, else leave NULL); label from `household_serving_fulltext` ("1 bar", "2 tbsp"). OFF: `serving_quantity` (g) and `serving_size` text. Generic foods: add `food_portion.csv` portions as seq 1, 2, … (`"1 cup"` + `gram_weight`). |
| 5 | Chains "Mcdonalds", "Kfc", "Chickfila"; brands "Mcdonald'S" | Title-casing runs after removing punctuation, and capitalises after `'` | Keep a small `tools/fooddb/brand_aliases.csv` (raw spelling → display). Apply it **before** any casing. Title-case rule: never capitalise the letter after an apostrophe. One brand per food: take the first brand before a comma, then alias it. |
| 6 | Big Mac keywords are "cdonald cdonalds" | Keyword builder slices off the first character | Keywords = lowercase brand with punctuation removed ("mcdonalds"), plus US/UK pairs (yogurt/yoghurt, fiber/fibre, flavor/flavour, doughnut/donut), plus short forms (pbj, mac cheese). Skip words already in name or brand. |
| 7 | Popularity takes only 4 values; 900 on 66% of foods | Fixed constants per source | Spread 0–1000. OFF: `round(150 * ln(1 + unique_scans_n))`, capped at 1000. USDA Branded: 300 + 100 × (number of barcodes/sizes sharing name+brand, max 4) + recency bonus. Restaurants: by chain item count. Staples (milk, egg, banana, apple, bread, rice, chicken breast, butter, cheese, coffee) 950. |
| 8 | Mixed case: "Milk CHOCOLATE Truffles, MILK CHOCOLATE"; 1,484 all-caps names | Casing applied to some words only; repeated fragments kept | Title-case every word that is all capitals (keep and/with/of/in lowercase, keep acronyms such as BBQ, USDA, PB&J). Then drop a trailing ", X" when X repeats words already in the name. Remove the brand from the start of the name. |
| 9 | 4,717 duplicate name + brand + kcal groups | No merge step | `GROUP BY lower(name), lower(brand), kcal_x10`: keep the lowest id, move its barcodes and servings over, and record the merged source keys in the id register. |
| 10 | 395 rows with saturated fat > fat; 93 with sugar > carbs + 1 g | §7 rules not applied | Fix or drop, and count it in the report. |
| 11 | Restaurant items with 0 kcal (392) | Same cause as #3 | Fixed by #3. |

**Accented dish names.** The app test looks for an item named "Crème brûlée". USDA writes some dishes without accents, so keep a tiny list that restores accents on common dish words: crème, brûlée, crêpe, café, purée, jalapeño, piña, entrée, sautéed, flambé. It's a few lines, applied to names only.

## 4. Text rules (short version of §4)

- Name ≤ 80 characters, brand ≤ 60, keywords ≤ 120.
- No weights or marketing text in names; weights go in `serving.grams_x10`.
- Generic foods keep USDA comma order: "Apples, raw, with skin".
- Serving labels use only these unit words: g, ml, oz, fl oz, cup, tbsp, tsp, slice, piece, item, serving.

## 5. Trimming rules: use these (no approval round needed)

Apply them in DuckDB **before** text cleanup, so every later step runs on fewer rows.

1. Keep **all** USDA Foundation, SR Legacy and FNDDS foods, and **all** MenuStat items. These are the basic foods and restaurants; build 3 was missing raw apples, bananas, eggs and chicken.
2. USDA Branded: keep rows with kcal present and a valid barcode. Keep only the **newest** row per GTIN (`available_date` / `publication_date`), and drop discontinued items (`discontinued_date` set).
3. Open Food Facts: keep products whose `countries_tags` include `en:united-states` or `en:canada`, with kcal present, and with `unique_scans_n ≥ 1` **or** a valid barcode not already in USDA Branded. Drop rows whose protein, fat and carbs are all missing.
4. Size: aim for `foods.db.gz` of **20–30 MB** (ceiling 40). If you're over, raise the OFF scan threshold first. Report the row count each rule removed.

## 6. Permanent ids (§5)

This is the first build that will ship, so ids can start fresh. Save `tools/fooddb/id_register.csv` (columns: `source,source_key,id`; gzip it if it's over 20 MB). Source keys: FDC id, OFF code, MenuStat `restaurant|item name`. The next build reads it and keeps the same ids.

## 7. Making it quick and cheap

- **Don't re-extract the raw files.** `calorie-app/work/FoodData_Central_csv_2026-10-29/` and `work/fdc/` hold the same USDA data twice (2.6 + 2.9 GB). Point DuckDB at one, then delete the other.
- **Stay in DuckDB until the final write.** Joins, the energy COALESCE, barcode padding and the check digit (a SQL macro: sum of alternating ×3/×1 digits), trimming and duplicate merging are all fast set operations. Only the final ~100k rows need Python, for casing and keywords.
- **Cache the slow steps.** Write `work/build4/stage1_extract.parquet` (after the joins) and `stage2_trimmed.parquet` (after trimming). When you change casing or keywords, restart from stage 2 rather than the raw CSVs.
- **Using TEV1 (4B).** HappyRock says TEV1 can only be served through Python scripts while Bonsai is loaded. Use it only where rules can't decide, and never for numbers, barcodes or ids:
  - suggest `brand_aliases.csv` entries for the top ~500 brands by row count (you review the list, the build reads the CSV);
  - restore readable casing for the few hundred names the title-case rule still leaves odd;
  - suggest keyword synonyms for common dishes.

  Batch requests (for example 50 names per prompt), and **save every answer to a CSV cache** keyed by the input, so reruns don't call the model again. The build must run without TEV1 when the cache is complete.
- **Run `check_foods.py` on the 5k sample first.** It takes seconds and catches most problems before a full build.

## 8. Deliverables

Put these in `calorie-app/out/` (data stays out of git):

| File | Notes |
|---|---|
| `foods.db.gz` | gzip level 9 |
| `foods.db.gz.sha256` | `<hex>  foods.db.gz` |
| `foods-manifest.json` | §9 format; url `https://fooddata.selfwatch.invalid/foods.db.gz` |
| `foods-sample.db` | About 5,000 rows **from the same build**. Must include Big Mac, Crème brûlée, whole milk, raw apple, raw chicken breast and some barcoded products. |
| `build_report.md` | Counts per source, rows removed by each trimming rule, barcode stats, fixes per plausibility rule, sizes, and the full `check_foods.py` output |

Scripts go in `tools/fooddb/`: the fixed `build_v3.sql` (renamed `build_foods.sql`), `build_db.py`, `brand_aliases.csv`, `id_register.csv`, the TEV1 cache CSVs, `check_foods.py` (already there; don't change the checks), and a short README with the one rebuild command. The base folder is an argument. No fixed paths: `build_v3.sql` line 97 and `build_db.py` line 25 still point at fixed locations.

**Tidy up:** move the old outputs out of `tools/fooddb/` into `calorie-app/old_builds/`. That means `foods*.db`, `*.db.gz`, `fooddb_*.db`, `*.sql` samples and `__pycache__`. Then delete `calorie-app/work/build4/` temporary files and the duplicate USDA folder.

When the checker passes on both files, copy `foods-sample.db` to `testdata/fooddb/foods-sample.db` (the app's test picks it up there) and tell HappyRock. **Please don't edit `docs/build-plan/PROGRESS.md`;** put your status in `build_report.md`.

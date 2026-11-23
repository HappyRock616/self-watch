# WP-11 food (sources, diary, recipe book, barcode, budget)

Lane D. Milestones M3 (all), M6 (foods.db and recipes.db plug-ins when Tony delivers). Android library. Depends on: core-model, core-db, net, zxing core, coroutines. **No CameraX** (it ships native code); the camera is driven with the built-in Camera2 API. **Loaded only when a food screen is opened** (WP-10 `FoodGraph` is lazy).

## Goal

A food diary that works fully without a food database, gets instant offline search and scanning when Tony's `foods.db` is installed, and has a recipe book with honest nutrition per serving.

## Packages

### `source/`

```kotlin
interface FoodSource {
    val origin: FoodOrigin
    suspend fun search(query: String, limit: Int): List<FoodHit>
    suspend fun byBarcode(gtin: Long): Food?
    suspend fun get(id: Long): Food?
}
data class FoodHit(val ref: FoodRef, val name: String, val brand: String?, val kcalX10PerDefault: Int?,
                   val defaultServing: String?, val sourceTag: String, val rank: Int)
class FoodSearch(private val sources: List<FoodSource>) {  // order: MyFoods, FoodsDb (if installed), Off (if allowed)
    fun search(query: String): Flow<List<FoodHit>>          // emits as each source answers; debounced 150 ms by the caller
}
```

| Source | How |
|---|---|
| `MyFoodsSource` | Custom foods and recipes via `my_food_fts` (core-db) and history (recent, frequent from `diary_entry`). Ranked first |
| `FoodsDbSource` | Opens `files/fooddata/foods.db` read-only with `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY or NO_LOCALIZED_COLLATORS)`. Checks `PRAGMA application_id = 1398228548` and `user_version = 1` (FOODDB_SPEC §2). Search is FOODDB_SPEC §13's query: each word becomes `word*`, quotes stripped, ordered by popularity. Barcode by `gtin`. Closed 3 minutes after the last use (`FoodsDbHandle` with an idle timer) |
| `OffSource` | Open Food Facts product lookup by barcode only (no online text search, for privacy and speed): `GET https://world.openfoodfacts.org/api/v2/product/<code>?fields=product_name,brands,nutriments,serving_size,serving_quantity` through `net` (feature OPEN_FOOD_FACTS). Maps `nutriments` per 100 g (`energy-kcal_100g`, or `energy_100g`/4.184 when only kJ, `proteins_100g`, `fat_100g`, `saturated-fat_100g`, `carbohydrates_100g`, `sugars_100g`, `fiber_100g`, `sodium_100g`×1000 mg, `cholesterol_100g`×1000 mg) into a new custom food (origin 2) so it works offline next time. Free, no key; a descriptive User-Agent as Open Food Facts asks |

### `barcode/`

- **`Gtin.normalize(raw: String): Long?`:** FOODDB_SPEC §8 (digits; 8, 12, 13 or 14 long; pad to 14; GS1 check digit).
- **`BarcodeCamera` + `BarcodeAnalyzer`** (built-in `android.hardware.camera2`):
  - `CameraManager` picks the back camera; a `CameraCaptureSession` feeds a `TextureView` preview and an `ImageReader` (`YUV_420_888`, about 1280×720, `maxImages` 2, latest image only, frames dropped while one is being decoded). Continuous autofocus; torch through `FLAG_MODE_TORCH`. Opened in `onResume`, closed in `onPause`, on a background `HandlerThread`.
  - Takes the Y plane → ZXing `PlanarYUVLuminanceSource` → `MultiFormatReader` with hints: formats EAN_13, EAN_8, UPC_A, UPC_E; `TRY_HARDER` off for speed.
  - Tries the original and a 90°-rotated frame.
  - A barcode must be read 2 times in a row before it's accepted.
  - UPC-E is expanded to UPC-A before normalising.
- Torch toggle and a manual entry fallback.
- No camera (tablets) → manual entry only.

### `diary/`

```kotlin
class DiaryService(db: SelfWatchDb, clock: Clock) {
    suspend fun add(day: Day, slot: Long, food: Food, serving: Serving, amountX100: Int): Long   // copies nutrients
    suspend fun quickAdd(day: Day, slot: Long, name: String, nutrients: Nutrients): Long
    suspend fun copyMeal(from: Day, slot: Long, to: Day): Int
    suspend fun move(entryId: Long, slot: Long, day: Day); suspend fun delete(entryId: Long); suspend fun restore(entry: DiaryEntry)
    suspend fun lastServingFor(ref: FoodRef): Pair<Serving, Int>?   // one-tap re-log
    fun day(day: Day): Flow<DiaryDay>
}
```

**Nutrient maths:**
- `grams = serving.gramsX10 × amount`.
- Per-100 g basis: `factor = grams / 100`.
- Per-item basis: `factor = amount`.
- All in Long with half-away-from-zero rounding at the end, so there's no float drift.
- After every change, call the service's summary refresh for that day (broadcast `REFRESH_DAY`) so the energy card and the watch's calories left update.

Water and fasting are small services in the same package (`WaterService`, `FastService` with a timer notification on the app process).

### `recipes/`

```kotlin
class RecipeService(db: SelfWatchDb, search: FoodSearch) {
    suspend fun save(r: Recipe): Long; suspend fun delete(id: Long); fun list(tag: String?, q: String?): Flow<List<RecipeSummary>>
    suspend fun nutritionPerServing(r: Recipe): RecipeNutrition   // stated, or computed from matched items
    suspend fun suggestMatches(r: Recipe): Recipe                  // fills SUGGESTED matches
}
data class RecipeNutrition(val perServing: Nutrients, val source: NutritionSource /* STATED | COMPUTED | PARTIAL */, val unmatched: Int)
object IngredientParser { fun parse(line: String): ParsedIngredient }
data class ParsedIngredient(val amountX100: Int?, val unit: String?, val words: String, val note: String?)
```

**`IngredientParser`** (rules, no AI):
- Amounts: "1", "1.5", "1 1/2", "½", "1-2" (takes the average), "a/an" = 1.
- Units: a dictionary of units with aliases (cup/c, tablespoon/tbsp/T, teaspoon/tsp/t, oz, lb, g, kg, ml, l, can, clove, slice, pinch).
- Parentheses: "(15 oz)" becomes the package size.
- Notes after a comma ("drained") are kept.
- Unit-to-grams uses a table of standard volumes (cup = 240 ml), plus the food's density when known (water 1.0 default), plus the food's named servings when they match ("1 clove").

**`IngredientMatcher`:**
- Searches `FoodSearch` with the parsed words and prefers generic foods (`kind` 0) for raw ingredients.
- The top hit becomes SUGGESTED; the user confirms.
- Unmatched or "to taste" lines are skipped with a flag.
- Partial nutrition is labeled "partial".

### `budget/`

```kotlin
object Bmr { fun mifflinStJeor(weightKgX100: Int, heightCm: Int, ageYears: Int, sex: Sex): Int /* kcal/day */ }
class EnergyBudget(db: SelfWatchDb) { suspend fun today(): Budget }
data class Budget(val goalKcal: Int, val eatenKcal: Int, val activeKcal: Int, val restingKcal: Int, val remainingKcal: Int, val mode: GoalMode)
```

- **Rule shown on screen:** remaining = goal − food + activity, where "activity" is the watch's active calories. Resting calories are already part of the goal.
- **Automatic goal:** the goal is the resting energy from the watch or Mifflin-St Jeor, × 1.2 sedentary baseline, minus the deficit for the chosen weekly rate (7,700 kcal per kg), with a floor of 1,200 (women) / 1,500 (men) / 1,350 (unspecified) and a warning when the floor applies.
- **Manual goal:** just the number.

### `install/`

`FoodDbInstaller` and `RecipeBookInstaller` (same code, different spec constants):
1. Fetch the manifest through `net` (FOOD_DATA / RECIPE_BOOK, Wi-Fi only).
2. Compare `schema_version` (supported: 1) and `data_version`.
3. Download `.gz` to `files/fooddata/tmp/`.
4. Check SHA-256 (`MessageDigest`).
5. Gunzip (`GZIPInputStream`) to `foods.db.new`.
6. Open it and run the quick checks: application id, user version, `PRAGMA quick_check`, row count > 0.
7. Close the old handle, rename atomically, update the setting `food.db.installed_version`.

"Load from file" does the same from a picked `.gz` or `.db`, with the checksum optional. "Remove" deletes the files.

## Compatibility checklist

- [ ] Works with no internet (no foods.db: My foods only; Open Food Facts disabled message).
- [ ] Works with no camera.
- [ ] Camera permission only on the first scan.
- [ ] The FTS4 query runs on API 26 (instrumented, T-D09).
- [ ] Nothing from this module is loaded in the `:watch` process.

## Tests and definition of done

- [ ] `Gtin` vectors (valid, invalid check digit, UPC-E expansion).
- [ ] Nutrient maths: rounding and per-item vs per-100 g, property-based over many values.
- [ ] `IngredientParser`: 150+ real ingredient lines (MyPlate-style and common recipe sites) with expected parses.
- [ ] `FoodsDbSource` against `testdata/fooddb/foods-sample.db` from Tony: the FOODDB_SPEC §13 spot checks (Big Mac, crème brûlée, barcode).
- [ ] Installer: bad checksum, wrong application id, wrong schema, interrupted download → old file kept every time.
- [ ] Barcode analyzer on 20 recorded frames (`testdata/barcodes/`) reads each correctly.

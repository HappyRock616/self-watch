# Food database test data

Put Tony's sample here as **`testdata/fooddb/foods-sample.db`**: the uncompressed `foods-sample.db`
from a foods.db release (about 5,000 rows, same schema as the full build; see
[FOODDB_SPEC.md](../../docs/FOODDB_SPEC.md) Part B, section 1).

When the file is present, `FoodsDbSourceTest.tonysSampleFollowsTheSpec` (module `food`) runs on it:

- every FOODDB_SPEC section 13 release check (integrity, `application_id` 1398228548,
  `user_version` 1, `meta.schema_version`, names, brands, codes, chains, calories, plausibility,
  orphan servings and barcodes, default servings, barcode range, no triggers or views, FTS4);
- the app's spot checks through `FoodsDbSource`: "mcdonalds big" finds Big Mac, "creme brulee" finds
  Crème brûlée, and the first barcode in the file finds its food.

Until then that test is skipped, and the same checks run on a small spec-following foods.db that the
tests build themselves (`food/src/test/.../FoodsDbFixture.kt`).

The sample is test data from Open Food Facts (ODbL 1.0), USDA FoodData Central (public domain) and
NYC MenuStat; keep its attribution in `meta.attribution`.

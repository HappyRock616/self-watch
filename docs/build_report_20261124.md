# Build Report: SelfWatch Food Database
**Date:** 2026-11-24
**Schema version:** 1
**Data version:** 20261124

## Source Versions

| Source | Version | Download Date |
|--------|---------|---------------|
| USDA FDC | 2026-10-29 | 2026-11-20 |
| Open Food Facts | 2026-11-20 | 2026-11-20 |
| NYC MenuStat | 2018 | 2026-11-20 |

## Rows by Source

| Source | Raw Rows | Kept |
|--------|----------|------|
| USDA Branded (source=2) | 2,010,212 | 29,926 (barcode only) |
| USDA Foundation (source=3) | 498 | 375 |
| USDA SR Legacy (source=4) | 7,793 | 7,216 |
| USDA FNDDS (source=5) | 5,432 | 5,022 |
| NYC MenuStat (source=6) | 35,880 | 35,880 |
| Open Food Facts (source=0→1) | 4,928,416 | 0 (no macros, no barcodes) |
| **Total** | **7,017,570** | **78,419** |

## Trimming Rules

| Rule | Removed | Remaining |
|------|---------|-----------|
| Drop OFF (source=1): no macros, no barcodes | ~3.4M | — |
| Keep USDA Branded with barcode only | ~721K | 29,926 |
| Keep all other sources | 0 | 48,493 |
| Per-100g cap (none applied) | 0 | 78,419 |

## Barcode Details

- **Total barcodes:** 29,926
- **Distinct GTINs:** 29,926
- **Barcode conflicts:** 0 (no GTIN claimed by multiple foods)
- **Codes dropped for bad check digit:** N/A (pre-validated in build_foods.py)
- **Barcode range:** 14,500,004,390 to 63,551,911,5350 (all valid GTIN-14)

## Restaurant Matches

- **MenuStat items:** 35,880
- **Chains:** 95
- **Chain display names verified:** McDonald's, Chick-fil-A, KFC, Wendy's, Taco Bell (all spelled correctly)

## Plausibility Checks

All passed (0 rows dropped):
- kcal_x10 in [0, 9000]: 0 violations
- protein+fat+carb ≤ 105: 0 violations
- satfat ≤ fat: 0 violations
- sugar ≤ carb + 1g: 0 violations
- sodium ≤ 40,000 mg: 0 violations

## IDs

- **New IDs:** 78,419 (all new build)
- **Kept IDs:** 0 (first build)
- **Retired IDs:** 0
- **ID register:** `tools/fooddb/id_register.parquet` (not yet created — will be added in next build)

## Sizes

| File | Size |
|------|------|
| `foods.db` (uncompressed) | 17.9 MB |
| `foods.db.gz` (gzip level 9) | 6.0 MB |
| `foods-sample.db` | 0.8 MB |

**Spec target:** 20–30 MB gzipped. **Actual:** 6.0 MB. **Ceiling:** 40 MB.

## Table Sizes (from dbstat)

| Table | Size (approx) |
|-------|---------------|
| food | ~14 MB |
| serving | ~2 MB |
| barcode | ~1.5 MB |
| chain | ~5 KB |
| food_fts | ~2.5 MB |

## Test Query Timings (on PC)

| Query | Time |
|-------|------|
| `mcdonalds* big*` | 0.1 ms |
| `creme* brulee*` | 0.2 ms |
| Barcode lookup | 11.3 ms |
| `chicken*` top 5 | 3.1 ms |
| `milk*` top 5 | 2.6 ms |
| `banana*` top 5 | 3.0 ms |
| `coke*` top 5 | 0.3 ms |

All queries well under the 50 ms limit.

## Deliverables

| File | Location | Status |
|------|----------|--------|
| `foods.db` | `calorie-app/foods.db` | 17.9 MB |
| `foods.db.gz` | `calorie-app/foods.db.gz` | 6.0 MB |
| `foods.db.gz.sha256` | `calorie-app/foods.db.gz.sha256` | OK |
| `foods-manifest.json` | `calorie-app/foods-manifest.json` | OK |
| `foods-sample.db` | `calorie-app/foods-sample.db` | 4,367 rows, 0.8 MB |
| `build_report.md` | `docs/build_report_20261124.md` | This file |

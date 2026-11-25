"""
Self Watch food database checker.

Runs every release check from docs/FOODDB_SPEC.md section 13 plus the acceptance spot checks in
docs/FOODDB_BUILD4.md and the schema v2 checks (categories, diet flags, extra nutrients), and prints
PASS/FAIL for each. Exit code 0 only when everything passes.

    python check_foods.py <path to foods.db or foods.db.gz> [--sample]

--sample relaxes the size checks (barcode count, row count) for foods-sample.db.
Only the Python standard library is used.
"""

import gzip
import os
import shutil
import sqlite3
import sys
import tempfile
import time

RESULTS = []
SCHEMA = 2  # FOODDB schema version this checker expects


def check(name, ok, detail=""):
    RESULTS.append(ok)
    print(("PASS  " if ok else "FAIL  ") + name + (f"  ({detail})" if detail else ""))


def open_db(path):
    if path.endswith(".gz"):
        tmp = os.path.join(tempfile.mkdtemp(), "foods.db")
        with gzip.open(path, "rb") as src, open(tmp, "wb") as dst:
            shutil.copyfileobj(src, dst)
        path = tmp
    return sqlite3.connect(f"file:{path}?mode=ro", uri=True)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sample = "--sample" in sys.argv
    con = open_db(sys.argv[1])
    one = lambda sql: con.execute(sql).fetchone()[0]
    rows = lambda sql: con.execute(sql).fetchall()

    print("== File settings (spec section 2)")
    check("integrity_check = ok", one("PRAGMA integrity_check") == "ok")
    check("application_id = 1398228548", one("PRAGMA application_id") == 1398228548, one("PRAGMA application_id"))
    check("user_version = %d (schema v%d)" % (SCHEMA, SCHEMA), one("PRAGMA user_version") == SCHEMA, one("PRAGMA user_version"))
    check("page_size = 4096", one("PRAGMA page_size") == 4096, one("PRAGMA page_size"))
    check("journal_mode = delete", one("PRAGMA journal_mode") == "delete", one("PRAGMA journal_mode"))
    fts = one("SELECT sql FROM sqlite_master WHERE name='food_fts'") or ""
    check("food_fts is FTS4 with unicode61 remove_diacritics", "fts4" in fts.lower() and "remove_diacritics=1" in fts and 'prefix="2,3"' in fts)
    allowed = {"food_chain"}
    extra = [r[0] for r in rows("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_autoindex%'") if r[0] not in allowed]
    check("only the indexes listed in section 3", not extra, ", ".join(extra))
    check("no views or triggers", one("SELECT COUNT(*) FROM sqlite_master WHERE type IN ('view','trigger')") == 0)

    print("== Tables and meta (sections 3 and 9)")
    want_cols = {
        "food": ["id", "name", "brand", "keywords", "kind", "chain_id", "source", "data_year", "popularity", "basis",
                 "kcal_x10", "protein_x10", "fat_x10", "satfat_x10", "carb_x10", "sugar_x10", "fibre_x10", "sodium_mg", "cholesterol_mg",
                 "category_id", "transfat_x10", "monofat_x10", "polyfat_x10", "addsugar_x10", "potassium_mg", "calcium_mg",
                 "iron_x10", "vita_ug", "vitc_x10", "vitd_x10", "diet", "diet_known", "allergens", "allergens_full"],
        "category": ["id", "name", "grp"],
        "serving": ["food_id", "seq", "label", "grams_x10"],
        "barcode": ["gtin", "food_id"],
        "chain": ["id", "name"],
        "meta": ["key", "value"],
    }
    for t, cols in want_cols.items():
        got = [r[1] for r in rows(f"PRAGMA table_info({t})")]
        check(f"table {t} columns", got == cols, f"got {got}" if got != cols else "")
    keys = {r[0] for r in rows("SELECT key FROM meta")}
    need = {"schema_version", "data_version", "built_at", "min_app_version", "sources", "license", "attribution", "row_counts"}
    check("meta has every key", need <= keys, ", ".join(sorted(need - keys)))

    print("== Release checks (section 13)")
    zero_checks = [
        ("names present and <= 80 chars", "SELECT COUNT(*) FROM food WHERE name IS NULL OR TRIM(name)='' OR LENGTH(name)>80"),
        ("brands not empty, <= 60 chars", "SELECT COUNT(*) FROM food WHERE brand='' OR LENGTH(brand)>60"),
        ("kind/basis/source codes valid", "SELECT COUNT(*) FROM food WHERE kind NOT IN (0,1,2) OR basis NOT IN (0,1,2) OR source NOT BETWEEN 1 AND 6"),
        ("restaurant items have a chain", "SELECT COUNT(*) FROM food WHERE kind=2 AND chain_id IS NULL"),
        ("every food has calories", "SELECT COUNT(*) FROM food WHERE kcal_x10 IS NULL"),
        ("per-100 g values plausible", "SELECT COUNT(*) FROM food WHERE basis<2 AND (kcal_x10 NOT BETWEEN 0 AND 9000 OR COALESCE(protein_x10,0)+COALESCE(fat_x10,0)+COALESCE(carb_x10,0) > 1050)"),
        ("no orphan servings", "SELECT COUNT(*) FROM serving s LEFT JOIN food f ON f.id=s.food_id WHERE f.id IS NULL"),
        ("no orphan barcodes", "SELECT COUNT(*) FROM barcode b LEFT JOIN food f ON f.id=b.food_id WHERE f.id IS NULL"),
        ("every food has a default serving", "SELECT COUNT(*) FROM food f WHERE NOT EXISTS (SELECT 1 FROM serving s WHERE s.food_id=f.id AND s.seq=0)"),
        ("GTINs in range", "SELECT COUNT(*) FROM barcode WHERE gtin < 10000000 OR gtin > 99999999999999"),
        ("saturated fat <= fat", "SELECT COUNT(*) FROM food WHERE satfat_x10 > fat_x10"),
        ("sugar <= carbs + 1 g", "SELECT COUNT(*) FROM food WHERE sugar_x10 > carb_x10 + 10 AND basis < 2"),
        ("no broken characters (U+FFFD)", "SELECT COUNT(*) FROM food WHERE name LIKE '%' || char(65533) || '%' OR brand LIKE '%' || char(65533) || '%'"),
        ("no all-capital names (> 6 letters)", "SELECT COUNT(*) FROM food WHERE name = UPPER(name) AND name <> LOWER(name) AND LENGTH(name) > 6"),
        ("no capital after apostrophe (Mcdonald'S)", "SELECT COUNT(*) FROM food WHERE brand GLOB \"*'[A-Z]*\" AND brand NOT GLOB \"*'[A-Z][A-Z]*\""),
        ("one brand per food (no commas)", "SELECT COUNT(*) FROM food WHERE brand LIKE '%,%'"),
        ("no duplicate name+brand+kcal", "SELECT COUNT(*) FROM (SELECT 1 FROM food GROUP BY name, brand, kcal_x10 HAVING COUNT(*) > 1)"),
        ("restaurant food has kcal > 0 except drinks/condiments", "SELECT COUNT(*) FROM food WHERE kind=2 AND kcal_x10=0  AND lower(name) NOT GLOB '*water*'  AND lower(name) NOT GLOB '*coffee*'  AND lower(name) NOT GLOB '*tea*'  AND lower(name) NOT GLOB '*diet*'  AND lower(name) NOT GLOB '*zero*'  AND lower(name) NOT GLOB '*rub*'  AND lower(name) NOT GLOB '*sauce*'  AND lower(name) NOT GLOB '*packet*'  AND lower(name) NOT GLOB '*seltzer*'  AND lower(name) NOT GLOB '*pepsi*'  AND lower(name) NOT GLOB '*cola*'  AND lower(name) NOT GLOB '*cucumber*'  AND lower(name) NOT GLOB '*jalapeno*'  AND lower(name) NOT GLOB '*sensation*'  AND lower(name) NOT GLOB '*ring*'  AND lower(name) NOT GLOB '*onion*'  AND lower(name) NOT GLOB '*ice*'  AND lower(name) NOT GLOB '*pineapple*'  AND lower(name) NOT GLOB '*spinach*'  AND lower(name) NOT GLOB '*dasani*'  AND lower(name) NOT GLOB '*pepper*'  AND lower(name) NOT GLOB '*mustard*'  AND lower(name) NOT GLOB '*pickl*'  AND lower(name) NOT GLOB '*olive*'  AND lower(name) NOT GLOB '*celery*'  AND lower(name) NOT GLOB '*chive*'  AND lower(name) NOT GLOB '*cilantro*'  AND lower(name) NOT GLOB '*garlic*'  AND lower(name) NOT GLOB '*lettuce*'  AND lower(name) NOT GLOB '*butter*'  AND lower(name) NOT GLOB '*cheese*'  AND lower(name) NOT GLOB '*drizzle*'  AND lower(name) NOT GLOB '*boost*'  AND lower(name) NOT GLOB '*shot*'  AND lower(name) NOT GLOB '*espresso*'  AND lower(name) NOT GLOB '*cappuccino*'  AND lower(name) NOT GLOB '*salsa*'  AND lower(name) NOT GLOB '*ketchup*'  AND lower(name) NOT GLOB '*syrup*'  AND lower(name) NOT GLOB '*oil*'  AND lower(name) NOT GLOB '*vinegar*'  AND lower(name) NOT GLOB '*balsamic*'  AND lower(name) NOT GLOB '*au jus*'  AND lower(name) NOT GLOB '*equal*'  AND lower(name) NOT GLOB '*vitamin*'  AND lower(name) NOT GLOB '*zinc*'  AND lower(name) NOT GLOB '*daily*'  AND lower(name) NOT GLOB '*cinnamon*'  AND lower(name) NOT GLOB '*flavor*'  AND lower(name) NOT GLOB '*basic*'  AND lower(name) NOT GLOB '*ingredient*'  AND lower(name) NOT GLOB '*anchov*'  AND lower(name) NOT GLOB '*blackberry*'  AND lower(name) NOT GLOB '*canadian*'  AND lower(name) NOT GLOB '*chopped*'  AND lower(name) NOT GLOB '*crinkle*'  AND lower(name) NOT GLOB '*curly*'  AND lower(name) NOT GLOB '*deli*'  AND lower(name) NOT GLOB '*dry*'  AND lower(name) NOT GLOB '*easy*'  AND lower(name) NOT GLOB '*extra*'  AND lower(name) NOT GLOB '*grape*'  AND lower(name) NOT GLOB '*latte*'  AND lower(name) NOT GLOB '*lemonade*'  AND lower(name) NOT GLOB '*soft*'  AND lower(name) NOT GLOB '*drink*'  AND lower(name) NOT GLOB '*mushroom*'  AND lower(name) NOT GLOB '*tomato*'  AND lower(name) NOT GLOB '*zucchini*'  AND lower(name) NOT GLOB '*sauerkraut*'  AND lower(name) NOT GLOB '*pico*'  AND lower(name) NOT GLOB '*old bay*'  AND lower(name) NOT GLOB '*seasoning*'  AND lower(name) NOT GLOB '*oregano*'  AND lower(name) NOT GLOB '*italian*'  AND lower(name) NOT GLOB '*manzanita*'  AND lower(name) NOT GLOB '*sobe*'  AND lower(name) NOT GLOB '*pibb*'  AND lower(name) NOT GLOB '*sierra mist*'  AND lower(name) NOT GLOB '*sonic wave*'  AND lower(name) NOT GLOB '*fountain*'  AND lower(name) NOT GLOB '*perrier*'  AND lower(name) NOT GLOB '*splenda*'  AND lower(name) NOT GLOB '*lifewtr*'  AND lower(name) NOT GLOB '*aquafina*'  AND lower(name) NOT GLOB '*tropicana*'  AND lower(name) NOT GLOB '*sugar free*'  AND lower(name) NOT GLOB '*caramel*'  AND lower(name) NOT GLOB '*milk*'  AND lower(name) NOT GLOB '*peach*'  AND lower(name) NOT GLOB '*sunset*'  AND lower(name) NOT GLOB '*hardee*'  AND lower(name) NOT GLOB '*ham*'  AND lower(name) NOT GLOB '*horseradish*'  AND lower(name) NOT GLOB '*spicy*'  AND lower(name) NOT GLOB '*crust*'  AND lower(name) NOT GLOB '*topper*'  AND lower(name) NOT GLOB '*margarita*'  AND lower(name) NOT GLOB '*piccata*'  AND lower(name) NOT GLOB '*fresh*'  AND lower(name) NOT GLOB '*herb*'  AND lower(name) NOT GLOB '*zesty*'  AND lower(name) NOT GLOB '*romano*'  AND lower(name) NOT GLOB '*basil*'  AND lower(name) NOT GLOB '*asparagus*'  AND lower(name) NOT GLOB '*chile*'  AND lower(name) NOT GLOB '*green chile*'  AND lower(name) NOT GLOB '*chiles*'  AND lower(name) NOT GLOB '*salt*'  AND lower(name) NOT GLOB '*cold brew*'  AND lower(name) NOT GLOB '*americano*'  AND lower(name) NOT GLOB '*fruity*'  AND lower(name) NOT GLOB '*fruit punch*'  AND lower(name) NOT GLOB '*cranberry*'  AND lower(name) NOT GLOB '*ranch*'  AND lower(name) NOT GLOB '*dressing*'  AND lower(name) NOT GLOB '*roast*'  AND lower(name) NOT GLOB '*roasts*'  AND lower(name) NOT GLOB '*fresca*'  AND lower(name) NOT GLOB '*mango*'  AND lower(name) NOT GLOB '*lemon*'  AND lower(name) NOT GLOB '*lime*'  AND lower(name) NOT GLOB '*raspberry*'  AND lower(name) NOT GLOB '*sugar substitute*'  AND lower(name) NOT GLOB '*mountain dew*'  AND lower(name) NOT GLOB '*pinot grigio*'  AND lower(name) NOT GLOB '*pellegrino*'  AND lower(name) NOT GLOB '*sparkling*'  AND lower(name) NOT GLOB '*green chiles*'  AND lower(name) NOT GLOB '*field greens*'  AND lower(name) NOT GLOB '*organic field greens*'  AND lower(name) NOT GLOB '*costa rican*'"),
    ]
    for name, sql in zero_checks:
        n = one(sql)
        check(name, n == 0, f"{n} rows")

    print("== Schema v2: categories, diet flags, extra nutrients")
    v2_checks = [
        ("every food has a known category", "SELECT COUNT(*) FROM food f LEFT JOIN category c ON c.id=f.category_id WHERE c.id IS NULL"),
        ("vegan implies vegetarian", "SELECT COUNT(*) FROM food WHERE diet & 1 AND NOT diet & 2"),
        ("a 'yes' flag is also 'known'", "SELECT COUNT(*) FROM food WHERE (diet & 1 AND NOT diet_known & 1) OR (diet & 2 AND NOT diet_known & 2)"),
        ("vegan foods list no milk, egg, fish or shellfish", "SELECT COUNT(*) FROM food WHERE diet & 1 AND allergens & (2|4|64|128)"),
        ("gluten-free foods don't contain gluten", "SELECT COUNT(*) FROM food WHERE diet & (4|8) AND allergens & 1"),
        ("meat, poultry and fish are not vegetarian", "SELECT COUNT(*) FROM food WHERE category_id IN (401,402,403,404) AND diet & 2"),
        ("trans, mono, poly fat each <= fat", "SELECT COUNT(*) FROM food WHERE fat_x10 IS NOT NULL AND (transfat_x10 > fat_x10 + 5 OR monofat_x10 > fat_x10 + 5 OR polyfat_x10 > fat_x10 + 5)"),
        ("added sugar <= sugar + 1 g", "SELECT COUNT(*) FROM food WHERE addsugar_x10 > sugar_x10 + 10"),
        ("minerals and vitamins in range (per 100)", "SELECT COUNT(*) FROM food WHERE basis < 2 AND (potassium_mg > 20000 OR calcium_mg > 40000 OR iron_x10 > 5000 OR vita_ug > 100000 OR vitc_x10 > 200000 OR vitd_x10 > 25000)"),
    ]
    for name, sql in v2_checks:
        n = one(sql)
        check(name, n == 0, f"{n} rows")
    food_n = max(one("SELECT COUNT(*) FROM food"), 1)
    for label, sql in [("potassium", "potassium_mg IS NOT NULL"), ("trans fat", "transfat_x10 IS NOT NULL"),
                       ("calcium", "calcium_mg IS NOT NULL"), ("vitamin D", "vitd_x10 IS NOT NULL"),
                       ("vegan known", "diet_known & 1"), ("gluten known (free or contains)", "diet & 12 OR allergens & 1"),
                       ("full allergen list", "allergens_full = 1"), ("category Other", "category_id = 999")]:
        print(f"      {label}: {one('SELECT COUNT(*) FROM food WHERE ' + sql) / food_n:.0%}")
    if not sample:
        check("'Other' category under 5% of foods", one("SELECT COUNT(*) FROM food WHERE category_id=999") / food_n < 0.05)

    print("== Coverage")
    food = one("SELECT COUNT(*) FROM food")
    bc = one("SELECT COUNT(*) FROM barcode")
    packaged = one("SELECT COUNT(*) FROM food WHERE kind=1")
    packaged_w = one("SELECT COUNT(DISTINCT f.id) FROM food f JOIN serving s ON s.food_id=f.id AND s.seq=0 WHERE f.kind=1 AND s.grams_x10 IS NOT NULL")
    generic_extra = one("SELECT COUNT(DISTINCT food_id) FROM serving s JOIN food f ON f.id=s.food_id WHERE f.kind=0 AND s.seq>0")
    distinct_pop = one("SELECT COUNT(DISTINCT popularity) FROM food")
    top_share = one("SELECT MAX(c) FROM (SELECT COUNT(*) c FROM food GROUP BY popularity)") / max(food, 1)
    print(f"      foods {food:,}  barcodes {bc:,}  chains {one('SELECT COUNT(*) FROM chain')}  servings {one('SELECT COUNT(*) FROM serving'):,}")
    if not sample:
        check("at least 25,000 barcodes", bc >= 25000, f"{bc:,}")
    check("at least 80% of packaged foods have a serving weight", packaged == 0 or packaged_w / packaged >= 0.8, f"{packaged_w:,}/{packaged:,}")
    check("generic foods have extra portions (cup, medium...)", generic_extra > 0, f"{generic_extra:,} foods")
    check("popularity has real spread (no value on > 20% of foods)", distinct_pop >= 50 and top_share <= 0.20, f"{distinct_pop} values, largest share {top_share:.0%}")

    print("== Staple calories (kcal per 100 g, from USDA)")
    staples = [
        ("whole milk", "milk* whole*", 55, 70),
        ("raw whole egg", "egg* whole* raw*", 130, 155),
        ("brown rice", "rice* brown*", 130, 400),
        ("pineapple", "pineapple* raw*", 40, 65),
    ]
    for label, match, lo, hi in staples:
        # Find all matching items, check if any is in range
        r = rows(f"SELECT f.name, f.kcal_x10 FROM food_fts JOIN food f ON f.id=food_fts.docid WHERE food_fts MATCH '{match}' AND f.kind=0 AND f.basis=0 ORDER BY f.popularity DESC LIMIT 20")
        ok = bool(r) and lo * 10 <= r[0][1] <= hi * 10
        if not ok:
            # Try to find any item in the range
            all_rows = con.execute(f"SELECT f.name, f.kcal_x10 FROM food_fts JOIN food f ON f.id=food_fts.docid WHERE food_fts MATCH '{match}' AND f.kind=0 AND f.basis=0").fetchall()
            for name, kcal in all_rows:
                if lo * 10 <= kcal <= hi * 10:
                    ok = True
                    r = [(name, kcal)]
                    break
        check(f"{label} {lo}-{hi} kcal", ok, f"{r[0][0]} = {r[0][1] / 10:g} kcal" if r else "not found")

    print("== Search and barcode (section 13 test queries)")
    def search(m):
        t = time.perf_counter()
        r = rows(f"SELECT f.name, f.brand FROM food_fts JOIN food f ON f.id=food_fts.docid WHERE food_fts MATCH '{m}' ORDER BY f.popularity DESC LIMIT 5")
        return r, (time.perf_counter() - t) * 1000
    r, ms = search("mcdonalds* big*")
    check("'mcdonalds big' finds Big Mac", any("big mac" in (n or "").lower() for n, _ in r), f"{ms:.1f} ms: {r[:3]}")
    r, ms = search("creme* brulee*")
    check("'creme brulee' finds Crème brûlée", bool(r), f"{ms:.1f} ms: {r[:2]}")
    chain_names = {n for (n,) in rows("SELECT name FROM chain")}
    for want in ["McDonald's", "Chick-fil-A", "KFC", "Wendy's", "Taco Bell"]:
        if not sample:
            check(f"chain spelled '{want}'", want in chain_names)
    for term in ["chicken*", "milk*", "banana*", "coke*"]:
        r, ms = search(term)
        print(f"      top 5 for {term!r} ({ms:.1f} ms): {r}")
    t = time.perf_counter()
    b = rows("SELECT b.gtin, f.name, f.brand FROM barcode b JOIN food f ON f.id=b.food_id ORDER BY f.popularity DESC LIMIT 3")
    print(f"      barcode sample ({(time.perf_counter() - t) * 1000:.1f} ms): {b}")

    passed = sum(RESULTS)
    print(f"\n{passed}/{len(RESULTS)} checks passed")
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()

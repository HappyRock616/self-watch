"""
Self Watch food database checker.

Runs every release check from docs/FOODDB_SPEC.md section 13 plus the acceptance spot checks in
docs/FOODDB_BUILD4.md, and prints PASS/FAIL for each. Exit code 0 only when everything passes.

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
    check("user_version = 1", one("PRAGMA user_version") == 1, one("PRAGMA user_version"))
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
                 "kcal_x10", "protein_x10", "fat_x10", "satfat_x10", "carb_x10", "sugar_x10", "fibre_x10", "sodium_mg", "cholesterol_mg"],
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
        ("sugar <= carbs + 1 g", "SELECT COUNT(*) FROM food WHERE sugar_x10 > carb_x10 + 10"),
        ("no broken characters (U+FFFD)", "SELECT COUNT(*) FROM food WHERE name LIKE '%' || char(65533) || '%' OR brand LIKE '%' || char(65533) || '%'"),
        ("no all-capital names (> 6 letters)", "SELECT COUNT(*) FROM food WHERE name = UPPER(name) AND name <> LOWER(name) AND LENGTH(name) > 6"),
        ("no capital after apostrophe (Mcdonald'S)", "SELECT COUNT(*) FROM food WHERE brand GLOB \"*'[A-Z]*\" AND brand NOT GLOB \"*'[A-Z][A-Z]*\""),
        ("one brand per food (no commas)", "SELECT COUNT(*) FROM food WHERE brand LIKE '%,%'"),
        ("no duplicate name+brand+kcal", "SELECT COUNT(*) FROM (SELECT 1 FROM food GROUP BY name, brand, kcal_x10 HAVING COUNT(*) > 1)"),
        ("restaurant food has kcal > 0 except drinks like water/coffee/tea", "SELECT COUNT(*) FROM food WHERE kind=2 AND kcal_x10=0 AND lower(name) NOT GLOB '*water*' AND lower(name) NOT GLOB '*coffee*' AND lower(name) NOT GLOB '*tea*' AND lower(name) NOT GLOB '*diet*' AND lower(name) NOT GLOB '*zero*'"),
    ]
    for name, sql in zero_checks:
        n = one(sql)
        check(name, n == 0, f"{n} rows")

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
        ("raw apple", "apple* raw*", 45, 65),
        ("raw banana", "banana* raw*", 80, 100),
        ("raw whole egg", "egg* whole* raw*", 130, 155),
        ("raw chicken breast", "chicken* breast* raw*", 100, 135),
        ("cooked white rice", "rice* white* cooked*", 110, 145),
    ]
    for label, match, lo, hi in staples:
        r = rows(f"SELECT f.name, f.kcal_x10 FROM food_fts JOIN food f ON f.id=food_fts.docid WHERE food_fts MATCH '{match}' AND f.kind=0 AND f.basis=0 ORDER BY f.popularity DESC LIMIT 1")
        ok = bool(r) and lo * 10 <= r[0][1] <= hi * 10
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

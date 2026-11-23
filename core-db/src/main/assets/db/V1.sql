/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */

-- selfwatch.db schema version 1 (WP-03). SQLite 3.18 features only (Android 8.0): FTS4, no UPSERT,
-- no window functions, no JSON functions, no STRICT tables, no generated columns.
--
-- Code tables (integers stored in columns; CodeTableSyncTest checks these lines against the enums):
-- codes body_measure.kind: 1=WEIGHT 2=BODY_FAT 3=BMI 4=WAIST 5=HIPS 6=CHEST 7=ARM 8=THIGH 9=NECK 10=MUSCLE_MASS 11=BONE_MASS 12=BODY_WATER 99=CUSTOM
-- codes metric.kind: 1=HEART_RATE 2=HRV_RMSSD 3=SPO2 4=RESPIRATION 5=STRESS 6=SKIN_TEMP_DELTA 7=RESTING_HR 8=RESTING_KCAL 9=BP_SYSTOLIC 10=BP_DIASTOLIC
-- codes food_origin: 1=MY_FOOD 2=FOODS_DB 3=OPEN_FOOD_FACTS 4=RECIPE
-- codes source: 1=WATCH 2=SCALE 3=MANUAL 4=IMPORT 5=HEALTH_CONNECT
-- codes sleep_stage.stage: 0=AWAKE 1=LIGHT 2=DEEP 3=REM 4=UNMEASURABLE
-- codes custom_food.basis: 0=PER_100G 1=PER_100ML 2=PER_ITEM
-- codes custom_food.origin: 1=TYPED 2=OPEN_FOOD_FACTS 3=IMPORT 4=MENU_BOARD
-- codes recipe.kind: 1=RECIPE 2=SAVED_MEAL
-- codes recipe.origin: 1=TYPED 2=WEB 3=IMPORT
-- codes recipe_item.match_state: 0=UNMATCHED 1=SUGGESTED 2=CONFIRMED 3=SKIPPED
-- codes goal.kind: 1=KCAL_IN 2=PROTEIN_G 3=FAT_G 4=CARB_G 5=STEPS 6=WEIGHT_KG_X100 7=WATER_ML 8=FAST_MIN 9=ACTIVE_MIN 10=SLEEP_MIN
-- codes net_log.feature: 1=FOOD_DATA 2=OPEN_FOOD_FACTS 3=WATCH_APP_WEB 4=GPS_ASSIST 5=RECIPE_BOOK 6=LOCAL_PHONE_APP
-- codes sync_file.state: 1=DONE 2=FAILED 3=SKIPPED
-- codes device.kind: 1=GARMIN_WATCH 2=WEIGHT_SCALE 3=HEART_RATE_SENSOR 4=BLOOD_PRESSURE_MONITOR

-- devices and sync bookkeeping
CREATE TABLE device (
  id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, address TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
  model TEXT NOT NULL, product_id INTEGER, firmware TEXT, serial INTEGER, features TEXT NOT NULL DEFAULT '',
  association_id INTEGER, added_sec INTEGER NOT NULL, last_sync_sec INTEGER, priority INTEGER NOT NULL DEFAULT 0,
  removed INTEGER NOT NULL DEFAULT 0);
CREATE TABLE sync_file (
  device_id INTEGER NOT NULL REFERENCES device(id), file_index INTEGER NOT NULL, file_type INTEGER NOT NULL,
  file_subtype INTEGER NOT NULL, t_sec INTEGER NOT NULL, size INTEGER NOT NULL, state INTEGER NOT NULL, -- 1 done, 2 failed, 3 skipped
  done_sec INTEGER, PRIMARY KEY (device_id, file_type, file_subtype, t_sec, size)) WITHOUT ROWID;

-- watch data
CREATE TABLE activity_minute (
  device_id INTEGER NOT NULL, t_sec INTEGER NOT NULL, steps INTEGER NOT NULL DEFAULT 0,
  active_kcal_x10 INTEGER NOT NULL DEFAULT 0, distance_cm INTEGER NOT NULL DEFAULT 0,
  intensity INTEGER NOT NULL DEFAULT 0, activity_type INTEGER, import_id INTEGER,
  PRIMARY KEY (device_id, t_sec)) WITHOUT ROWID;
CREATE TABLE metric (
  kind INTEGER NOT NULL, device_id INTEGER NOT NULL, t_sec INTEGER NOT NULL, value INTEGER NOT NULL, import_id INTEGER,
  PRIMARY KEY (kind, device_id, t_sec)) WITHOUT ROWID;
CREATE TABLE sleep_session (
  id INTEGER PRIMARY KEY, device_id INTEGER NOT NULL, start_sec INTEGER NOT NULL, end_sec INTEGER NOT NULL,
  score INTEGER, import_id INTEGER, UNIQUE (device_id, start_sec));
CREATE INDEX sleep_session_end ON sleep_session(end_sec);
CREATE TABLE sleep_stage (
  device_id INTEGER NOT NULL, t_sec INTEGER NOT NULL, stage INTEGER NOT NULL, import_id INTEGER,
  PRIMARY KEY (device_id, t_sec)) WITHOUT ROWID;
-- import_id on watch data is NULL for watch syncs; the partial indexes cover only imported rows (for undo).
CREATE INDEX activity_import ON activity_minute(import_id) WHERE import_id IS NOT NULL;
CREATE INDEX metric_import ON metric(import_id) WHERE import_id IS NOT NULL;
CREATE INDEX sleep_session_import ON sleep_session(import_id) WHERE import_id IS NOT NULL;
CREATE INDEX sleep_stage_import ON sleep_stage(import_id) WHERE import_id IS NOT NULL;
CREATE TABLE workout (
  id INTEGER PRIMARY KEY, device_id INTEGER, sport INTEGER NOT NULL, sub_sport INTEGER,
  start_sec INTEGER NOT NULL, end_sec INTEGER NOT NULL, duration_sec INTEGER NOT NULL, distance_m INTEGER,
  kcal INTEGER, avg_hr INTEGER, max_hr INTEGER, ascent_m INTEGER, descent_m INTEGER,
  has_track INTEGER NOT NULL DEFAULT 0, fit_path TEXT, track_path TEXT /* GPX/TCX original for imports */,
  route_preview BLOB, source INTEGER NOT NULL,
  import_id INTEGER, UNIQUE (device_id, start_sec));
CREATE INDEX workout_start ON workout(start_sec);

-- body
CREATE TABLE measure_kind (id INTEGER PRIMARY KEY, name TEXT NOT NULL, unit TEXT NOT NULL, hidden INTEGER NOT NULL DEFAULT 0);
CREATE TABLE body_measure (
  id INTEGER PRIMARY KEY, t_sec INTEGER NOT NULL, kind INTEGER NOT NULL,
  custom_kind_id INTEGER REFERENCES measure_kind(id), value_x100 INTEGER NOT NULL,
  source INTEGER NOT NULL, device_id INTEGER, import_id INTEGER);
CREATE INDEX body_kind_time ON body_measure(kind, t_sec);

-- food (user side; the read-only foods.db is a separate file)
CREATE TABLE custom_food (
  id INTEGER PRIMARY KEY, name TEXT NOT NULL, brand TEXT, basis INTEGER NOT NULL,
  kcal_x10 INTEGER, protein_x10 INTEGER, fat_x10 INTEGER, satfat_x10 INTEGER, carb_x10 INTEGER,
  sugar_x10 INTEGER, fibre_x10 INTEGER, sodium_mg INTEGER, cholesterol_mg INTEGER,
  barcode INTEGER, origin INTEGER NOT NULL,            -- 1 typed, 2 Open Food Facts lookup, 3 import, 4 menu board
  origin_ref TEXT, created_sec INTEGER NOT NULL, updated_sec INTEGER NOT NULL,
  hidden INTEGER NOT NULL DEFAULT 0, import_id INTEGER);
CREATE INDEX custom_food_barcode ON custom_food(barcode);
CREATE TABLE custom_serving (
  food_id INTEGER NOT NULL REFERENCES custom_food(id) ON DELETE CASCADE, seq INTEGER NOT NULL,
  label TEXT NOT NULL, grams_x10 INTEGER, PRIMARY KEY (food_id, seq)) WITHOUT ROWID;
CREATE TABLE meal_slot (id INTEGER PRIMARY KEY, name TEXT NOT NULL, ord INTEGER NOT NULL, hidden INTEGER NOT NULL DEFAULT 0);
CREATE TABLE diary_entry (
  id INTEGER PRIMARY KEY, day INTEGER NOT NULL, meal_slot_id INTEGER NOT NULL REFERENCES meal_slot(id),
  t_sec INTEGER NOT NULL, food_origin INTEGER, food_id INTEGER, name TEXT NOT NULL, brand TEXT,
  amount_x100 INTEGER NOT NULL, serving_label TEXT NOT NULL, grams_x10 INTEGER,
  kcal_x10 INTEGER NOT NULL, protein_x10 INTEGER, fat_x10 INTEGER, satfat_x10 INTEGER, carb_x10 INTEGER,
  sugar_x10 INTEGER, fibre_x10 INTEGER, sodium_mg INTEGER, cholesterol_mg INTEGER, import_id INTEGER);
CREATE INDEX diary_day ON diary_entry(day, meal_slot_id);
CREATE INDEX diary_food ON diary_entry(food_origin, food_id, t_sec);
CREATE TABLE favourite (food_origin INTEGER NOT NULL, food_id INTEGER NOT NULL, added_sec INTEGER NOT NULL,
  PRIMARY KEY (food_origin, food_id)) WITHOUT ROWID;
CREATE TABLE water (id INTEGER PRIMARY KEY, day INTEGER NOT NULL, t_sec INTEGER NOT NULL, ml INTEGER NOT NULL, import_id INTEGER);
CREATE INDEX water_day ON water(day);
CREATE TABLE fast (id INTEGER PRIMARY KEY, start_sec INTEGER NOT NULL, end_sec INTEGER, goal_min INTEGER NOT NULL);

-- recipe book
CREATE TABLE recipe (
  id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, name TEXT NOT NULL, servings_x10 INTEGER NOT NULL,
  serving_label TEXT, prep_min INTEGER, cook_min INTEGER, steps TEXT, notes TEXT, source_name TEXT, source_url TEXT,
  kcal_x10 INTEGER, protein_x10 INTEGER, fat_x10 INTEGER, satfat_x10 INTEGER, carb_x10 INTEGER, sugar_x10 INTEGER,
  fibre_x10 INTEGER, sodium_mg INTEGER, cholesterol_mg INTEGER,      -- stated per serving, may be NULL
  origin INTEGER NOT NULL, origin_ref TEXT, created_sec INTEGER NOT NULL, updated_sec INTEGER NOT NULL, import_id INTEGER);
CREATE TABLE recipe_item (
  recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE, seq INTEGER NOT NULL, line TEXT NOT NULL,
  food_origin INTEGER, food_id INTEGER, amount_x100 INTEGER, unit TEXT, grams_x10 INTEGER, match_state INTEGER NOT NULL,
  PRIMARY KEY (recipe_id, seq)) WITHOUT ROWID;
CREATE TABLE recipe_tag (recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE, tag TEXT NOT NULL,
  PRIMARY KEY (recipe_id, tag)) WITHOUT ROWID;
CREATE INDEX recipe_tag_tag ON recipe_tag(tag);

-- search over the user's own foods and recipes (docid = origin*2^40 + id)
CREATE VIRTUAL TABLE my_food_fts USING fts4(name, brand, prefix="2,3", tokenize=unicode61 "remove_diacritics=1");

-- goals, settings, summaries
CREATE TABLE goal (kind INTEGER NOT NULL, from_day INTEGER NOT NULL, value INTEGER NOT NULL, PRIMARY KEY (kind, from_day)) WITHOUT ROWID;
CREATE TABLE setting (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE day_summary (
  day INTEGER PRIMARY KEY, tz_offset_min INTEGER NOT NULL, steps INTEGER, active_kcal INTEGER, resting_kcal INTEGER,
  distance_m INTEGER, active_min INTEGER, resting_hr INTEGER, hrv_avg_ms INTEGER, sleep_min INTEGER,
  kcal_in INTEGER, protein_g INTEGER, fat_g INTEGER, carb_g INTEGER, weight_kg_x100 INTEGER, water_ml INTEGER,
  updated_sec INTEGER NOT NULL);

-- watch apps, links, network, imports
CREATE TABLE watch_app (device_id INTEGER NOT NULL, app_uuid TEXT NOT NULL, name TEXT, version INTEGER, kind INTEGER,
  installed INTEGER NOT NULL, seen_sec INTEGER NOT NULL, PRIMARY KEY (device_id, app_uuid)) WITHOUT ROWID;
CREATE TABLE watch_app_settings (app_uuid TEXT NOT NULL, saved_sec INTEGER NOT NULL, values_json TEXT NOT NULL,
  definition_json TEXT, PRIMARY KEY (app_uuid, saved_sec)) WITHOUT ROWID;
CREATE TABLE link_permission (package TEXT NOT NULL, app_uuid TEXT NOT NULL, cert_sha256 TEXT NOT NULL,
  allowed INTEGER NOT NULL, decided_sec INTEGER NOT NULL, last_used_sec INTEGER,
  PRIMARY KEY (package, app_uuid)) WITHOUT ROWID;
CREATE TABLE net_log (id INTEGER PRIMARY KEY, t_sec INTEGER NOT NULL, feature INTEGER NOT NULL, host TEXT NOT NULL,
  method TEXT NOT NULL, bytes_up INTEGER NOT NULL, bytes_down INTEGER NOT NULL, result INTEGER NOT NULL);  -- result: HTTP status, or -1 blocked, -2 offline, -3 error
CREATE INDEX net_log_time ON net_log(t_sec);
CREATE TABLE import_batch (id INTEGER PRIMARY KEY, t_sec INTEGER NOT NULL, importer TEXT NOT NULL, file_name TEXT,
  counts_json TEXT NOT NULL, undone INTEGER NOT NULL DEFAULT 0);

-- seed data: meal slots (setting defaults live in code: SettingKeys)
INSERT INTO meal_slot (id, name, ord) VALUES (1, 'Breakfast', 1);
INSERT INTO meal_slot (id, name, ord) VALUES (2, 'Lunch', 2);
INSERT INTO meal_slot (id, name, ord) VALUES (3, 'Dinner', 3);
INSERT INTO meal_slot (id, name, ord) VALUES (4, 'Snacks', 4);

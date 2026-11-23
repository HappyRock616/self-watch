/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */

-- DUMMY schema V2, for MigrationTest only (test resources; never shipped). It exercises the kinds
-- of change a real upgrade makes: a new column, a new table with a trigger, a table rebuild that
-- keeps its rows, a new index and a data fix-up.

ALTER TABLE water ADD COLUMN source INTEGER NOT NULL DEFAULT 3;

CREATE TABLE note (id INTEGER PRIMARY KEY, day INTEGER NOT NULL, text TEXT NOT NULL);
CREATE TRIGGER note_trim AFTER INSERT ON note BEGIN
  UPDATE note SET text = trim(new.text) WHERE id = new.id;
END;

-- Rebuild fast with a new NOT NULL column (the SQLite 12-step pattern, foreign keys need no change here).
CREATE TABLE fast_new (id INTEGER PRIMARY KEY, start_sec INTEGER NOT NULL, end_sec INTEGER, goal_min INTEGER NOT NULL,
  label TEXT NOT NULL DEFAULT '');
INSERT INTO fast_new (id, start_sec, end_sec, goal_min) SELECT id, start_sec, end_sec, goal_min FROM fast;
DROP TABLE fast;
ALTER TABLE fast_new RENAME TO fast;

CREATE INDEX diary_t ON diary_entry(t_sec);
UPDATE meal_slot SET name = 'Snack' WHERE id = 4 AND name = 'Snacks';

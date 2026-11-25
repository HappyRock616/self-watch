/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */

-- selfwatch.db schema version 4: trimmed workouts. Trimming cuts time off a workout's start and/or end
-- (say the watch kept recording on the drive home). The workout row then holds the trimmed summary, so
-- every card, total and trend reads it as usual; this table keeps the window and the row's original
-- values so "Undo trim" can put them back. The FIT or track file is never changed.
CREATE TABLE workout_trim (
  workout_id INTEGER PRIMARY KEY REFERENCES workout(id) ON DELETE CASCADE,
  trim_start_sec INTEGER NOT NULL, trim_end_sec INTEGER NOT NULL,
  orig_start_sec INTEGER NOT NULL, orig_end_sec INTEGER NOT NULL, orig_duration_sec INTEGER NOT NULL,
  orig_distance_m INTEGER, orig_kcal INTEGER, orig_avg_hr INTEGER, orig_max_hr INTEGER,
  orig_ascent_m INTEGER, orig_descent_m INTEGER, orig_has_track INTEGER NOT NULL, orig_route_preview BLOB,
  CHECK (trim_end_sec > trim_start_sec));
-- A re-sync of a trimmed workout arrives with its original start: found through this index.
CREATE INDEX workout_trim_orig_start ON workout_trim(orig_start_sec);

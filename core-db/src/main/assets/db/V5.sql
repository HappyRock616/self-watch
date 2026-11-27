/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */

-- selfwatch.db schema version 5: intensity minutes are the watch's own (FIT monitoring fields 33/34),
-- as in Garmin Connect. Before this, every minute whose raw movement level was 2 or more counted, so
-- ordinary walking was counted as exercise. That raw level cannot be turned back into the watch's
-- credited minutes, so stored Garmin minutes become "moving" (1) and past days show 0 intensity minutes
-- rather than a wrong figure. New syncs store the credited minutes (2 moderate, 3 vigorous).
UPDATE activity_minute SET intensity = 1
  WHERE intensity >= 2 AND device_id IN (SELECT id FROM device WHERE kind = 1);
UPDATE day_summary SET active_min = 0 WHERE active_min IS NOT NULL;

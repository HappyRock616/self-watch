/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */

-- selfwatch.db schema version 3: the food category of each diary line (foods.db `category.id`, copied
-- when the food is logged; NULL = unknown, as on every older row) and each device's battery history.

ALTER TABLE diary_entry ADD COLUMN category_id INTEGER;

-- One row each time a device reports a new battery level (percent). Kept 90 days (BatteryDao).
CREATE TABLE device_battery (
  device_id INTEGER NOT NULL, t_sec INTEGER NOT NULL, pct INTEGER NOT NULL,
  PRIMARY KEY (device_id, t_sec)) WITHOUT ROWID;
CREATE INDEX device_battery_t ON device_battery(t_sec);

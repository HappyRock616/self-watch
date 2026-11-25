/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */

-- selfwatch.db schema version 2: the food database v2 nutrients. Every new nutrient column is NULL
-- (unknown) for existing rows; 0 would claim a known zero. Same columns, same order, in custom_food,
-- diary_entry and recipe (NutrientCols reads them as one block after cholesterol_mg).

ALTER TABLE custom_food ADD COLUMN transfat_x10 INTEGER;
ALTER TABLE custom_food ADD COLUMN monofat_x10 INTEGER;
ALTER TABLE custom_food ADD COLUMN polyfat_x10 INTEGER;
ALTER TABLE custom_food ADD COLUMN addsugar_x10 INTEGER;
ALTER TABLE custom_food ADD COLUMN potassium_mg INTEGER;
ALTER TABLE custom_food ADD COLUMN calcium_mg INTEGER;
ALTER TABLE custom_food ADD COLUMN iron_x10 INTEGER;
ALTER TABLE custom_food ADD COLUMN vita_ug INTEGER;
ALTER TABLE custom_food ADD COLUMN vitc_x10 INTEGER;
ALTER TABLE custom_food ADD COLUMN vitd_x10 INTEGER;
-- custom food diet flags (foods.db bits: diet 1 vegan, 2 vegetarian, 4 gluten-free label; allergens 1 gluten … 256 sesame)
ALTER TABLE custom_food ADD COLUMN category_id INTEGER;
ALTER TABLE custom_food ADD COLUMN diet INTEGER NOT NULL DEFAULT 0;
ALTER TABLE custom_food ADD COLUMN diet_known INTEGER NOT NULL DEFAULT 0;
ALTER TABLE custom_food ADD COLUMN allergens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE custom_food ADD COLUMN allergens_full INTEGER NOT NULL DEFAULT 0;

ALTER TABLE diary_entry ADD COLUMN transfat_x10 INTEGER;
ALTER TABLE diary_entry ADD COLUMN monofat_x10 INTEGER;
ALTER TABLE diary_entry ADD COLUMN polyfat_x10 INTEGER;
ALTER TABLE diary_entry ADD COLUMN addsugar_x10 INTEGER;
ALTER TABLE diary_entry ADD COLUMN potassium_mg INTEGER;
ALTER TABLE diary_entry ADD COLUMN calcium_mg INTEGER;
ALTER TABLE diary_entry ADD COLUMN iron_x10 INTEGER;
ALTER TABLE diary_entry ADD COLUMN vita_ug INTEGER;
ALTER TABLE diary_entry ADD COLUMN vitc_x10 INTEGER;
ALTER TABLE diary_entry ADD COLUMN vitd_x10 INTEGER;

ALTER TABLE recipe ADD COLUMN transfat_x10 INTEGER;
ALTER TABLE recipe ADD COLUMN monofat_x10 INTEGER;
ALTER TABLE recipe ADD COLUMN polyfat_x10 INTEGER;
ALTER TABLE recipe ADD COLUMN addsugar_x10 INTEGER;
ALTER TABLE recipe ADD COLUMN potassium_mg INTEGER;
ALTER TABLE recipe ADD COLUMN calcium_mg INTEGER;
ALTER TABLE recipe ADD COLUMN iron_x10 INTEGER;
ALTER TABLE recipe ADD COLUMN vita_ug INTEGER;
ALTER TABLE recipe ADD COLUMN vitc_x10 INTEGER;
ALTER TABLE recipe ADD COLUMN vitd_x10 INTEGER;

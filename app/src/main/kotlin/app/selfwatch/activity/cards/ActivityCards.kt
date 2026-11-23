/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import app.selfwatch.cards.Card

/** Every card the Activity tab can show. A new Activity card is one more entry here (and in `CardPresets`). */
object ActivityCards {
    /** All Activity cards; hidden ones are listed in this order in Edit cards. */
    val ALL: List<Card<*>> = listOf(
        TodayRingsCard,
        TrendsCard,
        WorkoutsCard,
        IntensityMinutesCard,
        StepsTodayCard,
        ActiveKcalCard,
        StrengthSessionsCard,
        ActiveDaysCard,
        TrainingLoadCard,
        WeeklyVolumeCard,
        EasyHardCard,
    )
}

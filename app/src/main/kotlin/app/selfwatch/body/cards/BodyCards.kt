/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import app.selfwatch.cards.Card

/** Every card the Body tab can show. A new Body card is one more entry here (and in `CardPresets`). */
object BodyCards {
    /** All Body cards; hidden ones are listed in this order in Edit cards. */
    val ALL: List<Card<*>> = listOf(
        EnergyCard,
        MacrosCard,
        StepsCard,
        IntensityCard,
        WeightCard,
        MeasurementsCard,
        RecoveryCard,
        Spo2Card,
        SleepCard,
        WaistHeightCard,
        BodyFatCard,
        HrvBaselineCard,
        RestingHrCard,
        BodyStressCard,
        BodySignalsCard,
    )
}

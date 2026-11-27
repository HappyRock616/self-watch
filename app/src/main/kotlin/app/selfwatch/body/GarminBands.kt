/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.cards.CardStatus
import app.selfwatch.model.food.Sex

/** A named band with the card status color it gets. */
data class Band(@param:StringRes @get:StringRes val name: Int, val status: CardStatus)

/**
 * The bands Garmin uses, so Self Watch names a value the way the watch and Garmin Connect do
 * (field review, 2026-11-26).
 *
 * Sources: vívoactive 5 Owner's Manual (sleep score, Body Battery levels, VO2 max standard ratings,
 * HRV status), Garmin training readiness page, Index S2 manual (BMI after WHO, body fat after ACE), Index
 * BPM manual (blood pressure after the American Heart Association).
 */
object GarminBands {
    private val GOOD = CardStatus.GOOD
    private val CLOSE = CardStatus.CLOSE
    private val OFF = CardStatus.OFF

    /** Sleep score 0–100: Excellent 90+, Good 80–89, Fair 60–79, Poor under 60. */
    fun sleepScore(score: Int): Band = when {
        score >= 90 -> Band(R.string.band_excellent, GOOD)
        score >= 80 -> Band(R.string.band_good, GOOD)
        score >= 60 -> Band(R.string.band_fair, CLOSE)
        else -> Band(R.string.band_poor, OFF)
    }

    /** Energy level 0–100 (Garmin's reserve levels): high 76+, medium 51–75, low 26–50, very low 25 and under. */
    fun energy(level: Int): Band = when {
        level >= 76 -> Band(R.string.band_high, GOOD)
        level >= 51 -> Band(R.string.band_medium, GOOD)
        level >= 26 -> Band(R.string.band_low, CLOSE)
        else -> Band(R.string.band_very_low, OFF)
    }

    /** Readiness 0–100 with training readiness names: Prime 95+, High 75–94, Moderate 50–74, Low 25–49, Poor under 25. */
    fun readiness(score: Int): Band = when {
        score >= 95 -> Band(R.string.band_prime, GOOD)
        score >= 75 -> Band(R.string.band_high, GOOD)
        score >= 50 -> Band(R.string.band_moderate, CLOSE)
        score >= 25 -> Band(R.string.band_low, OFF)
        else -> Band(R.string.band_poor, OFF)
    }

    /** The watch's HRV status (FIT: 1 poor, 2 low, 3 unbalanced, 4 balanced), or null. */
    fun hrvStatus(code: Int?): Band? = when (code) {
        4 -> Band(R.string.band_balanced, GOOD)
        3 -> Band(R.string.band_unbalanced, CLOSE)
        2 -> Band(R.string.band_low, OFF)
        1 -> Band(R.string.band_poor, OFF)
        else -> null
    }

    /** BMI (WHO): under 18.5 underweight, 18.5–24.9 normal, 25–29.9 overweight, 30–34.9 / 35–39.9 / 40+ obesity class I / II / III. */
    fun bmi(bmi: Double): Band = when {
        bmi < 18.5 -> Band(R.string.band_underweight, CLOSE)
        bmi < 25.0 -> Band(R.string.band_normal, GOOD)
        bmi < 30.0 -> Band(R.string.band_overweight, CLOSE)
        bmi < 35.0 -> Band(R.string.band_obesity_1, OFF)
        bmi < 40.0 -> Band(R.string.band_obesity_2, OFF)
        else -> Band(R.string.band_obesity_3, OFF)
    }

    /**
     * Body fat % (ACE, as on Garmin Index scales). Men: under 6 essential, 6–13 athletes, 14–17 fitness,
     * 18–25 acceptable, 26+ obese. Women: under 14 essential, 14–20 athletes, 21–24 fitness, 25–31
     * acceptable, 32+ obese. Without a sex the average of the two edges is used.
     */
    fun bodyFat(pct: Double, sex: Sex): Band {
        val edges = when (sex) {
            Sex.MALE -> doubleArrayOf(6.0, 14.0, 18.0, 26.0)
            Sex.FEMALE -> doubleArrayOf(14.0, 21.0, 25.0, 32.0)
            else -> doubleArrayOf(10.0, 17.5, 21.5, 29.0)
        }
        return when {
            pct < edges[0] -> Band(R.string.band_essential, CLOSE)
            pct < edges[1] -> Band(R.string.band_athletes, GOOD)
            pct < edges[2] -> Band(R.string.band_fitness, GOOD)
            pct < edges[3] -> Band(R.string.band_acceptable, CLOSE)
            else -> Band(R.string.band_obese, OFF)
        }
    }

    /**
     * Blood pressure (American Heart Association): normal under 120 and under 80; elevated 120–129 and
     * under 80; stage 1 130–139 or 80–89; stage 2 140+ or 90+; crisis over 180 and/or over 120.
     */
    fun bloodPressure(sys: Int, dia: Int): Band = when {
        sys > 180 || dia > 120 -> Band(R.string.band_bp_crisis, OFF)
        sys >= 140 || dia >= 90 -> Band(R.string.band_bp_stage_2, OFF)
        sys >= 130 || dia >= 80 -> Band(R.string.band_bp_stage_1, OFF)
        sys >= 120 -> Band(R.string.band_bp_elevated, CLOSE)
        else -> Band(R.string.band_normal, GOOD)
    }

    // VO2 max standard ratings (Cooper Institute, in Garmin manuals): lower edges of Fair, Good, Excellent
    // and Superior, ml/kg/min, for the age groups 20–29 … 70–79.
    private val VO2_MALE = arrayOf(
        doubleArrayOf(41.7, 45.4, 51.1, 55.5), doubleArrayOf(40.5, 44.0, 48.3, 54.1), doubleArrayOf(38.5, 42.4, 46.4, 52.6),
        doubleArrayOf(35.6, 39.2, 43.4, 49.0), doubleArrayOf(32.3, 35.5, 39.5, 45.8), doubleArrayOf(29.4, 32.3, 36.7, 42.2),
    )
    private val VO2_FEMALE = arrayOf(
        doubleArrayOf(36.1, 39.5, 43.9, 49.7), doubleArrayOf(34.4, 37.8, 42.4, 47.5), doubleArrayOf(33.0, 36.3, 39.7, 45.4),
        doubleArrayOf(30.1, 33.0, 36.7, 41.2), doubleArrayOf(27.5, 30.0, 33.0, 37.9), doubleArrayOf(25.9, 28.1, 30.9, 36.8),
    )

    private fun vo2Table(sex: Sex): Array<DoubleArray> = when (sex) {
        Sex.FEMALE -> VO2_FEMALE
        Sex.MALE -> VO2_MALE
        else -> Array(VO2_MALE.size) { i -> DoubleArray(4) { j -> (VO2_MALE[i][j] + VO2_FEMALE[i][j]) / 2 } }
    }

    private fun ageGroup(age: Int): Int = ((age.coerceIn(20, 79) - 20) / 10)

    /** VO2 max rating for [age] and [sex]: Superior, Excellent, Good, Fair or Poor. */
    fun vo2Max(vo2: Double, age: Int, sex: Sex): Band {
        val e = vo2Table(sex)[ageGroup(age)]
        return when {
            vo2 >= e[3] -> Band(R.string.band_superior, GOOD)
            vo2 >= e[2] -> Band(R.string.band_excellent, GOOD)
            vo2 >= e[1] -> Band(R.string.band_good, GOOD)
            vo2 >= e[0] -> Band(R.string.band_fair, CLOSE)
            else -> Band(R.string.band_poor, OFF)
        }
    }

    /**
     * Fitness age from VO2 max, as Garmin derives it: the age whose typical VO2 max (the middle of the
     * "Good" rating for [sex]) equals [vo2], between 20 and 80. Garmin also folds in BMI, resting heart rate
     * and vigorous minutes, which only matter when no VO2 max is known; Self Watch shows fitness age only
     * once the watch has a VO2 max.
     */
    fun fitnessAge(vo2: Double, sex: Sex): Int {
        val t = vo2Table(sex)
        val typical = DoubleArray(t.size) { (t[it][1] + t[it][2]) / 2 } // falls with age
        val mids = IntArray(t.size) { 25 + 10 * it }
        if (vo2 >= typical[0]) {
            // Fitter than a typical 25-year-old: extend the first slope down to 20.
            val slope = (typical[0] - typical[1]) / 10
            return (25 - (vo2 - typical[0]) / slope).toInt().coerceAtLeast(MIN_FITNESS_AGE)
        }
        for (i in 1 until t.size) {
            if (vo2 >= typical[i]) {
                val f = (typical[i - 1] - vo2) / (typical[i - 1] - typical[i])
                return Math.round(mids[i - 1] + f * 10).toInt()
            }
        }
        val slope = (typical[t.size - 2] - typical[t.size - 1]) / 10
        return (mids.last() + (typical.last() - vo2) / slope).toInt().coerceAtMost(MAX_FITNESS_AGE)
    }

    private const val MIN_FITNESS_AGE = 20
    private const val MAX_FITNESS_AGE = 80
}

/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.device

import android.database.sqlite.SQLiteDatabase
import app.selfwatch.interop.TestSupport
import java.io.File

/**
 * Made-up export files for the device importers: a small Gadgetbridge database built here with the
 * table and column names Gadgetbridge uses (facts only, invented rows), and a Garmin "Export Your
 * Data" layout around the synthetic FIT samples.
 */
object DeviceFixtures {
    /** Start of the made-up data (2026-04-12 13:20 UTC, a whole minute). */
    const val T0: Long = 1_776_000_000L

    /** Path of the run FIT file on the "old phone", as Gadgetbridge records it. */
    const val GB_FIT_PATH: String = "/storage/emulated/0/Android/data/nodomain.freeyourgadget.gadgetbridge/files/AA_BB_CC_DD_EE_01/ACTIVITY/2026/run.fit"

    /** The Gadgetbridge database file. Expected: 10 minutes, 20 metrics, 4 stages, 1 night, 2 weights, 2 workouts. */
    fun gadgetbridgeDb(dir: File): File {
        val f = File(dir, "gb-fixture-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { d ->
            // Ref: Gadgetbridge 160beab41 GBDaoGenerator/src/nodomain/freeyourgadget/gadgetbridge/daogen/GBDaoGenerator.java (table and column names)
            d.execSQL("CREATE TABLE DEVICE (_id INTEGER PRIMARY KEY, NAME TEXT NOT NULL, MANUFACTURER TEXT NOT NULL, IDENTIFIER TEXT NOT NULL, TYPE INTEGER, TYPE_NAME TEXT, MODEL TEXT, ALIAS TEXT, PARENT_FOLDER TEXT)")
            d.execSQL("CREATE TABLE GARMIN_ACTIVITY_SAMPLE (TIMESTAMP INTEGER NOT NULL, DEVICE_ID INTEGER NOT NULL, USER_ID INTEGER NOT NULL, RAW_INTENSITY INTEGER NOT NULL, STEPS INTEGER NOT NULL, RAW_KIND INTEGER NOT NULL, HEART_RATE INTEGER NOT NULL, DISTANCE_CM INTEGER NOT NULL, ACTIVE_CALORIES INTEGER NOT NULL, PRIMARY KEY (TIMESTAMP, DEVICE_ID))")
            for (t in listOf("GARMIN_STRESS_SAMPLE" to "STRESS INTEGER", "GARMIN_SPO2_SAMPLE" to "SPO2 INTEGER, TYPE_NUM INTEGER",
                "GARMIN_RESPIRATORY_RATE_SAMPLE" to "RESPIRATORY_RATE REAL", "GARMIN_HEART_RATE_RESTING_SAMPLE" to "HEART_RATE INTEGER",
                "GARMIN_HRV_VALUE_SAMPLE" to "VALUE INTEGER", "GARMIN_SLEEP_STAGE_SAMPLE" to "STAGE INTEGER",
                "GARMIN_SLEEP_STATS_SAMPLE" to "SLEEP_SCORE INTEGER, AWAKENINGS_COUNT INTEGER", "GENERIC_WEIGHT_SAMPLE" to "WEIGHT_KG REAL, IMPEDANCE_OHM INTEGER")) {
                d.execSQL("CREATE TABLE ${t.first} (TIMESTAMP INTEGER NOT NULL, DEVICE_ID INTEGER NOT NULL, USER_ID INTEGER NOT NULL, ${t.second}, PRIMARY KEY (TIMESTAMP, DEVICE_ID))")
            }
            d.execSQL("CREATE TABLE BASE_ACTIVITY_SUMMARY (_id INTEGER PRIMARY KEY, NAME TEXT, START_TIME INTEGER NOT NULL, END_TIME INTEGER NOT NULL, OVERRIDE_ACTIVITY_KIND INTEGER, ACTIVITY_KIND INTEGER NOT NULL, GPX_TRACK TEXT, RAW_DETAILS_PATH TEXT, DEVICE_ID INTEGER NOT NULL, USER_ID INTEGER NOT NULL, SUMMARY_DATA TEXT, RAW_SUMMARY_DATA BLOB)")
            d.execSQL("CREATE TABLE MI_BAND_ACTIVITY_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, STEPS INTEGER)")

            d.execSQL("INSERT INTO DEVICE (_id, NAME, MANUFACTURER, IDENTIFIER, TYPE, TYPE_NAME, MODEL) VALUES (1, 'Forerunner 255', 'Garmin', 'AA:BB:CC:DD:EE:01', 0, 'GARMIN_FORERUNNER_255', '255')")
            for (i in 0 until 10) {
                d.execSQL("INSERT INTO GARMIN_ACTIVITY_SAMPLE VALUES (${T0 + i * 60}, 1, 1, 2, ${50 + i}, 1, ${70 + i}, ${4000 + i}, 3)")
            }
            // A gap filler: nothing measured.
            d.execSQL("INSERT INTO GARMIN_ACTIVITY_SAMPLE VALUES (${T0 + 600}, 1, 1, -1, -1, 2, -1, -1, -1)")
            d.execSQL("INSERT INTO MI_BAND_ACTIVITY_SAMPLE VALUES ($T0, 9, 1000)")
            val ms = T0 * 1000
            d.execSQL("INSERT INTO GARMIN_STRESS_SAMPLE VALUES ($ms, 1, 1, 25), (${ms + 180_000}, 1, 1, 40), (${ms + 360_000}, 1, 1, -1)")
            d.execSQL("INSERT INTO GARMIN_SPO2_SAMPLE VALUES ($ms, 1, 1, 96, 1), (${ms + 3_600_000}, 1, 1, 94, 1)")
            d.execSQL("INSERT INTO GARMIN_RESPIRATORY_RATE_SAMPLE VALUES ($ms, 1, 1, 14.5), (${ms + 120_000}, 1, 1, 15.0)")
            d.execSQL("INSERT INTO GARMIN_HEART_RATE_RESTING_SAMPLE VALUES ($ms, 1, 1, 52)")
            d.execSQL("INSERT INTO GARMIN_HRV_VALUE_SAMPLE VALUES ($ms, 1, 1, 41), (${ms + 300_000}, 1, 1, 44), (${ms + 600_000}, 1, 1, 39)")
            val night = (T0 + 12 * 3600) * 1000
            d.execSQL("INSERT INTO GARMIN_SLEEP_STAGE_SAMPLE VALUES ($night, 1, 1, 2), (${night + 1_800_000}, 1, 1, 3), (${night + 3_600_000}, 1, 1, 4), (${night + 5_400_000}, 1, 1, 1)")
            d.execSQL("INSERT INTO GARMIN_SLEEP_STATS_SAMPLE VALUES (${night + 5_460_000}, 1, 1, 80, 2)")
            d.execSQL("INSERT INTO GENERIC_WEIGHT_SAMPLE VALUES (${ms - 86_400_000}, 1, 1, 75.4, NULL), (${ms + 86_400_000}, 1, 1, 75.1, 500)")
            d.execSQL("INSERT INTO BASE_ACTIVITY_SUMMARY (_id, NAME, START_TIME, END_TIME, OVERRIDE_ACTIVITY_KIND, ACTIVITY_KIND, RAW_DETAILS_PATH, DEVICE_ID, USER_ID) VALUES (1, 'Morning run', ${ms - 7_200_000}, ${ms - 5_400_000}, 0, 16, '$GB_FIT_PATH', 1, 1)")
            d.execSQL("INSERT INTO BASE_ACTIVITY_SUMMARY (_id, NAME, START_TIME, END_TIME, OVERRIDE_ACTIVITY_KIND, ACTIVITY_KIND, RAW_DETAILS_PATH, DEVICE_ID, USER_ID) VALUES (2, 'Walk', ${ms + 7_200_000}, ${ms + 9_000_000}, 0, 1, NULL, 1, 1)")
        }
        return f
    }

    /** A Gadgetbridge backup zip around [gadgetbridgeDb]. */
    fun gadgetbridgeZip(dir: File): ByteArray {
        val db = gadgetbridgeDb(dir)
        val bytes = db.readBytes()
        db.delete()
        return TestSupport.zipOf(
            "preferences/global.json" to "{\"theme\":\"dark\"}".toByteArray(),
            "preferences/device_AA:BB:CC:DD:EE:01.json" to "{}".toByteArray(),
            "database/Gadgetbridge" to bytes,
            "files/AA_BB_CC_DD_EE_01/ACTIVITY/2026/run.fit" to TestSupport.bytes("fit/synthetic/activity_run_gps.fit"),
            "gadgetbridge.json" to "{\"version\":1}".toByteArray(),
        )
    }

    /** Hydration JSON in the shape Garmin's aggregator files use (two usable entries, one removal). */
    val HYDRATION: String = """
        [{"userProfilePK":1,"calendarDate":"2026-03-01","valueInML":500.0,"timestampLocal":"2026-03-01T08:30:00.0","activityId":null},
         {"userProfilePK":1,"calendarDate":"2026-03-01","valueInML":250.0},
         {"userProfilePK":1,"calendarDate":"2026-03-02","valueInML":-250.0}]
    """.trimIndent()

    /** A Garmin "Export Your Data" zip: uploaded FIT files in a nested zip, hydration JSON, other files. */
    fun garminExportZip(): ByteArray {
        val uploaded = TestSupport.zipOf(
            "activity_run_gps.fit" to TestSupport.bytes("fit/synthetic/activity_run_gps.fit"),
            "monitor_day.fit" to TestSupport.bytes("fit/synthetic/monitor_day.fit"),
            "sleep_night.fit" to TestSupport.bytes("fit/synthetic/sleep_night.fit"),
            "hrv_status.fit" to TestSupport.bytes("fit/synthetic/hrv_status.fit"),
            "weight.fit" to TestSupport.bytes("fit/synthetic/weight.fit"),
            "broken.fit" to byteArrayOf(14, 16, 1, 2, 3),
        )
        return TestSupport.zipOf(
            "DI_CONNECT/DI-Connect-User/user_profile.json" to "{\"userName\":\"x\"}".toByteArray(),
            "DI_CONNECT/DI-Connect-Uploaded-Files/UploadedFiles_0-_Part1.zip" to uploaded,
            "DI_CONNECT/DI-Connect-Aggregator/HydrationLogFile_2026-01-01_2026-04-01.json" to HYDRATION.toByteArray(),
            "DI_CONNECT/DI-Connect-Wellness/2026-03-01_sleepData.json" to "[]".toByteArray(),
        )
    }
}

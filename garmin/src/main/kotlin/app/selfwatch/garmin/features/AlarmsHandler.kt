/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.fit.io.FieldDef
import app.selfwatch.fit.io.FitBaseType
import app.selfwatch.fit.io.FitTime
import app.selfwatch.fit.io.FitWriter
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.model.device.Feature
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import app.selfwatch.model.watch.Alarm
import app.selfwatch.model.watch.WatchCommand
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek

/**
 * Alarms (A20). The reference sets alarms by uploading a FIT **settings** file (type 128/2) built by
 * [AlarmsFit]; the whole list replaces the watch's alarms. Watches with the `REALTIME_SETTINGS` flag have no
 * alarm slots for this method in the reference (their alarms live in the realtime settings service, which it
 * does not implement), so there [WatchCommand.SetAlarms] fails with Unsupported, as does a list longer than
 * [AlarmsFit.MAX_ALARMS].
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/GarminSupport.java (onSetAlarms: FitFileId SETTINGS +
 * FitAlarmSettings per alarm + FitDeviceSettings alarm arrays, uploaded with fileTransferHandler.initiateUpload),
 * devices/garmin/watches/GarminWatchCoordinator.java (getAlarmSlotCount: 0 with REALTIME_SETTINGS, else 10)
 */
public class AlarmsHandler(private val clock: Clock) : GfdiHandler {
    override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean = false

    override suspend fun command(cmd: WatchCommand, session: GarminSessionContext): Outcome<Unit>? {
        if (cmd !is WatchCommand.SetAlarms) return null
        if (!supported(session.capabilities)) return Outcome.Fail(SwError.Unsupported(Feature.ALARMS))
        if (cmd.alarms.size > AlarmsFit.MAX_ALARMS) return Outcome.Fail(SwError.BadInput("at most ${AlarmsFit.MAX_ALARMS} alarms"))
        val file = AlarmsFit.encode(cmd.alarms, clock.nowSec())
        SwLog.i(TAG, "setting ${cmd.alarms.size} alarms (${file.size} bytes)")
        return when (val r = session.transfers.upload(file, AlarmsFit.FIT_DATA_TYPE, AlarmsFit.SETTINGS_SUB_TYPE)) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Fail -> r
        }
    }

    /** Helpers. */
    public companion object {
        private const val TAG = "garmin.alarms"

        /** True when alarms can be set through the settings file on a watch with [caps]. */
        public fun supported(caps: Set<GarminCapability>): Boolean = GarminCapability.REALTIME_SETTINGS !in caps
    }
}

/**
 * Builds the FIT settings file that carries alarms.
 *
 * - `file_id` (0): type 2 (settings), manufacturer 1 (Garmin), product 65534 (Connect), serial 1, number 1,
 *   time created.
 * - one `alarm_settings` (222) per alarm: time 0 (u16 minutes after midnight), repeat 1 (u32z day bits: Monday
 *   1 … Sunday 64; 128 = once), enabled 2, sound 3 (3 = sound and vibration, the reference's default),
 *   backlight 4 (on), time created 5, snooze 7 (0), label 8 (FIT AlarmLabel: NONE 0, WAKE_UP 1, WORKOUT 2,
 *   REMINDER 3, APPOINTMENT 4, TRAINING 5, CLASS 6, MEDITATE 7, BEDTIME 8; matched by name, else NONE), message
 *   index 254.
 * - if there is at least one alarm, a `device_settings` (2) record with the arrays alarms_time 8 (u16),
 *   alarms_mode 9 (enum, 5 = custom), alarms_enabled 28 (enum) and alarms_repeat 92 (u32z).
 *
 * Records are big-endian, as the reference's record builder writes them; the header and CRC are standard.
 *
 * Ref: Gadgetbridge 160beab41 FitCodeGenerator/src/main/resources/fit_profile.json (FILE_ID, ALARM_SETTINGS 222,
 * DEVICE_SETTINGS 2 fields 8, 9, 28, 92; AlarmLabel, AlarmMode, Tone), service/devices/garmin/GarminSupport.java
 * (onSetAlarms values), fit/FitRecordDataBuilder.java (big-endian records), model/Alarm.java (day bits)
 */
public object AlarmsFit {
    /** Most alarms the watch has slots for (the reference's slot count). */
    public const val MAX_ALARMS: Int = 10

    /** File data type of FIT files. */
    public const val FIT_DATA_TYPE: Int = 128

    /** FIT file subtype of settings files. */
    public const val SETTINGS_SUB_TYPE: Int = 2

    /** FIT global message alarm_settings. */
    public const val GLOBAL_ALARM_SETTINGS: Int = 222

    /** FIT global message device_settings. */
    public const val GLOBAL_DEVICE_SETTINGS: Int = 2

    /** `repeat` value of a one-off alarm. */
    public const val REPEAT_ONCE: Long = 128

    private const val MANUFACTURER_GARMIN = 1
    private const val PRODUCT_CONNECT = 65534
    private const val SOUND_AND_VIBRATION = 3
    private const val MODE_CUSTOM = 5

    /** FIT AlarmLabel names, by value. */
    public val LABELS: List<String> = listOf("NONE", "WAKE_UP", "WORKOUT", "REMINDER", "APPOINTMENT", "TRAINING", "CLASS", "MEDITATE", "BEDTIME")

    /** The `repeat` bits for [days] (Monday 1 … Sunday 64), or [REPEAT_ONCE] when empty. */
    public fun repeatBits(days: Set<DayOfWeek>): Long =
        if (days.isEmpty()) REPEAT_ONCE else days.fold(0L) { acc, d -> acc or (1L shl (d.value - 1)) }

    /** The AlarmLabel value for [label] (by name, case and spaces ignored), or 0. */
    public fun labelOf(label: String?): Int {
        val n = label?.trim()?.uppercase()?.replace(' ', '_') ?: return 0
        return LABELS.indexOf(n).coerceAtLeast(0)
    }

    /** Encodes [alarms] (created at [nowSec], Unix seconds) as a FIT settings file. */
    public fun encode(alarms: List<Alarm>, nowSec: Long): ByteArray {
        val created = FitTime.fromEpoch(nowSec)
        val out = ByteArrayOutputStream()
        val w = FitWriter(out)
        w.header()
        w.define(
            0, 0,
            listOf(
                FieldDef(0, 1, FitBaseType.ENUM),
                FieldDef(1, 2, FitBaseType.UINT16),
                FieldDef(2, 2, FitBaseType.UINT16),
                FieldDef(3, 4, FitBaseType.UINT32Z),
                FieldDef(4, 4, FitBaseType.UINT32),
                FieldDef(5, 2, FitBaseType.UINT16),
            ),
            bigEndian = true,
        )
        w.data(0, listOf(SETTINGS_SUB_TYPE, MANUFACTURER_GARMIN, PRODUCT_CONNECT, 1L, created, 1))
        if (alarms.isNotEmpty()) {
            w.define(
                1, GLOBAL_ALARM_SETTINGS,
                listOf(
                    FieldDef(0, 2, FitBaseType.UINT16),
                    FieldDef(1, 4, FitBaseType.UINT32Z),
                    FieldDef(2, 1, FitBaseType.ENUM),
                    FieldDef(3, 1, FitBaseType.ENUM),
                    FieldDef(4, 1, FitBaseType.ENUM),
                    FieldDef(5, 4, FitBaseType.UINT32),
                    FieldDef(7, 1, FitBaseType.UINT8),
                    FieldDef(8, 1, FitBaseType.ENUM),
                    FieldDef(254, 2, FitBaseType.UINT16),
                ),
                bigEndian = true,
            )
            alarms.forEachIndexed { i, a ->
                w.data(
                    1,
                    listOf(minutes(a), repeatBits(a.days), if (a.enabled) 1 else 0, SOUND_AND_VIBRATION, 1, created, 0, labelOf(a.label), i),
                )
            }
            val n = alarms.size
            w.define(
                2, GLOBAL_DEVICE_SETTINGS,
                listOf(
                    FieldDef(8, 2 * n, FitBaseType.UINT16),
                    FieldDef(9, n, FitBaseType.ENUM),
                    FieldDef(28, n, FitBaseType.ENUM),
                    FieldDef(92, 4 * n, FitBaseType.UINT32Z),
                ),
                bigEndian = true,
            )
            w.data(
                2,
                listOf(
                    alarms.map { minutes(it) },
                    alarms.map { MODE_CUSTOM },
                    alarms.map { if (it.enabled) 1 else 0 },
                    alarms.map { repeatBits(it.days) },
                ),
            )
        }
        w.finish()
        return out.toByteArray()
    }

    private fun minutes(a: Alarm): Int = a.hour.coerceIn(0, 23) * 60 + a.minute.coerceIn(0, 59)
}

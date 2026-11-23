/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.api

import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads one kind of file from another app. Detection ([sniff]) is cheap; [plan] parses everything
 * into memory and describes it for the preview screen; [commit] writes the plan through an
 * [ImportSink] with the user's [ImportChoices].
 */
interface Importer {
    /** Stable id, for example "mfp", "gpx" or "recipe-jsonld". */
    val id: String

    /** Confidence 0..100 that this importer reads the file named [name] whose first bytes are [head] (up to 64 KB). */
    fun sniff(name: String, head: ByteArray): Int

    /** Parses [src] fully. Never throws on bad input: returns [Outcome.Fail] with [SwError.BadInput]. */
    suspend fun plan(src: InputSource): Outcome<ImportPlan>

    /** Writes [plan] with [choices]. */
    suspend fun commit(plan: ImportPlan, choices: ImportChoices): Outcome<ImportResult>
}

/** How one target field was matched to a file column; [header] and [index] are null/-1 when not found. */
data class ColumnMapping(val field: String, val header: String?, val index: Int)

/**
 * Result of date-format detection.
 *
 * @property best the format to preselect (a [app.selfwatch.interop.io.DateFormat] id), or null if none fits.
 * @property candidates every format that parses all samples; more than one means the user must choose.
 * @property ambiguousSamples a few sample values that read differently under the candidates.
 */
data class DateFormatGuess(val best: String?, val candidates: List<String>, val ambiguousSamples: List<String>) {
    /** True when the user has to pick the format. */
    val ambiguous: Boolean get() = candidates.size > 1

    /** Constants. */
    companion object {
        /** For files without dates (recipes, workouts with ISO times). */
        val NONE: DateFormatGuess = DateFormatGuess(null, emptyList(), emptyList())
    }
}

/**
 * Result of weight-unit detection.
 *
 * @property unit "kg" or "lb" (empty when the file has no weights).
 * @property confident false when the user should confirm.
 * @property reason short machine-readable reason: "header", "range" or "default".
 */
data class UnitGuess(val unit: String, val confident: Boolean, val reason: String) {
    /** Constants. */
    companion object {
        /** For files without weights. */
        val NONE: UnitGuess = UnitGuess("", true, "none")
    }
}

/**
 * Turns a plan's parsed form into final data for the user's choices. Importers whose parsing does not
 * depend on the choices return the same data every time.
 */
fun interface ImportHandle {
    /** The data to write when the user confirms with [choices]. */
    fun data(choices: ImportChoices): Outcome<ImportData>
}

/**
 * What the preview screen shows, plus the parsed data behind it.
 *
 * @property counts rows per area, for example "diary" to 1234.
 * @property preview the first rows of the main file (header first), for display.
 * @property duplicates rows already in Self Watch (0 until the database sink is connected).
 * @property warnings messages for the user (unknown columns, skipped rows).
 */
data class ImportPlan(
    val importerId: String,
    val fileName: String,
    val counts: Map<String, Int>,
    val preview: List<List<String>>,
    val columns: List<ColumnMapping>,
    val dateFormat: DateFormatGuess,
    val units: UnitGuess,
    val duplicates: Int,
    val warnings: List<String>,
    val handle: ImportHandle,
) {
    /** Choices that accept every guess. */
    fun defaultChoices(): ImportChoices = ImportChoices(dateFormat.best ?: "", units.unit.ifEmpty { WeightUnit.KG })
}

/** The user's confirmed choices on the preview screen. */
data class ImportChoices(val dateFormat: String, val weightUnit: String, val skipDuplicates: Boolean = true)

/** What a commit wrote: the `import_batch` id (for undo) and rows per area. */
data class ImportResult(val importId: Long, val counts: Map<String, Int>)

/** Weight unit ids used in choices and requests. */
object WeightUnit {
    /** Kilograms. */
    const val KG: String = "kg"

    /** Pounds. */
    const val LB: String = "lb"

    /** Pounds per kilogram. */
    const val LB_PER_KG: Double = 2.20462262185
}

/**
 * Where committed imports go. The database module implements this (one `import_batch` row, one
 * transaction tagged with its id, then `DbChanges` and summary refresh); until then [NONE] is used.
 */
interface ImportSink {
    /** How many items of [data] already exist (same day, name and amounts; same workout start). */
    suspend fun countDuplicates(importerId: String, data: ImportData): Int

    /** Writes [data] as one import batch. */
    suspend fun write(importerId: String, fileName: String, data: ImportData, skipDuplicates: Boolean): Outcome<ImportResult>

    /** Constants. */
    companion object {
        /** No database connected: no duplicates are known and writing fails. */
        val NONE: ImportSink = object : ImportSink {
            // TODO(core-db): replace with the ImportDao-backed sink; duplicates are 0 until then.
            override suspend fun countDuplicates(importerId: String, data: ImportData): Int = 0

            override suspend fun write(
                importerId: String,
                fileName: String,
                data: ImportData,
                skipDuplicates: Boolean,
            ): Outcome<ImportResult> = Outcome.Fail(SwError.Io("no database connected"))
        }
    }
}

/**
 * Shared importer plumbing: parsing runs on [Dispatchers.Default], every unexpected exception from
 * bad input becomes [SwError.BadInput], duplicates are counted through the [sink], and [commit]
 * hands the data to the sink.
 */
abstract class BaseImporter(protected val sink: ImportSink) : Importer {
    /** Parses [src]; may throw on malformed input (the caller converts it). Runs on a background thread. */
    protected abstract fun parse(src: InputSource): Outcome<ImportPlan>

    override suspend fun plan(src: InputSource): Outcome<ImportPlan> {
        val parsed = withContext(Dispatchers.Default) { guard(id) { parse(src) } }
        if (parsed !is Outcome.Ok) return parsed
        val plan = parsed.value
        val data = plan.handle.data(plan.defaultChoices())
        val dups = if (data is Outcome.Ok) sink.countDuplicates(id, data.value) else 0
        return Outcome.Ok(plan.copy(duplicates = dups))
    }

    override suspend fun commit(plan: ImportPlan, choices: ImportChoices): Outcome<ImportResult> {
        val data = withContext(Dispatchers.Default) { guard(id) { plan.handle.data(choices) } }
        if (data !is Outcome.Ok) return data as Outcome.Fail
        return sink.write(id, plan.fileName, data.value, choices.skipDuplicates)
    }

    override fun sniff(name: String, head: ByteArray): Int = try {
        sniffSafe(name, head).coerceIn(0, 100)
    } catch (e: RuntimeException) {
        SwLog.w(id, "sniff failed", e)
        0
    }

    /** [sniff] without the exception guard. */
    protected abstract fun sniffSafe(name: String, head: ByteArray): Int
}

/**
 * Runs [block], turning I/O and parsing exceptions into [Outcome.Fail]. Corrupt input must never
 * crash the app, so any [Exception] (but not cancellation or an [Error]) counts as bad input.
 */
inline fun <T> guard(tag: String, block: () -> Outcome<T>): Outcome<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    SwLog.w(tag, "read failed", e)
    Outcome.Fail(SwError.BadInput("cannot read file: ${e.javaClass.simpleName}"))
} catch (e: Exception) {
    SwLog.w(tag, "parse failed", e)
    Outcome.Fail(SwError.BadInput("unreadable file: ${e.javaClass.simpleName}"))
} catch (e: StackOverflowError) {
    SwLog.w(tag, "input nested too deeply", e)
    Outcome.Fail(SwError.BadInput("input nested too deeply"))
}

/** Picks importers for a file by asking each one to [Importer.sniff] it. */
class ImportRegistry(private val importers: List<Importer>) {
    /** Importers with a confidence above zero, most confident first. */
    fun detect(name: String, head: ByteArray): List<Pair<Importer, Int>> =
        importers.map { it to it.sniff(name, head) }.filter { it.second > 0 }.sortedByDescending { it.second }

    /** The importer with [id], or null. */
    fun byId(id: String): Importer? = importers.firstOrNull { it.id == id }

    /** Constants. */
    companion object {
        /** How many bytes of a file [detect] should be given. */
        const val HEAD_BYTES: Int = 64 * 1024
    }
}

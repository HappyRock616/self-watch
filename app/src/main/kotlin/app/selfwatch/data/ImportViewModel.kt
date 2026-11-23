/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.db.Table
import app.selfwatch.db.dao.ImportBatch
import app.selfwatch.interop.api.ImportChoices
import app.selfwatch.interop.api.ImportPlan
import app.selfwatch.interop.api.ImportRegistry
import app.selfwatch.interop.api.ImportResult
import app.selfwatch.interop.api.Importer
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.recipe.RecipeImporters
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Where the import screen is. */
enum class ImportStage {
    /** Nothing picked: the intro and the history. */
    IDLE,

    /** Reading and parsing the file. */
    READING,

    /** The preview: counts, detected date format and units, duplicates, warnings. */
    PREVIEW,

    /** Writing. */
    SAVING,

    /** Written; [ImportUiState.result] says what. */
    DONE,

    /** The file is a Self Watch backup: restore it from the Data screen instead. */
    BACKUP_FILE,

    /** No importer reads this file, or it could not be read; see [ImportUiState.error]. */
    FAILED,
}

/** Everything the import screen shows. */
data class ImportUiState(
    val stage: ImportStage = ImportStage.IDLE,
    val fileName: String? = null,
    /** Importers that can read the file, most likely first. */
    val candidates: List<String> = emptyList(),
    val importerId: String? = null,
    val plan: ImportPlan? = null,
    val choices: ImportChoices? = null,
    val result: ImportResult? = null,
    val error: String? = null,
    val history: List<ImportBatch> = emptyList(),
    /** Set after an undo: rows removed. */
    val undoneRows: Int? = null,
)

/**
 * Import (C18): pick a file → detect who wrote it → plan → preview with the detected date format and
 * weight unit (both can be changed), counts, duplicates and warnings → confirm or cancel. The history
 * lists every import batch with a one-step undo.
 */
class ImportViewModel(
    importers: List<Importer>,
    private val undoBatch: suspend (Long) -> Int,
    private val historyFlow: Flow<List<ImportBatch>>,
) : ViewModel() {

    private val registry = ImportRegistry(importers)
    private var source: InputSource? = null
    private val mutable = MutableStateFlow(ImportUiState())

    /** Screen state. */
    val state: StateFlow<ImportUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch { historyFlow.collect { h -> mutable.update { it.copy(history = h) } } }
    }

    /** Reads [src], picks the most likely importer and builds the preview. */
    fun open(src: InputSource) {
        source = src
        mutable.update { it.copy(stage = ImportStage.READING, fileName = src.name, plan = null, result = null, error = null, undoneRows = null) }
        viewModelScope.launch {
            val head = try {
                withContext(Dispatchers.IO) { readHead(src) }
            } catch (e: IOException) {
                SwLog.w(TAG, "cannot read the picked file", e)
                mutable.update { it.copy(stage = ImportStage.FAILED, error = e.javaClass.simpleName) }
                return@launch
            }
            val found = registry.detect(src.name, head).map { it.first.id }
            when {
                found.isEmpty() -> mutable.update { it.copy(stage = ImportStage.FAILED, candidates = emptyList(), error = null) }
                found.first() == BACKUP_ID -> mutable.update { it.copy(stage = ImportStage.BACKUP_FILE, candidates = found) }
                else -> {
                    val list = found.filter { it != BACKUP_ID }
                    mutable.update { it.copy(candidates = list) }
                    plan(list.first())
                }
            }
        }
    }

    /** Reads the file with importer [id] instead (one of [ImportUiState.candidates]). */
    fun useImporter(id: String) {
        if (id !in mutable.value.candidates) return
        mutable.update { it.copy(stage = ImportStage.READING) }
        viewModelScope.launch { plan(id) }
    }

    private suspend fun plan(id: String) {
        val src = source ?: return
        val importer = registry.byId(id) ?: return
        when (val r = importer.plan(src)) {
            is Outcome.Ok -> mutable.update {
                it.copy(stage = ImportStage.PREVIEW, importerId = id, plan = r.value, choices = r.value.defaultChoices(), error = null)
            }
            is Outcome.Fail -> mutable.update { it.copy(stage = ImportStage.FAILED, importerId = id, error = r.error.shortText()) }
        }
    }

    /** Overrides the date format (a `DateFormat` id such as `dd/MM/yyyy`). */
    fun setDateFormat(id: String) = mutable.update { s -> s.copy(choices = s.choices?.copy(dateFormat = id)) }

    /** Overrides the weight unit (`kg` or `lb`). */
    fun setWeightUnit(unit: String) = mutable.update { s -> s.copy(choices = s.choices?.copy(weightUnit = unit)) }

    /** Whether rows already in Self Watch are skipped (default yes). */
    fun setSkipDuplicates(skip: Boolean) = mutable.update { s -> s.copy(choices = s.choices?.copy(skipDuplicates = skip)) }

    /** Writes the previewed file with the current choices. */
    fun confirm() {
        val s = mutable.value
        val plan = s.plan ?: return
        val choices = s.choices ?: return
        val importer = registry.byId(plan.importerId) ?: return
        if (s.stage != ImportStage.PREVIEW) return
        mutable.update { it.copy(stage = ImportStage.SAVING) }
        viewModelScope.launch {
            when (val r = importer.commit(plan, choices)) {
                is Outcome.Ok -> mutable.update { it.copy(stage = ImportStage.DONE, result = r.value) }
                is Outcome.Fail -> mutable.update { it.copy(stage = ImportStage.FAILED, error = r.error.shortText()) }
            }
        }
    }

    /** Drops the preview without saving anything. */
    fun cancel() {
        source = null
        mutable.update { it.copy(stage = ImportStage.IDLE, fileName = null, plan = null, choices = null, candidates = emptyList(), error = null) }
    }

    /** Removes everything import batch [batchId] added. */
    fun undo(batchId: Long) {
        viewModelScope.launch {
            val n = undoBatch(batchId)
            mutable.update { s ->
                val stage = if (s.stage == ImportStage.DONE && s.result?.importId == batchId) ImportStage.IDLE else s.stage
                s.copy(undoneRows = n, stage = stage)
            }
        }
    }

    /** Clears the "undone" note. */
    fun undoShown() = mutable.update { it.copy(undoneRows = null) }

    private fun readHead(src: InputSource): ByteArray = src.open().use { input ->
        val buf = ByteArray(ImportRegistry.HEAD_BYTES)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        buf.copyOf(n)
    }

    /** Ids and factory. */
    companion object {
        private const val TAG = "ImportViewModel"

        /** The Self Watch backup importer (restored from the Data screen instead). */
        const val BACKUP_ID: String = "selfwatch-backup"

        /** The recipe importers, in the order the recipe book lists them. */
        val RECIPE_IDS: List<String> = RecipeImporters.all().map { it.id }

        /** [importers] limited to recipe files when [recipesOnly]. */
        fun importersFor(importers: List<Importer>, recipesOnly: Boolean): List<Importer> =
            if (recipesOnly) importers.filter { it.id in RECIPE_IDS } else importers

        /** Builds the view model from [graph]; [recipesOnly] reads and lists recipe files only. */
        fun factory(graph: AppGraph, recipesOnly: Boolean = false): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val i = graph.interop
                val history = historyOf(graph)
                ImportViewModel(
                    importers = importersFor(i.importers, recipesOnly),
                    undoBatch = { id -> i.sink.undo(id) },
                    historyFlow = if (recipesOnly) history.map { list -> list.filter { it.importer in RECIPE_IDS } } else history,
                )
            }
        }

        /** Import batches, newest first, now and after every change. */
        fun historyOf(graph: AppGraph): Flow<List<ImportBatch>> =
            kotlinx.coroutines.flow.flow {
                graph.changes.observe(setOf(Table.IMPORT_BATCH)).collect { emit(graph.db.imports.list()) }
            }
    }
}

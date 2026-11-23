/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.food.recipes.IngredientParser
import app.selfwatch.food.recipes.RecipeNutrition
import app.selfwatch.food.recipes.RecipeSummary
import app.selfwatch.food.source.FoodHit
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.ExportSource
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.interop.recipe.NextcloudRecipeExporter
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeItem
import app.selfwatch.model.food.RecipeKind
import app.selfwatch.model.log.SwLog
import app.selfwatch.ui.format.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Recipe book state. */
data class RecipeBookState(
    val loading: Boolean = true,
    val recipes: List<RecipeSummary> = emptyList(),
    val tags: List<String> = emptyList(),
    val tag: String? = null,
    val query: String = "",
)

/** Recipe book: list with tag filter and search (debounced 150 ms). */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RecipeBookViewModel(private val backendProvider: () -> FoodBackend) : ViewModel() {
    private val backend: FoodBackend by lazy(backendProvider)
    private val tag = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")

    /** Screen state. */
    val state: StateFlow<RecipeBookState> = combine(tag, query.debounce(AddFoodViewModel.DEBOUNCE_MS).map { it.trim() }.distinctUntilChanged()) { t, q -> t to q }
        .flatMapLatest { (t, q) ->
            backend.recipeList(t, q.ifEmpty { null }).map { list ->
                RecipeBookState(loading = false, recipes = list, tags = backend.recipeTags(), tag = t, query = q)
            }.catch { e ->
                SwLog.w(TAG, "recipe list failed", e)
                emit(RecipeBookState(loading = false, tag = t, query = q))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), RecipeBookState())

    /** Filters by [t] (null: all). */
    fun selectTag(t: String?) {
        tag.value = t
    }

    /** The search box changed. */
    fun setQuery(q: String) {
        query.value = q
    }

    private val eventChannel = Channel<RecipeBookEvent>(Channel.BUFFERED)

    /** One-shot events (export result). */
    val events: Flow<RecipeBookEvent> = eventChannel.receiveAsFlow()

    /** The exporter behind "Export recipe book" (Nextcloud Cookbook zip of schema.org recipes). */
    private val exporter: NextcloudRecipeExporter by lazy {
        NextcloudRecipeExporter(
            object : ExportSource {
                override suspend fun recipes(): List<Recipe> =
                    backend.recipeList(null, null).first().mapNotNull { backend.recipe(it.id) }
            },
        )
    }

    /** File name offered for the export. */
    fun exportFileName(nowSec: Long = backend.clock.nowSec()): String = exporter.suggestedName(ExportRequest(createdSec = nowSec))

    /** MIME type of the export. */
    val exportMimeType: String get() = exporter.mimeType(ExportRequest())

    /** Writes the whole recipe book to [out]. */
    fun export(out: OutputSink) {
        viewModelScope.launch {
            val event = try {
                when (val r = exporter.export(ExportRequest(createdSec = backend.clock.nowSec()), out)) {
                    is Outcome.Ok -> RecipeBookEvent.Exported(r.value.counts.values.sum())
                    is Outcome.Fail -> {
                        SwLog.w(TAG, "recipe export failed: ${r.error}")
                        RecipeBookEvent.Failed
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "recipe export failed", e)
                RecipeBookEvent.Failed
            }
            eventChannel.send(event)
        }
    }

    private companion object {
        const val TAG = "RecipeBookVM"
        const val STOP_MS = 5_000L
    }
}

/** One-shot recipe book events. */
sealed interface RecipeBookEvent {
    /** The export wrote [count] recipes. */
    data class Exported(val count: Int) : RecipeBookEvent

    /** The export failed. */
    data object Failed : RecipeBookEvent
}

/** One ingredient line on the recipe detail. */
data class IngredientUi(val item: RecipeItem, val text: String, val foodName: String?)

/** Recipe detail state. */
data class RecipeDetailState(
    val loading: Boolean = true,
    val recipe: Recipe? = null,
    val nutrition: RecipeNutrition? = null,
    /** Servings shown (×10); ingredient amounts are scaled to it. */
    val servingsX10: Int = 10,
    val lines: List<IngredientUi> = emptyList(),
    val missing: Boolean = false,
    val busy: Boolean = false,
) {
    /** Lines with no counted food (unmatched or suggested-but-unconfirmed). */
    val needsMatching: Boolean get() = lines.any { it.item.match == MatchState.UNMATCHED }
}

/** Recipe detail: per-serving nutrition and its honesty label, servings scaler, ingredient matches. */
class RecipeDetailViewModel(
    private val id: Long,
    private val backendProvider: () -> FoodBackend,
) : ViewModel() {
    private val backend: FoodBackend by lazy(backendProvider)
    private val mutable = MutableStateFlow(RecipeDetailState())

    /** Screen state. */
    val state: StateFlow<RecipeDetailState> = mutable.asStateFlow()

    init {
        reload()
    }

    /** Reads the recipe again (after editing). */
    fun reload() {
        viewModelScope.launch {
            try {
                val r = backend.recipe(id)
                if (r == null) {
                    mutable.value = RecipeDetailState(loading = false, missing = true)
                    return@launch
                }
                val keep = mutable.value.recipe?.let { mutable.value.servingsX10 } ?: r.servingsX10
                mutable.value = build(r, keep)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "load failed", e)
                mutable.value = RecipeDetailState(loading = false, missing = true)
            }
        }
    }

    private suspend fun build(r: Recipe, servingsX10: Int): RecipeDetailState {
        val names = HashMap<FoodRef, String?>()
        for (item in r.items) {
            val ref = item.food ?: continue
            if (ref !in names) names[ref] = backend.food(ref)?.name
        }
        return RecipeDetailState(
            loading = false,
            recipe = r,
            nutrition = backend.nutrition(r),
            servingsX10 = servingsX10,
            lines = lines(r, servingsX10, names),
        )
    }

    /** Shows the recipe scaled to [servingsX10] servings ×10 (at least 1). */
    fun setServings(servingsX10: Int) {
        val r = mutable.value.recipe ?: return
        val s = servingsX10.coerceAtLeast(1)
        val names = mutable.value.lines.mapNotNull { l -> l.item.food?.let { it to l.foodName } }.toMap()
        mutable.update { it.copy(servingsX10 = s, lines = lines(r, s, names)) }
    }

    /** Fills suggested foods for unmatched lines and saves. */
    fun suggest() = change { backend.suggestMatches(it) }

    /** Confirms line [seq] as food [ref] and saves. */
    fun confirm(seq: Int, ref: FoodRef) = change { r ->
        val food = backend.food(ref) ?: return@change r
        backend.confirmMatch(r, seq, food)
    }

    /** Skips line [seq] (not counted) and saves. */
    fun skip(seq: Int) = change { backend.skipMatch(it, seq) }

    private fun change(edit: suspend (Recipe) -> Recipe) {
        val r = mutable.value.recipe ?: return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val changed = edit(r)
                backend.saveRecipe(changed)
                mutable.value = build(changed, mutable.value.servingsX10)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "match change failed", e)
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    /** Constants and the scaling rule. */
    companion object {
        private const val TAG = "RecipeDetailVM"

        /** [amountX100] for a recipe of [fromServingsX10] scaled to [toServingsX10], rounded half up. */
        fun scaleX100(amountX100: Int, fromServingsX10: Int, toServingsX10: Int): Int {
            if (fromServingsX10 <= 0) return amountX100
            return NutrientMath.clampInt(NutrientMath.mulDivRound(amountX100.toLong(), toServingsX10.toLong(), fromServingsX10.toLong()))
        }

        /** The ingredient lines of [r] as shown for [servingsX10] servings ×10. */
        fun lines(r: Recipe, servingsX10: Int, names: Map<FoodRef, String?>): List<IngredientUi> = r.items.map { item ->
            IngredientUi(item, lineText(item, r.servingsX10, servingsX10), item.food?.let { names[it] })
        }

        /** "3 cup flour" when scaled, else the original line. */
        fun lineText(item: RecipeItem, fromX10: Int, toX10: Int): String {
            val amount = item.amountX100
            if (amount == null || fromX10 == toX10 || fromX10 <= 0) return item.line
            val p = IngredientParser.parse(item.line)
            val scaled = scaleX100(amount, fromX10, toX10)
            val parts = listOfNotNull(Fmt.x100(scaled.toLong()), item.unit ?: p.unit, p.words.takeIf { it.isNotBlank() })
            val main = parts.joinToString(" ")
            return if (p.note.isNullOrBlank()) main else "$main, ${p.note}"
        }
    }
}

/** The recipe editor form as typed. */
data class RecipeForm(
    val name: String = "",
    val servings: String = "",
    val ingredients: String = "",
    val steps: String = "",
    val tags: String = "",
)

/** Recipe editor events. */
sealed interface RecipeEditorEvent {
    /** Saved as recipe [id]. */
    data class Saved(val id: Long) : RecipeEditorEvent

    /** Deleted. */
    data object Deleted : RecipeEditorEvent

    /** A name is needed. */
    data object NameNeeded : RecipeEditorEvent

    /** Something failed. */
    data object Failed : RecipeEditorEvent
}

/** Recipe editor state. */
data class RecipeEditorState(val loading: Boolean = true, val form: RecipeForm = RecipeForm(), val id: Long = 0)

/**
 * Recipe editor: name, servings, ingredient lines (one per line, parsed), steps and tags. Lines that
 * did not change keep their matches; new lines get suggested matches on save.
 */
class RecipeEditorViewModel(private val id: Long, private val backendProvider: () -> FoodBackend) : ViewModel() {
    private val backend: FoodBackend by lazy(backendProvider)
    private val mutable = MutableStateFlow(RecipeEditorState())
    private val eventChannel = Channel<RecipeEditorEvent>(Channel.BUFFERED)
    private var existing: Recipe? = null

    /** Screen state. */
    val state: StateFlow<RecipeEditorState> = mutable.asStateFlow()

    /** One-shot events. */
    val events: Flow<RecipeEditorEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val r = if (id != 0L) backend.recipe(id) else null
            existing = r
            mutable.value = RecipeEditorState(loading = false, form = r?.let { formOf(it) } ?: RecipeForm(servings = "1"), id = r?.id ?: 0L)
        }
    }

    /** Validates, suggests matches for new lines and saves. */
    fun save(form: RecipeForm) {
        if (form.name.isBlank()) {
            viewModelScope.launch { eventChannel.send(RecipeEditorEvent.NameNeeded) }
            return
        }
        viewModelScope.launch {
            try {
                val built = backend.suggestMatches(recipeOf(form, existing))
                val saved = backend.saveRecipe(built)
                eventChannel.send(RecipeEditorEvent.Saved(saved))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "save failed", e)
                eventChannel.send(RecipeEditorEvent.Failed)
            }
        }
    }

    /** Deletes the recipe. */
    fun delete() {
        val r = existing ?: return
        viewModelScope.launch {
            try {
                backend.deleteRecipe(r.id)
                eventChannel.send(RecipeEditorEvent.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "delete failed", e)
                eventChannel.send(RecipeEditorEvent.Failed)
            }
        }
    }

    /** Constants and the form rules. */
    companion object {
        private const val TAG = "RecipeEditorVM"

        /** [r] as form text. */
        fun formOf(r: Recipe): RecipeForm = RecipeForm(
            name = r.name,
            servings = Fmt.x100(r.servingsX10 * 10L, 1),
            ingredients = r.items.sortedBy { it.seq }.joinToString("\n") { it.line },
            steps = r.steps.orEmpty(),
            tags = r.tags.sorted().joinToString(", "),
        )

        /**
         * The recipe [form] describes, built on [existing] (null for new): blank lines dropped,
         * unchanged lines keep their match, new lines are parsed and UNMATCHED; servings default to 1.
         */
        fun recipeOf(form: RecipeForm, existing: Recipe?): Recipe {
            val old = existing?.items.orEmpty().groupBy { it.line.trim() }.mapValues { it.value.toMutableList() }.toMutableMap()
            val items = form.ingredients.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, line ->
                val kept = old[line]?.removeFirstOrNull()
                if (kept != null) {
                    kept.copy(seq = i)
                } else {
                    val p = IngredientParser.parse(line)
                    RecipeItem(i, line, null, p.amountX100, p.unit, null, MatchState.UNMATCHED)
                }
            }
            val servingsX10 = Fmt.parseX100(form.servings.trim())?.let { ((it + 5) / 10).toInt() }?.takeIf { it > 0 } ?: 10
            val tags = form.tags.split(',', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val base = existing ?: Recipe(
                id = 0, kind = RecipeKind.RECIPE, name = "", servingsX10 = 10, servingLabel = null, prepMin = null, cookMin = null,
                steps = null, notes = null, sourceName = null, sourceUrl = null, statedPerServing = null, tags = emptySet(), items = emptyList(),
                importId = null,
            )
            return base.copy(
                name = form.name.trim(),
                servingsX10 = servingsX10,
                steps = form.steps.trim().ifEmpty { null },
                tags = tags,
                items = items,
            )
        }
    }
}

/** Match picker: searches every local food (no recipes) for an ingredient line. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MatchPickerViewModel(initial: String, private val backendProvider: () -> FoodBackend) : ViewModel() {
    private val backend: FoodBackend by lazy(backendProvider)
    private val query = MutableStateFlow(initial)

    /** Hits for the current query. */
    val hits: StateFlow<List<FoodHit>> = query.debounce(AddFoodViewModel.DEBOUNCE_MS).map { it.trim() }.distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.isEmpty()) flowOf(emptyList()) else backend.search(q).map { l -> l.filter { it.ref.origin != FoodOrigin.RECIPE } }
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** The search box changed. */
    fun setQuery(q: String) {
        query.value = q
    }
}

/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.shell.Tab
import app.selfwatch.ui.format.Units

/** The tabs that show an editable stack of cards. [key] names their settings (`cards.<key>`). */
enum class CardTab(
    /** Settings key part; never change it. */
    val key: String,
    /** Title of the tab's Edit cards screen. */
    @field:StringRes @get:StringRes val editTitle: Int,
    /** The tab's name (group headers in Edit cards). */
    @field:StringRes @get:StringRes val label: Int,
) {
    /** Body (home). */
    BODY("body", R.string.edit_cards_title_body, R.string.card_home_body),

    /** Activity. */
    ACTIVITY("activity", R.string.edit_cards_title_activity, R.string.card_home_activity),

    /** Food: the cards above the meals. */
    FOOD("food", R.string.edit_cards_title_food, R.string.card_home_food),
    ;

    /** The visible cards, in order (comma list of `id:size[:a]`, see [CardStore]). */
    val cardsKey: String get() = "cards.$key"

    /** Every card id the app knew when [cardsKey] was saved. */
    val knownKey: String get() = "cards.$key.known"

    /** The main goal [cardsKey] was last reset to (no longer written; removed on the next save). */
    val presetKey: String get() = "cards.$key.preset"
}

/** The two forms of every card. */
enum class CardSize(
    /** Stored code; never change it. */
    val code: String,
) {
    /** Half the width: one number, a label, a status color. Two small cards share a row. */
    SMALL("s"),

    /** The full width: chart, details or list. */
    LARGE("l"),
    ;

    /** Lookup. */
    companion object {
        /** The size stored as [code], or null. */
        fun byCode(code: String?): CardSize? = entries.firstOrNull { it.code == code }
    }
}

/** How a number stands against its goal or usual range; colors come from the theme. */
enum class CardStatus {
    /** On track (the theme's primary color). */
    GOOD,

    /** Close (the theme's secondary color). */
    CLOSE,

    /** Off track (muted). */
    OFF,

    /** No judgment (no color). */
    NONE,
}

/**
 * What a card shows in its small form: one main [value] (with an optional [unit]), a plain [label], an
 * optional short [note] and a [status] color. [progress] (0–1) draws a thin bar toward a goal.
 */
data class SmallFace(
    val value: CharSequence,
    val label: CharSequence,
    val unit: CharSequence? = null,
    val status: CardStatus = CardStatus.NONE,
    val note: CharSequence? = null,
    val progress: Float? = null,
)

/** What a card's loader can read: the database, the clock, the day shown and the display units. */
class CardEnv(
    /** The database (read only, from a loader). */
    val db: SelfWatchDb,
    /** Time source. */
    val clock: Clock,
    /** The day the tab shows: today on Body and Activity, the chosen diary day on Food. */
    val day: Day,
    /** Display units. */
    val units: Units,
)

/** What a card's view can do: open screens and tabs, and run work tied to the screen. */
interface CardHost {
    /** The tab screen showing the card (for sheets and dialogs). */
    val fragment: Fragment

    /** The screen's context. */
    val context: Context

    /** Opens [screen] above the tabs. */
    fun open(screen: Fragment)

    /** Switches to [tab]. */
    fun openTab(tab: Tab)

    /** Runs [block] while the screen is shown; failures are logged. */
    fun launch(block: suspend () -> Unit)
}

/**
 * One card type. A card is an `object` with a stable [id] (stored in settings, never change it; unique
 * across all tabs), its home [tab] (where Edit cards lists it; it can be added to any tab), its name and
 * one-line summary for Edit cards, the database [tables] that should reload it, a [load] that reads its
 * data (called off the main thread, only while the card is shown), a [createView] that builds its large
 * form and a [small] that says what its small form shows.
 *
 * [needs] lists the data the card shows: it is offered (and shown by default) only when one of the
 * user's devices records one of those kinds; empty means it always works (food log, goals).
 *
 * To add a card: write the object (and its layout), add it to its home tab's list (`BodyCards`,
 * `ActivityCards`, `FoodCards`), and to [CardPresets] when it is a default.
 */
abstract class Card<T>(
    /** Stable id, unique across tabs. */
    val id: String,
    /** The card's home tab. */
    val tab: CardTab,
    /** Name in Edit cards. */
    @field:StringRes @get:StringRes val title: Int,
    /** One line under the name in Edit cards. */
    @field:StringRes @get:StringRes val summary: Int,
) {
    /** Tables whose changes reload the card (never empty). */
    abstract val tables: Set<Table>

    /** The data the card needs from a device (any one of them); empty = always available. */
    open val needs: Set<DataKind> get() = emptySet()

    /** The size the card gets when the user adds it. */
    open val defaultSize: CardSize get() = CardSize.SMALL

    /** Reads the card's data. Runs on an IO thread; may throw (the card then keeps what it showed). */
    abstract suspend fun load(env: CardEnv): T

    /** Builds the card's large view inside [parent] (not attached). Runs once per screen. */
    abstract fun createView(host: CardHost, parent: ViewGroup): CardView<T>

    /** The small form for [data], or null when there is nothing to show yet ("No data yet"). */
    abstract fun small(ctx: Context, data: T): SmallFace?

    /**
     * The group Edit cards lists the card under instead of its home tab (for example "Device cards"),
     * or null for the home tab's group.
     */
    @get:StringRes
    open val group: Int? get() = null

    /**
     * An optional picture for the small form, shown above the number (for example the Day clock's
     * ring). Built once per small card; null for none.
     */
    open fun createSmallGraphic(ctx: Context): View? = null

    /** Shows [data] in the picture from [createSmallGraphic]. */
    open fun bindSmallGraphic(graphic: View, data: T) = Unit

    /**
     * Opens the card's detail screen from its small form; false when it has none (the large form then
     * opens on its own screen).
     */
    open fun openDetail(host: CardHost): Boolean = false

    override fun toString(): String = "${tab.key}/$id"
}

/** A card's view: [root] goes into the stack; [bind] shows freshly loaded data (main thread). */
abstract class CardView<T>(
    /** The card's root view. */
    val root: View,
) {
    /** Shows [data]. */
    abstract fun bind(data: T)

    /** Shorthand for finding views in [root]. */
    protected fun <V : View> find(@IdRes id: Int): V = root.findViewById(id)

    /** Inflates [layout] for a card in [parent]. */
    companion object {
        /** [layout] inflated for [parent], not attached. */
        fun inflate(parent: ViewGroup, @LayoutRes layout: Int): View =
            LayoutInflater.from(parent.context).inflate(layout, parent, false)
    }
}

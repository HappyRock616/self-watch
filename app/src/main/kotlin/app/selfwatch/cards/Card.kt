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
) {
    /** Body (home). */
    BODY("body", R.string.edit_cards_title_body),

    /** Activity. */
    ACTIVITY("activity", R.string.edit_cards_title_activity),

    /** Food: a strip of summary cards between the calories-left header and the meals. */
    FOOD("food", R.string.edit_cards_title_food),
    ;

    /** The visible cards, in order (comma list of ids). */
    val cardsKey: String get() = "cards.$key"

    /** Every card id the app knew when [cardsKey] was saved. */
    val knownKey: String get() = "cards.$key.known"

    /** The main goal [cardsKey] was last reset to. */
    val presetKey: String get() = "cards.$key.preset"
}

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
 * One card type. A card is an `object` with a stable [id] (stored in settings, never change it), the
 * [tab] it lives on, its name and one-line summary for Edit cards, the database [tables] that should
 * reload it, a [load] that reads its data (called off the main thread, only while the card is shown)
 * and a [createView] that builds its view.
 *
 * To add a card: write the object and its layout, add it to its tab's list (`BodyCards`,
 * `ActivityCards`, `FoodCards`), and add it to the goals that should show it in [CardPresets].
 */
abstract class Card<T>(
    /** Stable id, unique within its tab. */
    val id: String,
    /** The tab it lives on. */
    val tab: CardTab,
    /** Name in Edit cards. */
    @field:StringRes @get:StringRes val title: Int,
    /** One line under the name in Edit cards. */
    @field:StringRes @get:StringRes val summary: Int,
) {
    /** Tables whose changes reload the card (never empty). */
    abstract val tables: Set<Table>

    /** Reads the card's data. Runs on an IO thread; may throw (the card then keeps what it showed). */
    abstract suspend fun load(env: CardEnv): T

    /** Builds the card's view inside [parent] (not attached). Runs once per screen. */
    abstract fun createView(host: CardHost, parent: ViewGroup): CardView<T>

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

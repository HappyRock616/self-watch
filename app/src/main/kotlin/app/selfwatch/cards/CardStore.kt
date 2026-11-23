/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.db.Table
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Reads and saves the main goal and, per tab, which cards show in which order. Keys per tab:
 * `cards.<tab>` (visible ids in order), `cards.<tab>.known` (every id the app knew when it was saved)
 * and `cards.<tab>.preset` (the goal it was last reset to). The older `body.cards*` keys are moved to
 * the Body keys the first time they are read.
 */
class CardStore(private val settings: SettingsStore) {

    /** The stored main goal. */
    suspend fun mainGoal(): MainGoal = MainGoal.byId(settings.get(SettingKeys.MAIN_GOAL))

    /** Stores the main goal only (every tab keeps its cards). */
    suspend fun setMainGoal(goal: MainGoal) {
        settings.set(SettingKeys.MAIN_GOAL, goal.id)
    }

    /** Stores the main goal and resets every tab to its cards, in one write. */
    suspend fun setMainGoalWithCards(goal: MainGoal) {
        settings.setAll(
            buildMap {
                put(SettingKeys.MAIN_GOAL, goal.id)
                for (tab in CardTab.entries) putAll(values(tab, goal.cards(tab), goal))
                putAll(LEGACY_GONE)
            },
        )
    }

    /** The cards to show on [tab], in order. */
    suspend fun visible(tab: CardTab): List<Card<*>> {
        migrate(tab)
        return resolve(tab, settings.get(tab.cardsKey), settings.get(tab.knownKey), mainGoal())
    }

    /** [visible] now and after every settings change; repeats are dropped. */
    fun observeVisible(tab: CardTab, changes: Flow<Unit>): Flow<List<Card<*>>> =
        changes.map { visible(tab) }.distinctUntilChanged()

    /** Saves [visible] for [tab] (in order; every other card is hidden). [preset] is noted when it is a reset. */
    suspend fun save(tab: CardTab, visible: List<Card<*>>, preset: MainGoal? = null) {
        migrate(tab)
        settings.setAll(values(tab, visible, preset))
    }

    /** [tab] back to the main goal's set. Returns it. */
    suspend fun reset(tab: CardTab): List<Card<*>> {
        val goal = mainGoal()
        val cards = goal.cards(tab)
        save(tab, cards, goal)
        return cards
    }

    /** The goal [tab] was last reset to, or null when never reset. */
    suspend fun preset(tab: CardTab): MainGoal? {
        migrate(tab)
        return MainGoal.byIdOrNull(settings.get(tab.presetKey))
    }

    /** True when any tab has saved cards (the user, onboarding or a reset chose them). */
    suspend fun anySaved(): Boolean =
        CardTab.entries.any { tab -> migrate(tab); settings.get(tab.cardsKey) != null }

    /** Moves the old `body.cards*` keys to the Body keys (once; the new keys win when both exist). */
    private suspend fun migrate(tab: CardTab) {
        if (tab != CardTab.BODY) return
        val old = LEGACY.mapValues { (oldKey, _) -> settings.get(oldKey) }
        if (old.values.all { it == null }) return
        val moved = HashMap<String, String?>(LEGACY_GONE)
        if (settings.get(tab.cardsKey) == null) {
            for ((oldKey, newKey) in LEGACY) moved[newKey(tab, newKey)] = old[oldKey]
        }
        settings.setAll(moved)
    }

    private fun values(tab: CardTab, visible: List<Card<*>>, preset: MainGoal?): Map<String, String?> = buildMap {
        put(tab.cardsKey, encode(visible.filter { it.tab == tab }))
        put(tab.knownKey, CardRegistry.of(tab).joinToString(",") { it.id })
        if (preset != null) put(tab.presetKey, preset.id)
    }

    /** Pure parts. */
    companion object {
        private const val CARDS = "cards"
        private const val KNOWN = "known"
        private const val PRESET = "preset"

        /** Old Body key → which new key it becomes. */
        private val LEGACY: Map<String, String> = mapOf(
            SettingKeys.BODY_CARDS to CARDS,
            SettingKeys.BODY_CARDS_KNOWN to KNOWN,
            SettingKeys.BODY_CARDS_PRESET to PRESET,
        )

        /** Removes every old key. */
        private val LEGACY_GONE: Map<String, String?> = LEGACY.keys.associateWith { null }

        private fun newKey(tab: CardTab, which: String): String = when (which) {
            KNOWN -> tab.knownKey
            PRESET -> tab.presetKey
            else -> tab.cardsKey
        }

        /** Settings tables: a change there may change the cards. */
        val TABLES: Set<Table> = setOf(Table.SETTING)

        /** [visible] as the stored comma list. */
        fun encode(visible: List<Card<*>>): String = visible.distinct().joinToString(",") { it.id }

        /**
         * The cards to show on [tab] from the stored list [stored] (null = never saved: [goal]'s set).
         * Unknown ids are dropped and repeats ignored. A card the app did not have when the list was
         * saved (not in [known]) is added at the end when [goal]'s set has it; otherwise it starts
         * hidden. A card the user hid (known, not listed) never comes back on its own.
         */
        fun resolve(tab: CardTab, stored: String?, known: String?, goal: MainGoal): List<Card<*>> {
            val preset = goal.cards(tab)
            if (stored == null) return preset
            val visible = split(stored).mapNotNull { CardRegistry.byId(tab, it) }.distinct()
            val knownIds = known?.let { split(it).toSet() } ?: CardRegistry.of(tab).map { it.id }.toSet()
            val added = preset.filter { it.id !in knownIds && it !in visible }
            return visible + added
        }

        private fun split(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

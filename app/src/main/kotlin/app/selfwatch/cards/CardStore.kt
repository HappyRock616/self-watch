/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.db.Table
import app.selfwatch.activity.cards.WorkoutsCard
import app.selfwatch.food.cards.DailyBalanceCard
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One card placed on a tab: the [card], its [size], and whether it is [auto] (a default that shows only
 * while the user's devices record its data) or chosen by the user (always shown; it says so when no
 * device records its data).
 */
data class CardSlot(val card: Card<*>, val size: CardSize, val auto: Boolean = false)

/** The slots shown on a tab and what the devices record (one load). */
data class ShownCards(val slots: List<CardSlot>, val recorded: Recorded)

/**
 * Reads and saves, per tab, which cards show in which order and size, plus the main goal. Keys per tab:
 * `cards.<tab>` (the slots in order as `id:s` or `id:l`, `:a` added for auto slots; a bare `id` from
 * before sizes is a large card the user chose) and `cards.<tab>.known` (every id the app knew when it
 * was saved). A tab never saved shows [CardPresets.defaults]. The older `body.cards*` keys are moved to
 * the Body keys the first time they are read.
 *
 * [recorded] says which data the devices record (see [RecordedLoader]); [of] builds the store for the
 * app with the real one.
 */
class CardStore(
    private val settings: SettingsStore,
    private val recorded: suspend () -> Recorded = { Recorded.ALL },
) {

    /** The stored main goal (protein question and chart defaults; it no longer picks cards). */
    suspend fun mainGoal(): MainGoal = MainGoal.byId(settings.get(SettingKeys.MAIN_GOAL))

    /** Stores the main goal (every tab keeps its cards and every chart its layers). */
    suspend fun setMainGoal(goal: MainGoal) {
        settings.set(SettingKeys.MAIN_GOAL, goal.id)
    }

    /** What the devices record now. */
    suspend fun recorded(): Recorded = recorded.invoke()

    /** Every slot of [tab] (auto slots whose data is not recorded included), in order. */
    suspend fun slots(tab: CardTab, rec: Recorded? = null): List<CardSlot> {
        migrate(tab)
        return resolve(tab, settings.get(tab.cardsKey), settings.get(tab.knownKey), rec ?: recorded())
    }

    /** The slots shown on [tab] now: auto slots only while their data is recorded. */
    suspend fun shown(tab: CardTab): ShownCards {
        val rec = recorded()
        return ShownCards(shownOf(slots(tab, rec), rec), rec)
    }

    /** The cards shown on [tab], in order. */
    suspend fun visible(tab: CardTab): List<Card<*>> = shown(tab).slots.map { it.card }

    /** [shown] now and after every change; repeats are dropped. */
    fun observeShown(tab: CardTab, changes: Flow<Unit>): Flow<ShownCards> = changes.map { shown(tab) }.distinctUntilChanged()

    /** Saves [slots] for [tab] in order (every other card is hidden). Repeats keep the first. */
    suspend fun saveSlots(tab: CardTab, slots: List<CardSlot>) {
        migrate(tab)
        settings.setAll(values(tab, slots))
    }

    /** Saves [visible] for [tab] in order as cards the user chose, all in [size]. */
    suspend fun save(tab: CardTab, visible: List<Card<*>>, size: CardSize = CardSize.LARGE) {
        saveSlots(tab, visible.map { CardSlot(it, size) })
    }

    /** [tab] back to the default layout (it follows the devices again). Returns its slots. */
    suspend fun reset(tab: CardTab): List<CardSlot> {
        migrate(tab)
        settings.setAll(mapOf(tab.cardsKey to null, tab.knownKey to null, tab.presetKey to null))
        return slots(tab)
    }

    /** True when [tab] has a saved layout. */
    suspend fun isSaved(tab: CardTab): Boolean {
        migrate(tab)
        return settings.get(tab.cardsKey) != null
    }

    /** One-time fix-ups of saved layouts: the old Body keys, and Recent activities moved last on Activity. */
    private suspend fun migrate(tab: CardTab) {
        when (tab) {
            CardTab.BODY -> migrateLegacy(tab)
            CardTab.ACTIVITY -> migrateRecentLast(tab)
            CardTab.FOOD -> Unit
        }
    }

    /**
     * Moves Recent activities to the end of a saved Activity layout, once (flag [RECENT_LAST_KEY]); the rest
     * of the stored list is kept exactly as it was. Anything the user saves after that is their own order.
     */
    private suspend fun migrateRecentLast(tab: CardTab) {
        // The flag is set even when there was nothing to move, so a later order the user saves with
        // Recent activities elsewhere is never moved again.
        if (settings.get(RECENT_LAST_KEY) != null) return
        val stored = settings.get(tab.cardsKey)
        val moved = stored?.let { recentLast(it) }
        val writes = HashMap<String, String?>()
        writes[RECENT_LAST_KEY] = "1"
        if (moved != null && moved != stored) writes[tab.cardsKey] = moved
        settings.setAll(writes)
    }

    /** Moves the old `body.cards*` keys to the Body keys (once; the new keys win when both exist). */
    private suspend fun migrateLegacy(tab: CardTab) {
        val old = LEGACY.mapValues { (oldKey, _) -> settings.get(oldKey) }
        if (old.values.all { it == null }) return
        val moved = HashMap<String, String?>(LEGACY_GONE)
        if (settings.get(tab.cardsKey) == null) {
            for ((oldKey, newKey) in LEGACY) if (newKey != null) moved[newKey(tab, newKey)] = old[oldKey]
        }
        settings.setAll(moved)
    }

    private fun values(tab: CardTab, slots: List<CardSlot>): Map<String, String?> = mapOf(
        tab.cardsKey to encode(slots),
        // Every card this version has: a card missing from the saved list was hidden on purpose, while a
        // card missing from `known` is new since the save (see [resolve]).
        tab.knownKey to CardRegistry.all.joinToString(",") { it.id },
        tab.presetKey to null,
    )

    /** Pure parts. */
    companion object {
        private const val CARDS = "cards"
        private const val KNOWN = "known"
        private const val AUTO = "a"

        /** Old Body key → which new key it becomes (null: dropped). */
        private val LEGACY: Map<String, String?> = mapOf(
            SettingKeys.BODY_CARDS to CARDS,
            SettingKeys.BODY_CARDS_KNOWN to KNOWN,
            SettingKeys.BODY_CARDS_PRESET to null,
        )

        /** Set once saved Activity layouts have had Recent activities moved to the end. */
        const val RECENT_LAST_KEY: String = "cards.activity.recent_last"

        /**
         * [stored] (a saved `cards.<tab>` list) with the Recent activities entry moved to the end; every
         * other entry is kept as typed, unknown ids included. Unchanged when it has no such entry.
         */
        fun recentLast(stored: String): String {
            val entries = stored.split(',')
            val isRecent = { e: String -> e.trim().substringBefore(':') == WorkoutsCard.id }
            val recent = entries.firstOrNull(isRecent) ?: return stored
            return (entries.filterNot(isRecent) + recent).joinToString(",")
        }

        /** Removes every old key. */
        private val LEGACY_GONE: Map<String, String?> = LEGACY.keys.associateWith { null }

        /** Ids of cards merged into another card: old id → the card that replaces it. */
        val MOVED: Map<String, String> = mapOf(
            // Body's own steps and macros cards were duplicates of the Activity and Food ones.
            "steps" to "steps_today",
            "macros" to "macros_today",
        )

        private fun newKey(tab: CardTab, which: String): String = if (which == KNOWN) tab.knownKey else tab.cardsKey

        /** Settings tables: a change there may change the cards. */
        val TABLES: Set<Table> = setOf(Table.SETTING)

        /** The store for the app: [Recorded] read from [db]'s devices and data. */
        fun of(db: SelfWatchDb, clock: Clock = SystemClock): CardStore {
            val loader = RecordedLoader(db, clock)
            return CardStore(db.settings) { loader.load() }
        }

        /** [slots] as the stored comma list. */
        fun encode(slots: List<CardSlot>): String = slots.distinctBy { it.card }.joinToString(",") { s ->
            buildString {
                append(s.card.id).append(':').append(s.size.code)
                // Kept through saves, so a default the user only moved or resized still hides while no
                // device records its data, instead of turning into a card that says "not recorded".
                if (s.auto) append(':').append(AUTO)
            }
        }

        /** One stored entry, or null for an unknown id. */
        fun decode(entry: String): CardSlot? {
            val parts = entry.trim().split(':')
            val id = parts[0].let { MOVED[it] ?: it }
            val card = CardRegistry.byId(id) ?: return null
            // A bare id comes from before sizes existed, when every card was large.
            val size = CardSize.byCode(parts.getOrNull(1)) ?: CardSize.LARGE
            return CardSlot(card, size, auto = parts.getOrNull(2) == AUTO)
        }

        /** The slots of [slots] shown for [recorded]: auto slots only while their data is recorded. */
        fun shownOf(slots: List<CardSlot>, recorded: Recorded): List<CardSlot> =
            slots.filter { !it.auto || recorded.records(it.card) }

        /**
         * The slots of [tab] from the stored list [stored] (null = never saved: the defaults for
         * [recorded]). Unknown ids are dropped and repeats ignored; merged cards' ids map to the card that
         * replaced them. New cards the user has not seen start hidden (they are in Edit cards), except Daily
         * balance on Food, which replaced the fixed calories-left header and so joins at the top.
         */
        fun resolve(tab: CardTab, stored: String?, known: String?, recorded: Recorded): List<CardSlot> {
            if (stored == null) return CardPresets.defaults(tab, recorded)
            val slots = split(stored).mapNotNull { decode(it) }.distinctBy { it.card }
            val knownIds = known?.let { split(it).toSet() }.orEmpty()
            if (tab == CardTab.FOOD && DailyBalanceCard.id !in knownIds && slots.none { it.card == DailyBalanceCard }) {
                return listOf(CardSlot(DailyBalanceCard, CardSize.LARGE)) + slots
            }
            return slots
        }

        private fun split(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

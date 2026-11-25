/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.content.Context
import android.view.ViewGroup
import app.selfwatch.db.Table

/**
 * A catalog card for data Self Watch does not receive yet ([kind] is not [DataKind.stored]). It is listed
 * in Edit cards under "Show all" and, when added, says "Your devices don't record this". When a device
 * driver starts storing [kind] and [RecordedLoader] maps it, replace the card's [load] and views with
 * the real ones (keep its [id]); until then a device that records it gets "No data yet".
 */
class WaitingCard(
    id: String,
    tab: CardTab,
    title: Int,
    summary: Int,
    /** The data the card waits for. */
    val kind: DataKind,
    override val defaultSize: CardSize = CardSize.SMALL,
    /** The Edit cards group, when not the home tab (see [Card.group]). */
    override val group: Int? = null,
) : Card<Unit>(id, tab, title, summary) {

    override val tables: Set<Table> = setOf(Table.DEVICE)

    override val needs: Set<DataKind> = setOf(kind)

    override suspend fun load(env: CardEnv) = Unit

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Unit> =
        object : CardView<Unit>(WaitingCardView.create(this@WaitingCard, CardSize.LARGE, parent.context)) {
            override fun bind(data: Unit) = Unit
        }

    override fun small(ctx: Context, data: Unit): SmallFace? = null
}

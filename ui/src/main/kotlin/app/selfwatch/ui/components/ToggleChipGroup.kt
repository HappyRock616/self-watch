/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import androidx.annotation.ColorInt
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import app.selfwatch.ui.R
import app.selfwatch.ui.charts.ChartPalette
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * One toggle chip: a stable [id], its [label] and the series [color] it fills with when on. A [locked]
 * chip is always on and cannot be tapped (a legend entry for a series that is always shown).
 */
data class ToggleItem(
    val id: String,
    val label: String,
    @param:ColorInt @get:ColorInt val color: Int,
    val initiallyOn: Boolean = true,
    val locked: Boolean = false,
)

/**
 * Chips that switch chart series on and off. An on chip is filled with its series colour (so it doubles
 * as the legend, with white or near-black text by contrast); an off chip is outlined. When [chartId] is set, the on/off state is remembered per
 * chart across app restarts.
 */
class ToggleChipGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.chipGroupStyle,
) : ChipGroup(context, attrs, defStyleAttr) {

    private var building = false
    private val chipsById = LinkedHashMap<String, Chip>()

    /** Key for remembering state; set before [items]. Null means nothing is remembered. */
    var chartId: String? = null

    /** Called after a chip changes: the chip's id, whether it is now on, and every on id. */
    var onChange: ((id: String, on: Boolean, onIds: Set<String>) -> Unit)? = null

    /** The chips to show, in order. */
    var items: List<ToggleItem> = emptyList()
        set(v) {
            field = v
            rebuild()
        }

    /** The ids that are currently on. */
    val onIds: Set<String>
        get() = chipsById.filterValues { it.isChecked }.keys.toSet()

    init {
        isSingleLine = false
        isSelectionRequired = false
        isSingleSelection = false
    }

    /** Turns one chip on or off from code (fires [onChange]). Locked chips stay on. */
    fun setOn(id: String, on: Boolean) {
        if (!on && items.any { it.id == id && it.locked }) return
        chipsById[id]?.isChecked = on
    }

    private fun rebuild() {
        building = true
        removeAllViews()
        chipsById.clear()
        val saved = loadOff()
        val textOff = themeColor(com.google.android.material.R.attr.colorOnSurface)
        val outline = themeColor(com.google.android.material.R.attr.colorOutline)
        for (item in items) {
            // Text on a filled (on) chip: white or near-black, whichever reads better on that series colour.
            val textOn = ChartPalette.textOn(item.color)
            val chip = Chip(context).apply {
                text = item.label
                isCheckable = true
                isCheckedIconVisible = !item.locked
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                chipBackgroundColor = ColorStateList(states, intArrayOf(item.color, ColorUtils.setAlphaComponent(item.color, 0)))
                setTextColor(ColorStateList(states, intArrayOf(textOn, textOff)))
                checkedIconTint = ColorStateList.valueOf(textOn)
                chipStrokeColor = ColorStateList(states, intArrayOf(item.color, outline))
                chipStrokeWidth = context.dp(1f)
                isChecked = item.locked || if (saved != null) item.id !in saved else item.initiallyOn
                if (item.locked) {
                    isClickable = false
                    isFocusable = false
                }
                updateDescription(this, item.label)
                setOnCheckedChangeListener { c, on ->
                    updateDescription(c as Chip, item.label)
                    if (!building) {
                        saveOff()
                        onChange?.invoke(item.id, on, onIds)
                    }
                }
            }
            chipsById[item.id] = chip
            addView(chip)
        }
        building = false
    }

    private fun updateDescription(chip: Chip, label: String) {
        chip.contentDescription = context.getString(
            if (chip.isChecked) R.string.sw_chip_on else R.string.sw_chip_off,
            label,
        )
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadOff(): Set<String>? {
        val id = chartId ?: return null
        return prefs().getStringSet(KEY_PREFIX + id, null)
    }

    private fun saveOff() {
        val id = chartId ?: return
        val off = chipsById.filterValues { !it.isChecked }.keys.toSet()
        prefs().edit { putStringSet(KEY_PREFIX + id, off) }
    }

    private companion object {
        const val PREFS: String = "sw_chart_toggles"
        const val KEY_PREFIX: String = "off_"
    }
}

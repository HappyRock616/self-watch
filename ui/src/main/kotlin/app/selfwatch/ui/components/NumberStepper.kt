/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.withStyledAttributes
import androidx.core.widget.TextViewCompat
import app.selfwatch.ui.R
import app.selfwatch.ui.format.Fmt
import com.google.android.material.button.MaterialButton

/**
 * Amount entry: a − button, a number field (typed with the keyboard, in the locale's format), the unit
 * and a + button. The value is kept in hundredths ([valueX100]) so "1.5 cups" is 150.
 */
class NumberStepper @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val minus = iconButton(R.drawable.sw_ic_minus, R.string.sw_stepper_decrease)
    private val plus = iconButton(R.drawable.sw_ic_plus, R.string.sw_stepper_increase)
    private val input = AppCompatEditText(context).apply {
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SelfWatch_Title_Number)
        gravity = Gravity.CENTER
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        imeOptions = EditorInfo.IME_ACTION_DONE
        minEms = 3
        maxLines = 1
        contentDescription = context.getString(R.string.sw_stepper_amount)
    }
    private val unitView = context.swText(R.style.TextAppearance_SelfWatch_BodyLarge)
    private var updating = false

    /** The value in hundredths. Setting it updates the field. */
    var valueX100: Long = 100L
        set(v) {
            val clamped = v.coerceIn(minX100, maxX100)
            val changed = clamped != field
            field = clamped
            showValue()
            if (changed) onValueChange?.invoke(clamped)
        }

    /** Step for the − and + buttons, in hundredths (default 1.00). */
    var stepX100: Long = 100L

    /** Lowest allowed value in hundredths (default 0). */
    var minX100: Long = 0L

    /** Highest allowed value in hundredths. */
    var maxX100: Long = 999_999L

    /** Most fraction digits shown and accepted (0, 1 or 2). */
    var decimals: Int = 2
        set(v) {
            field = v.coerceIn(0, 2)
            showValue()
        }

    /** Unit after the field ("cups", "g"); null for none. */
    var unit: CharSequence? = null
        set(v) {
            field = v
            unitView.text = v
            val amount = context.getString(R.string.sw_stepper_amount)
            input.contentDescription = if (v.isNullOrEmpty()) amount else context.getString(R.string.sw_value_with_unit, amount, v)
        }

    /** Called with the new value whenever it changes (buttons or typing). */
    var onValueChange: ((Long) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        addView(minus, LayoutParams(wrap, wrap))
        addView(input, LayoutParams(0, wrap, 1f))
        addView(unitView, LayoutParams(wrap, wrap).apply { marginEnd = context.dp(8f).toInt() })
        addView(plus, LayoutParams(wrap, wrap))
        minus.setOnClickListener { step(-1) }
        plus.setOnClickListener { step(+1) }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val parsed = Fmt.parseX100(s?.toString().orEmpty()) ?: return
                val clamped = parsed.coerceIn(minX100, maxX100)
                if (clamped != valueX100) {
                    updating = true
                    valueX100 = clamped
                    updating = false
                }
            }
        })
        input.setOnFocusChangeListener { _, has -> if (!has) showValue(force = true) }

        context.withStyledAttributes(attrs, R.styleable.NumberStepper, defStyleAttr, 0) {
            unit = getText(R.styleable.NumberStepper_swUnit)
            stepX100 = getInt(R.styleable.NumberStepper_swStepX100, 100).toLong()
            minX100 = getInt(R.styleable.NumberStepper_swMinX100, 0).toLong()
            maxX100 = getInt(R.styleable.NumberStepper_swMaxX100, 999_999).toLong()
            decimals = getInt(R.styleable.NumberStepper_swDecimals, 2)
        }
        showValue(force = true)
    }

    private fun step(direction: Int) {
        valueX100 = StepperMath.step(valueX100, stepX100, direction, minX100, maxX100)
    }

    private fun showValue(force: Boolean = false) {
        if (updating && !force) return
        val text = Fmt.x100(valueX100, decimals)
        if (input.text?.toString() != text) {
            val was = updating
            updating = true
            input.setText(text)
            input.setSelection(text.length)
            updating = was
        }
        minus.isEnabled = valueX100 > minX100
        plus.isEnabled = valueX100 < maxX100
    }

    private fun iconButton(icon: Int, description: Int): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialIconButtonFilledTonalStyle).apply {
            setIconResource(icon)
            contentDescription = context.getString(description)
            val touch = resources.getDimensionPixelSize(R.dimen.sw_touch_target)
            minimumWidth = touch
            minimumHeight = touch
            minWidth = touch
            minHeight = touch
        }
}

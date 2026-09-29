package com.yinling.hotline

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating panel's vocabulary, in plain Views.
 *
 * The elder's home screen is Compose and the panel is a WindowManager overlay, so they cannot share
 * composables — but they must not look or read like two different products either. Everything visual
 * here comes from [Elder], the same numbers the home screen uses, so the two surfaces can only drift
 * if someone edits the tokens.
 *
 * Kept as small builders rather than a layout file because the panel is built per state and animated
 * while it changes shape.
 */
object OverlayUi {

    /** A line of text with the panel's type scale. */
    fun text(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int = Int.MAX_VALUE,
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        this.maxLines = maxLines
    }

    /** The card the panel's content sits in: same radius, padding and spacing as [ElderCard]. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    /** A tinted, rounded block used for the part of the message that matters. */
    fun tinted(context: Context, text: String, tint: Int, textColor: Int = Elder.ink.toArgb()): TextView =
        text(context, text, Elder.body.value, textColor).apply {
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = rounded(tint, dp(context, 12).toFloat())
        }

    /** `● 正在办` — the same shape as the home screen's status line. */
    fun statusRow(context: Context, phase: TaskPhase): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(dot(context, statusTone(phase).toArgb()))
        addView(
            text(context, statusText(phase), Elder.status.value, Elder.ink.toArgb(), bold = true).apply {
                setPadding(dp(context, 10), 0, 0, 0)
            },
        )
    }

    private fun dot(context: Context, color: Int): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(context, 16), dp(context, 16))
    }

    /** A primary (filled) or secondary (outlined) button, at the elder-facing touch size. */
    fun button(
        context: Context,
        label: String,
        primary: Boolean = true,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        textSize = Elder.body.value
        isAllCaps = false
        if (primary) {
            setBackgroundColor(Elder.brand.toArgb())
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        } else {
            background = rounded(android.graphics.Color.WHITE, dp(context, Elder.radius.value).toFloat()).apply {
                setStroke(dp(context, 1), Elder.line.toArgb())
            }
            setTextColor(Elder.brandDeep.toArgb())
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, if (primary) Elder.primaryHeight.value else Elder.secondaryHeight.value),
        ).apply { topMargin = dp(context, 8) }
    }

    /** One tappable answer, given the same weight as a primary action: answering is the whole job. */
    fun option(context: Context, label: String, onClick: () -> Unit): Button =
        button(context, label, primary = true, onClick = onClick)

    /** The bubble the panel collapses into. */
    fun bubble(context: Context, label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(context, 72)
            minHeight = dp(context, 38)
            setOnClickListener { onClick() }
            contentDescription = "展开银龄专线接线台"
        }

    /** The panel shell; its colour and radius are animated while it changes size. */
    fun shell(color: Int, radiusPx: Float, strokeColor: Int, strokePx: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            setStroke(strokePx, strokeColor)
        }

    fun rounded(color: Int, radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

    /** Vertical gap used between blocks, matching the home screen's spacing. */
    fun gap(context: Context, heightDp: Int = 8): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, heightDp),
        )
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    // Colours, re-exported so the panel never invents one of its own.
    val card: Int get() = Elder.card.toArgb()
    val brand: Int get() = Elder.brand.toArgb()
    val brandDeep: Int get() = Elder.brandDeep.toArgb()
    val ink: Int get() = Elder.ink.toArgb()
    val inkSoft: Int get() = Elder.inkSoft.toArgb()
    val attention: Int get() = Elder.attention.toArgb()
    val good: Int get() = Elder.good.toArgb()
    val problem: Int get() = Elder.problem.toArgb()
    val line: Int get() = Elder.line.toArgb()
    val doneTint: Int get() = 0xFFE8F6F3.toInt()
    val waitTint: Int get() = 0xFFFDF3E7.toInt()
    val panel: Int get() = 0xEEFFFFFF.toInt()
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

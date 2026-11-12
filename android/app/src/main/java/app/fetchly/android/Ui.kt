package app.fetchly.android

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Small UI kit: same look as the web app (slate neutrals, indigo accent, rounded cards). Light and dark follow the system. */

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.col(id: Int) = getColor(id)

fun Context.shape(fill: Int, radius: Int = 14, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(fill); cornerRadius = dp(radius).toFloat()
    if (stroke > 0) setStroke(dp(stroke), strokeColor)
}

fun Context.label(s: String, size: Float = 15f, color: Int = R.color.text, bold: Boolean = false, selectable: Boolean = false) =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(col(color)); setLineSpacing(0f, 1.15f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        if (selectable) setTextIsSelectable(true)
    }

enum class Kind { PRIMARY, TONAL, GHOST, DANGER, YOUTUBE, INSTAGRAM }

fun Context.button(text: String, kind: Kind = Kind.TONAL, height: Int = 48, onClick: () -> Unit) = Button(this).apply {
    this.text = text; isAllCaps = false; textSize = 16f; minHeight = 0; minimumHeight = 0; minWidth = 0
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    stateListAnimator = null; elevation = 0f
    val (fill, fg, stroke) = when (kind) {
        Kind.PRIMARY -> Triple(col(R.color.accent), col(R.color.on_accent), 0)
        Kind.TONAL -> Triple(col(R.color.tonal_bg), col(R.color.tonal_text), 0)
        Kind.GHOST -> Triple(col(R.color.card), col(R.color.text), col(R.color.border))
        Kind.YOUTUBE -> Triple(0xFFE62117.toInt(), 0xFFFFFFFF.toInt(), 0)
        Kind.INSTAGRAM -> Triple(0xFFC13584.toInt(), 0xFFFFFFFF.toInt(), 0)
        Kind.DANGER -> Triple(col(R.color.card), col(R.color.err_text), col(R.color.err_border))
    }
    setTextColor(fg)
    val base = shape(fill, 14, if (stroke != 0) 1 else 0, stroke)
    background = RippleDrawable(ColorStateList.valueOf(0x33808080), base, shape(fill, 14))
    layoutParams = LinearLayout.LayoutParams(-1, dp(height))
    setOnClickListener { onClick() }
}

/** A white (or dark) rounded card. Pass tone colors to make it red, green or amber. */
fun Context.card(bg: Int = R.color.card, border: Int = R.color.border, pad: Int = 16) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    background = shape(col(bg), 16, 1, col(border))
}

fun Context.tintCard(v: LinearLayout, bg: Int, border: Int) {
    v.background = shape(col(bg), 16, 1, col(border))
}

fun Context.progressBar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
    max = 100
    val fill = ClipDrawable(shape(col(R.color.accent), 4), Gravity.START, ClipDrawable.HORIZONTAL)
    progressDrawable = LayerDrawable(arrayOf(shape(col(R.color.track), 4), fill)).apply {
        setId(0, android.R.id.background); setId(1, android.R.id.progress)
    }
}

fun LinearLayout.add(v: View, top: Int = 0, w: Int = -1, h: Int = -2): View {
    addView(v, LinearLayout.LayoutParams(w, h).apply { topMargin = (top * resources.displayMetrics.density).toInt() })
    return v
}

/** Page column inside a scroll view that stays clear of the status bar, nav bar and keyboard. */
fun Activity.page(box: LinearLayout): ScrollView {
    box.setPadding(dp(20), dp(12), dp(20), dp(28))
    return ScrollView(this).apply {
        isFillViewport = true
        addView(box)
        setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(0, b.top, 0, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            }
            ins
        }
    }
}

/** Top bar for the inner screens: Back button on the left, title next to it. */
fun Activity.topBar(title: String): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    addView(button(getString(R.string.common_back), Kind.GHOST, 40) { finish() }.apply { setPadding(dp(16), 0, dp(16), 0) },
        LinearLayout.LayoutParams(-2, dp(40)))
    addView(label(title, 22f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
}

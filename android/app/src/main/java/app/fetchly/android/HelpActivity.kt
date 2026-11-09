package app.fetchly.android

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class HelpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        val scroll = ScrollView(this).apply { addView(box) }
        scroll.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); ins
        }

        fun text(s: String, size: Float, bold: Boolean = false, color: Int = 0xFF0F172A.toInt(), top: Int = 6): TextView =
            TextView(this).apply {
                this.text = s; textSize = size; setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(top), 0, 0)
                setTextIsSelectable(true)
            }

        box.addView(text("Help", 26f, true))
        box.addView(text("Find your problem below. Each topic says what you see, why, and how to fix it.", 15f, color = 0xFF475569.toInt()))

        val want = intent.getStringExtra("topic")
        var target: View? = null
        for (t in Help.topics) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFFE2E8F0.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
            }
            card.addView(text(t.title, 17f, true, 0xFF1E3A8A.toInt(), 0))
            if (t.simple.isNotEmpty()) card.addView(text("What you see: " + t.simple, 15f))
            card.addView(text("Why: " + t.why, 15f))
            card.addView(text("How to fix:", 15f, true, top = 10))
            t.fix.forEachIndexed { i, s -> card.addView(text("${i + 1}. $s", 15f)) }
            box.addView(card)
            if (t.id == want) target = card
        }
        setContentView(scroll)
        target?.let { c -> scroll.post { scroll.scrollTo(0, c.top) } }
    }
}

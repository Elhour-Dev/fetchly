package app.fetchly.android

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Help: one section per topic, each with short numbered steps. "How to" first, then "Fix a problem".
 * Works offline (all text is in the app). Opens the section of the failed download.
 */
class HelpActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = page(box)
        box.add(topBar("Help"))
        box.add(label("Tap a topic to open it.", 15f, R.color.muted), 14)

        val want = intent.getStringExtra("topic")
        var target: View? = null

        box.add(label("How to", 13f, R.color.muted, bold = true), 20)
        for (g in Help.guides) {
            val s = section(g.title, g.steps, null, g.note, g.id == want)
            box.add(s, 8)
            if (g.id == want) target = s
        }
        box.add(label("Fix a problem", 13f, R.color.muted, bold = true), 24)
        for (p in Help.problems) {
            val s = section(p.title, p.fix, p.why, null, p.id == want)
            box.add(s, 8)
            if (p.id == want) target = s
        }
        setContentView(scroll)
        target?.let { t -> scroll.post { scroll.scrollTo(0, t.top - dp(8)) } }
    }

    /** One topic: a title row that opens and closes it, then the steps. */
    private fun section(title: String, steps: List<String>, why: String?, note: String?, open: Boolean): LinearLayout {
        val card = card(pad = 0)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
            visibility = if (open) View.VISIBLE else View.GONE
        }
        val chevron = label(if (open) "▴" else "▾", 20f, R.color.muted)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minimumHeight = dp(52)
            addView(label(title, 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(chevron)
            setOnClickListener {
                val show = body.visibility != View.VISIBLE
                body.visibility = if (show) View.VISIBLE else View.GONE
                chevron.text = if (show) "▴" else "▾"
            }
        }
        card.addView(head)
        if (why != null) body.add(label(why, 15f, R.color.muted, selectable = true))
        steps.forEachIndexed { n, s ->
            body.add(label("${n + 1}. $s", 15f, selectable = true), if (n == 0 && why == null) 0 else 6)
        }
        if (note != null) {
            val tip = card(R.color.tip_bg, R.color.tip_border, 12)
            tip.addView(label(note, 14f, R.color.tip_text))
            body.add(tip, 12)
        }
        card.addView(body)
        if (open) tintCard(card, R.color.card, R.color.accent)
        return card
    }
}

package app.fetchly.android

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** Help: three expandable cards. Works offline (all text is in the app). Opens the card of the failed download. */
class HelpActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = page(box)
        box.add(topBar("Help"))
        box.add(label("Tap a card to open it. Each problem says what you see, why it happens, and how to fix it.", 15f, R.color.muted), 14)
        box.add(label(Help.COOKIE_NOTE, 15f, R.color.text, bold = true), 8)
        box.add(label("Playlists: paste a playlist link, tick the videos you want, pick one quality, then tap Download.\n" +
            "They are saved in Downloads, in a folder named after the playlist, with a number in front of each name.\n" +
            "Videos you already have, and private or deleted ones, are skipped. Use \"Retry failed\" at the end.", 14f, R.color.muted), 8)

        val want = intent.getStringExtra("topic")
        var target: View? = null
        var targetCard: LinearLayout? = null
        for (c in Help.cards) {
            val card = card(pad = 0)
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(16)); visibility = View.GONE
            }
            val chevron = label("▾", 20f, R.color.muted)
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                val t = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(c.title, 18f, bold = true))
                    addView(label(c.subtitle, 14f, R.color.muted))
                }
                addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                addView(chevron)
                setOnClickListener {
                    val open = body.visibility != View.VISIBLE
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    chevron.text = if (open) "▴" else "▾"
                }
            }
            card.addView(head)

            if (c.tip) {
                val tip = card(R.color.tip_bg, R.color.tip_border, 12)
                tip.addView(label(Help.TIP, 14f, R.color.tip_text))
                tip.addView(label(Help.COOKIE_NOTE, 14f, R.color.tip_text, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                body.add(tip)
            }
            for ((i, p) in c.problems.withIndex()) {
                val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                item.add(label(p.title, 16f, R.color.accent, bold = true))
                item.add(label("What you see", 13f, R.color.muted, bold = true), 8)
                item.add(label(p.see, 15f, selectable = true), 2)
                item.add(label("Why", 13f, R.color.muted, bold = true), 8)
                item.add(label(p.why, 15f, selectable = true), 2)
                item.add(label("How to fix", 13f, R.color.muted, bold = true), 8)
                p.fix.forEachIndexed { n, s -> item.add(label("${n + 1}. $s", 15f, selectable = true), 3) }
                body.add(item, if (i == 0 && !c.tip) 0 else 18)
                if (p.id == want) target = item
            }
            card.addView(body)
            box.add(card, 14)
            if (c.id == Help.cardOf(want)?.id) { body.visibility = View.VISIBLE; chevron.text = "▴"; targetCard = card }
        }
        setContentView(scroll)
        targetCard?.let { c -> scroll.post { scroll.scrollTo(0, c.top + (target?.top ?: 0) - dp(8)) } }
    }
}

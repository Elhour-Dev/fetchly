package app.fetchly.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** The list of finished downloads, kept on this phone only (latest 50). */
object History {
    class Entry(val id: Long, val title: String, val link: String, val label: String, val uri: String?, val mime: String?)

    private const val MAX = 50
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Entry> = try {
        val a = JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Entry(it.getLong("id"), it.getString("title"), it.getString("link"), it.optString("label"),
                it.optString("uri").ifEmpty { null }, it.optString("mime").ifEmpty { null })
        }
    } catch (e: Exception) { emptyList() }

    @Synchronized
    fun add(ctx: Context, title: String, link: String, label: String, uri: String?, mime: String?) {
        val e = JSONObject().put("id", System.currentTimeMillis()).put("title", title).put("link", link)
            .put("label", label).put("uri", uri ?: "").put("mime", mime ?: "")
        val a = JSONArray().put(e)
        all(ctx).take(MAX - 1).forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("link", it.link)
                .put("label", it.label).put("uri", it.uri ?: "").put("mime", it.mime ?: ""))
        }
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }

    @Synchronized
    fun remove(ctx: Context, id: Long) {
        val a = JSONArray()
        all(ctx).filter { it.id != id }.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("link", it.link)
                .put("label", it.label).put("uri", it.uri ?: "").put("mime", it.mime ?: ""))
        }
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }

    @Synchronized
    fun clear(ctx: Context) = prefs(ctx).edit().putString("list", "[]").apply()
}

/** History screen: open the file, download the link again, or remove an entry. */
class HistoryActivity : ThemedActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = page(box)
        box.add(topBar("History"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.add(list, 16)
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); Theme.syncIcon(this); render() }

    private fun render() {
        list.removeAllViews()
        val items = History.all(this)
        if (items.isEmpty()) {
            list.addView(label("Nothing here yet. Finished downloads show up in this list.", 16f, R.color.muted))
            return
        }
        items.forEach { e ->
            val c = card()
            c.addView(label(e.title, 16f, bold = true))
            c.add(label(listOf(e.label, hostOf(e.link)).filter { it.isNotEmpty() }.joinToString(" · "), 13f, R.color.muted), 2)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (e.uri != null) row.addView(button("Open", Kind.TONAL, 44) { open(e) },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            row.addView(button("Download again", Kind.TONAL, 44) { again(e) },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            row.addView(button("Remove", Kind.GHOST, 44) { History.remove(this, e.id); render() }, LinearLayout.LayoutParams(0, dp(44), 1f))
            c.add(row, 12)
            list.add(c, if (list.childCount == 0) 0 else 10)
        }
        list.add(button("Clear history", Kind.DANGER) { History.clear(this); render() }, 16)
    }

    private fun hostOf(link: String) = try { Uri.parse(link).host?.removePrefix("www.") ?: "" } catch (e: Exception) { "" }

    private fun open(e: History.Entry) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(e.uri), e.mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (x: Exception) {
            Toast.makeText(this, "Could not open the file. It may have been moved or deleted.", Toast.LENGTH_LONG).show()
        }
    }

    /** Hands the link to the main screen, the same way a link shared from another app arrives. */
    private fun again(e: History.Entry) {
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, e.link).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

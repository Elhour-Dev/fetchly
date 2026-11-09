package app.fetchly.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Settings: cookies (log in again, import, remove) and tools (yt-dlp version, update). */
class SettingsActivity : ThemedActivity() {
    private lateinit var cookieInfo: TextView
    private lateinit var remove: android.widget.Button
    private lateinit var version: TextView
    private lateinit var update: android.widget.Button
    @Volatile private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = page(box)
        box.add(topBar("Settings"))

        val ap = card()
        ap.addView(label("Appearance", 18f, bold = true))
        val cur = Theme.mode(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("system" to "Auto", "light" to "Light", "dark" to "Dark").forEachIndexed { i, (m, n) ->
            row.addView(button(n, if (m == cur) Kind.PRIMARY else Kind.GHOST, 44) {
                Theme.set(this, m); recreate()
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        ap.add(row, 12)
        box.add(ap, 16)

        val ck = card()
        ck.addView(label("Cookies", 18f, bold = true))
        cookieInfo = label("", 14f, R.color.muted)
        ck.add(cookieInfo, 4)
        ck.add(label("Only needed when a site asks for a login. " + Help.COOKIE_NOTE, 14f, R.color.muted), 6)
        ck.add(button("Log in again", Kind.PRIMARY) {
            val url = getSharedPreferences("prefs", MODE_PRIVATE).getString("login_url", null)
            startActivity(Intent(this, LoginActivity::class.java).putExtra("url", url))
        }, 14)
        ck.add(button("Import cookies.txt", Kind.TONAL) {
            @Suppress("DEPRECATION") startActivityForResult(Cookies.pickIntent(), 20)
        }, 8)
        remove = button("Remove cookies", Kind.DANGER) { Cookies.remove(this); refresh() } as android.widget.Button
        ck.add(remove, 8)
        box.add(ck, 16)

        val tl = card()
        tl.addView(label("Tools", 18f, bold = true))
        version = label("yt-dlp: starting…", 14f, R.color.muted)
        tl.add(version, 4)
        update = button("Update tools", Kind.TONAL) { updateTools() } as android.widget.Button
        tl.add(update, 14)
        box.add(tl, 16)

        setContentView(scroll)
        thread {
            try { Tools.init(this); val v = Tools.version(this); runOnUiThread { version.text = "yt-dlp: $v" } }
            catch (e: Exception) { runOnUiThread { version.text = "Tools could not start." } }
        }
    }

    override fun onResume() { super.onResume(); Theme.syncIcon(this); refresh() }

    private fun refresh() {
        val has = Tools.cookiesFile(this).exists()
        cookieInfo.text = if (has) "Saved on this phone. Kept private." else "None saved."
        remove.isEnabled = has; remove.alpha = if (has) 1f else 0.5f
        update.isEnabled = !updating && State.phase != State.Phase.RUNNING
        update.alpha = if (update.isEnabled) 1f else 0.5f
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val err = Cookies.import(this, uri)
        if (err == null) State.cookiesSaved = true
        Toast.makeText(this, err ?: "Cookies imported.", Toast.LENGTH_LONG).show()
        refresh()
    }

    // Keep the old yt-dlp if the update fails.
    private fun updateTools() {
        if (updating) return
        updating = true; refresh()
        version.text = "Updating yt-dlp…"
        thread {
            val msg = Tools.update(this).message
            runOnUiThread { updating = false; version.text = msg; refresh() }
        }
    }
}

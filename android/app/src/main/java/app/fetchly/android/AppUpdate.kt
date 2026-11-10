package app.fetchly.android

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import kotlin.concurrent.thread

/** Checks GitHub for a newer app version and offers the download link. Never blocks the app. */
object AppUpdate {
    private const val API = "https://api.github.com/repos/Elhour-Dev/fetchly/releases/latest"

    class Release(val tag: String, val url: String, val notes: String)

    /** "v1.2.0" -> [1,2,0]. True when [latest] is higher than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Latest release, or null when offline / no answer. Call on a background thread. */
    fun latest(): Release? = try {
        val c = java.net.URL(API).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Fetchy-Android")
        val body = if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        c.disconnect()
        body?.let {
            val j = org.json.JSONObject(it)
            if (j.optBoolean("draft") || j.optBoolean("prerelease")) null
            else Release(j.getString("tag_name"), j.getString("html_url"), j.optString("body").trim())
        }
    } catch (e: Exception) { null }

}

/** At every startup: if a newer release exists, show a pop-up. Silent on any failure. Never installs. */
fun Activity.checkAppUpdate() {
    thread {
        val rel = AppUpdate.latest() ?: return@thread
        val current = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }
        if (!AppUpdate.isNewer(rel.tag, current)) return@thread
        runOnUiThread { showAppUpdateDialog(rel) }
    }
}

private fun Activity.showAppUpdateDialog(rel: AppUpdate.Release) {
    if (isFinishing || isDestroyed) return
    val dlg = Dialog(this)
    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box = card(pad = 20)
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(label("New version ${rel.tag.removePrefix("v")}", 20f, bold = true))
    if (rel.notes.isNotEmpty()) {
        box.add(label("What's new", 15f, bold = true), 8)
        val scroll = android.widget.ScrollView(this).apply { addView(label(rel.notes, 14f, R.color.muted)) }
        box.add(scroll, 4, -1, dp(160))
    } else box.add(label("A newer version of Fetchy is available. Download opens the release page.", 15f, R.color.muted), 8)
    box.add(buttons, 18)
    fun add(v: View, top: Int) = buttons.add(v, top, -1, dp(48))
    add(button("Download", Kind.PRIMARY) {
        dlg.dismiss()
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.url))) } catch (e: Exception) { /* no browser */ }
    }, 0)
    add(button("Later", Kind.GHOST) { dlg.dismiss() }, 8)
    dlg.setContentView(box)
    dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dlg.show()
    dlg.window?.setLayout(
        (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT
    )
}

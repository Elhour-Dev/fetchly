package app.fetchly.android

import android.app.Activity
import android.app.Dialog
import android.content.Context
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
    private const val DAY = 24L * 60 * 60 * 1000
    @Volatile private var checked = false   // once per app start

    class Release(val tag: String, val url: String)

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
            else Release(j.getString("tag_name"), j.getString("html_url"))
        }
    } catch (e: Exception) { null }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    fun snoozed(ctx: Context, tag: String) = System.currentTimeMillis() < prefs(ctx).getLong("app_update_snooze_$tag", 0L)
    fun snooze(ctx: Context, tag: String) { prefs(ctx).edit().putLong("app_update_snooze_$tag", System.currentTimeMillis() + DAY).apply() }

    fun markChecked(): Boolean { val was = checked; checked = true; return !was }
}

/** Once per app start: if a newer version exists, show a pop-up with a Download button. */
fun Activity.checkAppUpdate() {
    if (!AppUpdate.markChecked()) return
    thread {
        val rel = AppUpdate.latest() ?: return@thread
        val current = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }
        if (!AppUpdate.isNewer(rel.tag, current) || AppUpdate.snoozed(this, rel.tag)) return@thread
        runOnUiThread { showAppUpdateDialog(rel) }
    }
}

private fun Activity.showAppUpdateDialog(rel: AppUpdate.Release) {
    if (isFinishing || isDestroyed) return
    val dlg = Dialog(this)
    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box = card(pad = 20)
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(label("Update available", 20f, bold = true))
    box.add(label("Fetchy ${rel.tag.removePrefix("v")} is ready. Download it and install it over this version.", 15f, R.color.muted), 8)
    box.add(buttons, 18)
    fun add(v: View, top: Int) = buttons.add(v, top, -1, dp(48))
    add(button("Download", Kind.PRIMARY) {
        AppUpdate.snooze(this, rel.tag); dlg.dismiss()
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.url))) } catch (e: Exception) { /* no browser */ }
    }, 0)
    add(button("Later", Kind.GHOST) { AppUpdate.snooze(this, rel.tag); dlg.dismiss() }, 8)
    dlg.setOnCancelListener { AppUpdate.snooze(this, rel.tag) }   // back button counts as "Later"
    dlg.setContentView(box)
    dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dlg.show()
    dlg.window?.setLayout(
        (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT
    )
}

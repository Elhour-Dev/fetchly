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

    /** [apk]: direct .apk link, or null if the release has none. */
    class Release(val tag: String, val url: String, val apk: String?, val notes: String)

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
            else {
                val assets = j.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", true) }
                    ?.optString("browser_download_url")?.takeIf { u -> u.isNotEmpty() }
                Release(j.getString("tag_name"), j.getString("html_url"), apk, j.optString("body").trim())
            }
        }
    } catch (e: Exception) { null }

    // A downloaded update waiting for the "install apps" permission (kept in memory only).
    @Volatile private var pending: Uri? = null

    /**
     * Opens the system installer for the downloaded update. The notification tap does nothing on some phones,
     * so Fetchy does it. If Fetchy may not install apps yet, the setting opens first; the install continues on return.
     */
    fun install(ctx: android.content.Context, uri: Uri) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            pending = uri
            android.widget.Toast.makeText(ctx, "Allow Fetchy to install updates, then come back.", android.widget.Toast.LENGTH_LONG).show()
            try {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { /* no settings screen: the file stays in Downloads */ }
            return
        }
        pending = null
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "Open the new Fetchy .apk from your Downloads to install it.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Called when the app comes back to the front: finish an install that was waiting for the permission. */
    fun installPending(ctx: android.content.Context) {
        val u = pending ?: return
        if (ctx.packageManager.canRequestPackageInstalls()) install(ctx, u)
    }
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
    } else box.add(label("A newer version of Fetchy is available. Download saves the app to your Downloads.", 15f, R.color.muted), 8)
    box.add(buttons, 18)
    fun add(v: View, top: Int) = buttons.add(v, top, -1, dp(48))
    add(button("Download", Kind.PRIMARY) {
        dlg.dismiss()
        if (!downloadApk(rel)) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.url))) } catch (e: Exception) { /* no browser */ }
    }, 0)
    add(button("Later", Kind.GHOST) { dlg.dismiss() }, 8)
    dlg.setContentView(box)
    dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dlg.show()
    dlg.window?.setLayout(
        (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT
    )
}

/** Downloads the .apk with the system download manager, then opens the installer by itself. False if not possible. */
private fun Activity.downloadApk(rel: AppUpdate.Release): Boolean {
    val link = rel.apk ?: return false
    return try {
        val name = "Fetchy-${rel.tag.removePrefix("v")}.apk"
        val req = android.app.DownloadManager.Request(Uri.parse(link))
            .setTitle(name)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
        val dm = getSystemService(android.app.DownloadManager::class.java)
        val id = dm.enqueue(req)
        val app = applicationContext
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                if (i.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try { app.unregisterReceiver(this) } catch (e: Exception) { }
                val uri = try { dm.getUriForDownloadedFile(id) } catch (e: Exception) { null }   // null when the download failed
                if (uri != null) AppUpdate.install(app, uri)
            }
        }
        val filter = android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, filter, android.content.Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(rx, filter)
        android.widget.Toast.makeText(this, "Downloading $name… The install screen opens when it is done.", android.widget.Toast.LENGTH_LONG).show()
        true
    } catch (e: Exception) { false }
}

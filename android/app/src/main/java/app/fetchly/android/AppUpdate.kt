package app.fetchly.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kotlin.concurrent.thread

/** Checks GitHub for a newer app version (start screen). Never blocks the app for long. */
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
        c.connectTimeout = 5000; c.readTimeout = 5000
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

/**
 * Start screen, shown before the main page once per app start: checks for a newer version.
 * Update or Skip when there is one. No answer within a few seconds, offline or any error: goes on to the app ([onDone]).
 */
fun Activity.showStartScreen(onDone: () -> Unit) {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL }
    val scroll = page(box)
    box.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_logo) }, LinearLayout.LayoutParams(dp(64), dp(64)))
    val title = label("Fetchy", 24f, bold = true).apply { gravity = Gravity.CENTER }
    box.add(title, 14, -2)
    val status = label("Checking for updates…", 15f, R.color.muted).apply { gravity = Gravity.CENTER }
    box.add(status, 6, -2)
    val spin = android.widget.ProgressBar(this).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
    }
    box.add(spin, 18, dp(32), dp(32))
    setContentView(scroll)

    var finished = false
    val giveUp = Runnable { if (!finished) { finished = true; onDone() } }   // slow network: do not keep the user waiting
    box.postDelayed(giveUp, 6000)
    fun done() { if (!finished) { finished = true; box.removeCallbacks(giveUp); onDone() } }

    thread {
        val rel = AppUpdate.latest()
        val current = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }
        runOnUiThread {
            if (finished || isFinishing || isDestroyed) return@runOnUiThread
            if (rel == null || !AppUpdate.isNewer(rel.tag, current)) { done(); return@runOnUiThread }
            box.removeCallbacks(giveUp)
            title.text = "New version ${rel.tag.removePrefix("v")}"
            status.text = "Update now, or skip and use this version."
            spin.visibility = View.GONE
            val area = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (rel.notes.isNotEmpty()) {
                val notes = card(pad = 12)
                notes.addView(label("What's new", 15f, bold = true))
                notes.add(android.widget.ScrollView(this).apply { addView(label(rel.notes, 14f, R.color.muted)) }, 4, -1, dp(150))
                area.add(notes)
            }
            area.add(button("Update", Kind.PRIMARY) {
                if (!downloadApk(rel)) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.url))) } catch (e: Exception) { /* no browser */ }
                done()
            }, 16, -1, dp(52))
            area.add(button("Skip", Kind.GHOST) { done() }, 8, -1, dp(52))
            box.add(area, 18, -1)
        }
    }
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

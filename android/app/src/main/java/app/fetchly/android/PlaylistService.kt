package app.fetchly.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlin.concurrent.thread
import kotlin.random.Random

/** Downloads the playlist queue in the background: a few videos at once, with a progress notification. */
class PlaylistService : Service() {
    companion object {
        const val EXTRA_CMD = "cmd"   // "start", "pause", "resume", "cancel" or "retry"
        const val EXTRA_N = "n"       // video number, or -1 for all
        private const val PARALLEL = 2
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 3
        private const val DONE_ID = 4
        private val lock = Any()
        private var workers = 0
        private var first = true
        @Volatile private var lastNotif = 0L

        fun send(ctx: Context, cmd: String, n: Int = -1) =
            ctx.startForegroundService(Intent(ctx, PlaylistService::class.java).putExtra(EXTRA_CMD, cmd).putExtra(EXTRA_N, n))
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val cmd = intent?.getStringExtra(EXTRA_CMD)
        if (cmd != null && cmd != "start") apply(cmd, intent!!.getIntExtra(EXTRA_N, -1))
        kick()
        ended()
        return START_NOT_STICKY
    }

    private fun kill(item: PlItem) { try { YoutubeDL.getInstance().destroyProcessById("pl-${item.e.n}") } catch (_: Exception) {} }
    private fun tempDir(item: PlItem) = File(cacheDir, "pl/${item.e.n}")

    private fun apply(cmd: String, n: Int) {
        synchronized(lock) {
            for (it in Pl.items) {
                if (n != -1 && it.e.n != n) continue
                val busy = it.state == PlItem.S.STARTING || it.state == PlItem.S.DOWNLOADING
                when (cmd) {
                    "pause" -> if (it.state == PlItem.S.QUEUED) it.state = PlItem.S.PAUSED else if (busy) { it.stop = "pause"; kill(it) }
                    "resume" -> if (it.state == PlItem.S.PAUSED) it.state = PlItem.S.QUEUED
                    "cancel" -> if (it.state == PlItem.S.QUEUED || it.state == PlItem.S.PAUSED) {
                        tempDir(it).deleteRecursively(); it.state = PlItem.S.CANCELLED; it.percent = 0
                    } else if (busy) { it.stop = "cancel"; kill(it) }
                    "retry" -> if (it.state == PlItem.S.FAILED || it.state == PlItem.S.CANCELLED) { it.state = PlItem.S.QUEUED; it.error = null }
                }
            }
        }
        Pl.changed()
    }

    private fun kick() {
        synchronized(lock) {
            val queued = Pl.items.count { it.state == PlItem.S.QUEUED }
            while (workers < minOf(PARALLEL, queued)) { workers++; thread { worker() } }
        }
    }

    private fun worker() {
        while (true) {
            var item: PlItem? = null
            var wait = 0L
            synchronized(lock) {
                item = Pl.items.firstOrNull { it.state == PlItem.S.QUEUED }
                if (item == null) workers--
                else {
                    item!!.state = PlItem.S.STARTING; item!!.stop = null; item!!.error = null
                    // A small random pause between requests, so the site is not hammered.
                    wait = if (first) 0 else Random.nextLong(1000, 3000)
                    first = false
                }
            }
            val it = item
            if (it == null) { main.post { ended() }; return }
            Pl.changed()
            try { Thread.sleep(wait); download(it) }
            catch (e: Exception) { it.state = PlItem.S.FAILED; it.error = "Something went wrong. Try again." }
            it.stop = null
            Pl.changed(); showProgress(true)
        }
    }

    private fun download(item: PlItem) {
        val e = item.e
        val dir = tempDir(item).apply { mkdirs() }
        val pid = "pl-${e.n}"
        val stem = "%03d - %s".format(e.n, Pl.safeName(e.title).ifEmpty { "video" })
        try {
            if (item.stop != null) throw IllegalStateException("stopped")
            Tools.init(this)
            val req = YoutubeDLRequest(e.url).apply {
                addOption("--no-playlist")
                addOption("-f", Pl.format)
                if (Pl.audio) { addOption("-x"); addOption("--audio-format", "mp3"); addOption("--audio-quality", "192K") }
                else addOption("--merge-output-format", "mp4")
                addOption("--no-mtime")
                addOption("-o", dir.absolutePath + "/" + stem.replace("%", "%%") + ".%(ext)s")
                val c = Tools.cookiesFile(this@PlaylistService)
                if (c.exists()) addOption("--cookies", c.absolutePath)
            }
            YoutubeDL.getInstance().execute(req, pid) { p, _, line ->
                if (item.stop != null) kill(item)
                val l = line ?: ""
                if (l.contains("[Merger]") || l.contains("[ExtractAudio]") || l.contains("[VideoRemuxer]")) item.state = PlItem.S.PROCESSING
                else if (p >= 0 && item.state != PlItem.S.PROCESSING) {
                    val pct = p.toInt().coerceIn(0, 100)
                    if (item.state != PlItem.S.DOWNLOADING || pct != item.percent) {
                        item.state = PlItem.S.DOWNLOADING; item.percent = pct
                        Pl.changed(); showProgress(false)
                    }
                }
            }
            if (item.stop != null) throw IllegalStateException("stopped")
            val file = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() } ?: throw IllegalStateException("No video file was made.")
            item.state = PlItem.S.PROCESSING; Pl.changed()
            save(file)
            Pl.archiveAdd(this, e.key); e.have = true
            dir.deleteRecursively()
            item.percent = 100; item.state = PlItem.S.DONE
        } catch (ex: Exception) {
            when (item.stop) {
                "pause" -> item.state = PlItem.S.PAUSED               // the partial file stays, so Resume continues
                "cancel" -> { dir.deleteRecursively(); item.percent = 0; item.state = PlItem.S.CANCELLED }
                else -> {
                    val raw = ex.message ?: ex.toString()
                    val reason = Pl.skipReason(raw)
                    item.error = reason ?: Help.find(raw, e.url)?.simple?.ifEmpty { null } ?: "The download did not work."
                    item.state = if (reason != null) PlItem.S.SKIPPED else PlItem.S.FAILED
                }
            }
        }
    }

    /** Copy into Downloads/<playlist name>/. */
    private fun save(f: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "video/mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Pl.folder())
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("Could not save to the Downloads folder.")
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
        contentResolver.update(uri, cv, null, null)
    }

    /** Stop the service when nothing is running. Shows the end summary unless videos are only paused. */
    private fun ended() {
        synchronized(lock) { if (workers > 0 || Pl.active()) return }
        val items = Pl.items
        if (items.isNotEmpty() && items.none { it.state == PlItem.S.PAUSED }) {
            fun count(s: PlItem.S) = items.count { it.state == s }
            val text = "${count(PlItem.S.DONE)} done · ${count(PlItem.S.FAILED)} failed · ${count(PlItem.S.SKIPPED)} skipped"
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Playlist finished").setContentText(text).setAutoCancel(true)
                .setContentIntent(openApp()).build()
            getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
        }
        first = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 2, Intent(this, PlaylistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(label: String, cmd: String, code: Int) = Notification.Action.Builder(null, label, PendingIntent.getService(
        this, code, Intent(this, PlaylistService::class.java).putExtra(EXTRA_CMD, cmd),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

    private fun notif(): Notification {
        val items = Pl.items
        val total = items.size.coerceAtLeast(1)
        val closed = items.count { !it.open }
        val parts = closed * 100 + items.filter { it.open }.sumOf { it.percent }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(Pl.title.ifEmpty { "Playlist" }).setContentText("$closed of ${items.size} finished")
            .setProgress(100, parts / total, false).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(action("Pause all", "pause", 3)).addAction(action("Cancel all", "cancel", 4))
            .build()
    }

    /** Update the notification, at most once a second unless [force]. */
    private fun showProgress(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotif < 1000) return
        lastNotif = now
        synchronized(lock) { if (workers == 0) return }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif())
    }
}

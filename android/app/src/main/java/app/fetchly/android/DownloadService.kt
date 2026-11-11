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
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs the downloads of [State.dls]. A few run at the same time; the others wait their turn (phase QUEUED),
 * so the phone does not slow down. Each one has its own folder and can be cancelled on its own.
 */
class DownloadService : Service() {
    companion object {
        const val EXTRA_ID = "id"           // the Dl to start (or to cancel, with ACTION_CANCEL)
        const val ACTION_CANCEL = "cancel"
        const val MAX_AT_ONCE = 2
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val DONE_CHANNEL = "done"
        private val pool = Executors.newFixedThreadPool(MAX_AT_ONCE)

        private fun pid(d: Dl) = "dl-${d.id}"

        /** Start a download that was just added to [State.dls]. */
        fun start(ctx: Context, d: Dl) =
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).putExtra(EXTRA_ID, d.id))

        /** Stop one download. One that is still waiting is removed at once; the others go on. */
        fun cancel(d: Dl) {
            synchronized(d) {
                d.cancelled = true
                if (d.phase == Dl.Phase.QUEUED) { State.dls.remove(d); State.changed(); return }
            }
            try { YoutubeDL.getInstance().destroyProcessById(pid(d)) } catch (_: Exception) {}
        }
    }

    @Volatile private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(NOTIF_ID, summary(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val id = intent?.getIntExtra(EXTRA_ID, -1) ?: -1
        val d = State.dls.firstOrNull { it.id == id }
        if (intent?.action == ACTION_CANCEL) d?.let { cancel(it) }
        else if (d != null && d.phase == Dl.Phase.QUEUED) pool.execute { work(d) }
        finishIfIdle()
        return START_NOT_STICKY
    }

    /** Remove the ongoing notification and stop when nothing runs or waits any more. */
    private fun finishIfIdle() {
        synchronized(DownloadService::class.java) {
            if (State.busy()) { notify(summary()); return }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStart)   // a newer start keeps the service running
        }
    }

    private fun work(d: Dl) {
        synchronized(d) {
            if (d.cancelled || d.phase != Dl.Phase.QUEUED) return
            d.phase = Dl.Phase.RUNNING
        }
        val url = d.url
        val format = d.format
        val audio = d.audio
        val codec = d.codec
        val label = d.label.ifEmpty { if (audio) "Audio" else "Best quality" }
        val subs = d.subs
        val subsAuto = d.subsAuto
        val playlist = false   // whole playlists have their own screen and service
        val dir = File(cacheDir, "job-${d.id}")
        d.stage = State.Stage.FETCH; d.percent = 0; d.text = "Fetching video info…"
        State.changed(); notify(summary())
        try {
            dir.deleteRecursively(); dir.mkdirs()
            Tools.init(this)
            // x = the extras: cover art and tags for audio, embedded subtitles for video.
            fun request(fmt: String, aud: Boolean, x: Boolean, info: File? = null) =
                (if (info != null) YoutubeDLRequest(emptyList()).apply { addOption("--load-info-json", info.absolutePath) }
                else YoutubeDLRequest(url)).apply {
                if (playlist) { addOption("--yes-playlist"); addOption("--ignore-errors") } else addOption("--no-playlist")
                addOption("-f", fmt)
                if (aud) {
                    addOption("-x"); addOption("--audio-format", codec)
                    if (codec == "mp3") addOption("--audio-quality", "192K")
                    if (x) {
                        addOption("--embed-metadata")
                        if (codec == "mp3") { addOption("--embed-thumbnail"); addOption("--convert-thumbnails", "jpg") }
                    }
                } else {
                    addOption("--merge-output-format", "mp4")
                    if (x && subs != null) {
                        addOption("--embed-subs"); addOption("--sub-langs", subs); addOption("--convert-subs", "srt")
                        if (subsAuto) addOption("--write-auto-subs")
                    }
                }
                addOption("--no-mtime")
                addOption("--socket-timeout", "60")
                addOption("--trim-filenames", "120")
                addOption("-o", dir.absolutePath + (if (playlist) "/%(playlist_index)03d - %(title).70s [%(id)s].%(ext)s" else "/%(title).80s [%(id)s].%(ext)s"))
                val c = Tools.cookiesFile(this@DownloadService)
                if (c.exists()) addOption("--cookies", c.absolutePath)
            }
            var last = -1
            val progress: (Float, Long, String?) -> Unit = { p, _, line ->
                val l = line ?: ""
                Regex("Downloading (?:item|video) (\\d+) of (\\d+)").find(l)?.let {
                    d.item = "Video ${it.groupValues[1]} of ${it.groupValues[2]} · "
                    d.stage = State.Stage.FETCH; last = -1
                }
                if (l.contains("[Merger]") || l.contains("[ExtractAudio]") || l.contains("[VideoRemuxer]") || l.contains("[ffmpeg]")) {
                    if (d.stage != State.Stage.FINISH) {
                        d.stage = State.Stage.FINISH; d.percent = 100
                        d.text = "Finishing up…"; State.changed()
                        notify(summary())
                    }
                } else if (d.stage != State.Stage.FINISH && p >= 0) {
                    val pct = p.toInt().coerceIn(0, 100)
                    if (d.stage != State.Stage.DOWNLOAD || pct != last) {
                        last = pct
                        d.stage = State.Stage.DOWNLOAD   // the real download has started
                        d.percent = pct
                        d.text = "Downloading… $pct%"
                        State.changed()
                        notify(summary())
                    }
                }
            }
            fun execute(req: YoutubeDLRequest) {
                if (d.cancelled) throw IllegalStateException("Cancelled")
                YoutubeDL.getInstance().execute(req, pid(d), progress)
            }
            // Use the answer saved by the quality picker, so the site is not asked twice. If that fails, ask normally.
            val info = d.info?.takeIf { it.exists() }
            fun runOnce(fmt: String, aud: Boolean, x: Boolean) {
                if (info != null) {
                    try { execute(request(fmt, aud, x, info)); return }
                    catch (e: Exception) {
                        if (d.cancelled) throw e
                        dir.deleteRecursively(); dir.mkdirs(); d.stage = State.Stage.FETCH; d.percent = 0; last = -1
                    }
                }
                execute(request(fmt, aud, x))
            }
            // Cover art / subtitles are a bonus: if adding them fails, download again without them.
            val extras = !playlist && (audio || subs != null)
            fun run(fmt: String, aud: Boolean) {
                if (!extras) { runOnce(fmt, aud, false); return }
                try { runOnce(fmt, aud, true) }
                catch (e: Exception) {
                    if (d.cancelled) throw e
                    dir.deleteRecursively(); dir.mkdirs(); d.stage = State.Stage.FETCH; d.percent = 0; last = -1
                    d.text = "Extras did not work. Downloading without them…"; State.changed()
                    runOnce(fmt, aud, false)
                }
            }
            try {
                run(format, audio)
            } catch (e: Exception) {
                // The chosen quality is not available for this video: use Best instead of failing.
                val raw = e.message ?: ""
                if (d.cancelled || audio || format == Quality.BEST || Help.find(raw, url)?.id != "g_format") throw e
                dir.deleteRecursively(); dir.mkdirs()
                d.stage = State.Stage.FETCH; d.percent = 0; last = -1
                d.text = "That quality is not available. Trying the best quality…"; State.changed()
                run(Quality.BEST, false)
            }
            if (d.cancelled) throw IllegalStateException("Cancelled")
            val sideFiles = setOf("jpg", "jpeg", "png", "webp", "srt", "vtt", "ass", "json")
            val files = dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && it.extension.lowercase() !in sideFiles }
                ?.sortedBy { it.name }.orEmpty()
            if (files.isEmpty()) throw IllegalStateException("No video file was made (no video found at this link).")
            d.stage = State.Stage.FINISH
            d.item = ""
            d.text = "Finishing up…"; d.percent = 100; State.changed()
            var openUri: Uri? = null
            var openMime: String? = null
            val name = if (playlist) { files.forEach { saveToDownloads(it) }; "${files.size} files" }
                else {
                    val f = files.maxByOrNull { it.length() }!!
                    val saved = saveToDownloads(f)
                    openUri = saved.uri; openMime = saved.mime
                    f.name
                }
            val title = if (playlist) "Playlist · $name" else name.substringBeforeLast('.').replace(Regex(" \\[[^\\]]+\\]$"), "")
            val tag = if (subs != null && !audio) "$label + subtitles" else label
            History.add(this, title, url, tag, openUri?.toString(), openMime)
            d.text = "Done. Saved in Downloads: $name"
            d.phase = Dl.Phase.DONE
            notifyDone(d, "Download finished", name, openUri, openMime)
        } catch (e: Exception) {
            if (d.cancelled) {
                State.dls.remove(d)   // a cancelled download leaves no card behind
            } else {
                val raw = (e.message ?: e.toString()).trim()
                val topic = Help.find(raw, url)
                d.failedStage = d.stage
                d.helpTopic = topic?.id
                d.needsLogin = topic?.login == true
                d.errorShort = topic?.simple?.ifEmpty { null } ?: "The download did not work."
                d.errorDetails = raw
                d.sessionCode = 0
                val base = Cookies.baseDomain(Cookies.hostOf(url))
                if (topic?.login == true && base == "instagram.com" && Cookies.savedHasSession(this, base))
                    d.sessionCode = Cookies.checkInstagram(this)
                d.text = ""
                d.phase = Dl.Phase.ERROR
                notifyDone(d, "Download failed", d.errorShort)
            }
        } finally {
            dir.deleteRecursively()
            d.info?.delete()
            State.changed()
            finishIfIdle()
        }
    }

    private class Saved(val name: String, val uri: Uri, val mime: String)

    private fun saveToDownloads(f: File): Saved {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "video/mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("Could not save to the Downloads folder.")
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
        contentResolver.update(uri, cv, null, null)
        return Saved(f.name, uri, mime)
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** One ongoing notification for all downloads. Cancel is offered when only one is going. */
    private fun summary(): Notification {
        val act = State.dls.filter { it.active }
        val running = act.filter { it.phase == Dl.Phase.RUNNING }
        val waiting = act.size - running.size
        val one = act.singleOrNull()?.takeIf { it.phase == Dl.Phase.RUNNING }
        val text = when {
            one != null -> one.text.ifEmpty { "Starting…" }
            act.isEmpty() -> "Starting…"
            else -> "${running.size} downloading" + (if (waiting > 0) " · $waiting waiting" else "")
        }
        val determinate = one != null && one.stage == State.Stage.DOWNLOAD
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Fetchy").setContentText(text)
            .setProgress(100, if (determinate) one!!.percent else 0, !determinate).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        act.singleOrNull()?.let { d ->
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, d.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        }
        return b.build()
    }

    private fun notify(n: Notification) {
        if (State.busy()) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    /** Sound + banner when a download ends (can be turned off in Settings). Tapping it opens the file, or the app. */
    private fun notifyDone(d: Dl, title: String, text: String, uri: Uri? = null, mime: String? = null) {
        if (!Quality.notifyDone(this)) return
        val tap = if (uri != null) PendingIntent.getActivity(
            this, 1000 + d.id, Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ) else openApp()
        val n = Notification.Builder(this, DONE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setContentIntent(tap).build()
        getSystemService(NotificationManager::class.java).notify(1000 + d.id, n)   // one per download
    }
}

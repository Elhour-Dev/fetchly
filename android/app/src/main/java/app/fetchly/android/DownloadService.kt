package app.fetchly.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
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
import kotlin.concurrent.thread

class DownloadService : Service() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_FORMAT = "format"   // yt-dlp format selector from the quality picker (null = Best)
        const val EXTRA_AUDIO = "audio"     // true = extract MP3
        const val EXTRA_USE_INFO = "use_info" // true = reuse the video info saved by the quality picker
        const val EXTRA_PLAYLIST = "playlist" // true = download every video of the playlist
        const val EXTRA_CODEC = "codec"     // audio file type: "mp3" or "m4a"
        const val EXTRA_LABEL = "label"     // quality name, shown in the history
        const val EXTRA_SUBS = "subs"       // subtitle language to embed (null = none)
        const val EXTRA_SUBS_AUTO = "subs_auto" // true = that language is an automatic caption
        const val ACTION_CANCEL = "cancel"
        private const val PID = "fetchly-download"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
        private const val DONE_CHANNEL = "done"
        @Volatile private var working = false
    }

    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(NOTIF_ID, notif("Starting…", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            try { YoutubeDL.getInstance().destroyProcessById(PID) } catch (_: Exception) {}
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null || working) {
            if (!working) stopSelf()
            return START_NOT_STICKY
        }
        working = true
        cancelled = false
        State.phase = State.Phase.RUNNING
        State.percent = 0
        State.failedUrl = url
        State.stage = State.Stage.FETCH
        State.failedStage = null
        State.text = "Fetching video info…"
        State.changed()
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: Quality.BEST
        val audio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        val useInfo = intent.getBooleanExtra(EXTRA_USE_INFO, false)
        val pl = intent.getBooleanExtra(EXTRA_PLAYLIST, false)
        State.item = ""
        val codec = intent.getStringExtra(EXTRA_CODEC) ?: "mp3"
        val label = intent.getStringExtra(EXTRA_LABEL) ?: if (audio) "Audio" else "Best quality"
        val subs = intent.getStringExtra(EXTRA_SUBS)
        val subsAuto = intent.getBooleanExtra(EXTRA_SUBS_AUTO, false)
        thread { work(url, format, audio, useInfo && !pl, pl, codec, label, subs, subsAuto) }
        return START_NOT_STICKY
    }

    private fun work(url: String, format: String, audio: Boolean, useInfo: Boolean, playlist: Boolean = false,
                     codec: String = "mp3", label: String = "", subs: String? = null, subsAuto: Boolean = false) {
        val dir = File(cacheDir, "job")
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
                addOption("--trim-filenames", "120")
                addOption("-o", dir.absolutePath + (if (playlist) "/%(playlist_index)03d - %(title).70s [%(id)s].%(ext)s" else "/%(title).80s [%(id)s].%(ext)s"))
                val c = Tools.cookiesFile(this@DownloadService)
                if (c.exists()) addOption("--cookies", c.absolutePath)
            }
            var last = -1
            val progress: (Float, Long, String?) -> Unit = { p, _, line ->
                val l = line ?: ""
                Regex("Downloading (?:item|video) (\\d+) of (\\d+)").find(l)?.let {
                    State.item = "Video ${it.groupValues[1]} of ${it.groupValues[2]} · "
                    State.stage = State.Stage.FETCH; last = -1
                }
                if (l.contains("[Merger]") || l.contains("[ExtractAudio]") || l.contains("[VideoRemuxer]") || l.contains("[ffmpeg]")) {
                    if (State.stage != State.Stage.FINISH) {
                        State.stage = State.Stage.FINISH; State.percent = 100
                        State.text = "Finishing up…"; State.changed()
                        notify(notif("Finishing up…", 0, true))
                    }
                } else if (State.stage != State.Stage.FINISH && p >= 0) {
                    val pct = p.toInt().coerceIn(0, 100)
                    if (State.stage != State.Stage.DOWNLOAD || pct != last) {
                        last = pct
                        State.stage = State.Stage.DOWNLOAD   // the real download has started
                        State.percent = pct
                        State.text = "Downloading… $pct%"
                        State.changed()
                        notify(notif("Downloading… $pct%", pct, false))
                    }
                }
            }
            // Use the answer saved by the quality picker, so the site is not asked twice. If that fails, ask normally.
            val info = if (useInfo) Quality.infoFile(this).takeIf { it.exists() } else null
            fun runOnce(fmt: String, aud: Boolean, x: Boolean) {
                if (info != null) {
                    try { YoutubeDL.getInstance().execute(request(fmt, aud, x, info), PID, progress); return }
                    catch (e: Exception) {
                        if (cancelled) throw e
                        dir.deleteRecursively(); dir.mkdirs(); State.stage = State.Stage.FETCH; State.percent = 0; last = -1
                    }
                }
                YoutubeDL.getInstance().execute(request(fmt, aud, x), PID, progress)
            }
            // Cover art / subtitles are a bonus: if adding them fails, download again without them.
            val extras = !playlist && (audio || subs != null)
            fun run(fmt: String, aud: Boolean) {
                if (!extras) { runOnce(fmt, aud, false); return }
                try { runOnce(fmt, aud, true) }
                catch (e: Exception) {
                    if (cancelled) throw e
                    dir.deleteRecursively(); dir.mkdirs(); State.stage = State.Stage.FETCH; State.percent = 0; last = -1
                    State.text = "Extras did not work. Downloading without them…"; State.changed()
                    runOnce(fmt, aud, false)
                }
            }
            try {
                run(format, audio)
            } catch (e: Exception) {
                // The chosen quality is not available for this video: use Best instead of failing.
                val raw = e.message ?: ""
                if (cancelled || audio || format == Quality.BEST || Help.find(raw, url)?.id != "g_format") throw e
                dir.deleteRecursively(); dir.mkdirs()
                State.stage = State.Stage.FETCH; State.percent = 0; last = -1
                State.text = "That quality is not available. Trying the best quality…"; State.changed()
                run(Quality.BEST, false)
            }
            if (cancelled) throw IllegalStateException("Cancelled")
            val sideFiles = setOf("jpg", "jpeg", "png", "webp", "srt", "vtt", "ass", "json")
            val files = dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && it.extension.lowercase() !in sideFiles }
                ?.sortedBy { it.name }.orEmpty()
            if (files.isEmpty()) throw IllegalStateException("No video file was made (no video found at this link).")
            State.stage = State.Stage.FINISH
            State.item = ""
            State.text = "Finishing up…"; State.percent = 100; State.changed()
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
            State.phase = State.Phase.DONE
            State.text = "Done. Saved in Downloads: $name"
            State.changed()
            notifyDone("Download finished", name, openUri, openMime)
        } catch (e: Exception) {
            if (cancelled) {
                State.phase = State.Phase.IDLE; State.text = "Cancelled."
            } else {
                val raw = (e.message ?: e.toString()).trim()
                val topic = Help.find(raw, url)
                State.phase = State.Phase.ERROR
                State.failedStage = State.stage
                State.helpTopic = topic?.id
                State.needsLogin = topic?.login == true
                State.errorShort = topic?.simple?.ifEmpty { null } ?: "The download did not work."
                State.errorDetails = raw
                State.sessionCode = 0
                val base = Cookies.baseDomain(Cookies.hostOf(url))
                if (topic?.login == true && base == "instagram.com" && Cookies.savedHasSession(this, base))
                    State.sessionCode = Cookies.checkInstagram(this)
                State.text = ""
                notifyDone("Download failed", State.errorShort)
            }
            State.changed()
        } finally {
            working = false
            dir.deleteRecursively()
            Quality.infoFile(this).delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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

    private fun notif(text: String, pct: Int, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Fetchy").setContentText(text)
            .setProgress(100, pct, indeterminate).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    private fun notify(n: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)

    /** Sound + banner when a download ends (can be turned off in Settings). Tapping it opens the file, or the app. */
    private fun notifyDone(title: String, text: String, uri: Uri? = null, mime: String? = null) {
        if (!Quality.notifyDone(this)) return
        val tap = if (uri != null) PendingIntent.getActivity(
            this, 2, Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ) else openApp()
        val n = Notification.Builder(this, DONE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setContentIntent(tap).build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
    }
}

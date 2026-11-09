package app.fetchly.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
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
        const val ACTION_CANCEL = "cancel"
        private const val PID = "fetchly-download"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
        @Volatile private var working = false
    }

    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
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
        thread { work(url, format, audio) }
        return START_NOT_STICKY
    }

    private fun work(url: String, format: String, audio: Boolean) {
        val dir = File(cacheDir, "job")
        try {
            dir.deleteRecursively(); dir.mkdirs()
            Tools.init(this)
            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("-f", format)
                if (audio) { addOption("-x"); addOption("--audio-format", "mp3"); addOption("--audio-quality", "192K") }
                else addOption("--merge-output-format", "mp4")
                addOption("--no-mtime")
                addOption("--trim-filenames", "120")
                addOption("-o", dir.absolutePath + "/%(title).80s [%(id)s].%(ext)s")
                val c = Tools.cookiesFile(this@DownloadService)
                if (c.exists()) addOption("--cookies", c.absolutePath)
            }
            var last = -1
            YoutubeDL.getInstance().execute(req, PID) { p, _, line ->
                val l = line ?: ""
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
            if (cancelled) throw IllegalStateException("Cancelled")
            val file = dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("No video file was made (no video found at this link).")
            State.stage = State.Stage.FINISH
            State.text = "Finishing up…"; State.percent = 100; State.changed()
            val name = saveToDownloads(file)
            State.phase = State.Phase.DONE
            State.text = "Done. Saved in Downloads: $name"
            State.changed()
            notifyDone("Download finished", name)
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
                State.text = ""
                notifyDone("Download failed", State.errorShort)
            }
            State.changed()
        } finally {
            working = false
            dir.deleteRecursively()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun saveToDownloads(f: File): String {
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
        return f.name
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
            .setContentTitle("Fetchly").setContentText(text)
            .setProgress(100, pct, indeterminate).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    private fun notify(n: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)

    private fun notifyDone(title: String, text: String) {
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setContentIntent(openApp()).build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
    }
}

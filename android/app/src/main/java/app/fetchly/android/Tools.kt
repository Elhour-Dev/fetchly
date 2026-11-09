package app.fetchly.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/** Shared state between the screen and the download service. Never holds cookie contents. */
object State {
    enum class Phase { IDLE, RUNNING, DONE, ERROR }

    @Volatile var phase = Phase.IDLE
    @Volatile var percent = 0
    @Volatile var text = ""
    @Volatile var errorShort = ""
    @Volatile var errorDetails = ""
    @Volatile var helpTopic: String? = null
    @Volatile var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    fun changed() { main.post { listener?.invoke() } }
}

object Tools {
    @Volatile private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        val app = ctx.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        ready = true
    }

    fun version(ctx: Context): String =
        try { YoutubeDL.version(ctx.applicationContext) ?: "unknown" } catch (e: Exception) { "unknown" }

    fun cookiesFile(ctx: Context) = java.io.File(ctx.filesDir, "cookies.txt")
}

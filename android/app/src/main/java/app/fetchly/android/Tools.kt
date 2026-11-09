package app.fetchly.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

/** Shared state between the screen and the download service. Never holds cookie contents. */
object State {
    enum class Phase { IDLE, RUNNING, DONE, ERROR }

    @Volatile var phase = Phase.IDLE
    @Volatile var percent = 0
    @Volatile var text = ""
    @Volatile var errorShort = ""
    @Volatile var errorDetails = ""
    @Volatile var helpTopic: String? = null
    @Volatile var needsLogin = false
    @Volatile var failedUrl = ""
    @Volatile var cookiesSaved = false   // true after a login or import; the error card then offers "Retry"
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

    fun version(ctx: Context): String {
        try { YoutubeDL.version(ctx.applicationContext)?.let { return it } } catch (_: Exception) {}
        return try {
            YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList()).apply { addOption("--version") }).out.trim()
                .ifEmpty { "unknown" }
        } catch (e: Exception) { "unknown" }
    }

    fun cookiesFile(ctx: Context) = java.io.File(ctx.filesDir, "cookies.txt")
}

/**
 * Cookies for yt-dlp live in one private file (app storage, no backup). Contents are never logged,
 * shown, exported or sent anywhere except to yt-dlp itself.
 */
object Cookies {
    private const val HEADER = "# Netscape HTTP Cookie File"

    /** "www.instagram.com" -> "instagram.com". Rough, enough for common sites. */
    fun baseDomain(host: String): String {
        val p = host.lowercase().removePrefix("www.").removePrefix("m.").removePrefix("mobile.").split('.')
        val n = if (p.size >= 3 && p.last().length == 2 && p[p.size - 2].length <= 3) 3 else 2
        return p.takeLast(n).joinToString(".")
    }

    fun hostOf(url: String): String = try { android.net.Uri.parse(url).host ?: "" } catch (_: Exception) { "" }

    /** Where to send the user to log in for a failed link. */
    fun loginUrl(link: String): String {
        val base = baseDomain(hostOf(link))
        return when (base) {
            "instagram.com" -> "https://www.instagram.com/accounts/login/"
            "facebook.com" -> "https://www.facebook.com/login/"
            "x.com", "twitter.com" -> "https://x.com/i/flow/login"
            "tiktok.com" -> "https://www.tiktok.com/login"
            "reddit.com" -> "https://www.reddit.com/login/"
            "vimeo.com" -> "https://vimeo.com/log_in"
            "youtube.com", "youtu.be" -> "https://www.youtube.com/"
            "" -> "https://www.instagram.com/accounts/login/"
            else -> "https://$base/"
        }
    }

    private fun domainOf(line: String) = line.substringBefore('\t').removePrefix("#HttpOnly_").removePrefix(".").lowercase()

    private fun isData(line: String) = line.isNotBlank() && (!line.startsWith("#") || line.startsWith("#HttpOnly_"))

    /** Add cookie lines. Older lines for the same domains are replaced; other sites are kept. */
    @Synchronized
    fun merge(ctx: Context, lines: List<String>) {
        val f = Tools.cookiesFile(ctx)
        val fresh = lines.filter { isData(it) }
        val domains = fresh.map { domainOf(it) }.toSet()
        val old = if (f.exists()) f.readLines().filter { isData(it) && domainOf(it) !in domains } else emptyList()
        f.writeText((listOf(HEADER) + old + fresh).joinToString("\n") + "\n")
    }

    /** Netscape lines for the cookies a WebView holds for these hosts. */
    fun fromWebView(hosts: Set<String>, base: String): List<String> {
        val cm = android.webkit.CookieManager.getInstance()
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        val expiry = System.currentTimeMillis() / 1000 + 180L * 24 * 3600
        for (h in hosts + setOf(base, "www.$base")) {
            val raw = cm.getCookie("https://$h") ?: continue
            for (part in raw.split(';')) {
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty() || !seen.add(name)) continue
                out.add(".$base\tTRUE\t/\tTRUE\t$expiry\t$name\t$value")
            }
        }
        return out
    }

    /** Copy a picked cookies.txt into private storage. Returns an error message, or null when it worked. */
    fun import(ctx: Context, uri: android.net.Uri): String? = try {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val text = String(bytes)
        if (bytes.size > 5_000_000 || !(text.take(4000).contains("Netscape") || text.take(4000).contains("\t")))
            "This does not look like a cookies.txt file (Netscape format)."
        else { merge(ctx, text.lines()); null }
    } catch (e: Exception) { "Could not read that file." }

    /** File picker that opens in the Downloads folder. */
    fun pickIntent() = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "*/*"
        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
            android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"))
    }

    fun remove(ctx: Context) {
        Tools.cookiesFile(ctx).delete()
        State.cookiesSaved = false
        clearWebView()
    }

    fun clearWebView() {
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
    }
}

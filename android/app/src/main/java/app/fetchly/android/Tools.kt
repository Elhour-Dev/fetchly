package app.fetchly.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

/** Shared state between the screen and the download service. Never holds cookie contents. */
object State {
    /** Steps of one download, same idea as the desktop app. */
    enum class Stage(val failedAt: String) { FETCH("fetching video info"), DOWNLOAD("downloading"), FINISH("finishing (merging video and audio)") }

    /** The downloads shown on the main page, newest first. */
    val dls = java.util.concurrent.CopyOnWriteArrayList<Dl>()
    private val ids = java.util.concurrent.atomic.AtomicInteger()
    fun nextId() = ids.incrementAndGet()
    /** True while a download runs or waits. */
    fun busy() = dls.any { it.active }

    @Volatile var cookiesSaved = false   // true after a login or import; error cards then offer "Retry"
    @Volatile var loginFor = 0           // the download whose "Log in" was tapped
    @Volatile var startupChecked = false // the "tools are old" check runs once per app start
    @Volatile var appUpdateChecked = false // the start screen (app update check) shows once per app start
    @Volatile var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    fun changed() { main.post { listener?.invoke() } }
}

/** One download: what to get, and how far it is. The service fills it in; the main page shows one card for it. */
class Dl(
    val id: Int, val url: String, val format: String = Quality.BEST, val audio: Boolean = false, val codec: String = "mp3",
    val label: String = "", val subs: String? = null, val subsAuto: Boolean = false,
    val info: java.io.File? = null,   // the video info saved by the quality picker (null = ask the site again)
    val name: String = "",            // video title, when known
) {
    enum class Phase { QUEUED, RUNNING, DONE, ERROR }

    @Volatile var phase = Phase.QUEUED
    @Volatile var stage = State.Stage.FETCH
    @Volatile var failedStage: State.Stage? = null
    @Volatile var percent = 0
    @Volatile var text = ""
    @Volatile var item = ""              // "Video 2 of 10 · " while a playlist downloads
    @Volatile var errorShort = ""
    @Volatile var errorDetails = ""
    @Volatile var helpTopic: String? = null
    @Volatile var needsLogin = false
    @Volatile var sessionCode = 0        // HTTP status of the Instagram login check after a failure (0 = not checked)
    @Volatile var updateOffered = false  // the update pop-up was already shown for this error
    @Volatile var cancelled = false
    val active get() = phase == Phase.QUEUED || phase == Phase.RUNNING

    /** A copy to try again (asks the site again, so an old saved answer is not reused). */
    fun again() = Dl(State.nextId(), url, format, audio, codec, label, subs, subsAuto, null, name)

    companion object {
        /** A problem that is not a download (no link shared, tools did not start): shown as an error card. */
        fun problem(short: String, details: String = "", topic: String? = null) = Dl(State.nextId(), "").apply {
            phase = Phase.ERROR; errorShort = short; errorDetails = details; helpTopic = topic
        }
    }
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

    class UpdateResult(val ok: Boolean, val message: String, val version: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Update yt-dlp. Keeps the old version if the new one does not work. Call on a background thread. */
    fun update(ctx: Context): UpdateResult {
        val dir = java.io.File(ctx.noBackupFilesDir, "youtubedl-android/yt-dlp")
        val backup = java.io.File(ctx.noBackupFilesDir, "yt-dlp-backup")
        try {
            init(ctx)
            val before = version(ctx)
            backup.deleteRecursively()
            if (dir.exists()) dir.copyRecursively(backup, true)
            val r = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            val after = version(ctx)
            if (after == "unknown") throw IllegalStateException("New version did not start")
            prefs(ctx).edit().putLong("last_update", System.currentTimeMillis()).apply()
            val msg = if (r == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) ctx.getString(R.string.tools_up_to_date, after)
            else ctx.getString(R.string.tools_updated, before, after)
            return UpdateResult(true, msg, after)
        } catch (e: Exception) {
            try { if (backup.exists()) { dir.deleteRecursively(); backup.copyRecursively(dir, true) } } catch (_: Exception) {}
            val v = version(ctx)
            return UpdateResult(false, ctx.getString(R.string.tools_update_failed, v, (e.message ?: e.toString()).take(200)), v)
        } finally {
            backup.deleteRecursively()
        }
    }

    // When to offer the update pop-up. It never updates by itself.
    private const val WEEK = 7L * 24 * 3600 * 1000
    private const val DAY = 24L * 3600 * 1000

    /** Errors that updating yt-dlp may fix (ids from Help). */
    val updateTopics = setOf("g_update", "g_format", "g_unsupported")

    /** No pop-up for 24 hours after "Later", and none for 3 days after an update (the newest yt-dlp cannot fix an unsupported link). */
    fun updateSnoozed(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        return now < prefs(ctx).getLong("update_snooze_until", 0) || now - prefs(ctx).getLong("last_update", 0) < 3 * DAY
    }
    /** True when yt-dlp was updated in the last 3 days, so a "please report this issue" error is the site's fault. */
    fun recentlyUpdated(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("last_update", 0) < 3 * DAY
    fun snoozeUpdate(ctx: Context) { prefs(ctx).edit().putLong("update_snooze_until", System.currentTimeMillis() + DAY).apply() }

    /** True when the tools are more than 7 days old (never updated: counted from when the app was installed or updated). */
    fun updateOld(ctx: Context): Boolean {
        // The bundled yt-dlp can already be old on a fresh install, so go by its own release date (e.g. 2025.11.12).
        val built = try {
            java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US).parse(version(ctx).take(10))?.time   // call off the UI thread
        } catch (_: Exception) { null } ?: 0L
        val installed = if (built > 0) 0L else
            try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime } catch (_: Exception) { System.currentTimeMillis() }
        // Once an update was run (even "already the newest"), count from that day: the newest release can itself be weeks old.
        val last = prefs(ctx).getLong("last_update", 0).takeIf { it > 0 } ?: maxOf(built, installed)
        return System.currentTimeMillis() - last > WEEK
    }
}

/**
 * Cookies for yt-dlp live in one private file (app storage, no backup). Contents are never logged,
 * shown, exported or sent anywhere except to yt-dlp itself.
 */
/** Sites Fetchy cannot download from: the app says so at once (same text as the Windows app). */
object Links {
    fun unsupported(ctx: Context, link: String): String? {
        val uri = try { android.net.Uri.parse(link.trim()) } catch (_: Exception) { return null }
        val host = (uri.host ?: "").lowercase().trimEnd('.')
        fun on(site: String) = host == site || host.endsWith(".$site")
        if (on("snapchat.com")) return ctx.getString(R.string.links_snapchat)
        if (on("instagram.com") && Regex("^/(stories|s)/", RegexOption.IGNORE_CASE).containsMatchIn(uri.path ?: ""))
            return ctx.getString(R.string.links_ig_stories)
        return null
    }
}

object Cookies {
    private const val HEADER = "# Netscape HTTP Cookie File"

    /** "www.instagram.com" -> "instagram.com". Rough, enough for common sites. */
    fun baseDomain(host: String): String {
        val p = host.lowercase().removePrefix("www.").removePrefix("m.").removePrefix("mobile.").split('.')
        val n = if (p.size >= 3 && p.last().length == 2 && p[p.size - 2].length <= 3) 3 else 2
        return p.takeLast(n).joinToString(".")
    }

    fun hostOf(url: String): String = try { android.net.Uri.parse(url).host ?: "" } catch (_: Exception) { "" }

    const val YOUTUBE_LOGIN = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"
    const val INSTAGRAM_LOGIN = "https://www.instagram.com/accounts/login/"

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
            "youtube.com", "youtu.be" -> YOUTUBE_LOGIN
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

    /** Netscape lines for the cookies a WebView holds for these hosts. Flushes the WebView store first. */
    fun fromWebView(hosts: Set<String>, base: String): List<String> {
        val cm = android.webkit.CookieManager.getInstance()
        cm.flush()
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        val expiry = System.currentTimeMillis() / 1000 + 180L * 24 * 3600
        for (h in hosts + setOf(base, "www.$base")) {
            val raw = cm.getCookie("https://$h") ?: continue
            for (part in raw.split(';')) {
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim().replace(Regex("[\\t\\r\\n]"), "")
                if (name.isEmpty() || !seen.add(name)) continue
                out.add(".$base\tTRUE\t/\tTRUE\t$expiry\t$name\t$value")
            }
        }
        return out
    }

    /** Cookie names that prove a login on well-known sites (all must be present). null = unknown site. */
    fun sessionNames(base: String): Set<String>? = when (base) {
        "instagram.com", "tiktok.com" -> setOf("sessionid")
        "facebook.com" -> setOf("c_user", "xs")
        "x.com", "twitter.com" -> setOf("auth_token")
        "reddit.com" -> setOf("reddit_session")
        else -> null
    }

    private fun nameOf(line: String) = line.split('\t').getOrNull(5) ?: ""
    private fun valueOf(line: String) = line.split('\t').getOrNull(6) ?: ""

    /** True when every session cookie is there with a non-empty value. Looks at names only. */
    fun hasSession(lines: List<String>, base: String): Boolean {
        val need = sessionNames(base) ?: return lines.isNotEmpty()
        return need.all { n -> lines.any { nameOf(it) == n && valueOf(it).isNotEmpty() } }
    }

    /** Read the saved file back and check the session cookie is in it (for the given site). */
    fun savedHasSession(ctx: Context, base: String): Boolean {
        val f = Tools.cookiesFile(ctx)
        if (!f.exists()) return false
        return hasSession(f.readLines().filter { isData(it) && domainOf(it) == base }, base)
    }

    /**
     * Asks Instagram itself whether the saved login is accepted. Sends the cookies only to instagram.com
     * (like yt-dlp does) and returns the HTTP status only. Call on a background thread. 0 = no answer.
     */
    fun checkInstagram(ctx: Context): Int = try {
        val f = Tools.cookiesFile(ctx)
        val header = f.readLines().filter { isData(it) && domainOf(it) == "instagram.com" }
            .joinToString("; ") { nameOf(it) + "=" + valueOf(it) }
        val c = java.net.URL("https://www.instagram.com/api/v1/accounts/current_user/?edit=true")
            .openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000; c.instanceFollowRedirects = false
        c.setRequestProperty("Cookie", header)
        c.setRequestProperty("X-IG-App-ID", "936619743392459")
        c.setRequestProperty("User-Agent", android.webkit.WebSettings.getDefaultUserAgent(ctx))   // same browser the login used
        val code = c.responseCode
        c.disconnect(); code
    } catch (e: Exception) { 0 }

    /** Cookie names only (never values), for logs. */
    fun names(lines: List<String>) = lines.map { nameOf(it) }

    /** Copy a picked cookies.txt into private storage. Returns an error message, or null when it worked. */
    fun import(ctx: Context, uri: android.net.Uri): String? = try {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val text = String(bytes)
        if (bytes.size > 5_000_000 || !(text.take(4000).contains("Netscape") || text.take(4000).contains("\t")))
            ctx.getString(R.string.common_cookies_bad_file)
        else { merge(ctx, text.lines()); null }
    } catch (e: Exception) { ctx.getString(R.string.common_cookies_read_fail) }

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

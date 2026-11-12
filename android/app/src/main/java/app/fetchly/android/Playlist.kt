package app.fetchly.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** One video of a playlist, from the fast (flat) fetch. [key] is its line in the download archive. */
class PlEntry(val n: Int, val url: String, val key: String, val title: String, val duration: Int, val thumb: String?, val unavailable: String?) {
    @Volatile var have = false
}

/** One video in the download queue. */
class PlItem(val e: PlEntry) {
    enum class S { QUEUED, STARTING, DOWNLOADING, PROCESSING, PAUSED, DONE, FAILED, SKIPPED, CANCELLED }
    @Volatile var state = S.QUEUED
    @Volatile var percent = 0
    @Volatile var error: String? = null
    @Volatile var stop: String? = null   // "pause" or "cancel", asked by the user
    val active get() = state == S.QUEUED || state == S.STARTING || state == S.DOWNLOADING || state == S.PROCESSING
    val open get() = active || state == S.PAUSED
}

/** The playlist on screen and its queue. Shared by the playlist screen and the playlist service. */
object Pl {
    const val MIX_CAP = 50   // auto-generated Mix playlists never end

    @Volatile var url = ""
    @Volatile var title = ""
    @Volatile var capped = false
    @Volatile var loading = false
    @Volatile var error: String? = null
    @Volatile var entries: List<PlEntry> = emptyList()
    @Volatile var items: List<PlItem> = emptyList()   // empty = still choosing videos
    @Volatile var format = Quality.BEST
    @Volatile var audio = false
    val selected = HashSet<Int>()                     // main thread only
    @Volatile var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    fun changed() { main.post { listener?.invoke() } }
    fun active() = items.any { it.active }

    fun isList(link: String) = link.contains("list=") || link.contains("/playlist")
    fun hasVideo(link: String) = try {
        val u = Uri.parse(link)
        u.getQueryParameter("v") != null || (u.host == "youtu.be" && (u.path?.length ?: 0) > 1)
    } catch (_: Exception) { false }

    private val skipReasons = listOf(
        "private video" to R.string.pl_r_private, "members-only" to R.string.pl_r_members, "has been removed" to R.string.pl_r_removed,
        "deleted" to R.string.pl_r_deleted, "video unavailable" to R.string.pl_r_unavailable, "not available" to R.string.pl_r_unavailable,
        "geo" to R.string.pl_r_geo)
    fun skipReason(ctx: Context, raw: String): String? {
        val low = raw.lowercase(); return skipReasons.firstOrNull { low.contains(it.first) }?.let { ctx.getString(it.second) }
    }

    fun safeName(s: String, max: Int = 70) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ")
        .trim(' ', '.').take(max).trim(' ', '.')
    fun folder() = safeName(title).ifEmpty { "Playlist" }

    // Download archive: one "site id" line per finished video, so it is not downloaded twice.
    private fun archive(ctx: Context) = File(ctx.filesDir, "downloaded.txt")
    fun archived(ctx: Context): Set<String> = try { archive(ctx).readLines().toHashSet() } catch (_: Exception) { emptySet() }
    @Synchronized fun archiveAdd(ctx: Context, key: String) {
        if (key.isEmpty() || key in archived(ctx)) return
        try { archive(ctx).appendText(key + "\n") } catch (_: Exception) {}
    }

    /** Fast fetch of the list (no request per video). The result arrives through [listener]. */
    fun load(ctx: Context, link: String) {
        val app = ctx.applicationContext
        url = link; title = ""; capped = false; entries = emptyList(); items = emptyList(); error = null; loading = true
        selected.clear()
        thread {
            try {
                Tools.init(app)
                try { YoutubeDL.getInstance().destroyProcessById("pl-info") } catch (_: Exception) {}   // an older fetch, if any
                val mix = try { Uri.parse(link).getQueryParameter("list")?.startsWith("RD") == true } catch (_: Exception) { false }
                val req = YoutubeDLRequest(link).apply {
                    addOption("--flat-playlist"); addOption("--yes-playlist"); addOption("-J")
                    if (mix) addOption("--playlist-end", MIX_CAP.toString())
                    val c = Tools.cookiesFile(app)
                    if (c.exists()) addOption("--cookies", c.absolutePath)
                }
                val out = YoutubeDL.getInstance().execute(req, "pl-info") { _, _, _ -> }.out
                val info = JSONObject(out.substring(out.indexOf('{')))
                val arr = info.optJSONArray("entries")
                val have = archived(app)
                val list = ArrayList<PlEntry>()
                val isMix = mix || info.optString("id").startsWith("RD")
                for (i in 0 until minOf(arr?.length() ?: 0, if (isMix) MIX_CAP else Int.MAX_VALUE)) {
                    val e = arr!!.optJSONObject(i) ?: continue
                    fun str(k: String) = if (e.isNull(k)) "" else e.optString(k)
                    val target = str("url").ifEmpty { str("webpage_url") }
                    if (!target.startsWith("http")) continue
                    val id = str("id"); val site = str("ie_key").lowercase()
                    val name = str("title").ifEmpty { app.getString(R.string.pl_video_n, i + 1) }
                    val thumb = if (site == "youtube" && id.isNotEmpty()) "https://i.ytimg.com/vi/$id/mqdefault.jpg"
                        else e.optJSONArray("thumbnails")?.let { t -> t.optJSONObject(t.length() - 1)?.optString("url") }?.takeIf { it.startsWith("https://") }
                    val gone = when (name.lowercase()) { "[private video]" -> app.getString(R.string.pl_r_private); "[deleted video]" -> app.getString(R.string.pl_r_deleted); else -> null }
                    val key = if (site.isNotEmpty() && id.isNotEmpty()) "$site $id" else ""
                    list.add(PlEntry(i + 1, target, key, name, if (e.isNull("duration")) 0 else e.optDouble("duration", 0.0).toInt(), thumb, gone)
                        .apply { this.have = key.isNotEmpty() && key in have })
                }
                if (list.isEmpty()) throw IllegalStateException("No videos were found in this playlist.")
                val name = info.optString("title")   // empty: the screens show a default name
                main.post {
                    if (link != url) return@post
                    title = name; capped = isMix; entries = list; selected.addAll(list.map { it.n }); loading = false
                    listener?.invoke()
                }
            } catch (e: Exception) {
                val raw = e.message ?: e.toString()
                main.post {
                    if (link != url) return@post
                    error = if (raw.startsWith("No videos")) app.getString(R.string.pl_no_videos)
                        else Help.find(raw, link)?.let { app.getString(it.simple) } ?: app.getString(R.string.pl_load_failed)
                    loading = false
                    listener?.invoke()
                }
            }
        }
    }

    /** Turn the selection into a queue. Unavailable and already downloaded videos are skipped with the reason. */
    fun begin(ctx: Context, opt: QOption) {
        format = opt.format; audio = opt.audio
        val have = archived(ctx)
        items = entries.filter { it.n in selected }.map { e ->
            PlItem(e).apply {
                val reason = e.unavailable ?: if (e.key.isNotEmpty() && e.key in have) ctx.getString(R.string.pl_already) else null
                if (reason != null) { state = PlItem.S.SKIPPED; error = reason }
            }
        }
    }
}

/** Small thumbnail loader for the playlist rows (no extra library). */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(120)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun show(view: ImageView, url: String?) {
        view.tag = url
        val ready = url?.let { cache.get(it) }
        view.setImageBitmap(ready)
        if (url == null || ready != null) return
        pool.execute {
            if (view.tag != url) return@execute   // the row was reused while waiting
            val bmp = try {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000; readTimeout = 8000
                    inputStream.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                }
            } catch (_: Exception) { null } ?: return@execute
            cache.put(url, bmp)
            main.post { if (view.tag == url) view.setImageBitmap(bmp) }
        }
    }
}

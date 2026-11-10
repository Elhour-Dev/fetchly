package app.fetchly.android

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import kotlin.math.abs

/** One row of the quality picker. [format] is the yt-dlp format selector; [audio] means "extract audio" as [codec] (mp3 or m4a). */
class QOption(val key: String, val label: String, val detail: String, val size: Long?, val format: String, val audio: Boolean, val codec: String = "mp3")

/** A subtitle language the video offers. [auto] = automatic captions. */
class Sub(val code: String, val name: String, val auto: Boolean)

/** What the site offers for one video, plus what to show above the choices. [duration] in seconds (0 = unknown). */
class Loaded(val options: List<QOption>, val subs: List<Sub>, val title: String = "", val duration: Double = 0.0,
             val thumbnail: String = "", val uploader: String = "")

/** Loads what a video offers and turns it into the same choices as the desktop app. */
object Quality {
    const val AUDIO_FORMAT = "ba/b"
    const val M4A_FORMAT = "ba[ext=m4a]/ba/b"
    private val tiers = listOf(1080, 720, 480)

    fun remembered(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("quality", "best") ?: "best"
    fun remember(ctx: Context, key: String) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("quality", key).apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    fun subsOn(ctx: Context) = prefs(ctx).getBoolean("subs_on", false)
    fun setSubsOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("subs_on", on).apply()
    fun subLang(ctx: Context) = prefs(ctx).getString("sub_lang", null)
    fun setSubLang(ctx: Context, code: String) = prefs(ctx).edit().putString("sub_lang", code).apply()
    fun notifyDone(ctx: Context) = prefs(ctx).getBoolean("notify_done", true)
    fun setNotifyDone(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("notify_done", on).apply()

    /** The language to embed: the saved choice, else the phone's language, else English, else the first one. */
    fun pickSub(ctx: Context, subs: List<Sub>): Sub? {
        val saved = subLang(ctx)
        val phone = java.util.Locale.getDefault().language.lowercase()
        return subs.firstOrNull { it.code == saved }
            ?: subs.firstOrNull { it.code.lowercase().substringBefore('-') == phone }
            ?: subs.firstOrNull { it.code.lowercase().startsWith("en") }
            ?: subs.firstOrNull()
    }

    /** Same style as the desktop app: "12.3 MB". */
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var v = bytes / 1024.0
        var i = 0
        val units = listOf("KB", "MB", "GB", "TB")
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return "%.1f %s".format(java.util.Locale.US, v, units[i])
    }

    private fun best(h: Int, k: String = "height") = "bv*[ext=mp4][$k<=?$h]+ba[ext=m4a]/b[ext=mp4][$k<=?$h]/bv*[$k<=?$h]+ba/b[$k<=?$h]/b"
    const val BEST = "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b"

    private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)
    private fun JSONObject.num(k: String) = if (isNull(k)) 0.0 else optDouble(k, 0.0)
    private fun JSONObject.hasCodec(codec: String) = str(codec).let { it.isNotEmpty() && it != "none" }

    /** Ask yt-dlp for the formats. Throws when it cannot. Call on a background thread; [pid] lets the caller cancel it. */
    fun fetch(ctx: Context, link: String, pid: String): Loaded {
        Tools.init(ctx)
        infoFile(ctx).delete()
        val req = YoutubeDLRequest(link).apply {
            addOption("--no-playlist")
            addOption("-J")
            val c = Tools.cookiesFile(ctx)
            if (c.exists()) addOption("--cookies", c.absolutePath)
        }
        val out = YoutubeDL.getInstance().execute(req, pid) { _, _, _ -> }.out
        val json = out.substring(out.indexOf('{'))
        val info = JSONObject(json)
        // Keep the answer so the download does not ask the site again (a second request is often refused, e.g. TikTok 403).
        if (info.optString("_type", "video") == "video") try { infoFile(ctx).writeText(json) } catch (_: Exception) {}
        return Loaded(options(info), subtitles(info), info.str("title"), info.num("duration"), info.str("thumbnail"),
            info.str("uploader").ifEmpty { info.str("channel") })
    }

    private val subCode = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")

    /** Uploaded subtitles, plus the automatic captions of the video's own language (listed as "-orig"). */
    private fun subtitles(info: JSONObject): List<Sub> {
        fun nameOf(code: String, tracks: org.json.JSONArray): String {
            for (i in 0 until tracks.length()) tracks.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }?.let { return it }
            return code
        }
        val out = ArrayList<Sub>()
        info.optJSONObject("subtitles")?.let { s ->
            for (code in s.keys().asSequence().toList()) {
                val tracks = s.optJSONArray(code) ?: continue
                if (code != "live_chat" && subCode.matches(code) && tracks.length() > 0) out.add(Sub(code, nameOf(code, tracks), false))
            }
        }
        info.optJSONObject("automatic_captions")?.let { a ->
            for (code in a.keys().asSequence().toList()) {
                val tracks = a.optJSONArray(code) ?: continue
                if (code.endsWith("-orig") && subCode.matches(code) && out.none { it.code == code.removeSuffix("-orig") })
                    out.add(Sub(code, nameOf(code, tracks).removeSuffix(" (Original)") + " (auto)", true))
            }
        }
        return out.take(60)
    }

    /** The choices for a whole playlist: one quality for every video (sizes are not known up front). */
    fun playlistOptions() = listOf(QOption("best", "Best quality", "", null, BEST, false)) +
        tiers.map { QOption(it.toString(), "${it}p", "", null, best(it), false) } +
        QOption("audio", "Audio only (MP3)", "", null, AUDIO_FORMAT, true)

    /** The saved answer of the last [fetch] (may not exist). */
    fun infoFile(ctx: Context) = java.io.File(ctx.cacheDir, "info.json")

    private fun options(info: JSONObject): List<QOption> {
        val duration = info.num("duration")
        val arr = info.optJSONArray("formats") ?: return emptyList()
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }

        fun bytes(f: JSONObject): Long? {
            for (k in listOf("filesize", "filesize_approx")) f.num(k).takeIf { it > 0 }?.let { return it.toLong() }
            val tbr = f.num("tbr").takeIf { it > 0 } ?: (f.num("vbr") + f.num("abr")).takeIf { it > 0 }
            return if (tbr != null && duration > 0) (tbr * 1000 / 8 * duration).toLong() else null
        }
        // Short side, so a phone video of 720x1280 counts as "720p".
        fun dim(f: JSONObject): Int {
            val w = f.num("width").toInt(); val h = f.num("height").toInt()
            return if (w > 0 && h > 0) minOf(w, h) else h
        }
        fun rank(f: JSONObject) = f.num("tbr")

        val videos = all.filter { it.hasCodec("vcodec") && dim(it) >= 144 }
        val audios = all.filter { it.hasCodec("acodec") && !it.hasCodec("vcodec") }
        val audioBest = audios.maxByOrNull { rank(it) }
        val audioSize = audioBest?.let { bytes(it) }
        fun total(f: JSONObject): Long? {
            val s = bytes(f) ?: return null
            return if (!f.hasCodec("acodec") && audioSize != null) s + audioSize else s
        }
        // MP4 is preferred for playing on phones, like the download itself.
        fun pick(list: List<JSONObject>) = list.filter { it.str("ext") == "mp4" }.ifEmpty { list }.maxByOrNull { rank(it) }

        val out = ArrayList<QOption>()
        val top = pick(videos)
        out.add(QOption("best", "Best quality", if (top != null) "Up to ${dim(top)}p" else "Highest available",
            top?.let { total(it) }, BEST, false))

        val portrait = top != null && top.num("width") > 0 && top.num("width") < top.num("height")
        for (t in tiers) {
            val at = videos.filter { abs(dim(it) - t) <= t / 20 }
            val cand = pick(at) ?: continue
            out.add(QOption(t.toString(), "${t}p", "MP4 video", total(cand), best(t, if (portrait) "width" else "height"), false))
        }
        if (all.any { it.hasCodec("acodec") }) {
            out.add(QOption("audio", "Audio only (MP3)", "MP3 · 192 kbps · cover art and tags", audioSize, AUDIO_FORMAT, true))
            out.add(QOption("m4a", "Audio only (M4A)", "M4A · original quality · tags", audioSize, M4A_FORMAT, true, "m4a"))
        }
        return out
    }
}

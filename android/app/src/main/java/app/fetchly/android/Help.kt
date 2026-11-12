package app.fetchly.android

/**
 * One problem, one Help section. The texts are string resources (they follow the phone language):
 * `title` is what the user sees; `why` is one plain sentence; `fix` is a string-array of short steps.
 * `simple` is the one-line message shown on the error card.
 */
class Problem(
    val id: String, val title: Int, val simple: Int, val match: Regex?,
    val why: Int, val fix: Int,
    val login: Boolean = false, val site: String? = null
)

/** One "how to" section of Help: short steps (a string-array), and an optional note under them. */
class Guide(val id: String, val title: Int, val steps: Int, val note: Int? = null)

object Help {
    val COOKIE_NOTE = R.string.help_cookie_note

    private fun r(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    val guides = listOf(
        Guide("download", R.string.help_g_download_title, R.array.help_g_download_steps, R.string.help_g_download_note),
        Guide("playlist", R.string.help_g_playlist_title, R.array.help_g_playlist_steps, R.string.help_g_playlist_note),
        Guide("login", R.string.help_g_login_title, R.array.help_g_login_steps, R.string.help_g_login_note),
        Guide("find", R.string.help_g_find_title, R.array.help_g_find_steps),
        Guide("tools", R.string.help_g_tools_title, R.array.help_g_tools_steps, R.string.help_g_tools_note)
    )

    val problems = listOf(
        Problem("yt_bot", R.string.help_p_yt_bot_title,
            R.string.help_p_yt_bot_simple, r("not a bot|confirm you.?re not|http error 403"),
            R.string.help_p_yt_bot_why, R.array.help_p_yt_bot_fix, login = true, site = "youtube"),
        Problem("yt_unavail", R.string.help_p_yt_unavail_title,
            R.string.help_p_yt_unavail_simple,
            r("video (is )?unavailable|age[- ]restrict|confirm your age|inappropriate|has been terminated|country|geo[- ]?(restrict|block)|your region|not available in your|blocked it in your"),
            R.string.help_p_yt_unavail_why, R.array.help_p_yt_unavail_fix, login = true, site = "youtube"),
        Problem("ig_login", R.string.help_p_ig_login_title,
            R.string.help_p_ig_login_simple,
            r("log ?in|sign ?in|login required|authenticat|cookies|rate-limit|requires an account|members?[- ]only|need to log"),
            R.string.help_p_ig_login_why, R.array.help_p_ig_login_fix, login = true, site = "instagram"),
        Problem("ig_empty", R.string.help_p_ig_empty_title,
            R.string.help_p_ig_empty_simple,
            r("empty media response|no video in this post|there is no video"),
            R.string.help_p_ig_empty_why, R.array.help_p_ig_empty_fix, login = true, site = "instagram"),
        Problem("ig_private", R.string.help_p_ig_private_title,
            R.string.help_p_ig_private_simple,
            r("private|deleted|removed|no longer available|content is not available"),
            R.string.help_p_ig_private_why, R.array.help_p_ig_private_fix, login = true, site = "instagram"),
        Problem("g_unsupported", R.string.help_p_g_unsupported_title,
            R.string.help_p_g_unsupported_simple,
            r("unsupported url|not supported|no video|videos only|could not find|no media|not a valid|invalid url|no supported"),
            R.string.help_p_g_unsupported_why, R.array.help_p_g_unsupported_fix),
        Problem("g_ffmpeg", R.string.help_p_g_ffmpeg_title,
            R.string.help_p_g_ffmpeg_simple,
            r("ffmpeg|ffprobe|postprocess|merg(e|ing)|no audio|no sound"),
            R.string.help_p_g_ffmpeg_why, R.array.help_p_g_ffmpeg_fix),
        Problem("g_network", R.string.help_p_g_network_title,
            R.string.help_p_g_network_simple,
            r("timed out|timeout|too long|connection|proxy|ssl|certificate|network|failed to fetch|unreachable|429|403|forbidden|too many requests|temporar|resolve"),
            R.string.help_p_g_network_why, R.array.help_p_g_network_fix),
        Problem("g_update", R.string.help_p_g_update_title,
            R.string.help_p_g_update_simple,
            r("unable to extract|update yt-dlp|outdated|nsig|signature|extractor error|please report this issue|sabr|player response"),
            R.string.help_p_g_update_why, R.array.help_p_g_update_fix),
        Problem("g_format", R.string.help_p_g_format_title,
            R.string.help_p_g_format_simple,
            r("format.{0,20}(is )?not available|requested format|no such format|no formats"),
            R.string.help_p_g_format_why, R.array.help_p_g_format_fix, login = true)
    )

    // Order matters: the first match wins. Site-specific problems are tried first for that site's links.
    private val order = listOf("yt_bot", "ig_login", "ig_empty", "yt_unavail", "ig_private",
        "g_unsupported", "g_ffmpeg", "g_network", "g_update", "g_format")
    private val all = order.map { id -> problems.first { it.id == id } }

    fun find(error: String, link: String): Problem? {
        val base = Cookies.baseDomain(Cookies.hostOf(link))
        val site = when (base) { "instagram.com" -> "instagram"; "youtube.com", "youtu.be" -> "youtube"; else -> null }
        val first = all.filter { it.site == null || it.site == site }
        return (first + all).firstOrNull { it.match?.containsMatchIn(error) == true }
    }
}

package app.fetchly.android

/**
 * One problem, one Help section. The title is what the user sees; `why` is one plain sentence; `fix` are short steps.
 * `simple` is the one-line message shown on the error card.
 */
class Problem(
    val id: String, val title: String, val simple: String, val match: Regex?,
    val why: String, val fix: List<String>,
    val login: Boolean = false, val site: String? = null
)

/** One "how to" section of Help: short steps, and an optional note under them. */
class Guide(val id: String, val title: String, val steps: List<String>, val note: String? = null)

object Help {
    const val TIP = "Tip: use a second account, not your main one. It is safer if the site flags the login."
    const val COOKIE_NOTE = "Cookies are like a password. Never share them."

    private fun r(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    val guides = listOf(
        Guide("download", "Download a video", listOf(
            "In the video app, tap Share, then Copy link.",
            "In Fetchy, tap Paste.",
            "Pick a quality.",
            "Tap Download."
        ), "You can also share a link straight to Fetchy from the Share menu."),
        Guide("playlist", "Download a playlist", listOf(
            "Paste a playlist link and tap Download.",
            "Tick the videos you want.",
            "Pick one quality, then tap Download.",
            "If some fail, tap \"Retry failed\" at the end."
        ), "Videos you already have are skipped."),
        Guide("login", "Log in to YouTube or Instagram", listOf(
            "Open Settings.",
            "Tap \"YouTube login\" or \"Instagram login\".",
            "Sign in, then tap Done.",
            "Login page blocked? Tap \"Import cookies.txt\" and pick the file from Downloads."
        ), "$TIP $COOKIE_NOTE"),
        Guide("find", "Find your videos", listOf(
            "Open the Files (or My Files) app.",
            "Open Downloads. Playlists have their own folder there.",
            "Or tap \"Download history\" in Fetchy."
        )),
        Guide("tools", "Keep downloads working", listOf(
            "Open Settings.",
            "Tap \"Update tools\".",
            "Wait for the new version number."
        ), "Sites change often. Updating the tools fixes most new errors.")
    )

    val problems = listOf(
        Problem("yt_bot", "YouTube: \"Sign in to confirm you're not a bot\"",
            "YouTube thinks this is a bot and blocked the download.", r("not a bot|confirm you.?re not|http error 403"),
            "YouTube does not trust the connection. A VPN makes it worse.",
            listOf(
                "Turn off any VPN or proxy.",
                "Tap \"Log in\" and sign in to YouTube.",
                "Google blocks the login? Use \"Import cookies.txt\" instead.",
                "Tap \"Retry\"."
            ), login = true, site = "youtube"),
        Problem("yt_unavail", "YouTube: \"Video unavailable\"",
            "This video is private, removed, blocked in your country, or needs an adult account.",
            r("video (is )?unavailable|age[- ]restrict|confirm your age|inappropriate|has been terminated|country|geo[- ]?(restrict|block)|your region|not available in your|blocked it in your"),
            "The video is private, removed, blocked where you are, or for adults only.",
            listOf(
                "Open the link in your browser. If it does not play there, Fetchy cannot get it.",
                "For adults only: tap \"Log in\" with an adult account, then \"Retry\".",
                "Removed or private: only the owner can fix that."
            ), login = true, site = "youtube"),
        Problem("ig_login", "\"Login required\"",
            "This site wants you to be logged in.",
            r("log ?in|sign ?in|login required|authenticat|cookies|rate-limit|requires an account|members?[- ]only|need to log"),
            "The site only shows this to people who are logged in.",
            listOf(
                "Tap \"Log in\" and sign in.",
                "Tap Done. Fetchy tries again by itself.",
                "No login page? Use \"Import cookies.txt\"."
            ), login = true, site = "instagram"),
        Problem("ig_empty", "Instagram: empty result",
            "The site sent back nothing.",
            r("empty media response|no video in this post|there is no video"),
            "You are not logged in, or the post has only photos.",
            listOf(
                "Tap \"Log in\", then \"Retry\".",
                "Check that the post is a video or a Reel.",
                "Wait a few minutes, then try again."
            ), login = true, site = "instagram"),
        Problem("ig_private", "Private or deleted post",
            "This post is private or was removed.",
            r("private|deleted|removed|no longer available|content is not available"),
            "The owner made it private or deleted it.",
            listOf(
                "Open the link in your browser. If you cannot see it, Fetchy cannot either.",
                "Private: log in with an account that can see it, then tap \"Retry\".",
                "Deleted: it is gone for good."
            ), login = true, site = "instagram"),
        Problem("g_unsupported", "Link not supported",
            "This link is not a video Fetchy can download.",
            r("unsupported url|not supported|no video|videos only|could not find|no media|not a valid|invalid url|no supported"),
            "The link is not a video page, is cut off, or the site is not supported.",
            listOf(
                "Use the video's Share button, then Copy link.",
                "Paste the whole link. It starts with https://",
                "Photos do not work. Fetchy downloads videos."
            )),
        Problem("g_ffmpeg", "No sound",
            "The video and sound could not be joined.",
            r("ffmpeg|ffprobe|postprocess|merg(e|ing)|no audio|no sound"),
            "Sites often send video and sound apart, and joining them failed.",
            listOf(
                "Tap \"Retry\".",
                "Free some storage space on your phone.",
                "Open Settings and tap \"Update tools\"."
            )),
        Problem("g_network", "Slow or failed download",
            "The connection failed, timed out, or the site refused it.",
            r("timed out|timeout|too long|connection|proxy|ssl|certificate|network|failed to fetch|unreachable|429|403|forbidden|too many requests|temporar|resolve"),
            "Weak connection, a VPN, or the site is limiting you.",
            listOf(
                "Check that the internet works in your browser.",
                "Turn off VPN and Private DNS.",
                "Wait a few minutes, then tap \"Retry\".",
                "Try the other network (Wi-Fi or mobile data)."
            )),
        Problem("g_update", "\"Unable to extract\" or \"update yt-dlp\"",
            "yt-dlp is probably out of date.",
            r("unable to extract|update yt-dlp|outdated|nsig|signature|extractor error|please report this issue|sabr|player response"),
            "The site changed, and the tools need an update.",
            listOf(
                "Open Settings and tap \"Update tools\".",
                "Wait for the new version number.",
                "Go back and tap \"Retry\"."
            )),
        Problem("g_format", "\"Format not available\"",
            "No matching video format was found.",
            r("format.{0,20}(is )?not available|requested format|no such format|no formats"),
            "The site gave no format Fetchy can use. Often it needs a login.",
            listOf(
                "Open Settings and tap \"Update tools\".",
                "Tap \"Log in\", or see \"Log in to YouTube or Instagram\".",
                "Tap \"Retry\"."
            ), login = true)
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

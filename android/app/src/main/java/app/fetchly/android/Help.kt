package app.fetchly.android

/** One problem: what you see, why it happens, how to fix it. `simple` is the one-line message shown on the error card. */
class Problem(
    val id: String, val title: String, val simple: String, val match: Regex?,
    val see: String, val why: String, val fix: List<String>,
    val login: Boolean = false, val site: String? = null
)

class HelpCard(val id: String, val title: String, val subtitle: String, val tip: Boolean, val problems: List<Problem>)

object Help {
    const val TIP = "Use a secondary account, not your main one. It's safer for your main account if the site flags the login. " +
        "It lowers the risk, it doesn't remove it."
    const val COOKIE_NOTE = "Cookies are like a password. Never share them."

    private fun r(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    private val yt = HelpCard("youtube", "YouTube", "Bot check, video unavailable", true, listOf(
        Problem("yt_bot", "\"Sign in to confirm you're not a bot\"",
            "YouTube thinks this is a bot and blocked the download.", r("not a bot|confirm you.?re not"),
            "YouTube asks you to sign in to prove you are not a bot.",
            "YouTube blocks requests it does not trust. VPNs and busy networks make it worse.",
            listOf(
                "If a VPN or proxy is on, turn it off. Or set it to \"direct\" for Fetchy.",
                "Tap \"Log in\". If Google says the app is not allowed, tap \"Import cookies.txt\" instead.",
                "For the file: log in to YouTube on a computer, export cookies.txt with a cookies extension, and copy it to your phone's Downloads.",
                "Tap \"Retry\"."
            ), login = true, site = "youtube"),
        Problem("yt_unavail", "Video unavailable",
            "This video is private, removed, blocked in your country, or needs an adult account.",
            r("video unavailable|age[- ]restrict|confirm your age|inappropriate|has been terminated|country|geo[- ]?(restrict|block)|your region|not available in your|blocked it in your"),
            "\"Video unavailable\", \"Private video\" or \"not available in your country\".",
            "The owner hid or removed it, it is blocked where you are, or it needs a logged-in adult.",
            listOf(
                "Open the link in your phone browser. If it does not play there, Fetchy cannot get it.",
                "Age limit: tap \"Log in\" or \"Import cookies.txt\" with an adult account, then \"Retry\".",
                "Blocked in your country: a VPN may help, but it can also trigger the bot check.",
                "Removed or private: only the owner can fix that."
            ), login = true, site = "youtube")
    ))

    private val ig = HelpCard("instagram", "Instagram", "Login required, empty result, private post", true, listOf(
        Problem("ig_login", "Login required",
            "This site wants you to be logged in.",
            r("log ?in|sign ?in|login required|authenticat|cookies|rate-limit|requires an account|members?[- ]only|need to log"),
            "\"Login required\", \"Instagram needs a login\" or \"rate-limit reached\". Other sites can say the same.",
            "The site only shows this to logged-in people.",
            listOf(
                "Tap \"Log in\" and sign in on the site's page. Then tap \"Done\".",
                "Tap \"Retry\".",
                "No login page, or it is blocked? Tap \"Import cookies.txt\" and pick the file from Downloads."
            ), login = true, site = "instagram"),
        Problem("ig_empty", "Empty result",
            "The site sent back nothing.",
            r("empty media response|no video in this post|there is no video"),
            "\"Empty media response\", or no video found in a post.",
            "You are probably not logged in, or the post has only photos.",
            listOf(
                "Tap \"Log in\" (or \"Import cookies.txt\"), then \"Retry\".",
                "Check that the post is a video or a Reel.",
                "Wait a few minutes and try again."
            ), login = true, site = "instagram"),
        Problem("ig_private", "Private or deleted post",
            "This post is private or was removed.",
            r("private|deleted|removed|no longer available|content is not available"),
            "\"This content is not available\", \"private\" or \"deleted\".",
            "The owner made it private or deleted it.",
            listOf(
                "Open the link in your phone browser. If you cannot see it there, Fetchy cannot either.",
                "Private: log in with an account that may see it (tap \"Log in\"), then \"Retry\".",
                "Deleted: it is gone for good."
            ), login = true, site = "instagram")
    ))

    private val general = HelpCard("general", "General", "Sound, speed, links, tools, files", false, listOf(
        Problem("g_unsupported", "Link not supported",
            "This link is not a video Fetchy can download.",
            r("unsupported url|not supported|no video|videos only|could not find|no media|not a valid|invalid url|no supported"),
            "\"Unsupported URL\" or \"No video found\".",
            "The link is not a video page, is cut off, or the site is not supported.",
            listOf(
                "Copy the link of the video itself. Use the video's Share button.",
                "Paste the whole link. It starts with https://",
                "Photos do not work. Fetchy downloads videos.",
                "Open Settings, tap \"Update tools\", then try again."
            )),
        Problem("g_ffmpeg", "No sound, or ffmpeg missing",
            "The video and sound could not be joined.",
            r("ffmpeg|ffprobe|postprocess|merg(e|ing)|no audio|no sound"),
            "The video has no sound, or an error names ffmpeg.",
            "Many sites keep video and sound apart. Fetchy joins them with ffmpeg.",
            listOf(
                "Tap \"Retry\".",
                "Free some storage space on your phone.",
                "Open Settings and tap \"Update tools\".",
                "Still broken? Reinstall Fetchy."
            )),
        Problem("g_network", "Slow or failed download",
            "The connection failed or timed out.",
            r("timed out|timeout|too long|connection|proxy|ssl|certificate|network|failed to fetch|unreachable|429|too many requests|temporar|resolve"),
            "The download is very slow, stops, times out, or says \"too many requests\" (429).",
            "Weak connection, a VPN, or the site is limiting you.",
            listOf(
                "Check that Wi-Fi or mobile data works in your browser.",
                "Turn off VPN and Private DNS, then tap \"Retry\".",
                "\"Too many requests\": wait a few minutes.",
                "Try the other network (Wi-Fi or mobile data)."
            )),
        Problem("g_update", "Outdated tools",
            "yt-dlp is probably out of date.",
            r("unable to extract|update yt-dlp|outdated|nsig|signature|extractor error|please report this issue|sabr|player response"),
            "\"Unable to extract\" or \"please update yt-dlp\".",
            "Sites change often. Old tools stop working.",
            listOf(
                "Open Settings and tap \"Update tools\".",
                "Wait until the new version number shows.",
                "Go back and tap \"Retry\". If the update fails, the old version keeps working."
            )),
        Problem("g_format", "\"Format not available\"",
            "No matching video format was found.",
            r("format.{0,20}(is )?not available|requested format|no such format|no formats"),
            "\"Requested format is not available\".",
            "The site gave no video and sound mix that Fetchy can use. Often it needs a login.",
            listOf(
                "Open Settings and tap \"Update tools\".",
                "Log in or import cookies (see the Instagram or YouTube card).",
                "Tap \"Retry\"."
            )),
        Problem("g_storage", "File not found in Downloads",
            "", null,
            "The box says Done, but you cannot find the video.",
            "Fetchy saves finished videos in your phone's Downloads folder.",
            listOf(
                "Open the Files (or My Files) app, then Downloads.",
                "Look for the name shown in the green box.",
                "Android 13 and newer: allow Notifications for Fetchy to see progress. Downloads work without it."
            ))
    ))

    val cards = listOf(ig, yt, general)

    // Order matters: the first match wins. Site-specific problems are tried first for that site's links.
    private val order = listOf("yt_bot", "ig_login", "ig_empty", "yt_unavail", "ig_private",
        "g_unsupported", "g_ffmpeg", "g_network", "g_update", "g_format")
    private val all = order.map { id -> cards.flatMap { it.problems }.first { it.id == id } }

    fun cardOf(problemId: String?) = cards.firstOrNull { c -> c.problems.any { it.id == problemId } }

    fun find(error: String, link: String): Problem? {
        val base = Cookies.baseDomain(Cookies.hostOf(link))
        val site = when (base) { "instagram.com" -> "instagram"; "youtube.com", "youtu.be" -> "youtube"; else -> null }
        val first = all.filter { it.site == null || it.site == site }
        return (first + all).firstOrNull { it.match?.containsMatchIn(error) == true }
    }
}

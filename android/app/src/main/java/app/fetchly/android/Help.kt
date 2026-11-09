package app.fetchly.android

class Topic(
    val id: String, val title: String, val simple: String, val match: Regex,
    val why: String, val fix: List<String>
)

object Help {
    private fun r(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    val topics = listOf(
        Topic("bot", "YouTube says \"Sign in to confirm you're not a bot\"",
            "YouTube thinks this is a bot and blocked the download.",
            r("not a bot|confirm you.?re not"),
            "YouTube blocks requests it does not trust. This is common on VPNs and busy networks.",
            listOf(
                "Tap \"Update tools\" on the main screen.",
                "Turn off any VPN and try again (Wi-Fi or mobile data).",
                "On a computer, log in to YouTube in a private window, export a cookies.txt file (Netscape format) with a cookies extension, and copy the file to your phone.",
                "On the main screen tap \"Import cookies.txt\" and pick that file. Then try the link again.",
                "Keep cookies.txt private. It holds your login. Never share it.",
                "Warning: using your main Google account can get it limited. A spare account is safer."
            )),
        Topic("login", "Instagram and other sites that need a login",
            "This site wants you to be logged in.",
            r("log ?in|sign ?in|login required|authenticat|cookies|rate-limit|empty media response|requires an account|members?[- ]only|need to log"),
            "The site only shows this content to signed-in users.",
            listOf(
                "On a computer, log in to the site in your browser.",
                "Export a cookies.txt file (Netscape format) with a cookies extension and copy it to your phone.",
                "On the main screen tap \"Import cookies.txt\" and pick the file.",
                "Try the link again. Public Instagram posts shared from the Instagram app may work without cookies.",
                "Warning: automated use of your main account can get it blocked. Use a spare account."
            )),
        Topic("private", "Private, deleted or age-restricted video",
            "This video is private, removed, or needs an adult account.",
            r("private|deleted|removed|no longer available|age[- ]restrict|confirm your age|inappropriate|video unavailable|has been terminated"),
            "The owner hid or deleted it, or the site wants a signed-in adult account.",
            listOf(
                "Open the link in your phone browser. If you cannot watch it there, Fetchly cannot download it.",
                "Private video: only the owner can allow access.",
                "Deleted video: it is gone.",
                "Age-restricted: import cookies from a logged-in adult account (see the YouTube topic)."
            )),
        Topic("country", "\"Video not available in your country\"",
            "This video is blocked in your country.",
            r("country|geo[- ]?(restrict|block)|your region|not available in your|blocked it in your"),
            "The owner limited the video to some countries.",
            listOf(
                "Check that the video plays in your phone browser.",
                "A VPN can change your country, but VPNs can also cause YouTube bot errors."
            )),
        Topic("unsupported", "Link not supported, or not a video",
            "This link is not a video Fetchly can download.",
            r("unsupported url|not supported|no video|videos only|could not find|no media|not a valid|invalid url|no supported"),
            "Fetchly downloads videos only. Photos, pages without video and some sites are not supported.",
            listOf(
                "Copy the link of the video itself (use the Share button of the video).",
                "Make sure the link starts with http:// or https://.",
                "Tap \"Update tools\" in case the site changed."
            )),
        Topic("ffmpeg", "A video with no sound, or a merge error",
            "The video and sound could not be joined.",
            r("ffmpeg|ffprobe|postprocess|merg(e|ing)|no audio|no sound"),
            "Many sites keep video and sound apart. Fetchly joins them with ffmpeg.",
            listOf(
                "Try the download again.",
                "Free some storage space on your phone.",
                "Reinstall Fetchly if it keeps happening."
            )),
        Topic("network", "Slow or failing downloads, network errors",
            "The connection failed or timed out.",
            r("timed out|timeout|too long|connection|proxy|ssl|certificate|network|failed to fetch|unreachable|429|too many requests|temporar|resolve"),
            "The network dropped, is slow, or the site is limiting you.",
            listOf(
                "Check that Wi-Fi or mobile data works.",
                "Turn off VPN or Private DNS and try again.",
                "Wait a few minutes if you see \"too many requests\" (429)."
            )),
        Topic("update", "The tools are outdated: tap \"Update tools\"",
            "yt-dlp is probably out of date.",
            r("unable to extract|update yt-dlp|outdated|nsig|signature|extractor error|please report this issue|sabr|player response"),
            "Sites change often. yt-dlp must be new to keep working.",
            listOf(
                "Tap \"Update tools\" on the main screen and wait for the new version.",
                "Try the link again.",
                "If the update fails, the old version keeps working."
            )),
        Topic("format", "\"Format not available\"",
            "No matching video format was found.",
            r("format.{0,20}(is )?not available|requested format|no such format|no formats"),
            "The site did not give a format Fetchly can use.",
            listOf(
                "Tap \"Update tools\" and try again.",
                "Some videos need a login: see the login topic."
            )),
        Topic("storage", "Where are my videos? Notifications?",
            "",
            Regex("(?!)"),
            "Fetchly saves finished videos to the phone's Downloads folder.",
            listOf(
                "Open the Files (My Files) app, then Downloads.",
                "Android 13 and newer: allow Notifications for Fetchly to see download progress. Downloads work without it.",
                "You can share a link from YouTube, Instagram and others: tap Share, then Fetchly."
            ))
    )

    // Order matters: the most specific topics come first.
    fun find(error: String): Topic? = topics.firstOrNull { it.match.containsMatchIn(error) }
}

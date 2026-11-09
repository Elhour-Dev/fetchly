package app.fetchly.android

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Lets the user log in to a site inside the app. On "Done" the cookies go to the private cookies file for yt-dlp.
 * Nothing is bypassed: if a site (for example Google) refuses WebView logins, we just say so.
 */
class LoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var notice: LinearLayout
    private lateinit var noticeText: android.widget.TextView
    private val hosts = LinkedHashSet<String>()
    private lateinit var base: String
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    companion object {
        const val RESULT_IMPORT = RESULT_FIRST_USER   // the user chose "Import cookies.txt" instead
        private const val TAG = "FetchlyLogin"        // logs: host, status codes, cookie NAMES only. Never values.
    }

    private fun log(msg: String) = Log.d(TAG, msg)

    /** Checks for the session cookie by itself, so the user does not have to tap Done. */
    private val poll = object : Runnable {
        override fun run() { if (!finished) { check(); handler.postDelayed(this, 1000) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra("url") ?: "https://www.instagram.com/accounts/login/"
        base = Cookies.baseDomain(Cookies.hostOf(start))
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("login_url", start).apply()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                v.setPadding(0, b.top, 0, b.bottom)
            } else {
                @Suppress("DEPRECATION") v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            }
            ins
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(button("Cancel", Kind.GHOST, 40) { finish() }.apply { setPadding(dp(16), 0, dp(16), 0) }, LinearLayout.LayoutParams(-2, dp(40)))
            addView(label("Log in to $base", 16f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
            addView(button("Done", Kind.PRIMARY, 40) { done() }.apply { setPadding(dp(20), 0, dp(20), 0) }, LinearLayout.LayoutParams(-2, dp(40)))
        }
        root.addView(bar)

        notice = card(R.color.tip_bg, R.color.tip_border, 12).apply { visibility = View.GONE }
        noticeText = label("", 14f, R.color.tip_text)
        notice.addView(noticeText)
        notice.addView(button("Import cookies.txt", Kind.TONAL) { setResult(RESULT_IMPORT); finish() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(notice, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme
                return s != "https" && s != "http"   // never hand off to other apps
            }
            override fun onPageStarted(v: WebView, url: String, icon: Bitmap?) { seen(url) }
            override fun onPageFinished(v: WebView, url: String) { seen(url); log("page finished: " + safe(url)); check() }
            // The site refused to open here. We do not work around it: we point to the cookies.txt import.
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) { log("load error ${e.errorCode} on " + safe(r.url.toString())); blocked() }
            }
            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                if (r.isForMainFrame && e.statusCode >= 400) { log("HTTP ${e.statusCode} on " + safe(r.url.toString())); blocked() }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // Start from a clean browser so an old session cannot leak in; saved cookies stay in the private file.
        CookieManager.getInstance().removeAllCookies { runOnUiThread { web.loadUrl(start); handler.postDelayed(poll, 1000) } }
    }

    /** host + path only: no query, no fragment. */
    private fun safe(url: String) = try { val u = Uri.parse(url); (u.host ?: "?") + (u.path ?: "") } catch (_: Exception) { "?" }

    /** The site refused the login page. Not worked around. If the session cookie arrived anyway, this is not an error. */
    private fun blocked() {
        if (finished || Cookies.hasSession(Cookies.fromWebView(hosts, base), base) && Cookies.sessionNames(base) != null) return
        noticeText.text = "This site did not open its login page inside the app. Fetchy does not work around that. " +
            "Tap Cancel, or use \"Import cookies.txt\" below."
        notice.visibility = View.VISIBLE
    }

    /** If the session cookie is there, save, verify, say "Logged in" and close the page. */
    private fun check() {
        if (finished) return
        val known = Cookies.sessionNames(base) ?: return   // unknown site: the user taps Done
        val lines = Cookies.fromWebView(hosts, base)
        if (!Cookies.hasSession(lines, base)) return
        save(lines, known)
    }

    private fun save(lines: List<String>, known: Set<String>?) {
        Cookies.merge(this, lines)
        val ok = known == null || Cookies.savedHasSession(this, base)
        log("saved ${lines.size} cookies " + Cookies.names(lines) + ", session cookie in file: $ok")
        if (!ok) { Toast.makeText(this, "The login could not be saved. Try again, or use Import cookies.txt.", Toast.LENGTH_LONG).show(); return }
        finished = true
        State.cookiesSaved = true
        Toast.makeText(this, "Logged in", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun seen(url: String) {
        val u = Uri.parse(url)
        val h = u.host ?: return
        if (Cookies.baseDomain(h) == base) hosts.add(h)
        val blocked = h.endsWith("google.com") || url.contains("disallowed_useragent")
        if (blocked) {
            noticeText.text = "This site does not allow login inside apps. Tap Cancel, then use \"Import cookies.txt\"."
            notice.visibility = View.VISIBLE
        } else if (notice.visibility == View.VISIBLE && noticeText.text.startsWith("This site does not allow")) notice.visibility = View.GONE
    }

    private fun done() {
        val lines = Cookies.fromWebView(hosts, base)
        log("Done tapped, cookies: " + Cookies.names(lines))
        if (lines.isEmpty()) {
            Toast.makeText(this, "No login found yet. Log in first, then tap Done.", Toast.LENGTH_LONG).show(); return
        }
        if (!Cookies.hasSession(lines, base)) {
            // No login, but the site gave this browser its normal visitor cookies. They can be enough for a public link,
            // so keep them and try again. If it fails, Fetchy says login is still needed.
            Toast.makeText(this, "No login found. Saved the site's visitor cookies; trying again.", Toast.LENGTH_LONG).show()
            save(lines, null); return
        }
        save(lines, Cookies.sessionNames(base))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else finish() }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        Cookies.clearWebView()
        web.destroy()
        super.onDestroy()
    }
}

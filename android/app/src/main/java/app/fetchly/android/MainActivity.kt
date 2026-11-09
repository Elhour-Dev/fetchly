package app.fetchly.android

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var download: android.widget.Button
    private lateinit var status: LinearLayout
    private lateinit var sTitle: TextView
    private lateinit var sSub: TextView
    private lateinit var bar: android.widget.ProgressBar
    private lateinit var cancel: View
    private lateinit var logIn: View
    private lateinit var importBtn: View
    private lateinit var retry: View
    private lateinit var fix: View
    private lateinit var copy: View
    @Volatile private var ready = false
    private var pendingStart = false
    private val urlRegex = Regex("https?://\\S+")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = page(box)

        // Header: logo + name on the left, Help and Settings on the right (like the web header).
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo); setPadding(dp(9), dp(9), dp(9), dp(9))
            background = shape(col(R.color.accent), 12)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(label("Fetchly", 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        header.addView(button("Help", Kind.GHOST, 40) { openHelp(null) }.apply { setPadding(dp(16), 0, dp(16), 0) },
            LinearLayout.LayoutParams(-2, dp(40)))
        header.addView(button("Settings", Kind.GHOST, 40) { startActivity(Intent(this, SettingsActivity::class.java)) }
            .apply { setPadding(dp(16), 0, dp(16), 0) }, LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(8) })
        box.add(header)

        box.add(label("Paste a link.\nGet the video.", 30f, bold = true), 28)
        box.add(label("Works with YouTube, Instagram and many more sites.", 16f, R.color.muted), 8)

        // Big paste field + big download button.
        url = EditText(this).apply {
            id = R.id.url_field
            hint = "Paste video link here"; setHintTextColor(col(R.color.muted)); setTextColor(col(R.color.text))
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setSingleLine(); textSize = 18f; minHeight = dp(64)
            setPadding(dp(16), 0, dp(16), 0)
            background = shape(col(R.color.card), 16, 1, col(R.color.border))
        }
        box.add(url, 24)
        val small = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        small.addView(button("Paste", Kind.TONAL, 44) { pasteFromClipboard() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        small.addView(button("Clear", Kind.GHOST, 44) { url.setText("") }, LinearLayout.LayoutParams(0, dp(44), 1f))
        box.add(small, 10)

        download = button("Download", Kind.PRIMARY, 64) { startDownload() }.apply { textSize = 19f }
        box.add(download, 14, -1, dp(64))

        // One status card: progress, done, or error.
        status = card().apply { visibility = View.GONE }
        sTitle = label("", 17f, bold = true)
        sSub = label("", 15f)
        status.addView(sTitle)
        status.add(sSub, 4)
        bar = progressBar()
        status.add(bar, 12, -1, dp(8))
        cancel = button("Cancel download", Kind.GHOST) {
            startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
        }
        status.add(cancel, 14)
        logIn = button("Log in", Kind.PRIMARY) {
            startActivity(Intent(this, LoginActivity::class.java).putExtra("url", Cookies.loginUrl(State.failedUrl)))
        }
        status.add(logIn, 14)
        importBtn = button("Import cookies.txt", Kind.TONAL) {
            @Suppress("DEPRECATION") startActivityForResult(Cookies.pickIntent(), 20)
        }
        status.add(importBtn, 8)
        retry = button("Retry", Kind.PRIMARY) { startDownload() }
        status.add(retry, 14)
        fix = button("How to fix", Kind.TONAL) { openHelp(State.helpTopic) }
        status.add(fix, 8)
        copy = button("Copy error details", Kind.GHOST) { copyErrorDetails() }
        status.add(copy, 8)
        box.add(status, 20)

        box.add(label("Finished videos are saved in your Downloads folder.", 14f, R.color.muted), 24)

        setContentView(scroll)
        State.listener = { render() }

        // Get yt-dlp ready on a background thread (first start unpacks it).
        thread {
            try {
                Tools.init(this)
                ready = true
                runOnUiThread {
                    if (pendingStart) { pendingStart = false; State.phase = State.Phase.IDLE; State.text = ""; startDownload() } else { if (State.phase == State.Phase.IDLE) State.text = ""; render() }
                }
            } catch (e: Exception) {
                runOnUiThread { State.phase = State.Phase.ERROR; State.helpTopic = null; State.needsLogin = false
                    State.errorShort = "The download tools could not start."; State.errorDetails = e.message ?: e.toString(); render() }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() { super.onResume(); State.listener = { render() }; render() }
    override fun onDestroy() { State.listener = null; super.onDestroy() }

    /** Share menu: a link shared to Fetchly fills the box and starts the download. */
    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val shared = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        i.action = null
        val link = urlRegex.find(shared)?.value
        if (link == null) { State.phase = State.Phase.ERROR; State.helpTopic = "g_unsupported"; State.needsLogin = false
            State.errorShort = "No link found in what you shared."; State.errorDetails = ""; render(); return }
        url.setText(link)
        startDownload()
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val link = t?.let { urlRegex.find(it)?.value }
        if (link != null) url.setText(link) else Toast.makeText(this, "No link in the clipboard.", Toast.LENGTH_SHORT).show()
    }

    private fun startDownload() {
        val link = urlRegex.find(url.text.toString())?.value
        if (link == null) { Toast.makeText(this, "Please paste a link that starts with http:// or https://", Toast.LENGTH_LONG).show(); return }
        if (State.phase == State.Phase.RUNNING) return
        if (!ready) {
            pendingStart = true
            State.phase = State.Phase.RUNNING; State.percent = 0
            State.text = "Getting ready… the download will start by itself."
            render(); return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        State.cookiesSaved = false
        State.phase = State.Phase.RUNNING; State.text = "Starting…"; State.percent = 0
        startForegroundService(Intent(this, DownloadService::class.java).putExtra(DownloadService.EXTRA_URL, link))
        render()
    }

    private fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }

    private fun render() {
        val p = State.phase
        val running = p == State.Phase.RUNNING
        download.isEnabled = !running
        download.alpha = if (download.isEnabled) 1f else 0.5f

        show(status, p != State.Phase.IDLE)
        show(bar, running)
        show(cancel, running && ready)
        bar.progress = State.percent
        val err = p == State.Phase.ERROR
        val retryMode = err && State.cookiesSaved
        val login = err && State.needsLogin && !retryMode
        when (p) {
            State.Phase.RUNNING -> {
                tint(R.color.card, R.color.border, R.color.text, R.color.muted)
                sTitle.text = if (ready) "Downloading" else "Getting ready"
                sSub.text = State.text
            }
            State.Phase.DONE -> {
                tint(R.color.ok_bg, R.color.ok_border, R.color.ok_text, R.color.ok_text)
                sTitle.text = "Done"
                sSub.text = State.text.removePrefix("Done. ")
            }
            State.Phase.ERROR -> {
                tint(R.color.err_bg, R.color.err_border, R.color.err_text, R.color.err_text)
                if (retryMode) { sTitle.text = "Cookies saved"; sSub.text = "Tap Retry to download again." }
                else {
                    sTitle.text = State.errorShort
                    sSub.text = if (login) "This site wants you to be logged in. Tap \"Log in\", sign in, then tap Done. " +
                        "If the site does not allow it, tap \"Import cookies.txt\"." else ""
                }
            }
            else -> {}
        }
        show(sSub, sSub.text.isNotEmpty())
        show(logIn, login); show(importBtn, login); show(retry, retryMode)
        show(fix, err && State.helpTopic != null)
        show(copy, err)
    }

    private fun tint(bg: Int, border: Int, title: Int, sub: Int) {
        tintCard(status, bg, border)
        sTitle.setTextColor(col(title)); sSub.setTextColor(col(sub))
    }

    private fun openHelp(topic: String?) =
        startActivity(Intent(this, HelpActivity::class.java).putExtra("topic", topic))

    private fun copyErrorDetails() {
        val link = urlRegex.find(url.text.toString())?.value?.substringBefore('?') ?: ""
        val text = buildString {
            appendLine("Fetchly Android 1.0")
            appendLine("yt-dlp: " + Tools.version(this@MainActivity))
            appendLine("Android " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("Cookies saved: " + Tools.cookiesFile(this@MainActivity).exists())
            appendLine("Link: $link")
            appendLine("Error:")
            append(State.errorDetails)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fetchly error", text))
        Toast.makeText(this, "Error details copied.", Toast.LENGTH_SHORT).show()
    }

    // The user picked a cookies.txt: copied to private app storage, never logged.
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val err = Cookies.import(this, uri)
        if (err == null) State.cookiesSaved = true
        Toast.makeText(this, err ?: "Cookies imported.", Toast.LENGTH_LONG).show()
        render()
    }
}

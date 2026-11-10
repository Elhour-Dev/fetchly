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
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.concurrent.thread

class MainActivity : ThemedActivity() {
    private lateinit var url: EditText
    private lateinit var download: android.widget.Button
    private lateinit var status: LinearLayout
    private lateinit var sTitle: TextView
    private lateinit var sSub: TextView
    private lateinit var bar: android.widget.ProgressBar
    private lateinit var spin: android.widget.ProgressBar
    private lateinit var cancel: View
    private lateinit var logIn: View
    private lateinit var dismiss: View
    private lateinit var importBtn: View
    private lateinit var retry: View
    private lateinit var fix: View
    private lateinit var copy: View
    @Volatile private var ready = false
    private var pendingStart = false
    private val urlRegex = Regex("https?://\\S+")

    // Quality picker
    private var singleFor: String? = null   // a video+playlist link where "This video only" was chosen
    private lateinit var qCard: LinearLayout
    private lateinit var qNote: TextView
    private lateinit var qList: LinearLayout
    private var options: List<QOption>? = null
    private var subs: List<Sub> = emptyList()
    private lateinit var subsRow: LinearLayout
    private var selected = "best"
    private var loadedLink: String? = null
    private var loading = false
    private var failed = false
    private var failWhy = ""   // short reason the qualities could not be loaded (from the help topics)
    private var loadSeq = 0
    private var wasRunning = false
    private var slow = false   // the quality check is taking long: offer the Download button anyway
    private val slowTimer = Runnable { slow = true; renderQuality() }
    private val loader = Runnable { loadQualities() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = page(box)

        // Header: logo + name on the left, Help and Settings on the right (like the web header).
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(label("Fetchy", 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
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

        qCard = card().apply { visibility = View.GONE }
        qCard.addView(label("Quality", 13f, R.color.muted, bold = true))
        qNote = label("", 14f, R.color.muted)
        qCard.add(qNote, 6)
        qList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        qCard.add(qList, 8)
        subsRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        qCard.add(subsRow, 10)
        box.add(qCard, 14)
        url.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (State.phase == State.Phase.ERROR) { State.phase = State.Phase.IDLE; State.text = ""; State.needsLogin = false; State.helpTopic = null; State.cookiesSaved = false; render() }   // a new link clears the old error
                url.removeCallbacks(loader); url.postDelayed(loader, 500) }
        })

        // Hidden until the qualities are loaded (or could not be loaded): see renderQuality().
        download = button("Download", Kind.PRIMARY, 64) { startDownload() }.apply { textSize = 19f; visibility = View.GONE }
        box.add(download, 14, -1, dp(64))

        // One status card: progress, done, or error.
        status = card().apply { visibility = View.GONE }
        sTitle = label("", 17f, bold = true)
        sSub = label("", 15f)
        status.addView(sTitle)
        status.add(sSub, 4)
        bar = progressBar()
        status.add(bar, 12, -1, dp(8))
        spin = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
        }
        status.add(spin, 12, dp(28), dp(28))
        cancel = button("Cancel download", Kind.GHOST) {
            startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
        }
        status.add(cancel, 14)
        logIn = button("Log in", Kind.PRIMARY) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, LoginActivity::class.java).putExtra("url", Cookies.loginUrl(State.failedUrl)), 21)
        }
        status.add(logIn, 14)
        dismiss = button("Cancel", Kind.GHOST) { State.phase = State.Phase.IDLE; State.changed() }
        status.add(dismiss, 8)
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

        box.add(button("Download history", Kind.GHOST) { startActivity(Intent(this, HistoryActivity::class.java)) }, 20)
        box.add(label("Finished videos are saved in your Downloads folder.", 14f, R.color.muted), 16)

        setContentView(scroll)
        State.listener = { render() }

        // Get yt-dlp ready on a background thread (first start unpacks it).
        thread {
            try {
                Tools.init(this)
                ready = true
                // Once per app start: offer an update if the tools are old. It only updates after a tap.
                val offer = !State.startupChecked && Tools.updateOld(this) && !Tools.updateSnoozed(this)
                State.startupChecked = true
                runOnUiThread {
                    if (offer) showUpdateDialog(false) { startDownload() }
                    if (pendingStart) { pendingStart = false; State.phase = State.Phase.IDLE; State.text = ""; startDownload() } else { if (State.phase == State.Phase.IDLE) State.text = ""; render() }
                    loadQualities()
                }
            } catch (e: Exception) {
                runOnUiThread { State.phase = State.Phase.ERROR; State.helpTopic = null; State.needsLogin = false
                    State.errorShort = "The download tools could not start."; State.errorDetails = e.message ?: e.toString(); render() }
            }
        }
        handleIntent(intent)
        checkAppUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private val shownTheme by lazy { Theme.mode(this) }
    override fun onResume() {
        super.onResume()
        if (Theme.mode(this) != shownTheme) { recreate(); return }
        State.listener = { render() }; render()
    }
    override fun onDestroy() { State.listener = null; super.onDestroy() }

    /** Share menu: a link shared to Fetchy fills the box and starts the download. */
    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val shared = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        i.action = null
        val link = urlRegex.find(shared)?.value
        if (link == null) { State.phase = State.Phase.ERROR; State.helpTopic = "g_unsupported"; State.needsLogin = false
            State.errorShort = "No link found in what you shared."; State.errorDetails = ""; render(); return }
        url.setText(link)   // the quality picker loads; tap Download to start
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
        // Playlist links open the playlist screen. A link with a video and a list asks first.
        if (Pl.isList(link) && link != singleFor) {
            val open = { startActivity(Intent(this, PlaylistActivity::class.java).putExtra("url", link)) }
            if (!Pl.hasVideo(link)) open()
            else android.app.AlertDialog.Builder(this).setTitle("This link has a video and a playlist.")
                .setItems(arrayOf("This video only", "Whole playlist")) { _, i -> if (i == 0) { singleFor = link; startDownload() } else open() }
                .show()
            return
        }
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
        State.updateOffered = false
        State.phase = State.Phase.RUNNING; State.text = "Fetching video info…"; State.percent = 0
        State.stage = State.Stage.FETCH; State.failedStage = null
        cancelLoad()
        renderQuality()
        val opt = if (loadedLink == link) options?.firstOrNull { it.key == selected } else null
        val sub = if (opt != null && !opt.audio && Quality.subsOn(this)) Quality.pickSub(this, subs) else null
        if (opt != null) Quality.remember(this, opt.key)
        else Toast.makeText(this, if (failed) "Could not load the qualities. Downloading the best quality."
            else "Downloading the best quality.", Toast.LENGTH_LONG).show()
        startForegroundService(Intent(this, DownloadService::class.java).putExtra(DownloadService.EXTRA_URL, link)
            .putExtra(DownloadService.EXTRA_FORMAT, opt?.format).putExtra(DownloadService.EXTRA_AUDIO, opt?.audio == true)
            .putExtra(DownloadService.EXTRA_USE_INFO, opt != null)
            .putExtra(DownloadService.EXTRA_CODEC, opt?.codec).putExtra(DownloadService.EXTRA_LABEL, opt?.label)
            .putExtra(DownloadService.EXTRA_SUBS, sub?.code).putExtra(DownloadService.EXTRA_SUBS_AUTO, sub?.auto == true))
        render()
    }

    private fun cancelLoad() {
        url.removeCallbacks(loader); url.removeCallbacks(slowTimer); slow = false
        try { YoutubeDL.getInstance().destroyProcessById("formats-$loadSeq") } catch (_: Exception) {}
        if (loading) loadedLink = null   // an unfinished check does not count: it runs again after the download
        loadSeq++; loading = false
    }

    /** Ask yt-dlp what this video offers (runs after a link is pasted, and again when the link changes). */
    private fun loadQualities() {
        val link = urlRegex.find(url.text.toString())?.value
        // A pure playlist link has its own screen with its own quality choice.
        if (link == null || (Pl.isList(link) && !Pl.hasVideo(link))) { cancelLoad(); loadedLink = null; options = null; failed = false; renderQuality(); return }
        if (link == loadedLink || State.phase == State.Phase.RUNNING) return
        cancelLoad()
        options = null; subs = emptyList(); failed = false; loading = true
        if (!ready) { renderQuality(); return }   // starts again when the tools are ready
        loadedLink = link
        url.postDelayed(slowTimer, 40_000)
        renderQuality()
        val seq = loadSeq
        thread {
            var why = ""
            val res: Loaded? = try { Quality.fetch(this, link, "formats-$seq") } catch (e: Exception) {
                why = Help.find((e.message ?: e.toString()).trim(), link)?.simple.orEmpty(); null }
            runOnUiThread {
                if (seq != loadSeq) return@runOnUiThread
                loading = false; slow = false; url.removeCallbacks(slowTimer)
                options = res?.options?.takeIf { it.isNotEmpty() }
                subs = if (options != null) res?.subs.orEmpty() else emptyList()
                failed = options == null
                failWhy = if (failed) why else ""
                val want = Quality.remembered(this)
                selected = if (options?.any { it.key == want } == true) want else "best"
                renderQuality()
            }
        }
    }

    private fun renderQuality() {
        val has = loading || options != null || failed
        show(qCard, has)
        // The Download button appears with the qualities. If they could not be loaded it appears alone and
        // downloads the best quality. A playlist-only link gets it at once: the playlist screen has its own choice.
        val link = urlRegex.find(url.text.toString())?.value
        val listOnly = link != null && Pl.isList(link) && !Pl.hasVideo(link)
        show(download, options != null || failed || listOnly || (loading && slow))
        qList.removeAllViews()
        qNote.text = when {
            loading -> if (!ready) "Getting ready…" else if (slow) "Still checking… this is taking long. You can tap Download to get the best quality now."
                else "Checking what this video offers…"
            failed -> (if (failWhy.isNotEmpty()) failWhy else "Could not load the qualities.") + " Tap Download to try the best quality anyway."
            else -> ""
        }
        show(qNote, qNote.text.isNotEmpty())
        options?.forEach { o ->
            val on = o.key == selected
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = shape(col(if (on) R.color.tonal_bg else R.color.card), 14, if (on) 2 else 1, col(if (on) R.color.accent else R.color.border))
                setOnClickListener { selected = o.key; renderQuality() }
            }
            row.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    if (on) setColor(col(R.color.accent))
                    setStroke(dp(2), col(if (on) R.color.accent else R.color.border))
                }
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) })
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            names.addView(label(o.label, 16f, if (on) R.color.tonal_text else R.color.text, bold = true))
            names.addView(label(o.detail, 13f, R.color.muted))
            row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            o.size?.let { row.addView(label("~" + Quality.size(it), 14f, R.color.muted)) }
            qList.add(row, if (qList.childCount == 0) 0 else 8)
        }
        renderSubs()
    }

    /** "Embed subtitles" switch and language button; only for video qualities of a video that has subtitles. */
    private fun renderSubs() {
        subsRow.removeAllViews()
        val sel = options?.firstOrNull { it.key == selected }
        val has = sel != null && !sel.audio && subs.isNotEmpty()
        show(subsRow, has)
        if (!has) return
        subsRow.addView(android.widget.CheckBox(this).apply {
            text = "Embed subtitles"; textSize = 15f; setTextColor(col(R.color.text))
            isChecked = Quality.subsOn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Quality.setSubsOn(this@MainActivity, on) }
        })
        val cur = Quality.pickSub(this, subs)!!
        subsRow.add(button("Language: ${cur.name}", Kind.GHOST, 40) {
            android.app.AlertDialog.Builder(this).setTitle("Subtitle language")
                .setItems(subs.map { if (it.name == it.code) it.code else "${it.name} (${it.code})" }.toTypedArray()) { _, i ->
                    Quality.setSubLang(this, subs[i].code); renderSubs()
                }.show()
        }, 6, -1, dp(40))
    }

    private fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }

    private fun render() {
        val p = State.phase
        val running = p == State.Phase.RUNNING
        download.isEnabled = !running
        download.alpha = if (download.isEnabled) 1f else 0.5f
        // A link pasted during a download is checked once the download is over.
        if (wasRunning && !running) url.post { loadQualities() }
        wasRunning = running

        show(status, p != State.Phase.IDLE)
        val downloading = running && ready && State.stage == State.Stage.DOWNLOAD
        show(bar, downloading)                 // a real progress bar only while bytes are coming in
        show(spin, running && !downloading)    // spinner while fetching info, finishing, or getting ready
        show(cancel, running && ready)
        bar.progress = State.percent
        val err = p == State.Phase.ERROR
        val retryMode = err && State.cookiesSaved
        val login = err && State.needsLogin && !retryMode
        when (p) {
            State.Phase.RUNNING -> {
                tint(R.color.card, R.color.border, R.color.text, R.color.muted)
                when {
                    !ready -> { sTitle.text = "Getting ready"; sSub.text = State.text }
                    State.stage == State.Stage.FETCH -> { sTitle.text = "Fetching video info…"; sSub.text = "" }
                    State.stage == State.Stage.DOWNLOAD -> { sTitle.text = "Downloading…"; sSub.text = State.item + "${State.percent}%" }
                    else -> { sTitle.text = "Finishing up…"; sSub.text = "Merging video and audio." }
                }
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
                    val fresh = State.helpTopic == "g_update" && Tools.recentlyUpdated(this)
                    sTitle.text = if (fresh) "This site cannot be read right now." else State.errorShort
                    val at = State.failedStage?.let { "Failed while ${it.failedAt}." } ?: ""
                    val base = Cookies.baseDomain(Cookies.hostOf(State.failedUrl))
                    val loggedIn = Cookies.sessionNames(base) != null && Cookies.savedHasSession(this, base)
                    sSub.text = (if (fresh) "The tools are already up to date. The site changed and the downloader cannot read this link yet. " +
                        "Try again in a few days, after the next yt-dlp update. "
                    else if (login && loggedIn) when (State.sessionCode) {
                        200 -> "Instagram accepts your login, but gave no video for this link. The story may be gone, or this account cannot see it. "
                        401, 403 -> "Instagram no longer accepts the saved login. Tap \"Log in\" to sign in again. "
                        429 -> "Instagram is limiting this network right now. Turn off VPN or switch network, wait a few minutes, then tap Retry. "
                        else -> "Fetchy has your $base login, but the site still asked for one. " +
                            "The login may have expired, or this account cannot see this link. Log in again, or import a fresh cookies.txt. "
                    }
                    else if (login) "Tap \"Log in\", sign in, then tap Done. Fetchy then retries by itself. " +
                        "If the site does not allow it, use \"Import cookies.txt\" in Settings. " else "") + at
                }
            }
            else -> {}
        }
        if (err && !State.updateOffered && State.helpTopic in Tools.updateTopics && !Tools.updateSnoozed(this)) {
            State.updateOffered = true
            showUpdateDialog(true) { startDownload() }
        }
        show(sSub, sSub.text.isNotEmpty())
        show(logIn, login); show(dismiss, login); show(importBtn, false); show(retry, retryMode)
        show(fix, err && !login && State.helpTopic != null)
        show(copy, err && !login)
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
            appendLine("Fetchy Android 1.0")
            appendLine("yt-dlp: " + Tools.version(this@MainActivity))
            appendLine("Android " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("Cookies saved: " + Tools.cookiesFile(this@MainActivity).exists())
            val base = Cookies.baseDomain(Cookies.hostOf(State.failedUrl))
            if (Cookies.sessionNames(base) != null) appendLine("Login cookie for $base in file: " + Cookies.savedHasSession(this@MainActivity, base))
            if (State.sessionCode != 0) appendLine("Instagram login check: HTTP " + State.sessionCode)
            appendLine("Link: $link")
            appendLine("Failed while: " + (State.failedStage?.failedAt ?: "unknown"))
            appendLine("Error:")
            append(State.errorDetails)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fetchy error", text))
        Toast.makeText(this, "Error details copied.", Toast.LENGTH_SHORT).show()
    }

    // The user picked a cookies.txt: copied to private app storage, never logged.
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 21) {   // login page
            when (resultCode) {
                RESULT_OK -> { Toast.makeText(this, "Logged in. Retrying the download…", Toast.LENGTH_SHORT).show(); startDownload() }
                LoginActivity.RESULT_IMPORT -> @Suppress("DEPRECATION") startActivityForResult(Cookies.pickIntent(), 20)
            }
            return
        }
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val err = Cookies.import(this, uri)
        if (err == null) State.cookiesSaved = true
        val base = Cookies.baseDomain(Cookies.hostOf(State.failedUrl))
        val noLogin = err == null && Cookies.sessionNames(base) != null && !Cookies.savedHasSession(this, base)
        Toast.makeText(this, err ?: if (noLogin) "Imported, but this file has no login cookie for $base. Export it again while logged in."
            else "Cookies imported.", Toast.LENGTH_LONG).show()
        render()
    }
}

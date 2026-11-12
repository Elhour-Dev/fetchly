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
    private lateinit var dlBox: LinearLayout              // one card per download, newest on top
    private val cards = HashMap<Int, DlCard>()
    @Volatile private var ready = false
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
    private var ytAskedLink = ""   // the YouTube link the login question was already shown for
    private var notSupported = ""   // "Not supported: ..." for an Instagram story or a Snapchat link
    private var loadSeq = 0
    private var loaded: Loaded? = null   // title, length and picture of the checked video
    private lateinit var preview: LinearLayout
    private lateinit var thumbBox: android.widget.FrameLayout
    private lateinit var thumb: ImageView
    private lateinit var durBadge: TextView
    private lateinit var pTitle: TextView
    private lateinit var pMeta: TextView
    private var slow = false   // the quality check is taking long: offer the Download button anyway
    private val slowTimer = Runnable { slow = true; renderQuality() }
    private val loader = Runnable { loadQualities() }

    // Start screen (update check) is shown first; pop-ups wait until the main page is up.
    private lateinit var scroll: android.widget.ScrollView
    private var starting = false
    private val afterStartQueue = ArrayList<() -> Unit>()
    private fun afterStart(f: () -> Unit) { if (starting) afterStartQueue.add(f) else f() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = page(box)

        // Header: logo + name on the left, Help and Settings on the right (like the web header).
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(label("Fetchy", 20f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))   // pushes Help and Settings to the far side
        header.addView(button(getString(R.string.common_help), Kind.GHOST, 40) { openHelp(null) }.apply { setPadding(dp(16), 0, dp(16), 0) },
            LinearLayout.LayoutParams(-2, dp(40)))
        header.addView(button(getString(R.string.common_settings), Kind.GHOST, 40) { startActivity(Intent(this, SettingsActivity::class.java)) }
            .apply { setPadding(dp(16), 0, dp(16), 0) }, LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(8) })
        box.add(header)

        box.add(label(getString(R.string.main_tagline), 30f, bold = true), 28)
        box.add(label(getString(R.string.main_works_with), 16f, R.color.muted), 8)

        // Big paste field + big download button.
        url = EditText(this).apply {
            id = R.id.url_field
            hint = getString(R.string.main_paste_hint); setHintTextColor(col(R.color.muted)); setTextColor(col(R.color.text))
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setSingleLine(); textSize = 18f; minHeight = dp(64)
            setPadding(dp(16), 0, dp(16), 0)
            background = shape(col(R.color.card), 16, 1, col(R.color.border))
        }
        box.add(url, 24)
        val small = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        small.addView(button(getString(R.string.common_paste), Kind.TONAL, 44) { pasteFromClipboard() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        small.addView(button(getString(R.string.common_clear), Kind.GHOST, 44) { url.setText("") }, LinearLayout.LayoutParams(0, dp(44), 1f))
        box.add(small, 10)

        qCard = card().apply { visibility = View.GONE }
        // The video's picture, length and title, above the qualities (shown once they are loaded).
        preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 0, 0, dp(14)) }
        thumbBox = object : android.widget.FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) =   // 16:9, like the desktop app
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w) * 9 / 16, MeasureSpec.EXACTLY))
        }.apply { background = shape(col(R.color.border), 12); clipToOutline = true }
        thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumbBox.addView(thumb, android.widget.FrameLayout.LayoutParams(-1, -1))
        durBadge = label("", 12f, bold = true).apply {
            setTextColor(android.graphics.Color.WHITE); setPadding(dp(7), dp(3), dp(7), dp(3))
            background = shape(0xB3000000.toInt(), 6)
        }
        thumbBox.addView(durBadge, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(8); bottomMargin = dp(8) })
        preview.addView(thumbBox, LinearLayout.LayoutParams(-1, -2))
        pTitle = label("", 16f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        preview.add(pTitle, 10)
        pMeta = label("", 13f, R.color.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        preview.add(pMeta, 2)
        qCard.addView(preview)
        qCard.addView(label(getString(R.string.main_quality), 13f, R.color.muted, bold = true))
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
                url.removeCallbacks(loader); url.postDelayed(loader, 500) }
        })

        // Hidden until the qualities are loaded (or could not be loaded): see renderQuality().
        download = button(getString(R.string.common_download), Kind.PRIMARY, 64) { startDownload() }.apply { textSize = 19f; visibility = View.GONE }
        box.add(download, 14, -1, dp(64))

        // Downloads: one card each (progress, done, or error). Several can run; the box above stays free.
        dlBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        box.add(dlBox, 20)
        box.add(button(getString(R.string.main_history), Kind.GHOST) { startActivity(Intent(this, HistoryActivity::class.java)) }, 20)
        box.add(label(getString(R.string.main_saved_note), 14f, R.color.muted), 16)

        // Once per app start: the update check screen comes before the main page.
        if (State.appUpdateChecked) setContentView(scroll)
        else { State.appUpdateChecked = true; starting = true; showStartScreen { startDone() } }
        State.listener = { render() }

        // Get yt-dlp ready on a background thread (first start unpacks it).
        thread {
            try {
                Tools.init(this)
                ready = true
                // Once per app start: offer an update if the tools are old. It only updates after a tap.
                // Not on the very first start: a new user should see the app, not a pop-up. A failing download still offers it.
                val sp = getSharedPreferences("prefs", MODE_PRIVATE)
                val firstRun = !sp.getBoolean("started_before", false)
                if (firstRun) sp.edit().putBoolean("started_before", true).apply()
                val offer = !firstRun && !State.startupChecked && Tools.updateOld(this) && !Tools.updateSnoozed(this)
                State.startupChecked = true
                runOnUiThread {
                    if (offer) afterStart { showUpdateDialog(false) { startDownload() } }
                    render()
                    loadQualities()
                }
            } catch (e: Exception) {
                runOnUiThread { State.dls.add(0, Dl.problem(getString(R.string.main_tools_failed), e.message ?: e.toString())); render() }
            }
        }
        handleIntent(intent)
    }

    /** The start screen is done: show the main page, then anything that waited for it. */
    private fun startDone() {
        if (isFinishing || isDestroyed) return
        setContentView(scroll)
        scroll.requestApplyInsets()
        starting = false
        afterStartQueue.toList().forEach { it() }
        afterStartQueue.clear()
        loadQualities()
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
        AppUpdate.installPending(this)   // an update that was waiting for the "install apps" permission
    }
    override fun onDestroy() { State.listener = null; super.onDestroy() }

    /** Share menu: a link shared to Fetchy fills the box and starts the download. */
    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val shared = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        i.action = null
        val link = urlRegex.find(shared)?.value
        if (link == null) { State.dls.add(0, Dl.problem(getString(R.string.main_no_link_shared), topic = "g_unsupported")); render(); return }
        url.setText(link)   // the quality picker loads; tap Download to start
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val link = t?.let { urlRegex.find(it)?.value }
        if (link != null) url.setText(link) else Toast.makeText(this, getString(R.string.main_no_link_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun startDownload() {
        val link = urlRegex.find(url.text.toString())?.value
        if (link == null) { Toast.makeText(this, getString(R.string.main_bad_link), Toast.LENGTH_LONG).show(); return }
        Links.unsupported(this, link)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); return }
        // Playlist links open the playlist screen. A link with a video and a list asks first.
        if (Pl.isList(link) && link != singleFor) {
            val open = { startActivity(Intent(this, PlaylistActivity::class.java).putExtra("url", link)) }
            if (!Pl.hasVideo(link)) open()
            else android.app.AlertDialog.Builder(this).setTitle(getString(R.string.main_video_and_list))
                .setItems(arrayOf(getString(R.string.main_this_video_only), getString(R.string.main_whole_playlist))) { _, i -> if (i == 0) { singleFor = link; startDownload() } else open() }
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        State.cookiesSaved = false
        val opt = if (loadedLink == link) options?.firstOrNull { it.key == selected } else null
        val sub = if (opt != null && !opt.audio && Quality.subsOn(this)) Quality.pickSub(this, subs) else null
        if (opt != null) Quality.remember(this, opt.key)
        // The picker's saved answer gets its own copy: the next link pasted overwrites it.
        val id = State.nextId()
        val info = if (opt == null) null else try {
            java.io.File(cacheDir, "info-$id.json").also { Quality.infoFile(this).copyTo(it, true) }
        } catch (_: Exception) { null }
        startDl(Dl(id, link, opt?.format ?: Quality.BEST, opt?.audio == true, opt?.codec ?: "mp3", opt?.label.orEmpty(),
            sub?.code, sub?.auto == true, info, if (opt != null) loaded?.title.orEmpty() else ""))
        Toast.makeText(this, when {
            opt != null -> getString(R.string.main_started)
            failed -> getString(R.string.main_started_failed_q)
            else -> getString(R.string.main_started_best)
        }, Toast.LENGTH_LONG).show()
        url.setText("")   // free the box for the next link
        loadQualities()
    }

    /** Add a download to the list and hand it to the service (it waits if enough are running already). */
    private fun startDl(d: Dl) {
        State.dls.filter { !it.active }.drop(9).forEach { State.dls.remove(it) }   // keep the list short
        State.dls.add(0, d)
        DownloadService.start(this, d)
        render()
    }

    /** Try a failed download again, as a new card in place of the old one. */
    private fun retry(old: Dl) {
        if (old.url.isEmpty()) return
        State.cookiesSaved = false
        State.dls.remove(old)
        startDl(old.again())
    }

    private fun cancelLoad() {
        url.removeCallbacks(loader); url.removeCallbacks(slowTimer); slow = false
        try { YoutubeDL.getInstance().destroyProcessById("formats-$loadSeq") } catch (_: Exception) {}
        if (loading) loadedLink = null   // an unfinished check does not count: it runs again after the download
        loadSeq++; loading = false
    }

    /** Ask yt-dlp what this video offers (runs after a link is pasted, and again when the link changes). */
    private fun loadQualities() {
        if (starting) return   // runs again when the main page shows
        val link = urlRegex.find(url.text.toString())?.value
        if (link == null) ytAskedLink = ""   // the field was cleared: the same link may ask again
        notSupported = link?.let { Links.unsupported(this, it) }.orEmpty()
        if (notSupported.isNotEmpty()) {   // say it at once, no check and no Download button
            cancelLoad(); loadedLink = null; options = null; failed = false; renderQuality(); return
        }
        if (link != null && askYoutubeLogin(link)) {
            cancelLoad(); loadedLink = null; options = null; failed = false; renderQuality(); return
        }
        // A pure playlist link has its own screen with its own quality choice.
        if (link == null || (Pl.isList(link) && !Pl.hasVideo(link))) { cancelLoad(); loadedLink = null; options = null; failed = false; renderQuality(); return }
        if (link == loadedLink) return
        cancelLoad()
        options = null; subs = emptyList(); failed = false; loading = true; loaded = null
        if (!ready) { renderQuality(); return }   // starts again when the tools are ready
        loadedLink = link
        url.postDelayed(slowTimer, 40_000)
        renderQuality()
        val seq = loadSeq
        thread {
            var why = ""
            val res: Loaded? = try { Quality.fetch(this, link, "formats-$seq") } catch (e: Exception) {
                val t = Help.find((e.message ?: e.toString()).trim(), link)
                why = if (t?.id == "g_update" && Tools.recentlyUpdated(this)) getString(R.string.common_site_unreadable) else t?.let { getString(it.simple) }.orEmpty(); null }
            runOnUiThread {
                if (seq != loadSeq) return@runOnUiThread
                loading = false; slow = false; url.removeCallbacks(slowTimer)
                options = res?.options?.takeIf { it.isNotEmpty() }
                subs = if (options != null) res?.subs.orEmpty() else emptyList()
                failed = options == null
                failWhy = if (failed) why else ""
                val want = Quality.remembered(this)
                selected = if (options?.any { it.key == want } == true) want else "best"
                loaded = res?.takeIf { options != null }
                loaded?.let { showPreview(it, link, seq) }
                renderQuality()
            }
        }
    }

    /** Fill in the picture, length and title above the qualities. The picture loads on its own thread. */
    private fun showPreview(l: Loaded, link: String, seq: Int) {
        pTitle.text = l.title.ifEmpty { getString(R.string.main_untitled) }
        pMeta.text = listOf(l.uploader, Cookies.hostOf(link).removePrefix("www.")).filter { it.isNotEmpty() }.joinToString(" · ")
        show(pMeta, pMeta.text.isNotEmpty())
        val secs = l.duration.toLong()
        durBadge.text = if (secs <= 0) "" else if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
            else "%d:%02d".format(secs / 60, secs % 60)
        show(durBadge, secs > 0)
        thumb.setImageDrawable(null)
        val pic = l.thumbnail.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        show(thumbBox, pic != null)
        if (pic == null) return
        val width = resources.displayMetrics.widthPixels
        thread {
            val bmp = loadPicture(pic, width)
            runOnUiThread {
                if (seq != loadSeq) return@runOnUiThread
                if (bmp != null) thumb.setImageBitmap(bmp) else show(thumbBox, false)
            }
        }
    }

    /** Download a thumbnail, scaled down to about [maxWidth] pixels. Null when it cannot be loaded. */
    private fun loadPicture(link: String, maxWidth: Int): android.graphics.Bitmap? = try {
        val c = java.net.URL(link).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        val bytes = c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > 8_000_000) error("too big") }
            out.toByteArray()
        }
        c.disconnect()
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }

    /**
     * First YouTube link while not logged in to YouTube: suggest logging in now, so the download does not fail later.
     * Asked only once. Uses the normal login screen. True when the question is shown (the qualities load after it).
     */
    private fun askYoutubeLogin(link: String): Boolean {
        val base = Cookies.baseDomain(Cookies.hostOf(link))
        if (base != "youtube.com" && base != "youtu.be") return false
        // Asked for every YouTube link while no YouTube login is saved, but only once per link (not again after "Not now").
        if (link == ytAskedLink || Cookies.savedHasSession(this, "youtube.com")) return false
        ytAskedLink = link
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = card(pad = 20)
        box.addView(label(getString(R.string.yt_login_title), 20f, bold = true))
        box.add(label(getString(R.string.yt_login_body), 15f, R.color.muted), 8)
        box.add(label(getString(R.string.yt_login_tip), 14f, R.color.muted), 8)
        var toLogin = false
        box.add(button(getString(R.string.common_log_in), Kind.PRIMARY) {
            toLogin = true; dlg.dismiss()
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, LoginActivity::class.java).putExtra("url", Cookies.YOUTUBE_LOGIN), 22)
        }, 18, -1, dp(48))
        box.add(button(getString(R.string.main_not_now), Kind.GHOST) { dlg.dismiss() }, 8, -1, dp(48))
        dlg.setOnDismissListener { if (!toLogin) loadQualities() }   // "Not now" or Back: go on with the link
        dlg.setContentView(box)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(400)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        return true
    }

    private fun renderQuality() {
        val has = loading || options != null || failed || notSupported.isNotEmpty()
        show(qCard, has)
        show(preview, options != null && loaded != null)
        // The Download button appears with the qualities. If they could not be loaded it appears alone and
        // downloads the best quality. A playlist-only link gets it at once: the playlist screen has its own choice.
        val link = urlRegex.find(url.text.toString())?.value
        val listOnly = link != null && Pl.isList(link) && !Pl.hasVideo(link)
        show(download, options != null || failed || listOnly || (loading && slow))
        qList.removeAllViews()
        qNote.text = when {
            notSupported.isNotEmpty() -> notSupported
            loading -> if (!ready) getString(R.string.q_getting_ready) else if (slow) getString(R.string.q_still_checking)
                else getString(R.string.q_checking)
            failed -> getString(R.string.q_failed_note, if (failWhy.isNotEmpty()) failWhy else getString(R.string.q_load_failed))
            else -> ""
        }
        qNote.setTextColor(col(if (notSupported.isNotEmpty()) R.color.err_text else R.color.muted))
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
            text = getString(R.string.subs_embed); textSize = 15f; setTextColor(col(R.color.text))
            isChecked = Quality.subsOn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Quality.setSubsOn(this@MainActivity, on) }
        })
        val cur = Quality.pickSub(this, subs)!!
        subsRow.add(button(getString(R.string.subs_language, cur.name), Kind.GHOST, 40) {
            android.app.AlertDialog.Builder(this).setTitle(getString(R.string.subs_pick_title))
                .setItems(subs.map { if (it.name == it.code) it.code else "${it.name} (${it.code})" }.toTypedArray()) { _, i ->
                    Quality.setSubLang(this, subs[i].code); renderSubs()
                }.show()
        }, 6, -1, dp(40))
    }

    private fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }

    private fun render() {
        val list = State.dls.toList()
        cards.keys.filter { id -> list.none { it.id == id } }.forEach { id -> cards.remove(id)?.let { dlBox.removeView(it.root) } }
        list.forEachIndexed { i, d ->
            val c = cards.getOrPut(d.id) { DlCard(d) }
            if (dlBox.indexOfChild(c.root) != i) { dlBox.removeView(c.root); dlBox.addView(c.root, i, LinearLayout.LayoutParams(-1, -2)) }
            (c.root.layoutParams as LinearLayout.LayoutParams).topMargin = if (i == 0) 0 else dp(12)
            c.bind()
        }
        show(dlBox, list.isNotEmpty())
    }

    /** One download's card: progress, done, or error, with its own buttons. */
    private inner class DlCard(val d: Dl) {
        val root = card()
        val name = label("", 13f, R.color.muted, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val title = label("", 17f, bold = true)
        val sub = label("", 15f)
        val bar = progressBar()
        val spin = android.widget.ProgressBar(this@MainActivity).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
        }
        val cancel = button(getString(R.string.dl_cancel), Kind.GHOST) { DownloadService.cancel(d) }
        val logIn = button(getString(R.string.common_log_in), Kind.PRIMARY) {
            State.loginFor = d.id
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this@MainActivity, LoginActivity::class.java).putExtra("url", Cookies.loginUrl(d.url)), 21)
        }
        val retryBtn = button(getString(R.string.common_retry), Kind.PRIMARY) { retry(d) }
        val fix = button(getString(R.string.dl_how_fix), Kind.TONAL) { openHelp(d.helpTopic) }
        val copy = button(getString(R.string.dl_copy_details), Kind.GHOST) { copyErrorDetails(d) }
        val close = button(getString(R.string.common_close), Kind.GHOST) { State.dls.remove(d); render() }
        private var autoClose = false

        init {
            root.addView(name); root.add(title, 2); root.add(sub, 4)
            root.add(bar, 12, -1, dp(8)); root.add(spin, 12, dp(28), dp(28))
            root.add(cancel, 14); root.add(logIn, 14); root.add(retryBtn, 14)
            root.add(fix, 8); root.add(copy, 8); root.add(close, 8)
        }

        fun bind() {
            val p = d.phase
            val running = p == Dl.Phase.RUNNING
            val waiting = p == Dl.Phase.QUEUED
            name.text = d.name.ifEmpty { Cookies.hostOf(d.url).removePrefix("www.") }
            show(name, name.text.isNotEmpty())
            val downloading = running && d.stage == State.Stage.DOWNLOAD
            show(bar, downloading)                            // a real progress bar only while bytes are coming in
            show(spin, (running && !downloading) || waiting)  // spinner while waiting, fetching info, or finishing
            show(cancel, running || waiting)
            bar.progress = d.percent
            val err = p == Dl.Phase.ERROR
            val retryMode = err && State.cookiesSaved && d.url.isNotEmpty()
            val login = err && d.needsLogin && !retryMode
            when (p) {
                Dl.Phase.QUEUED -> {
                    tint(R.color.card, R.color.border, R.color.text, R.color.muted)
                    title.text = getString(R.string.dl_waiting); sub.text = getString(R.string.dl_waiting_sub)
                }
                Dl.Phase.RUNNING -> {
                    tint(R.color.card, R.color.border, R.color.text, R.color.muted)
                    when (d.stage) {
                        State.Stage.FETCH -> { title.text = getString(R.string.dl_fetching); sub.text = "" }
                        State.Stage.DOWNLOAD -> { title.text = getString(R.string.common_downloading); sub.text = d.item + "${d.percent}%" }
                        else -> { title.text = getString(R.string.common_finishing); sub.text = getString(R.string.dl_merging) }
                    }
                }
                Dl.Phase.DONE -> {
                    tint(R.color.ok_bg, R.color.ok_border, R.color.ok_text, R.color.ok_text)
                    title.text = getString(R.string.common_done)
                    sub.text = d.text
                    // A finished card closes by itself after a while, to make room (the file is in History).
                    if (!autoClose) { autoClose = true; root.postDelayed({ if (State.dls.remove(d)) render() }, 10_000) }
                }
                Dl.Phase.ERROR -> {
                    tint(R.color.err_bg, R.color.err_border, R.color.err_text, R.color.err_text)
                    if (retryMode) { title.text = getString(R.string.dl_cookies_saved); sub.text = getString(R.string.dl_tap_retry) }
                    else {
                        val fresh = d.helpTopic == "g_update" && Tools.recentlyUpdated(this@MainActivity)
                        title.text = if (fresh) getString(R.string.common_site_unreadable) else d.errorShort
                        val at = d.failedStage?.let { failedWhile(it) } ?: ""
                        val base = Cookies.baseDomain(Cookies.hostOf(d.url))
                        val loggedIn = Cookies.sessionNames(base) != null && Cookies.savedHasSession(this@MainActivity, base)
                        sub.text = (if (fresh) getString(R.string.err_fresh) + " "
                        else if (login && loggedIn) when (d.sessionCode) {
                            200 -> getString(R.string.err_ig_200) + " "
                            401, 403 -> getString(R.string.err_ig_401) + " "
                            429 -> getString(R.string.err_ig_429) + " "
                            else -> getString(R.string.err_has_login, base) + " "
                        }
                        else if (login) getString(R.string.err_ask_login) + " " else "") + at
                    }
                }
            }
            if (err && !d.updateOffered && d.helpTopic in Tools.updateTopics && !Tools.updateSnoozed(this@MainActivity)) {
                d.updateOffered = true
                showUpdateDialog(true) { retry(d) }
            }
            show(sub, sub.text.isNotEmpty())
            show(logIn, login); show(retryBtn, retryMode)
            show(fix, err && d.helpTopic != null && (!login || loggedInNow(d)))
            show(copy, err)   // always on an error, also when a login is asked (so a failed login can be reported)
            close.text = getString(if (login) R.string.common_cancel else R.string.common_close)
            show(close, p == Dl.Phase.DONE || err)
        }

        private fun tint(bg: Int, border: Int, t: Int, s: Int) {
            tintCard(root, bg, border)
            title.setTextColor(col(t)); sub.setTextColor(col(s)); name.setTextColor(col(s))
        }
    }

    private fun failedWhile(s: State.Stage) = getString(when (s) {
        State.Stage.FETCH -> R.string.err_failed_fetch
        State.Stage.DOWNLOAD -> R.string.err_failed_download
        State.Stage.FINISH -> R.string.err_failed_finish
    })

    private fun openHelp(topic: String?) =
        startActivity(Intent(this, HelpActivity::class.java).putExtra("topic", topic))

    private fun loggedInNow(d: Dl): Boolean {
        val base = Cookies.baseDomain(Cookies.hostOf(d.url))
        return Cookies.sessionNames(base) != null && Cookies.savedHasSession(this, base)
    }

    private fun copyErrorDetails(d: Dl) {
        val link = d.url.substringBefore('?')
        val text = buildString {
            appendLine("Fetchy Android " + packageManager.getPackageInfo(packageName, 0).versionName)
            appendLine("yt-dlp: " + Tools.version(this@MainActivity))
            appendLine("Android " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("Cookies saved: " + Tools.cookiesFile(this@MainActivity).exists())
            val base = Cookies.baseDomain(Cookies.hostOf(d.url))
            if (Cookies.sessionNames(base) != null) appendLine("Login cookie for $base in file: " + Cookies.savedHasSession(this@MainActivity, base))
            if (d.sessionCode != 0) appendLine("Instagram login check: HTTP " + d.sessionCode)
            appendLine("Link: $link")
            appendLine("Failed while: " + (d.failedStage?.failedAt ?: "unknown"))
            appendLine("Error:")
            append(d.errorDetails.ifEmpty { d.errorShort })
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fetchy error", text))
        Toast.makeText(this, getString(R.string.main_error_copied), Toast.LENGTH_SHORT).show()
    }

    // The user picked a cookies.txt: copied to private app storage, never logged.
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 21) {   // login page
            when (resultCode) {
                RESULT_OK -> State.dls.firstOrNull { it.id == State.loginFor }?.let {
                    Toast.makeText(this, getString(R.string.main_logged_in_retry), Toast.LENGTH_SHORT).show(); retry(it)
                }
                LoginActivity.RESULT_IMPORT -> @Suppress("DEPRECATION") startActivityForResult(Cookies.pickIntent(), 20)
            }
            return
        }
        if (requestCode == 22) {   // login suggested for the first YouTube link: then load the qualities
            if (resultCode == LoginActivity.RESULT_IMPORT) @Suppress("DEPRECATION") startActivityForResult(Cookies.pickIntent(), 20)
            else loadQualities()
            return
        }
        if (requestCode != 20) return
        if (resultCode != RESULT_OK) { loadQualities(); return }
        val uri = data?.data ?: return
        val err = Cookies.import(this, uri)
        if (err == null) State.cookiesSaved = true
        val base = Cookies.baseDomain(Cookies.hostOf(State.dls.firstOrNull { it.id == State.loginFor }?.url ?: ""))
        val noLogin = err == null && Cookies.sessionNames(base) != null && !Cookies.savedHasSession(this, base)
        Toast.makeText(this, err ?: if (noLogin) getString(R.string.main_imported_no_login, base)
            else getString(R.string.common_cookies_imported), Toast.LENGTH_LONG).show()
        render()
        loadQualities()
    }
}

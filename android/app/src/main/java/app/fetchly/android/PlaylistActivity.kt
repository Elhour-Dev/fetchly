package app.fetchly.android

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** Playlist screen: pick videos, then watch the download queue. The queue itself runs in [PlaylistService]. */
class PlaylistActivity : ThemedActivity() {
    private val pageSize = 100   // rows shown at a time ("Load more" adds another page)
    private val kbps = mapOf("best" to 4500, "1080" to 4000, "720" to 2200, "480" to 1200, "audio" to 192)   // rough, for the size estimate

    private lateinit var head: TextView
    private lateinit var sub: TextView
    private lateinit var note: TextView
    private lateinit var spin: ProgressBar
    private lateinit var retry: View
    private lateinit var pickTop: LinearLayout
    private lateinit var pickFoot: LinearLayout
    private lateinit var queueTop: LinearLayout
    private lateinit var list: ListView
    private lateinit var more: android.widget.Button
    private lateinit var count: TextView
    private lateinit var qualityBtn: android.widget.Button
    private lateinit var start: android.widget.Button
    private lateinit var qStage: TextView
    private lateinit var qBar: ProgressBar
    private lateinit var qCounts: TextView
    private lateinit var qButtons: LinearLayout

    private var mode = ""
    private var filter = ""
    private var shown = pageSize
    private var rows: List<PlEntry> = emptyList()
    private var buttonsKey = ""
    private var pending = false
    private lateinit var option: QOption

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent.getStringExtra("url")
        if (link != null && savedInstanceState == null) {
            val busy = Pl.items.any { it.open }
            if (busy && link != Pl.url) Toast.makeText(this, getString(R.string.pl_busy), Toast.LENGTH_LONG).show()
            else if (link != Pl.url || Pl.error != null) Pl.load(this, link)
            else if (!busy) Pl.items = emptyList()
        }
        if (Pl.url.isEmpty()) { finish(); return }
        val options = Quality.playlistOptions(this)
        option = options.firstOrNull { it.key == Quality.remembered(this) } ?: options[0]

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(dp(20), b.top + dp(12), dp(20), b.bottom + dp(12))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(dp(20), ins.systemWindowInsetTop + dp(12), dp(20), ins.systemWindowInsetBottom + dp(12))
            }
            ins
        }
        root.add(topBar(getString(R.string.pl_title)))
        head = label("", 17f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        sub = label("", 13f, R.color.muted)
        root.add(head, 12); root.add(sub, 2)

        // Loading or error.
        spin = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
        }
        root.add(spin, 28, dp(28), dp(28))
        note = label("", 15f)
        root.add(note, 12)
        retry = button(getString(R.string.pl_try_again), Kind.TONAL) { Pl.load(this, Pl.url); render() }
        root.add(retry, 12)

        // Step 1: choose videos.
        pickTop = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = field(getString(R.string.pl_search_hint), InputType.TYPE_CLASS_TEXT)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filter = s.toString(); shown = pageSize; refilter(); render() }
        })
        pickTop.add(search, 10, -1, dp(44))
        val range = field(getString(R.string.pl_range_hint), InputType.TYPE_CLASS_TEXT)
        val line1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        line1.addView(range, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
        line1.addView(small(getString(R.string.pl_select_range)) {
            val picked = parseRange(range.text.toString())
            if (picked == null) Toast.makeText(this, getString(R.string.pl_range_bad), Toast.LENGTH_SHORT).show()
            else { Pl.selected.clear(); Pl.selected.addAll(picked); render() }
        }, LinearLayout.LayoutParams(-2, dp(40)))
        pickTop.add(line1, 8)
        val line2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun third(text: String, end: Int, f: (Int) -> Unit) = line2.addView(small(text) { rows.forEach { f(it.n) }; render() },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(end) })
        third(getString(R.string.pl_all), 8) { Pl.selected.add(it) }
        third(getString(R.string.pl_none), 8) { Pl.selected.remove(it) }
        third(getString(R.string.pl_invert), 0) { if (!Pl.selected.remove(it)) Pl.selected.add(it) }
        pickTop.add(line2, 8)
        root.add(pickTop)

        // Step 2: overall progress and the end summary.
        queueTop = card(pad = 14)
        qStage = label("", 15f, bold = true)
        qBar = progressBar()
        qCounts = label("", 13f, R.color.muted)
        qButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        queueTop.addView(qStage); queueTop.add(qBar, 10, -1, dp(8)); queueTop.add(qCounts, 8); queueTop.add(qButtons, 10)
        root.add(queueTop, 12)

        list = ListView(this).apply { divider = null; isVerticalScrollBarEnabled = true }
        more = small(getString(R.string.pl_load_more)) { shown += pageSize; render() }
        list.addFooterView(LinearLayout(this).apply {
            setPadding(0, dp(8), 0, dp(8)); addView(more, LinearLayout.LayoutParams(-1, dp(40)))
        }, null, false)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            if (mode == "queue") Pl.items.getOrNull(pos)?.let { itemMenu(it) }
            else rows.getOrNull(pos)?.let { if (!Pl.selected.remove(it.n)) Pl.selected.add(it.n); render() }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })

        pickFoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        count = label("", 13f, R.color.muted)
        pickFoot.add(count, 8)
        qualityBtn = button("", Kind.TONAL, 44) { chooseQuality(options) }
        pickFoot.add(qualityBtn, 8, -1, dp(44))
        start = button(getString(R.string.common_download), Kind.PRIMARY, 52) { startQueue() }
        pickFoot.add(start, 8, -1, dp(52))
        root.add(pickFoot)

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        Pl.listener = { if (!pending) { pending = true; list.postDelayed({ pending = false; render() }, 200) } }
        if (::list.isInitialized) render()
    }
    override fun onPause() { Pl.listener = null; super.onPause() }

    private fun field(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText; setHintTextColor(col(R.color.muted)); setTextColor(col(R.color.text))
        inputType = type; setSingleLine(); textSize = 15f
        setPadding(dp(12), 0, dp(12), 0)
        background = shape(col(R.color.card), 12, 1, col(R.color.border))
    }

    private fun small(text: String, onClick: () -> Unit) =
        button(text, Kind.GHOST, 40, onClick).apply { textSize = 14f; setPadding(dp(12), 0, dp(12), 0) }

    private fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }

    private fun refilter() {
        val q = filter.trim().lowercase()
        rows = if (q.isEmpty()) Pl.entries else Pl.entries.filter { it.title.lowercase().contains(q) }
    }

    /** "5-20" or "1-3, 8" -> the video numbers, or null when it cannot be read. */
    private fun parseRange(text: String): Set<Int>? {
        val out = HashSet<Int>()
        val parts = text.split(',', ' ').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        for (p in parts) {
            val m = Regex("^(\\d{1,6})(?:-(\\d{1,6}))?$").find(p.trim()) ?: return null
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
            for (n in minOf(a, b)..minOf(maxOf(a, b), Pl.entries.size)) out.add(n)
        }
        return out
    }

    private fun render() {
        val now = when { Pl.loading -> "wait"; Pl.error != null -> "error"; Pl.items.isNotEmpty() -> "queue"; else -> "pick" }
        if (now != mode) {
            mode = now; buttonsKey = ""
            if (now == "pick") refilter()
            show(spin, now == "wait"); show(note, now == "wait" || now == "error"); show(retry, now == "error")
            show(pickTop, now == "pick"); show(pickFoot, now == "pick"); show(queueTop, now == "queue")
            show(list, now == "pick" || now == "queue")
        }
        head.text = Pl.title.ifEmpty { getString(R.string.pl_title) }
        sub.text = if (Pl.entries.isEmpty()) "" else getString(R.string.pl_n_videos, Pl.entries.size) +
            if (Pl.capped) " · " + getString(R.string.pl_mix_capped, Pl.MIX_CAP) else ""
        show(sub, sub.text.isNotEmpty())
        when (now) {
            "wait" -> note.text = getString(R.string.pl_fetching)
            "error" -> note.text = Pl.error
            "pick" -> drawPick()
            "queue" -> drawQueue()
        }
        adapter.notifyDataSetChanged()
    }

    private fun drawPick() {
        val left = rows.size - minOf(rows.size, shown)
        show(more, left > 0)
        more.text = getString(R.string.pl_load_more_left, left)
        val picked = Pl.entries.filter { it.n in Pl.selected }
        val real = picked.filter { it.unavailable == null && !it.have }
        val timed = real.filter { it.duration > 0 }
        val size = if (timed.isEmpty()) "" else {
            val seconds = timed.sumOf { it.duration.toLong() } * real.size / timed.size
            " · " + getString(R.string.pl_about, Quality.size(seconds * (kbps[option.key] ?: 4500) * 125))
        }
        count.text = getString(R.string.pl_selected, picked.size, Pl.entries.size) + size
        qualityBtn.text = getString(R.string.pl_quality_for_all, option.label)
        start.isEnabled = picked.isNotEmpty()
        start.alpha = if (start.isEnabled) 1f else 0.5f
        start.text = if (picked.isEmpty()) getString(R.string.pl_select_to_download)
            else if (picked.size == 1) getString(R.string.pl_download_one) else getString(R.string.pl_download_n, picked.size)
    }

    private fun drawQueue() {
        show(more, false)
        val items = Pl.items
        fun n(s: PlItem.S) = items.count { it.state == s }
        val open = items.count { it.open }
        val finished = open == 0
        val running = Pl.active()
        val paused = n(PlItem.S.PAUSED)
        val failed = n(PlItem.S.FAILED)
        qBar.progress = ((items.size - open) * 100 + items.filter { it.open }.sumOf { it.percent }) / items.size.coerceAtLeast(1)
        qStage.text = when { finished -> getString(R.string.pl_finished); running -> getString(R.string.pl_progress, items.size - open, items.size); else -> getString(R.string.pl_paused) }
        val cancelled = n(PlItem.S.CANCELLED)
        qCounts.text = getString(R.string.pl_counts, n(PlItem.S.DONE), failed, n(PlItem.S.SKIPPED)) +
            (if (cancelled > 0) " · " + getString(R.string.pl_cancelled_n, cancelled) else "") + "\n" + getString(R.string.pl_saved_in, Pl.folder())

        val key = "$finished|$running|${paused > 0}|${failed > 0}"
        if (key == buttonsKey) return
        buttonsKey = key
        qButtons.removeAllViews()
        fun add(text: String, onClick: () -> Unit) = qButtons.addView(small(text, onClick),
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (qButtons.childCount > 0) marginStart = dp(8) })
        if (running) add(getString(R.string.pl_pause_all)) { PlaylistService.send(this, "pause") }
        if (paused > 0) add(getString(R.string.pl_resume_all)) { PlaylistService.send(this, "resume") }
        if (!finished) add(getString(R.string.pl_cancel_all)) { PlaylistService.send(this, "cancel") }
        if (finished) add(getString(R.string.pl_open_folder)) { openFolder() }
        if (failed > 0 && !running) add(getString(R.string.pl_retry_failed)) { PlaylistService.send(this, "retry") }
        if (finished) add(getString(R.string.pl_back_to_list)) { Pl.items = emptyList(); render() }
    }

    private fun itemMenu(item: PlItem) {
        val s = item.state
        val actions = ArrayList<Pair<String, String>>()
        if (s == PlItem.S.QUEUED || s == PlItem.S.STARTING || s == PlItem.S.DOWNLOADING) actions.add(getString(R.string.pl_pause) to "pause")
        if (s == PlItem.S.PAUSED) actions.add(getString(R.string.pl_resume) to "resume")
        if (s == PlItem.S.QUEUED || s == PlItem.S.STARTING || s == PlItem.S.DOWNLOADING || s == PlItem.S.PAUSED) actions.add(getString(R.string.common_cancel) to "cancel")
        if (s == PlItem.S.FAILED || s == PlItem.S.CANCELLED) actions.add(getString(R.string.common_retry) to "retry")
        if (actions.isEmpty()) { item.error?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }; return }
        AlertDialog.Builder(this).setTitle(item.e.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> PlaylistService.send(this, actions[i].second, item.e.n) }
            .show()
    }

    private fun chooseQuality(options: List<QOption>) {
        AlertDialog.Builder(this).setTitle(getString(R.string.pl_quality_all_title))
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(option)) { d, i ->
                option = options[i]; Quality.remember(this, option.key); d.dismiss(); render()
            }.show()
    }

    private fun startQueue() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        Pl.begin(this, option)
        PlaylistService.send(this, "start")
        list.setSelection(0)
        render()
    }

    private fun openFolder() {
        try {
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/${Pl.folder()}")
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR))
        } catch (_: Exception) {
            try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            catch (_: Exception) { Toast.makeText(this, getString(R.string.pl_open_files_hint, Pl.folder()), Toast.LENGTH_LONG).show() }
        }
    }

    private fun time(s: Int) = if (s <= 0) "" else if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

    private class Row(val view: LinearLayout, val box: CheckBox, val num: TextView, val thumb: ImageView,
                      val title: TextView, val sub: TextView, val bar: ProgressBar, val dur: TextView)

    private fun newRow(): Row {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val box = CheckBox(this).apply { isClickable = false; isFocusable = false }
        val num = label("", 12f, R.color.muted).apply { gravity = Gravity.END }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(col(R.color.track), 6); clipToOutline = true
        }
        val title = label("", 14f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = label("", 12f, R.color.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val bar = progressBar()
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(title); text.addView(sub); text.add(bar, 4, -1, dp(4))
        val dur = label("", 12f, R.color.muted)
        view.addView(box, LinearLayout.LayoutParams(-2, -2))
        view.addView(num, LinearLayout.LayoutParams(dp(30), -2).apply { marginEnd = dp(8) })
        view.addView(thumb, LinearLayout.LayoutParams(dp(64), dp(36)).apply { marginEnd = dp(10) })
        view.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(dur, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        return Row(view, box, num, thumb, title, sub, bar, dur).also { view.tag = it }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = if (mode == "queue") Pl.items.size else if (mode == "pick") minOf(shown, rows.size) else 0
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView?.tag as? Row ?: newRow()
            val queue = mode == "queue"
            val item = if (queue) Pl.items.getOrNull(position) else null
            val e = item?.e ?: rows.getOrNull(position) ?: return row.view
            row.num.text = e.n.toString()
            row.title.text = e.title
            if (row.thumb.tag != e.thumb) Thumbs.show(row.thumb, e.thumb)
            show(row.box, !queue); show(row.dur, !queue)
            var color = R.color.muted
            if (item == null) {
                row.box.isChecked = e.n in Pl.selected
                row.dur.text = time(e.duration)
                val why = e.unavailable ?: if (e.have) getString(R.string.pl_already) else null
                row.sub.text = if (why != null) getString(R.string.pl_will_skip, why) else ""
                row.view.alpha = if (why != null) 0.6f else 1f
                show(row.bar, false)
            } else {
                row.view.alpha = 1f
                val s = item.state
                row.sub.text = when (s) {
                    PlItem.S.QUEUED -> getString(R.string.pl_st_waiting)
                    PlItem.S.STARTING -> getString(R.string.common_starting)
                    PlItem.S.DOWNLOADING -> "${item.percent}%"
                    PlItem.S.PROCESSING -> getString(R.string.common_finishing)
                    PlItem.S.PAUSED -> if (item.percent > 0) getString(R.string.pl_st_paused_at, item.percent) else getString(R.string.pl_st_paused)
                    PlItem.S.DONE -> getString(R.string.common_done)
                    PlItem.S.FAILED -> getString(R.string.pl_st_failed, item.error ?: getString(R.string.pl_st_unknown_error))
                    PlItem.S.SKIPPED -> getString(R.string.pl_st_skipped, item.error ?: getString(R.string.pl_st_not_available))
                    PlItem.S.CANCELLED -> getString(R.string.pl_st_cancelled)
                }
                color = when (s) { PlItem.S.DONE -> R.color.ok_text; PlItem.S.FAILED -> R.color.err_text; PlItem.S.SKIPPED -> R.color.tip_text; else -> R.color.muted }
                show(row.bar, s == PlItem.S.STARTING || s == PlItem.S.DOWNLOADING || s == PlItem.S.PROCESSING || s == PlItem.S.PAUSED)
                row.bar.progress = item.percent
            }
            row.sub.setTextColor(col(color))
            show(row.sub, row.sub.text.isNotEmpty())
            return row.view
        }
    }
}

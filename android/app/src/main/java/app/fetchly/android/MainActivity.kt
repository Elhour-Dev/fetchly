package app.fetchly.android

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var download: Button
    private lateinit var cancel: Button
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var errBox: LinearLayout
    private lateinit var errText: TextView
    private lateinit var fixBtn: Button
    private lateinit var version: TextView
    private lateinit var update: Button
    private lateinit var cookiesInfo: TextView
    private lateinit var removeCookies: Button
    @Volatile private var ready = false
    @Volatile private var updating = false
    private val urlRegex = Regex("https?://\\S+")

    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF1E3A8A.toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (primary) 0xFF2563EB.toInt() else 0xFFE0E7FF.toInt())
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun label(s: String, size: Float, bold: Boolean = false, color: Int = 0xFF0F172A.toInt()) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(32)) }
        val scroll = ScrollView(this).apply { addView(box) }
        scroll.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(0, b.top, 0, b.bottom); ins
        }

        box.addView(label("Fetchly", 30f, true, 0xFF1E3A8A.toInt()))
        box.addView(label("Paste a video link, then tap Download.", 16f, color = 0xFF475569.toInt()))

        url = EditText(this).apply {
            hint = "Paste video link here"; inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(); textSize = 16f
        }
        box.addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        box.addView(row(
            button("Paste", false) { pasteFromClipboard() },
            button("Clear", false) { url.setText(""); }
        ))

        download = button("Download", true) { startDownload() }
        box.addView(download, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16) })

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        box.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        status = label("", 15f)
        box.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        cancel = button("Cancel download", false) {
            startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
        }.apply { visibility = View.GONE }
        box.addView(cancel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        errBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(0xFFFEF2F2.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFFFECACA.toInt()) }
        }
        errText = label("", 15f, color = 0xFF991B1B.toInt())
        errBox.addView(errText)
        fixBtn = button("How to fix", false) { openHelp(State.helpTopic) }
        errBox.addView(row(fixBtn, button("Copy error details", false) { copyErrorDetails() }),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        box.addView(errBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        box.addView(View(this).apply { setBackgroundColor(0xFFE2E8F0.toInt()) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(28); bottomMargin = dp(16) })

        version = label("Tools: starting…", 14f, color = 0xFF475569.toInt())
        box.addView(version)
        update = button("Update tools", false) { updateTools() }
        box.addView(update, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        cookiesInfo = label("", 14f, color = 0xFF475569.toInt())
        box.addView(cookiesInfo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        removeCookies = button("Remove cookies", false) {
            Tools.cookiesFile(this).delete(); refreshCookies()
        }
        box.addView(row(
            button("Import cookies.txt", false) { pickCookies() },
            removeCookies
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        box.addView(button("Help", false) { openHelp(null) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        setContentView(scroll)
        refreshCookies()
        State.listener = { render() }

        // Get yt-dlp ready on a background thread (first start unpacks it).
        thread {
            try {
                Tools.init(this)
                ready = true
                runOnUiThread { version.text = "Tools: yt-dlp " + Tools.version(this); render() }
            } catch (e: Exception) {
                runOnUiThread { version.text = "Tools could not start: " + (e.message ?: e.toString()) }
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
        if (link == null) { status.text = "No link found in what you shared."; return }
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
        if (link == null) { status.text = "Please paste a link that starts with http:// or https://"; return }
        if (State.phase == State.Phase.RUNNING) return
        if (!ready) { status.text = "Tools are still starting. Try again in a moment."; return }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        State.phase = State.Phase.RUNNING; State.text = "Starting…"; State.percent = 0
        startForegroundService(Intent(this, DownloadService::class.java).putExtra(DownloadService.EXTRA_URL, link))
        render()
    }

    private fun render() {
        val running = State.phase == State.Phase.RUNNING
        download.isEnabled = ready && !running && !updating
        download.alpha = if (download.isEnabled) 1f else 0.5f
        cancel.visibility = if (running) View.VISIBLE else View.GONE
        bar.visibility = if (running) View.VISIBLE else View.GONE
        bar.progress = State.percent
        status.text = State.text
        val err = State.phase == State.Phase.ERROR
        errBox.visibility = if (err) View.VISIBLE else View.GONE
        if (err) { errText.text = State.errorShort; fixBtn.visibility = if (State.helpTopic != null) View.VISIBLE else View.GONE }
        update.isEnabled = !running && !updating
    }

    private fun openHelp(topic: String?) =
        startActivity(Intent(this, HelpActivity::class.java).putExtra("topic", topic))

    private fun copyErrorDetails() {
        val link = urlRegex.find(url.text.toString())?.value?.substringBefore('?') ?: ""
        val text = buildString {
            appendLine("Fetchly Android 1.0")
            appendLine("yt-dlp: " + Tools.version(this@MainActivity))
            appendLine("Android " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("Cookies file imported: " + Tools.cookiesFile(this@MainActivity).exists())
            appendLine("Link: $link")
            appendLine("Error:")
            append(State.errorDetails)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fetchly error", text))
        Toast.makeText(this, "Error details copied.", Toast.LENGTH_SHORT).show()
    }

    // ---- Update tools: keep the old yt-dlp if the update fails ----
    private fun updateTools() {
        if (!ready || updating) return
        updating = true; render()
        version.text = "Updating yt-dlp…"
        thread {
            val before = Tools.version(this)
            val dir = File(noBackupFilesDir, "youtubedl-android/yt-dlp")
            val backup = File(noBackupFilesDir, "yt-dlp-backup")
            var msg: String
            try {
                backup.deleteRecursively()
                if (dir.exists()) dir.copyRecursively(backup, true)
                val r = YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                val after = Tools.version(this)
                if (after == "unknown") throw IllegalStateException("New version did not start")
                msg = if (r == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "Already the newest version: yt-dlp $after"
                else "Updated: yt-dlp $before to $after"
            } catch (e: Exception) {
                // Put the old version back so downloads keep working.
                try {
                    if (backup.exists()) { dir.deleteRecursively(); backup.copyRecursively(dir, true) }
                } catch (_: Exception) {}
                msg = "Update failed. Old version kept: yt-dlp " + Tools.version(this) + "\n(" + (e.message ?: e.toString()).take(200) + ")"
            } finally {
                backup.deleteRecursively()
            }
            runOnUiThread { updating = false; version.text = msg; render() }
        }
    }

    // ---- Cookies: user picks a cookies.txt; it is copied to private app storage, never logged ----
    private fun pickCookies() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        @Suppress("DEPRECATION") startActivityForResult(i, 20)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val head = String(bytes, 0, minOf(bytes.size, 4000))
            if (bytes.size > 5_000_000 || !(head.contains("Netscape") || head.contains("\t")))
                throw IllegalArgumentException("This does not look like a cookies.txt file (Netscape format).")
            Tools.cookiesFile(this).writeBytes(bytes)
            Toast.makeText(this, "Cookies imported.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Could not read that file.", Toast.LENGTH_LONG).show()
        }
        refreshCookies()
    }

    private fun refreshCookies() {
        val has = Tools.cookiesFile(this).exists()
        cookiesInfo.text = if (has) "Cookies: imported (kept private on this phone)" else "Cookies: none. Only needed for login problems."
        removeCookies.isEnabled = has; removeCookies.alpha = if (has) 1f else 0.5f
    }
}

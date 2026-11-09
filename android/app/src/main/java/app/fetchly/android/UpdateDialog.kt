package app.fetchly.android

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import kotlin.concurrent.thread

/**
 * Small pop-up that offers "Update tools". It only updates after the user taps the button.
 * [forError]: shown after a failed download (then "Retry" is offered after the update).
 */
fun Activity.showUpdateDialog(forError: Boolean, onRetry: () -> Unit) {
    if (isFinishing || isDestroyed) return
    val dlg = Dialog(this)
    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box = card(pad = 20)
    val title = label("Update tools?", 20f, bold = true)
    val body = label(
        if (forError) "This problem may be fixed by updating yt-dlp, the tool that reads video sites."
        else "The tools were not updated for more than 7 days. Sites change often, so updating keeps downloads working.",
        15f, R.color.muted
    )
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(title)
    box.add(body, 8)
    box.add(buttons, 18)

    fun setButtons(vararg b: View) {
        buttons.removeAllViews()
        b.forEachIndexed { i, v -> buttons.add(v, if (i == 0) 0 else 8, -1, dp(48)) }
    }

    fun finished(r: Tools.UpdateResult) {
        title.text = if (r.ok) "Tools updated" else "Update failed"
        body.text = if (r.ok) "New version: yt-dlp ${r.version}" else r.message
        val close = button(if (r.ok && forError) "Close" else "Done", Kind.GHOST) { dlg.dismiss() }
        if (r.ok && forError) setButtons(button("Retry", Kind.PRIMARY) { dlg.dismiss(); onRetry() }, close)
        else setButtons(close)
        dlg.setCancelable(true)
    }

    setButtons(
        button("Update tools", Kind.PRIMARY) {
            title.text = "Updating…"
            body.text = "Please wait. This takes a few seconds."
            buttons.removeAllViews()
            dlg.setCancelable(false)
            thread {
                val r = Tools.update(this)
                if (!r.ok) Tools.snoozeUpdate(this)
                runOnUiThread { if (!isFinishing && !isDestroyed) finished(r) }
            }
        },
        button("Later", Kind.GHOST) { Tools.snoozeUpdate(this); dlg.dismiss() }
    )
    dlg.setOnCancelListener { Tools.snoozeUpdate(this) }  // back button counts as "Later"
    dlg.setContentView(box)
    dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dlg.show()
    dlg.window?.setLayout(
        (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT
    )
}

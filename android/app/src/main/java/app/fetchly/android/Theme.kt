package app.fetchly.android

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Theme choice: "system", "light" or "dark". Also swaps the launcher icon to match. */
object Theme {
    fun mode(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("theme", "system") ?: "system"
    fun set(c: Context, m: String) { c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("theme", m).apply() }

    fun wrap(base: Context): Context {
        val m = mode(base)
        if (m == "system") return base
        val cfg = Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (m == "dark") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return base.createConfigurationContext(cfg)
    }

    fun isDark(c: Context) = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Enable the launcher alias that matches the current look. */
    fun syncIcon(c: Context) {
        val dark = isDark(c)
        val pm = c.packageManager
        fun set(n: String, on: Boolean) {
            val cn = ComponentName(c.packageName, "app.fetchly.android.$n")
            val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(cn) != want) pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
        }
        // Enable the new one first so the app never has no launcher icon.
        if (dark) { set("IconDark", true); set("IconLight", false) } else { set("IconLight", true); set("IconDark", false) }
    }
}

open class ThemedActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Theme.wrap(newBase))
}

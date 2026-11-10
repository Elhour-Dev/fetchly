package app.fetchly.android

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/** Theme choice: "system", "light" or "dark". */
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
}

open class ThemedActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Theme.wrap(newBase))
}

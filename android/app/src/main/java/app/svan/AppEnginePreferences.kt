package app.svan

import android.content.Context
import android.content.Intent

/** App choices are frozen for each capture grant, matching its UID exclusions. */
class AppEnginePreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("app_engines", Context.MODE_PRIVATE)

    fun systemOnlyPackages(): Set<String> = prefs.all.filterValues { it == true }.keys.toSet()
    fun usesSystemOnly(pkg: String): Boolean = prefs.getBoolean(pkg, false)

    fun setSystemOnly(pkg: String, systemOnly: Boolean) {
        if (usesSystemOnly(pkg) == systemOnly) return
        prefs.edit().apply {
            if (systemOnly) putBoolean(pkg, true) else remove(pkg)
        }.apply()
        // The current AudioRecord has immutable capture filters. Stop it before
        // unmuting any source; the next explicit grant builds the new exclusions.
        appContext.stopService(Intent(appContext, CaptureService::class.java))
        EqController.log("app engine: $pkg → ${if (systemOnly) "system only" else "auto"}; restart capture to apply")
    }
}

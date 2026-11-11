package de.radiolaria.splitscreenstart

import android.content.Context
import android.os.Build

/** Wie der Splitscreen ausgelöst wird. */
enum class Method(val key: String, val label: String) {
    AUTO("auto", "Automatisch (Verfahren nacheinander probieren)"),
    VIA_NAVI_TASK("navitask", "Nebeneinander über Navi-Task (ab Android 12L)"),
    DIRECT("direct", "Nebeneinander direkt (ab Android 12L)"),
    TOGGLE("toggle", "System-Umschalter Splitscreen (bis Android 12)");

    companion object {
        fun of(key: String?): Method = entries.firstOrNull { it.key == key } ?: AUTO

        /** Reihenfolge der Versuche. Ein Verfahren, das schon einmal geklappt hat, kommt zuerst. */
        fun sequence(selected: Method, lastSuccess: Method?): List<Method> {
            if (selected != AUTO) return listOf(selected)
            // 12L = API 32: neuer Splitscreen, LAUNCH_ADJACENT darf von Vollbild aus teilen.
            val base = if (Build.VERSION.SDK_INT >= 32) {
                listOf(VIA_NAVI_TASK, DIRECT, TOGGLE)
            } else {
                listOf(TOGGLE, DIRECT, VIA_NAVI_TASK)
            }
            if (lastSuccess == null || lastSuccess !in base) return base
            return listOf(lastSuccess) + base.filter { it != lastSuccess }
        }
    }
}

object Prefs {
    const val MAGIC_EARTH = "com.generalmagic.magicearth"
    const val CAR_SCANNER = "com.ovz.carscanner"

    val DELAYS_MS = listOf(1000, 2000, 3000, 5000, 8000)
    private const val DEFAULT_DELAY_MS = 2000

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun navi(c: Context): String? = sp(c).getString("navi", null)
    fun setNavi(c: Context, pkg: String?) = sp(c).edit().putString("navi", pkg).apply()

    fun obd(c: Context): String? = sp(c).getString("obd", null)
    fun setObd(c: Context, pkg: String?) = sp(c).edit().putString("obd", pkg).apply()

    fun method(c: Context): Method = Method.of(sp(c).getString("method", null))
    fun setMethod(c: Context, m: Method) = sp(c).edit().putString("method", m.key).apply()

    fun delayMs(c: Context): Int = sp(c).getInt("delay", DEFAULT_DELAY_MS)
    fun setDelayMs(c: Context, ms: Int) = sp(c).edit().putInt("delay", ms).apply()

    fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("enabled", on).apply()

    /** Nach einem kompletten Fehlschlag pausiert die Automatik, damit es im Auto nicht dauernd flackert. */
    fun paused(c: Context): Boolean = sp(c).getBoolean("paused", false)
    fun setPaused(c: Context, p: Boolean) = sp(c).edit().putBoolean("paused", p).apply()

    fun lastSuccess(c: Context): Method? = sp(c).getString("lastSuccess", null)?.let { Method.of(it) }
    fun setLastSuccess(c: Context, m: Method?) = sp(c).edit().putString("lastSuccess", m?.key).apply()

    /** Beim ersten Start Magic Earth und Car Scanner vorwählen, falls installiert. */
    fun applyDefaults(c: Context, apps: List<AppEntry>) {
        val pkgs = apps.map { it.pkg }.toSet()
        if (navi(c) == null && MAGIC_EARTH in pkgs) setNavi(c, MAGIC_EARTH)
        if (obd(c) == null && CAR_SCANNER in pkgs) setObd(c, CAR_SCANNER)
    }
}

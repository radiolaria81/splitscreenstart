package de.radiolaria.splitscreenstart

import android.content.Context
import android.content.Intent

data class AppEntry(val pkg: String, val label: String)

object AppList {
    /** Alle Apps mit Startsymbol, alphabetisch, ohne diese App selbst. */
    fun load(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(intent, 0)
        return infos
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != c.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
}

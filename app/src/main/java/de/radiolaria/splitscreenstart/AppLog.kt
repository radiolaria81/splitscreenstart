package de.radiolaria.splitscreenstart

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kleines Protokoll in einer Datei, damit sich Versuche im Auto später nachlesen und kopieren lassen. */
object AppLog {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private var loaded = false
    private val listeners = mutableSetOf<() -> Unit>()
    private val fmt = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)

    private fun file(c: Context) = File(c.applicationContext.filesDir, "log.txt")

    @Synchronized
    private fun ensureLoaded(c: Context) {
        if (loaded) return
        loaded = true
        val f = file(c)
        if (f.exists()) {
            try {
                f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
            } catch (e: Exception) {
            }
        }
    }

    fun add(c: Context, msg: String) {
        val snapshot: List<() -> Unit>
        synchronized(this) {
            ensureLoaded(c)
            lines.addLast("${fmt.format(Date())}  $msg")
            while (lines.size > MAX_LINES) lines.removeFirst()
            try {
                file(c).writeText(lines.joinToString("\n"))
            } catch (e: Exception) {
            }
            snapshot = listeners.toList()
        }
        android.util.Log.i("SplitStart", msg)
        snapshot.forEach { it() }
    }

    @Synchronized
    fun text(c: Context): String {
        ensureLoaded(c)
        return lines.joinToString("\n")
    }

    @Synchronized
    fun clear(c: Context) {
        lines.clear()
        loaded = true
        try {
            file(c).delete()
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    @Synchronized
    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun deviceLine(): String =
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}, Build ${Build.DISPLAY}"
}

package de.radiolaria.splitscreenstart

import android.app.Activity
import android.content.Intent

/**
 * Unsichtbare Activity für das Verfahren "über den Navi-Task".
 *
 * Sie wird mit FLAG_ACTIVITY_NEW_TASK gestartet. Weil ihre taskAffinity (siehe Manifest) dem
 * Paketnamen der Navi-App entspricht, legt Android sie oben auf den schon laufenden Navi-Task.
 * Von dort startet sie die OBD-App mit FLAG_ACTIVITY_LAUNCH_ADJACENT. Ab Android 12L darf eine
 * App im Vollbild damit den Splitscreen öffnen; die Navi liegt dann auf der einen, die OBD-App
 * auf der anderen Seite. Danach beendet sich die Activity sofort.
 *
 * isTaskRoot == true im Log heißt: Android hat einen eigenen Task angelegt, der Trick hat also
 * nicht gegriffen (z. B. weil die Navi-App eine andere taskAffinity benutzt).
 */
open class Trampoline : Activity() {
    private var started = false

    override fun onResume() {
        super.onResume()
        if (started) {
            finish()
            return
        }
        started = true
        val obd = intent.getStringExtra(EXTRA_OBD)
        val launch = obd?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            AppLog.add(this, "Trampolin: OBD-App lässt sich nicht starten ($obd)")
            finish()
            return
        }
        AppLog.add(this, "Trampolin läuft in Task $taskId, eigener Task: $isTaskRoot")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        try {
            startActivity(launch)
        } catch (e: Exception) {
            AppLog.add(this, "Trampolin: Start der OBD-App fehlgeschlagen: $e")
        }
        finish()
    }

    companion object {
        const val EXTRA_OBD = "obd"

        /** Navi-Paket -> passende Trampolin-Klasse (taskAffinity steht im Manifest). */
        val byNavi: Map<String, Class<out Activity>> = mapOf(
            Prefs.MAGIC_EARTH to TrampolineMagicEarth::class.java,
            "net.osmand" to TrampolineOsmAnd::class.java,
            "net.osmand.plus" to TrampolineOsmAndPlus::class.java,
            "app.organicmaps" to TrampolineOrganicMaps::class.java,
        )
    }
}

class TrampolineMagicEarth : Trampoline()
class TrampolineOsmAnd : Trampoline()
class TrampolineOsmAndPlus : Trampoline()
class TrampolineOrganicMaps : Trampoline()

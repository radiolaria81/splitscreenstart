package de.radiolaria.splitscreenstart

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Bedienungshilfe-Dienst: merkt, wann die Navi-App nach vorne kommt, und legt dann die OBD-App
 * im Splitscreen daneben.
 *
 * Ausgelöst wird nur beim Wechsel von einer anderen App zur Navi (nicht bei jedem Dialog in der
 * Navi). Ist der Splitscreen schon aktiv, passiert nichts. Klappt kein Verfahren, pausiert die
 * Automatik, bis in der App "Jetzt testen" gedrückt oder eine Einstellung geändert wird.
 */
class SplitService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastFgPkg: String? = null
    private var busy = false
    private var lastAttemptEnd = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("Dienst verbunden. ${AppLog.deviceLine()}")
        log("System-Umschalter Splitscreen: ${toggleInfo()}")
        // Navi schon vorne, z. B. weil das Radio sie nach dem Hochfahren selbst gestartet hat?
        handler.postDelayed({
            val navi = Prefs.navi(this)
            val front = rootInActiveWindow?.packageName?.toString()
            lastFgPkg = front
            if (navi != null && front == navi) trigger("Navi war beim Dienststart schon vorne", force = false)
        }, 3000)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == SYSTEM_UI) return
        // Nur echte App-Fenster zählen (keine Tastatur, keine Systemfenster).
        val win = safeWindows().firstOrNull { it.id == e.windowId }
        if (win != null && win.type != AccessibilityWindowInfo.TYPE_APPLICATION) return

        val prev = lastFgPkg
        lastFgPkg = pkg
        val navi = Prefs.navi(this) ?: return
        if (pkg == navi && prev != navi) {
            trigger("Navi kam nach vorne (vorher: ${prev ?: "unbekannt"})", force = false)
        }
    }

    /** Aufruf aus der App: Navi nach vorne holen und sofort versuchen, auch wenn pausiert. */
    fun testRun() {
        val navi = Prefs.navi(this)
        if (navi == null) {
            log("Test: keine Navi-App gewählt")
            return
        }
        Prefs.setPaused(this, false)
        log("Test gestartet")
        if (!bringToFront(navi)) return
        lastFgPkg = navi
        trigger("Test", force = true)
    }

    private fun trigger(reason: String, force: Boolean) {
        if (busy) return
        if (!force) {
            if (!Prefs.enabled(this)) return
            if (Prefs.paused(this)) {
                log("Navi erkannt, Automatik ist aber pausiert (letzter Versuch ging schief). In der App „Jetzt testen“ drücken.")
                return
            }
            if (SystemClock.elapsedRealtime() - lastAttemptEnd < COOLDOWN_MS) return
        }
        busy = true
        log("Auslöser: $reason")
        handler.postDelayed({ runAttempt() }, Prefs.delayMs(this).toLong())
    }

    private fun runAttempt() {
        val navi = Prefs.navi(this)
        val obd = Prefs.obd(this)
        if (navi == null || obd == null) {
            log("Navi- oder OBD-App nicht gewählt")
            finishAttempt(false)
            return
        }
        if (packageManager.getLaunchIntentForPackage(obd) == null) {
            log("OBD-App $obd hat kein Startsymbol / ist nicht installiert")
            finishAttempt(false)
            return
        }
        if (isSplitActive(navi, obd)) {
            log("Splitscreen mit beiden Apps ist schon aktiv – nichts zu tun")
            finishAttempt(true)
            return
        }
        if (!isAppVisible(navi)) {
            log("Navi ist nicht mehr vorne – abgebrochen. Fenster: ${windowSummary()}")
            finishAttempt(true) // kein Fehlschlag des Verfahrens, also nicht pausieren
            return
        }
        val list = Method.sequence(Prefs.method(this), Prefs.lastSuccess(this))
        // Welche Seite eine App bekommt, entscheidet das System danach, welche App zuerst vorne ist.
        // "Seiten tauschen": OBD zuerst nach vorne holen und die Navi daneben legen.
        if (Prefs.swapped(this)) {
            log("Seiten getauscht: erst OBD-App nach vorne, dann Navi daneben")
            if (!bringToFront(obd)) {
                finishAttempt(false)
                return
            }
            handler.postDelayed({ runMethod(list, 0, obd, navi) }, SWAP_SETTLE_MS)
        } else {
            runMethod(list, 0, navi, obd)
        }
    }

    /** anchor = App, die schon im Vollbild vorne ist; other = App, die daneben kommt. */
    private fun runMethod(list: List<Method>, i: Int, anchor: String, other: String) {
        val m = list[i]
        log("Versuch ${i + 1}/${list.size}: ${m.label}")
        startMethod(m, anchor, other) { started ->
            if (!started) {
                next(list, i, anchor, other, bringBack = false)
            } else {
                handler.postDelayed({
                    if (isSplitActive(anchor, other)) {
                        log("ERFOLG mit: ${m.label}. Fenster: ${windowSummary()}")
                        Prefs.setLastSuccess(this, m)
                        applyFocusThenFinish(anchor, other)
                    } else {
                        log("Kein Splitscreen erkannt. Fenster: ${windowSummary()}")
                        next(list, i, anchor, other, bringBack = true)
                    }
                }, VERIFY_MS)
            }
        }
    }

    private fun next(list: List<Method>, i: Int, anchor: String, other: String, bringBack: Boolean) {
        if (i + 1 >= list.size) {
            log("Kein Verfahren hat den Splitscreen geöffnet. Automatik pausiert.")
            // Zum Weiterfahren auf jeden Fall die Navi nach vorne.
            val navi = Prefs.navi(this)
            if (bringBack && navi != null && !isAppVisible(navi)) bringToFront(navi)
            finishAttempt(false)
            return
        }
        if (bringBack && !isAppVisible(anchor)) {
            bringToFront(anchor)
            handler.postDelayed({ runMethod(list, i + 1, anchor, other) }, 2000)
        } else {
            runMethod(list, i + 1, anchor, other)
        }
    }

    /**
     * Fokus nach erfolgreichem Splitscreen auf die gewählte App legen: Sie wird über ihr
     * Startsymbol noch einmal aufgerufen. Liegt ihr Task schon in einer Splitscreen-Hälfte,
     * bekommt er damit den Fokus. Danach wird geprüft, ob der Splitscreen noch steht.
     */
    private fun applyFocusThenFinish(a: String, b: String) {
        val target = when (Prefs.focus(this)) {
            Prefs.FOCUS_OBD -> Prefs.obd(this)
            Prefs.FOCUS_NAVI -> Prefs.navi(this)
            else -> null
        }
        if (target == null) {
            finishAttempt(true)
            return
        }
        if (focusedAppPkg() == target) {
            log("Fokus liegt schon auf $target")
            finishAttempt(true)
            return
        }
        log("Fokus auf $target legen")
        bringToFront(target)
        handler.postDelayed({
            if (isSplitActive(a, b)) {
                log("Fokus jetzt auf: ${focusedAppPkg() ?: "unbekannt"}")
            } else {
                log("Achtung: Der Fokuswechsel hat den Splitscreen beendet. Unter „Fokus am Ende“ „nicht ändern“ wählen. Fenster: ${windowSummary()}")
            }
            finishAttempt(true)
        }, VERIFY_MS)
    }

    private fun focusedAppPkg(): String? =
        safeWindows().firstOrNull { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.let { pkgOf(it) }

    private fun startMethod(m: Method, anchor: String, other: String, cb: (Boolean) -> Unit) {
        when (m) {
            Method.DIRECT -> cb(startAdjacent(other))

            Method.VIA_NAVI_TASK -> {
                val cls = Trampoline.byAnchor[anchor]
                if (cls == null) {
                    log("Für $anchor ist kein Trampolin hinterlegt – übersprungen")
                    cb(false)
                } else {
                    val intent = Intent(this, cls)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(Trampoline.EXTRA_TARGET, other)
                    cb(tryStart(intent, "Trampolin"))
                }
            }

            Method.TOGGLE -> {
                val ok = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                log("System-Umschalter Splitscreen: Rückgabe $ok")
                if (!ok) {
                    cb(false)
                } else {
                    // Wie von Hand: Navi ist angedockt, die zweite Hälfte zeigt den App-Auswähler.
                    // Ein normaler Start landet dort (wie Antippen im Auswähler), ohne LAUNCH_ADJACENT.
                    handler.postDelayed({ cb(bringToFront(other)) }, 1200)
                }
            }

            Method.AUTO -> cb(false)
        }
    }

    private fun startAdjacent(pkg: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        return tryStart(launch, pkg)
    }

    private fun bringToFront(pkg: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            log("$pkg lässt sich nicht starten")
            return false
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(launch, "Navi")
    }

    private fun tryStart(intent: Intent, what: String): Boolean =
        try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            log("Start $what fehlgeschlagen: $e")
            false
        }

    private fun finishAttempt(ok: Boolean) {
        busy = false
        lastAttemptEnd = SystemClock.elapsedRealtime()
        if (!ok) Prefs.setPaused(this, true)
    }

    // ---- Fenster auswerten ----

    private fun safeWindows(): List<AccessibilityWindowInfo> =
        try {
            windows ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    private fun pkgOf(w: AccessibilityWindowInfo): String? =
        try {
            w.root?.packageName?.toString()
        } catch (e: Exception) {
            null
        }

    private fun isAppVisible(pkg: String): Boolean {
        if (rootInActiveWindow?.packageName?.toString() == pkg) return true
        return safeWindows().any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && pkgOf(it) == pkg }
    }

    /**
     * Splitscreen gilt als aktiv, wenn es ein Splitscreen-Trennbalken-Fenster gibt oder wenn Navi
     * und OBD beide als App-Fenster sichtbar sind und sich nicht überlappen.
     */
    private fun isSplitActive(navi: String, obd: String): Boolean {
        // Reihenfolge der Argumente egal.
        val ws = safeWindows()
        if (ws.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }) return true
        var naviRect: Rect? = null
        var obdRect: Rect? = null
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val p = pkgOf(w) ?: continue
            val r = Rect()
            w.getBoundsInScreen(r)
            if (p == navi) naviRect = r
            if (p == obd) obdRect = r
        }
        val a = naviRect ?: return false
        val b = obdRect ?: return false
        return !Rect.intersects(a, b)
    }

    private fun windowSummary(): String {
        val ws = safeWindows()
        if (ws.isEmpty()) return "(keine)"
        return ws.joinToString("; ") { w ->
            val r = Rect()
            w.getBoundsInScreen(r)
            "${typeName(w.type)}:${pkgOf(w) ?: "-"}@${r.left},${r.top}-${r.right},${r.bottom}"
        }
    }

    private fun typeName(t: Int): String = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "App"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "Tastatur"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "System"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11y"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "Trennbalken"
        else -> "Typ$t"
    }

    fun toggleInfo(): String =
        if (Build.VERSION.SDK_INT >= 30) {
            if (systemActions.any { it.id == GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN }) {
                "vom System angeboten"
            } else {
                "vom System NICHT angeboten"
            }
        } else {
            "unbekannt (Android < 11), zeigt sich beim Versuch"
        }

    private fun log(msg: String) = AppLog.add(this, msg)

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val VERIFY_MS = 2000L
        private const val COOLDOWN_MS = 10_000L
        private const val SWAP_SETTLE_MS = 2000L

        @Volatile
        var instance: SplitService? = null
            private set
    }
}

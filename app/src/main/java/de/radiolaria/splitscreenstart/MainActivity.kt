package de.radiolaria.splitscreenstart

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var root: LinearLayout
    private var apps: List<AppEntry> = emptyList()
    private val logListener: () -> Unit = { runOnUiThread { refreshLog() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = AppList.load(this)
        Prefs.applyDefaults(this, apps)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Splitscreen-Start"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Startet die OBD-App automatisch im Splitscreen neben der Navi."
            textSize = 15f
            setPadding(0, dp(4), 0, dp(12))
        })

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        root.addView(button("Bedienungshilfen öffnen (Dienst einschalten)") {
            startSafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            root.addView(hint(
                "Ist der Schalter in den Bedienungshilfen ausgegraut („Eingeschränkte Einstellung“): " +
                    "App-Info öffnen → Menü ⋮ oben rechts → „Eingeschränkte Einstellungen zulassen“."
            ))
        }
        root.addView(button("App-Info öffnen") {
            startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        root.addView(Switch(this).apply {
            text = "Automatik aktiv"
            textSize = 17f
            isChecked = Prefs.enabled(this@MainActivity)
            setPadding(0, dp(16), 0, dp(8))
            setOnCheckedChangeListener { _, on ->
                Prefs.setEnabled(this@MainActivity, on)
                Prefs.setPaused(this@MainActivity, false)
                refreshStatus()
            }
        })

        // Erster Eintrag "nicht gewählt", damit nie stillschweigend irgendeine App vorgewählt wird.
        val labels = listOf("– bitte wählen –") + apps.map { "${it.label}  (${it.pkg})" }
        val pkgs: List<String?> = listOf<String?>(null) + apps.map { it.pkg }

        root.addView(label("Navi-App"))
        root.addView(spinner(labels, pkgs.indexOf(Prefs.navi(this))) { i ->
            if (Prefs.navi(this) != pkgs[i]) {
                Prefs.setNavi(this, pkgs[i])
                settingChanged()
            }
        })

        root.addView(label("OBD-App (kommt daneben)"))
        root.addView(spinner(labels, pkgs.indexOf(Prefs.obd(this))) { i ->
            if (Prefs.obd(this) != pkgs[i]) {
                Prefs.setObd(this, pkgs[i])
                settingChanged()
            }
        })

        val methods = Method.entries
        root.addView(label("Verfahren"))
        root.addView(spinner(methods.map { it.label }, methods.indexOf(Prefs.method(this))) { i ->
            if (Prefs.method(this) != methods[i]) {
                Prefs.setMethod(this, methods[i])
                settingChanged()
            }
        })

        val sides = listOf(false, true)
        root.addView(label("Seitenanordnung"))
        root.addView(spinner(
            listOf("Standard (Navi zuerst, OBD kommt daneben)", "Seiten tauschen (OBD zuerst, Navi kommt daneben)"),
            sides.indexOf(Prefs.swapped(this))
        ) { i ->
            if (Prefs.swapped(this) != sides[i]) {
                Prefs.setSwapped(this, sides[i])
                Prefs.setPaused(this, false)
                refreshStatus()
            }
        })

        val delays = Prefs.DELAYS_MS
        root.addView(label("Wartezeit nach Navi-Start"))
        root.addView(spinner(delays.map { "${it / 1000} s" }, delays.indexOf(Prefs.delayMs(this))) { i ->
            if (Prefs.delayMs(this) != delays[i]) {
                Prefs.setDelayMs(this, delays[i])
                settingChanged()
            }
        })

        root.addView(button("Jetzt testen (startet Navi + OBD)") {
            val svc = SplitService.instance
            if (svc == null) {
                toast("Dienst läuft nicht – erst in den Bedienungshilfen einschalten.")
            } else {
                svc.testRun()
            }
        })

        root.addView(label("Protokoll"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Kopieren") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Splitscreen-Start Log", AppLog.deviceLine() + "\n" + AppLog.text(this)))
            toast("Protokoll kopiert")
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Leeren") {
            AppLog.clear(this)
            refreshLog()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(logView)

        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        AppLog.addListener(logListener)
        refreshStatus()
        refreshLog()
    }

    override fun onPause() {
        AppLog.removeListener(logListener)
        super.onPause()
    }

    private fun settingChanged() {
        Prefs.setPaused(this, false)
        Prefs.setLastSuccess(this, null)
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabledInSettings = serviceEnabled()
        val svc = SplitService.instance
        val sb = StringBuilder()
        sb.append(AppLog.deviceLine()).append('\n')
        sb.append("Dienst in Bedienungshilfen: ").append(if (enabledInSettings) "eingeschaltet" else "AUS").append('\n')
        sb.append("Dienst läuft: ").append(if (svc != null) "ja" else "nein").append('\n')
        if (svc != null) sb.append("System-Umschalter Splitscreen: ").append(svc.toggleInfo()).append('\n')
        val last = Prefs.lastSuccess(this)
        sb.append("Zuletzt erfolgreich: ").append(last?.label ?: "noch nie").append('\n')
        if (Prefs.paused(this)) {
            sb.append("Automatik PAUSIERT (letzter Versuch ging schief) – „Jetzt testen“ hebt das auf.\n")
        }
        status.text = sb.toString()
    }

    private fun refreshLog() {
        val t = AppLog.text(this)
        // Neueste Einträge oben.
        logView.text = if (t.isEmpty()) "(leer)" else t.lines().asReversed().joinToString("\n")
        refreshStatus()
    }

    private fun serviceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.split(':').any { it.startsWith("$packageName/") }
    }

    // ---- kleine UI-Helfer ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun spinner(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (selected in labels.indices) s.setSelection(selected)
        s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSelect(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return s
    }

    private fun startSafe(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("Lässt sich auf diesem Gerät nicht öffnen: ${e.javaClass.simpleName}")
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}

# Splitscreen-Start

Kleine Android-App für das Autoradio: Sobald die Navi-App (Standard: Magic Earth) nach vorne kommt,
legt sie die OBD-App (Standard: Car Scanner) automatisch im Splitscreen daneben.

- Funktioniert unabhängig davon, wie die Navi gestartet wird (Symbol, Autostart nach dem Hochfahren, aus einer anderen App)
- Auswahl von Navi- und OBD-App, Verfahren und Wartezeit nur über Auswahllisten
- Protokoll in der App, mit „Kopieren“-Knopf zum Weitergeben
- Kein Internetzugriff, keine Abhängigkeiten außer der Kotlin-Standardbibliothek, ab Android 7.0

## Installieren und einrichten

1. APK aus dem neuesten [Release](../../releases/latest) aufs Radio bringen (USB-Stick oder Browser) und installieren.
2. App öffnen → **Bedienungshilfen öffnen** → „Splitscreen-Start“ einschalten.
   Ab Android 13 ist der Schalter bei per Datei installierten Apps oft ausgegraut: dann
   **App-Info öffnen** → Menü ⋮ → „Eingeschränkte Einstellungen zulassen“, danach noch einmal.
3. Navi-App und OBD-App in den Listen prüfen.
4. **Jetzt testen** drücken. Danach steht im Protokoll, welches Verfahren geklappt hat.

## Wie der Splitscreen ausgelöst wird

Android hat dafür keine einheitliche Schnittstelle für normale Apps, deshalb gibt es drei Verfahren.
„Automatisch“ probiert sie nacheinander und merkt sich das erfolgreiche.

| Verfahren | Android | Wie |
|---|---|---|
| Nebeneinander über Navi-Task | ab 12L (API 32) | Unsichtbare Activity mit `taskAffinity` der Navi landet in deren Task und startet die OBD-App mit `FLAG_ACTIVITY_LAUNCH_ADJACENT`. Ab 12L darf eine App im Vollbild damit den Splitscreen öffnen. |
| Nebeneinander direkt | ab 12L (API 32) | Der Dienst startet die OBD-App selbst mit `FLAG_ACTIVITY_LAUNCH_ADJACENT`. |
| System-Umschalter | bis 12 | `performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)` dockt die Navi an, danach wird die OBD-App gestartet. Ab Android 13 bietet AOSP diese Aktion nicht mehr an. |

Das Navi-Task-Verfahren gibt es für Magic Earth, OsmAnd, OsmAnd+ und Organic Maps (die `taskAffinity`
muss im Manifest stehen).

Ob es geklappt hat, prüft der Dienst über die sichtbaren Fenster (Trennbalken-Fenster oder Navi
und OBD nebeneinander ohne Überlappung). Klappt kein Verfahren, **pausiert die Automatik**, damit
im Auto nichts dauernd flackert. „Jetzt testen“ oder eine geänderte Einstellung hebt die Pause auf.

## Wann wird ausgelöst

Nur beim Wechsel von einer anderen App zur Navi und wenn der Splitscreen nicht schon aktiv ist.
Danach 10 s Ruhe. Läuft die Navi schon, wenn der Dienst startet (Autostart nach dem Hochfahren),
wird ebenfalls ausgelöst.

## Bauen

GitHub Actions baut bei jedem Push auf `main`. Ändert sich die Datei `VERSION`, entsteht ein neues Release
mit der APK. Lokal: `./gradlew assembleRelease`.

Der Signierschlüssel `app/signing.jks` liegt bewusst im Repo, damit Updates sich über die vorige
Version installieren lassen. Für eine reine Privat-App ist das in Ordnung; für Verteilung an Dritte
sollte er durch ein Secret ersetzt werden.

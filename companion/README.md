# Begleitprozess

Spricht über einen lokalen Socket mit dem Mod. Es gibt zwei Programme: die KI (`agent.py`) und
die Konsole zum Testen von Hand (`console.py`). Es kann immer nur eines verbunden sein; das zuletzt
gestartete übernimmt.

## KI

Normalerweise startet der Mod den Begleitprozess selbst bei `/smartcraft join` (abschalten mit
`/smartcraft companion autostart off`, von Hand mit `/smartcraft companion start|stop|status`). Aus der Jar entpackt er
ihn nach `config/smartcraft/` und legt dort eine eigene Python-Umgebung mit `anthropic` an; im Entwicklungsordner nimmt
er `companion/` und das Python, in dem `anthropic` schon installiert ist. Ausgabe: `logs/smartcraft/companion.log`.

Für den Start von Hand einmalig einrichten:

```
pip install -r companion/requirements.txt
```

Den API-Schlüssel am einfachsten im Spiel festlegen:

```
/smartcraft apikey sk-ant-...
```

Der Mod ruft selbst keine API auf; er reicht den Schlüssel an den Begleitprozess weiter (sofort oder beim
nächsten Verbinden), der ihn in `companion/api_key.txt` speichert (steht in `.gitignore`). In den Logs steht
er als `***`. Alternativ als Umgebungsvariable, in PowerShell für das aktuelle Fenster:

```
$env:ANTHROPIC_API_KEY = "sk-ant-..."
```

Dann:

1. Spiel starten (`./gradlew runClient`), Welt öffnen, `/smartcraft join`.
2. In einem zweiten Fenster: `python companion/agent.py`
3. Im Spiel: `/smartcraft goal Baue eine Steinspitzhacke`

Das Fenster des Begleitprozesses zeigt jeden Schritt mit Ergebnis und den bisherigen Kosten. Im
Spiel halten `/smartcraft pause`, `/smartcraft resume` und `/smartcraft stop` die KI an. Ein Ziel lässt
sich auch gleich beim Start mitgeben: `python companion/agent.py "Baue eine Steinspitzhacke"`.

Du kannst der KI auch einfach in den Chat schreiben: Sie antwortet dort und erledigt, worum du
sie bittest. Sind mehrere Spieler im Spiel, fühlt sie sich nur angesprochen, wenn ihr Name fällt.
Eine Nachricht an sie hält die gerade laufende Aktion an, damit sie gleich reagieren kann, und
kostet wie jeder Schritt Modellaufrufe.

Ein Kostenlimit gibt es nur, wenn `SMARTCRAFT_COST_LIMIT` gesetzt ist; sonst läuft ein Ziel, bis es
fertig ist oder die Höchstzahl an Schritten erreicht. Was die KI getan hat, steht danach in
`companion/logs/`, ihre Notizen pro Welt in `companion/memory/<welt>_<seed>/notes.json`. Der Systemprompt ist
`companion/prompts/system.md`.

## Konsole

Voraussetzung: Python 3.11, keine weiteren Pakete. Das Spiel muss mit einer geöffneten Welt
laufen (`./gradlew runClient`, das Launcher-Profil mit Fabric oder `./gradlew runServer`).

```
python companion/console.py
```

Die Konsole zeigt jedes Event des Mods als eine Zeile. `!!` markiert hohe Priorität
(Gefahr, Schaden), `<-` alles andere. Einen Befehl schickst du als Name plus optionale
Argumente als JSON-Objekt:

```
ping
look
```

Jeder Befehl bekommt genau eine Antwort `<name>_result` mit `OK`, `FAILED` oder `CANCELLED`
und der `id` des Befehls. Unbekannte Befehle werden mit `reason=unknown_command` abgelehnt.

Der Bot selbst tritt weiterhin im Spiel mit `/smartcraft join` bei.

## Einstellungen

| Umgebungsvariable | Bedeutung | Standard |
|---|---|---|
| `SMARTCRAFT_PORT` | Port des Mods; muss im Spiel und hier gleich gesetzt sein | `25599` |
| `ANTHROPIC_API_KEY` | Schlüssel für die API (nur `agent.py`); ohne die Variable liest `agent.py` die erste Zeile aus `companion/api_key.txt` (steht in .gitignore) | – |
| `SMARTCRAFT_MODEL` | Modell | `claude-sonnet-5-5` |
| `SMARTCRAFT_EFFORT` | Wie gründlich das Modell nachdenkt: `low`, `medium`, `high` | `medium` |
| `SMARTCRAFT_COST_LIMIT` | Kostenlimit pro Ziel in Dollar; `0` heißt kein Limit | `0` |
| `SMARTCRAFT_MAX_STEPS` | Höchstzahl an Modellaufrufen pro Ziel, `0` = unbegrenzt | `0` |
| `SMARTCRAFT_FALLBACKS` | `0` schaltet das Ausweichmodell bei einer abgelehnten Anfrage ab | `1` |
| `SMARTCRAFT_CHAT` | `plans`: nur kurze Ansagen, was er vorhat (spart Tokens); `full` (oder `1`): gesprächig mit Humor und Anstößen zum Reden; `off` (oder `0`): kein Chat | `plans` |
| (entfällt) | Wie oft der Bot redet, regelt jetzt `/smartcraft talkmode light\|medium\|hard` (Abstände in `TALK_SETTINGS`) | – |
| `SMARTCRAFT_TTS` | `1` startet mit Sprachausgabe (Chatnachrichten des Bots werden unter Windows vorgelesen); im Spiel umschalten mit `/smartcraft tts on` bzw. `off` | `0` |
| `SMARTCRAFT_TTS_RATE` | Sprechtempo, `-10` bis `10` | `1` |
| `SMARTCRAFT_TTS_PIPER_VOICE` | Piper-Stimmmodell in `companion/voices/` (laden: dort `python -m piper.download_voices <name>`); fehlt es, spricht die Windows-Stimme | `de_DE-thorsten-high` |
| `SMARTCRAFT_TTS_VOICE` | Teil des Stimmennamens, z. B. `Katja`; sonst die erste deutsche Stimme | – |

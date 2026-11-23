# Begleitprozess

Spricht über einen lokalen Socket mit dem Mod. Es gibt zwei Programme: die KI (`agent.py`) und
die Konsole zum Testen von Hand (`console.py`). Es kann immer nur eines verbunden sein; das zuletzt
gestartete übernimmt.

## KI

Einmalig einrichten:

```
pip install -r companion/requirements.txt
```

Den API-Schlüssel am einfachsten im Spiel festlegen:

```
/aiplayer apikey sk-ant-...
```

Der Mod ruft selbst keine API auf; er reicht den Schlüssel an den Begleitprozess weiter (sofort oder beim
nächsten Verbinden), der ihn in `companion/api_key.txt` speichert (steht in `.gitignore`). In den Logs steht
er als `***`. Alternativ als Umgebungsvariable, in PowerShell für das aktuelle Fenster:

```
$env:ANTHROPIC_API_KEY = "sk-ant-..."
```

Dann:

1. Spiel starten (`./gradlew runClient`), Welt öffnen, `/aiplayer join`.
2. In einem zweiten Fenster: `python companion/agent.py`
3. Im Spiel: `/aiplayer goal Baue eine Steinspitzhacke`

Das Fenster des Begleitprozesses zeigt jeden Schritt mit Ergebnis und den bisherigen Kosten. Im
Spiel halten `/aiplayer pause`, `/aiplayer resume` und `/aiplayer stop` die KI an. Ein Ziel lässt
sich auch gleich beim Start mitgeben: `python companion/agent.py "Baue eine Steinspitzhacke"`.

Du kannst der KI auch einfach in den Chat schreiben: Sie antwortet dort und erledigt, worum du
sie bittest. Sind mehrere Spieler im Spiel, fühlt sie sich nur angesprochen, wenn ihr Name fällt.
Eine Nachricht an sie hält die gerade laufende Aktion an, damit sie gleich reagieren kann, und
kostet wie jeder Schritt Modellaufrufe.

Ein Kostenlimit gibt es nur, wenn `AIPLAYER_COST_LIMIT` gesetzt ist; sonst läuft ein Ziel, bis es
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

Der Bot selbst tritt weiterhin im Spiel mit `/aiplayer join` bei.

## Einstellungen

| Umgebungsvariable | Bedeutung | Standard |
|---|---|---|
| `AIPLAYER_PORT` | Port des Mods; muss im Spiel und hier gleich gesetzt sein | `25599` |
| `ANTHROPIC_API_KEY` | Schlüssel für die API (nur `agent.py`); ohne die Variable liest `agent.py` die erste Zeile aus `companion/api_key.txt` (steht in .gitignore) | – |
| `AIPLAYER_MODEL` | Modell | `claude-sonnet-5-5` |
| `AIPLAYER_EFFORT` | Wie gründlich das Modell nachdenkt: `low`, `medium`, `high` | `medium` |
| `AIPLAYER_COST_LIMIT` | Kostenlimit pro Ziel in Dollar; `0` heißt kein Limit | `0` |
| `AIPLAYER_MAX_STEPS` | Höchstzahl an Modellaufrufen pro Ziel, `0` = unbegrenzt | `0` |
| `AIPLAYER_FALLBACKS` | `0` schaltet das Ausweichmodell bei einer abgelehnten Anfrage ab | `1` |
| `AIPLAYER_CHAT` | `plans`: nur kurze Ansagen, was er vorhat (spart Tokens); `full` (oder `1`): gesprächig mit Humor und Anstößen zum Reden; `off` (oder `0`): kein Chat | `plans` |
| (entfällt) | Wie oft der Bot redet, regelt jetzt `/aiplayer talkmode light\|medium\|hard` (Abstände in `TALK_SETTINGS`) | – |
| `AIPLAYER_TTS` | `1` startet mit Sprachausgabe (Chatnachrichten des Bots werden unter Windows vorgelesen); im Spiel umschalten mit `/aiplayer tts on` bzw. `off` | `0` |
| `AIPLAYER_TTS_RATE` | Sprechtempo, `-10` bis `10` | `1` |
| `AIPLAYER_TTS_PIPER_VOICE` | Piper-Stimmmodell in `companion/voices/` (laden: dort `python -m piper.download_voices <name>`); fehlt es, spricht die Windows-Stimme | `de_DE-thorsten-high` |
| `AIPLAYER_TTS_VOICE` | Teil des Stimmennamens, z. B. `Katja`; sonst die erste deutsche Stimme | – |

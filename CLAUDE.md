# CLAUDE.md – SmartCraft, KI-Minecraft-Spieler (Fabric-Mod)

## Projektziel
Ein Fabric-Mod, über den ein Sprachmodell (Claude via Anthropic API) eine Spielfigur
in Minecraft Java Edition steuert. Langfristig: selbstständig bis zum Enderdrachen.
Aktuell: kleine, zuverlässige Schritte statt großer Sprünge.

Bewusste Entscheidung: **kein Mineflayer, kein Mindcraft.** Der Mod sitzt direkt im Spiel
und bringt seine eigene Bibliothek für Wahrnehmung, Events und Aktionen mit.

**Die KI ist ein eigener, zweiter Spieler.** Sie spawnt als zusätzliche Spielfigur in der Welt,
mit eigenem Namen, Inventar, Leben und Position. Der menschliche Spieler schaut zu (z. B. im
Zuschauermodus) und wird nie selbst gesteuert.
- Umsetzung serverseitig als „Fake Player“: eine echte `ServerPlayerEntity` ohne echten
  Client, mit simulierter Netzwerkverbindung (Prinzip wie beim Carpet-Mod `/player`)
- **Ziel-Erlebnis: Welt öffnen → „Im LAN öffnen“ → `/smartcraft join` → die KI tritt bei.**
  Für alle Spieler muss es wie ein echter Join aussehen:
  - Join-/Leave-Nachricht im Chat („Claude ist dem Spiel beigetreten“)
  - Eintrag in der Tab-Liste, Name über dem Kopf, Skin
  - Für andere LAN-Spieler normal sichtbar, kann chatten
- Technisch KEIN echter Netzwerk-Client: Der Fake Player wird im Server des Hosts erzeugt
  und durchläuft den normalen Join-Ablauf des PlayerManagers. Kein Minecraft-Account nötig,
  Physik/Kollision/Pathfinding kommen vom Spiel.
- Der Mod muss nur beim Host installiert sein, andere LAN-Spieler brauchen ihn nicht
  (keine eigenen Blöcke/Items/Pakete, die Clients kennen müssten)
- Funktioniert im Einzelspieler (mit oder ohne LAN) und auf einem lokalen Dedicated Server
- Der Mod ist daher primär ein **Server-Mod**; Client-Code nur für optionale Anzeigen
- Befehle: `/smartcraft join [name]`, `/smartcraft leave`, `/smartcraft status`, `/smartcraft look`,
  für die KI `/smartcraft goal <text>`, `/smartcraft pause`, `/smartcraft resume`, `/smartcraft stop`, `/smartcraft tts on|off`, `/smartcraft locate on|off`, `/smartcraft stats on|off`, `/smartcraft xpbar on|off`, `/smartcraft info`, `/smartcraft inventar`, `/smartcraft humor light|medium|hard|crazy`,
  `/smartcraft talkmode light|medium|hard`, `/smartcraft dimensionfollow on|off`, `/smartcraft gamemode <modus>`, `/smartcraft streamer [off]`, `/smartcraft apikey <key>`, `/smartcraft companion start|stop|status|autostart on|off`,
  Testhilfen `/smartcraft debug punch`, `/smartcraft debug break`, `/smartcraft debug bedprobe <anzahl> <ticks> <unter_kopf>` (Messung: Betten direkt unter dem Drachenkopf) und `/smartcraft kit nether` (alles für den Nether, mit 10 Obsidian), `/smartcraft kit end` (alles fürs End, 18 Enderaugen, 16 Betten)
- Bot-Zustand (Inventar, Position) wird wie bei echten Spielern gespeichert und beim
  nächsten Join wiederhergestellt

## Aktueller Stand
<!-- Nach jeder abgeschlossenen Etappe aktualisieren -->
- [x] Etappe 1: Fundament (leerer Mod, `runClient` läuft)
- [x] Etappe 1b: KI-Spieler joint per `/smartcraft join` (Chat-Nachricht, Tab-Liste, sichtbar,
      auch für einen zweiten LAN-Spieler ohne Mod)
- [x] Etappe 2: Wahrnehmung und erste Events (Blöcke, Schaden, Tod, Mobs, Gefahren, Snapshot
      per `/smartcraft look`), dazu Rückstoß-Fix und automatischer Respawn
- [x] Etappe 3: Brücke zum Begleitprozess in beide Richtungen (Events raus, Befehle rein,
      Konsole `companion/console.py`)
- [ ] Etappe 4: Aktionsbibliothek, alles per Befehl ohne KI. Nach jeder Unteretappe auf Okay warten.
  - [x] 4a Bewegung: `goto` (A*), `goto_entity`, `follow`, `stop`; Grundgerüst für asynchrone
        Aktionen (`_started`/`_result`, Timeout, „steckt fest“, Abbruch bei Gefahr, Sicherheitsnetz)

  - [x] 4b Abbauen, Sammeln, Platzieren, Inventar: `mine`, `collect`, `pickup_items`, `place`,
        `equip`, `inventory`, `drop`, dazu `goto` mit `allow_dig`/`allow_place` und das Event
        `tool_low_durability`
  - [x] 4c Crafting und Schmelzen: `craft`, `recipe`, `smelt`, `smelt_collect`, `place_utility`,
        `store`, `take`, Ortsgedächtnis für Werkbank/Ofen/Truhe, Event `smelting_done`
  - [ ] 4d Überleben und Kampf: `eat`, `cook`, `attack`, `defend`, `flee`, `shield`, `equip_best_armor`,
        `sleep`, `shelter`, `light`, Events `hunger_low`, `health_low`, `night_falling`, Reflexe
        (Notfall-Essen, Luft holen, Enderman nicht ansehen), `collect` mit `allow_dig` – gebaut;
        Servertest 51 von 51, Gesamtparcours durch die Nacht 2 von 3. Abnahme über die Testdurchläufe (Entscheidung von Hannes, 10.11.2026)
  - [x] 4e Testparcours: in 4d aufgegangen (`tests/server/test_course.py`, Auftrag von Hannes)
- [ ] Etappe 5: KI-Anbindung – gebaut (13.10.2026 vorgezogen auf Wunsch von Hannes, Etappe 4d ist im
      Spiel noch nicht abgenommen): `companion/agent.py` mit Tool Use, jedes Schema ein Tool, Systemprompt
      in `companion/prompts/system.md`, Notizblock `remember`, `wait`, kein Kostenlimit (optional über `SMARTCRAFT_COST_LIMIT`),
      `/smartcraft goal|pause|resume|stop`, Befehl `chat`, Event `chat_received` (die KI liest den Chat,
      antwortet und sagt ihre Pläne mit Humor an). Servertest ohne API: siehe ARCHITECTURE.md. Erster echter Lauf
      am 14.10.2026 (Haiku, 2 h, volle Eisenrüstung, kein Tod), Fehler daraus behoben, siehe Offene Punkte
- [ ] Etappe 6: Gedächtnis für wichtige Orte gebaut (10.11.2026: Portale, Lavaseen, Spawner, Festungen, Dörfer,
      Endportalräume, Betten; `known_places` in `look`, Befehl `places`), Teilziele bis zum Enderdrachen offen

## Architektur (Kurzfassung)
```
Minecraft ── Fabric-Mod (Java) ──Socket/JSON── Begleitprozess ──API── Claude
              │ Wahrnehmung                      │ Prompt-Aufbau
              │ Events                           │ Modell-Aufruf
              │ Aktionen + Sicherheitsnetz       │ Antwort → Befehl
```
Details in `ARCHITECTURE.md`. Schemas für Events und Befehle in `schemas/`.

## Tech-Stack
- Minecraft Java Edition 26.2, Fabric Loader + Fabric API, offizielle Mojang-Namen
  (kein Yarn: `ServerPlayer` statt `ServerPlayerEntity`, `PlayerList` statt `PlayerManager`)
- Java 25, Gradle (Wrapper benutzen, nie globales Gradle). Gradle läuft über
  `gradle/gradle-daemon-jvm.properties` auf JDK 25 (`~/.jdks`), nicht auf dem System-Java 27
- Begleitprozess: Python 3.11, siehe `companion/` (ab Etappe 3); für die KI das offizielle SDK `anthropic`
- Versionen von Minecraft/Fabric/Loom stehen in `gradle.properties` – nie raten,
  bei Änderungen in der offiziellen Fabric-Doku prüfen

## Befehle
```bash
./gradlew build          # Mod bauen
./gradlew runClient      # Spiel mit Mod starten (Testwelt)
./gradlew runServer      # Testserver ohne Fenster (flache Welt in run/world), Befehle über die Konsole
./gradlew test           # Unit-Tests
python tests/server/test_movement.py   # Servertest Bewegung, flache Welt (ca. 4 Minuten)
python tests/server/test_blocks.py     # Servertest Abbauen/Setzen/Inventar, flache Welt (ca. 5 Minuten)
python tests/server/test_craft.py 3    # Servertest Crafting/Schmelzen/Truhe und Parcours bis zur Eisenspitzhacke (ca. 7 Minuten)
python tests/server/test_survival.py   # Servertest Essen, Kampf, Flucht, Rüstung, Schlafen, Unterschlupf (ca. 6 Minuten)
python tests/server/test_course.py 3   # Gesamtparcours in normaler Welt bis durch die Nacht, 3 Läufe (ca. 70 Minuten)
python tests/server/test_equip.py      # Servertest Ausrüsten und Werkzeug-Warnung (ca. 2 Minuten)
python tests/server/test_agent.py      # Servertest der KI-Schleife ohne API (ca. 2 Minuten)
python tests/server/test_fixes.py      # Servertest der Fehlerbehebungen nach dem ersten KI-Lauf (ca. 4 Minuten)
python tests/server/test_until_death.py 100   # Dauerlauf in frischer Welt ohne Nachhilfe, bis der Bot stirbt
python tests/server/test_course.py 1 --kamera # Parcours mit Zuschauer-Fenster und Bildern nach run/screenshots
python tests/server/test_forest.py 5   # Servertest Holz sammeln im Wald, 5 Durchläufe (ca. 5 Minuten)
python tests/server/test_end.py        # Servertest Weg ins End: Türen, shoot, Drache, Endportal, Enderauge (ca. 6 Minuten)
python tests/server/test_magma.py      # Servertest vom Magmablock herunter (goto, flee; ca. 1 Minute)
python tests/server/test_portal.py     # Servertest build_portal (ca. 1 Minute)
python tests/server/test_farm.py       # Servertest farm_spawner: Zombies, Lohe mit Bogen (ca. 2 Minuten)
python tests/server/test_fortress.py   # Servertest Festung: tunnel/goto graben sie nicht an, locate, in_fortress (ca. 2 Minuten)
python tests/server/test_ghast.py      # Servertest Feuerball zurückschlagen, Kante meiden bei Ghast (ca. 1 Minute)
python tests/server/test_junk.py       # Servertest Abraum wegwerfen bei vollem Inventar (ca. 1 Minute)
python tests/server/test_hunt.py       # Servertest collect jagt bei Holz/Stein nebenbei Nutztiere (ca. 1 Minute)
python tests/server/test_corridor.py   # Servertest Nether: goto mit allow_place baut geradeaus über Lava (ca. 1 Minute)
python tests/server/test_lava_island.py # Servertest Lava-Reflex baut Insel, farm_endermen ohne Bausteine (ca. 1 Minute)
python tests/server/test_stats.py      # Servertest Leben/Hunger unter dem Namen (ca. 30 Sekunden)
python tests/server/test_tools.py      # Servertest Eimer/Obsidian, use, Behälter, Tunnel, Sprinten, Festung (ca. 4 Minuten)
python tests/server/test_bed_cycle.py  # Messung One-Cycle: Bettschaden am Drachen, Kristalle stehen (ca. 3 Minuten)
python tests/server/test_bed_bomb.py   # Servertest bed_bomb: Betten sprengen den hockenden Drachen (ca. 2 Minuten)
python tests/server/test_settings.py   # Servertest Einstellungen (info, humor, talkmode, settings) und shelter nachts bei voller Ausrüstung (ca. 1 Minute)
# Die Servertests nicht gleichzeitig mit `gradlew build` oder untereinander starten.
# Schneller: SMARTCRAFT_TEST_TICK_RATE=60 vor den Testaufruf setzen (Spiel läuft mit 60 statt 20 Ticks, etwa halbe Dauer).
```
Begleitprozess: `python companion/agent.py` (KI, braucht `pip install -r companion/requirements.txt` und
`ANTHROPIC_API_KEY`) oder `python companion/console.py` (Konsole zum Testen), siehe `companion/README.md`.

## Kernprinzipien (nicht verhandelbar)
1. **Rückmeldung statt Vermutung.** Jede Aktion erzeugt ein Ergebnis-Event mit
   `ok`/`failed` und Grund. Das Modell handelt nur nach bestätigten Fakten.
2. **Token sparen.** Nur Relevantes senden: Item in der Hand statt ganzes Inventar,
   gefilterte Umgebung statt aller Blöcke im Radius. Vollständige Daten nur auf Anfrage.
3. **Gefahren zuerst.** Gefahren-Events (Lava, Abgrund, Schaden, feindliche Mobs,
   wenig Leben/Hunger, kein Boden voraus) haben höchste Priorität.
4. **Strategie oben, Handwerk unten.** Das Modell gibt High-Level-Befehle
   (`goto`, `mine`, `craft`, `place`, …). Pathfinding und Feinsteuerung macht der Mod.
5. **Sicherheitsnetz.** Offensichtlich tödliche Aktionen (Schritt ins Leere,
   Graben direkt nach unten ohne Prüfung) blockiert der Mod und meldet sie als Event.
6. **Mod und KI getrennt.** Keine API-Aufrufe und keine Schlüssel im Mod.

## Konventionen
- Package-Root: `de.hannes.smartcraft`, Mod-ID `smartcraft`, Befehl `/smartcraft`, Umgebungsvariablen `SMARTCRAFT_*`, Datenordner `logs/smartcraft/` und `<welt>/smartcraft/places.json`
- Events und Befehle sind versioniertes JSON (`"v": 1`). Schema zuerst ändern, dann Code.
- Event-Namen: `snake_case`, Vergangenheitsform für Fakten (`block_broken`,
  `damage_taken`), Ergebnis-Events als `<befehl>_result`
- Jede neue Aktion braucht: Schema, Ergebnis-Event mit Fehlergründen, Sicherheitscheck,
  Eintrag in `ARCHITECTURE.md`
- Kommentare und Doku auf Deutsch, Code-Bezeichner auf Englisch
- Spiel-Code nur auf dem richtigen Thread anfassen (Server-Tick), Socket-I/O nie
  auf dem Spiel-Thread blockierend

## Debugging
- Debug-Log unter `run/logs/smartcraft/` protokolliert jede Wahrnehmung, Entscheidung
  und jedes Ergebnis mit Zeitstempel und Tick. Bei Fehlern zuerst dort nachsehen.
- Fehler reproduzierbar machen: Weltseed und Startposition im Log festhalten.

## Sicherheit
- `ANTHROPIC_API_KEY` als Umgebungsvariable, per `/smartcraft apikey <key>` (der Mod reicht ihn nur weiter, Logs zeigen `***`) oder in `companion/api_key.txt` (in .gitignore, liest nur der
  Begleitprozess, nie der Mod), nie committen, nie loggen
- Socket nur auf `127.0.0.1` binden
- Nur für Einzelspieler/lokale Testwelten, nicht für öffentliche Server

## Arbeitsweise für Claude
- Etappen strikt nacheinander. Jede Etappe endet mit grünem Build und einer kurzen
  Testanleitung für das Spiel. Danach auf Feedback warten.
- Vor größeren Änderungen: kurz den Plan zeigen.
- Bei mehreren sinnvollen Wegen oder Unklarheiten: nachfragen statt raten.
- Nach jeder abgeschlossenen Etappe: „Aktueller Stand“ oben aktualisieren und
  wichtige Entscheidungen unten eintragen.

## Offene Punkte
<!-- Bekannte Lücken, die bewusst noch nicht behoben sind -->
- Zäune, Mauern, Eisentüren, Falltüren und Glasscheiben sind für die Wegfindung Hindernisse. Holztüren und
  Zauntore öffnet er im Vorbeigehen.
- Die Wegsuche weiß nicht, welches Werkzeug im Inventar ist: `goto` mit `allow_dig` plant auch durch
  Stein ohne Spitzhacke und scheitert dann ehrlich mit `stuck` / `dig_failed_wrong_tool`.
- Eisen finden klappt nur in passendem Gelände: `collect #iron_ores` mit `allow_dig` fand im
  Parcours je nach Startplatz 0 bis 27 von 27 Erzen. Erz unter oder neben Wasser lässt der Bot
  liegen, und weit entferntes im Fels sprengt die Suchgrenze. Für die KI heißt das: Höhlen suchen
  oder gezielt hinabgraben. Ungeklärt ist, warum er an zwei Startplätzen gar keins erreichte.
- Der Mod reagiert nicht von selbst auf `entity_nearby`: In den Parcours- und Dauerläufen ohne KI
  starb der Bot dreimal, weil während eines langen Befehls niemand reagierte (Creeper beim Holz,
  Zombie am Ofen, Creeper in der Höhle). Mit KI hält der Begleitprozess die Aktion an und fragt das
  Modell; ob das schnell genug ist, zeigt erst der Test mit echter API.
- Alte Tool-Ergebnisse werden gekürzt (nur Status, Grund, Standort); was darin stand, kennt das Modell danach nicht mehr. Vom Chat kennt die KI nur die letzten 12 Zeilen;
  private Nachrichten (`/msg`) liest sie nicht. Jede Chatzeile an den Bot kostet Modellaufrufe und
  hält eine laufende Aktion an.
- Kampf: kein Bogen, kein Ausweichen vor einzelnen Pfeilen, mehrere Gegner nur nacheinander. Gegen
  drei Gegner zugleich fiel das Leben im Parcours unter die Schwelle (`health_low`); Flucht und
  Hütte haben ihn gerettet.
- Die Wegfindung taucht nicht mehr (Kopf unter Wasser ist tabu). Ziele unter Wasser sind damit
  unerreichbar.
- `sleep` sucht nur im Umkreis von 24 Blöcken (gemerkte Betten nennt `look` in `known_places`).
- Erster echter KI-Lauf (14.10.2026, Haiku, „Spiel das komplette Spiel durch“, 2 h, 0,13 $): volle
  Eisenrüstung, Eisenschwert, kein Tod, keine Diamanten. Der Bot kommt langsam voran (viel Laufen, Warten).
  Behoben danach: Hütte am Hang, Erzader, Aufgabe bei Unerreichbarem, Bett-Rezept, Werkbank-Weg, Herzschlag
- Zweiter KI-Lauf (14.10.2026, 85 Min., 0,39 $): volle Eisenrüstung, Diamantspitzhacke, Bett, Feuerzeug;
  ein Tod (im Wasser eingebaut, ertrunken). Lava für das Netherportal nicht gefunden. Behoben danach:
  `shelter` im Wasser, `goto` aus tiefem Wasser, Befehl `descend`, Prompt (allein, Lava-Tiefe, garen)
- Der Test „auf einen Enderman zulaufen“ in `test_survival.py` wackelt (einmal 50 von 51, danach wieder grün)
- Die Parcours-Skripte helfen nach (Tiere, fehlendes Erz, fehlende Gegner hingestellt, Tageszeit
  bei der Vorbereitung angehalten); siehe Kopf von `tests/server/test_course.py`.
- Schmelzen: nur der normale Ofen; die Liste der befüllten Öfen (für `smelting_done`) gilt bis zum
  Neustart der Welt. Truhen: nur einfache Truhen, keine Fässer.
- Wird `collect` von außen abgebrochen, während der Bot auf seinem Turm steht, bleibt er oben
  (Ergebnis: `standing_on_own_pillar`). Herunter kommt er mit `mine` auf den Block unter sich.
- Die Wegsuche läuft im Server-Thread mit Zeitbudget. Einzelne Ausreißer über 3 ms pro Tick kommen
  vor; ganz weg wären sie nur mit einer Suche auf einer Kopie der Blockdaten in einem eigenen Thread.
- `bucket`, `use`, `tunnel` und `store`/`take` mit `pos` fehlen in `companion/console.py`; `items_received` ist ungetestet (braucht einen zweiten Spieler). Handel mit Dorfbewohnern geht nicht (`use` öffnet das Fenster nur und schließt es wieder).
- `seal_water` in einer gefluteten Mulde: Danach bleibt oft ein flacher Rest Fließwasser im eigenen Feld (`in_water: true`); Abbauen geht trotzdem in normalem Tempo, weil der Kopf frei ist. Ganz unter Wasser ohne Luft ertrinkt er beim Zubauen.
- `shoot` auf den fliegenden Enderdrachen trifft im Test kaum (in 4 Läufen einmal 0,8 Schaden); Kristalle, Lohen und
  der Drache im Nahkampf funktionieren. Den eigentlichen Drachenkampf (Kristalle auf Türmen, Käfige, Atem, Leere)
  hat noch kein Lauf erreicht.
- Nether-Läufe (16.10.2026, `tests/server/run_nether.py`, Ziel 10 Lohenruten und lebend zurück): bisher 11 Erfolge in
  22 Läufen (Läufe 6 bis 28), Ergebnisse in `run/logs/nether_results.log`. Behoben (16.10., nach Lauf 28, nur im
  Servertest geprüft, kein echter Lauf mehr): vom Festungsdach in den Spawnerraum (senkrecht graben, Fallhöhe 5 für
  `farm_spawner`, Spalte vor einem Sprung freiräumen). `test_tools` läuft mit normaler Tickrate durch (37 von 37, 17.10.); mit `SMARTCRAFT_TEST_TICK_RATE=60` scheitern Wasser-Reflex und `fortress_in_sight` (Zeitabhängigkeit). Lohenruten fallen manchmal unerreichbar (Läufe 5, 17). Der Trank-Test in `test_magma.py` wackelt
- Respawn-Zeit, Fahrzeuge und der Abspann nach dem Endportal sind ungetestet (siehe ARCHITECTURE.md).
- Drachenläufe (15.10.2026, `tests/server/run_dragon.py`, 10 Läufe, Start ausgerüstet im Nether): Kein Lauf erreichte das End.
  Bestes Ergebnis: Festung und Lohenspawner über `locate`, 7 Lohenruten mit `farm_spawner` in 2 bis 3 Minuten (Läufe 5, 6, 9).
  Offen: Endermen jagen klappte kaum (seit 16.10. lockt `attack` sie durch Ansehen an, nur im Servertest geprüft), der Rückweg über `build_portal` ist im echten Lauf noch nicht
  erprobt, `tunnel` prüft keinen losen Kies über dem Gang. Die Fixes nach Lauf 10 (Festungswände zum Spawner, Piglins, Lohen
  unter dem Bot) sind nur im Servertest geprüft

## Entscheidungslog
<!-- Datum – Entscheidung – Begründung -->
- Fabric statt Forge – schlanker, schneller Einstieg für Experimente
- KI als serverseitiger Fake Player (zweiter Spieler) – Mensch schaut zu, Bot hat eigenen Körper
- LAN-Join simuliert statt echtem Netzwerk-Client – kein Account nötig, kein Protokoll-Nachbau,
  Mod nur beim Host
- Eigene Bibliothek statt Mineflayer – volle Kontrolle über Events direkt im Spiel
- Standardmodell: Sonnet – gutes Verhältnis aus Qualität, Tempo und Kosten bei vielen Aufrufen
- 2026-10-13 – Minecraft 26.2, Java 25, keine Yarn-Mappings – 26.x verlangt Java 25 und nutzt die
  offiziellen Mojang-Namen; 26.2 passt zur vorhandenen Installation
- 2026-10-13 – Fake Player nutzt die Vanilla-Klassen `PrepareSpawnTask` und `PlayerList.placeNewPlayer`
  plus ein Mixin (`Player.isClientAuthoritative`) – möglichst wenig eigener Join-Code
- 2026-10-13 – Transport TCP `127.0.0.1:25599`, eine JSON-Nachricht pro Zeile, Mod lauscht;
  Begleitprozess in Python – keine Zusatzbibliothek im Mod
- 2026-10-13 – `FakeConnection` übernimmt die Client-Reaktionen, auf die sich der Server verlässt
  (Rückstoß, Teleport-Bestätigung); Liste in ARCHITECTURE.md – ohne das bleibt z. B. Rückstoß aus
- 2026-10-13 – Respawn automatisch 20 Ticks nach dem Tod über den Vanilla-Weg – sonst blockiert
  ein toter Bot jedes Testen
- 2026-10-13 – Events werden an der Quelle begrenzt (nur bei Änderung, Serien zusammengefasst) –
  Log und Modell sollen nicht überlaufen
- 2026-10-13 – Ergebnis eines Befehls trägt `status` (`ok`/`failed`/`cancelled`) statt eines
  booleschen `ok` – Abbruch ist ein eigener Ausgang, kein Fehler
- 2026-10-13 – Ohne verbundenen Begleitprozess werden Events nicht aufgestaut, sondern verworfen;
  den aktuellen Stand holt `look` – ein später verbundener Prozess soll nicht mit Altem überflutet werden
- 2026-10-13 – Wegsuche zeitlich gedeckelt (3 ms pro Tick, 12.000 Felder) und in Teilstücken –
  eine lange Suche darf keinen Tick blockieren, weite Ziele bleiben trotzdem erreichbar
- 2026-10-13 – Gefahr bricht eine Aktion ab statt sie zu pausieren, und nur wenn Lava/Feuer neu
  auftaucht – einfacher Zustand; die Entscheidung „weiter oder nicht“ liegt beim Absender
- 2026-10-13 – Automatische Servertests in `tests/server/` – Bewegungen lassen sich nur im
  laufenden Spiel prüfen, und von Hand wäre das nicht wiederholbar
- 2026-10-13 – Unerreichbare Ziele: Abbruch nach 3.000 Feldern ohne Annäherung statt zweiter Suche,
  Ergebnis nennt `closest` – Absage in unter einer Sekunde, und die KI kann von dort weiterdenken
- 2026-10-13 – Teilblöcke nach ihrer Form eingeordnet (Höhe und Grundfläche) statt nach Blockliste –
  deckt Platten, Treppen, Teppich, Schnee ohne Pflege einer Liste ab
- 2026-10-13 – Aktionen bestehen aus kleinen Handgriffen im Paket `skill`, die je Tick weiterlaufen –
  `mine`, `collect` und das Graben in `goto` teilen sich denselben Abbau-Code samt Sicherheitsnetz
- 2026-10-13 – Hohe Bäume: hochbauen und Turm danach abbauen statt auf Laub klettern – der Bot
  steht am Ende sicher wieder auf dem Boden
- 2026-10-13 – Servertests auf eigenem Port 25598 – ein Testlauf hatte sich mit Hannes' laufendem
  Spiel verbunden und dort einen `stop` ausgelöst
- 2026-10-13 – Einsammeln richtet sich nach der Aufhebe-Reichweite des Spiels und gräbt sich notfalls
  durch Laub – im Wald lagen Stämme auf Laub und Reststämmen, der Waldtest bestand nur 2 von 5
- 2026-10-13 – `craft` und `smelt` stellen Werkbank und Ofen nicht selbst auf, sondern melden, was fehlt;
  `place_utility` ist ein eigener Befehl – der Bot verbaut nichts ungefragt, die KI entscheidet wo
- 2026-10-13 – `smelt` endet nach dem Befüllen, das Ende meldet `smelting_done` – der Bot steht nicht
  zehn Sekunden pro Barren herum und bleibt für Gefahren ansprechbar
- 2026-10-13 – Rezepte werden zur Laufzeit aus dem Spiel gelesen, Zutaten als Tag benannt
  (`#minecraft:planks`) – keine eigene Rezeptliste, die bei neuen Versionen veraltet
- 2026-10-13 – Ortsgedächtnis als JSON im Weltordner statt in den Spielerdaten – pro Welt, von Hand
  lesbar, unabhängig vom Namen des Bots
- 2026-10-13 – Schaden bricht `attack`, `defend`, `flee`, `eat`, `shelter` nicht ab; dafür `cancelled:
  health_low` bei `min_health` – ein Kampf, der beim ersten Treffer endet, ist keiner
- 2026-10-13 – Ertrinken und Verhungern brechen keine Aktion ab, und die Wegfindung taucht nicht mehr –
  im Parcours ertrank der Bot, weil jeder Schadenstick das rettende `goto` abbrach
- 2026-10-13 – Reflexe im Mod nur für das, was keinen Aufschub duldet (Notfall-Essen bei Leben ≤ 4,
  Luft holen, Enderman nicht ansehen, Schild halten), jeweils mit Event – alles andere entscheidet die KI
- 2026-10-13 – Ob Essen schadet, welche Waffe und welche Rüstung die beste ist, liest der Mod aus den
  Daten des Spiels (Wirkungen, Attribute) – keine eigenen Listen, wie schon bei den Rezepten
- 2026-10-13 – `collect` gräbt nur mit `allow_dig`/`allow_place` zum Block – der Bot gräbt keine Stollen
  ungefragt; mit Graben geht er dicht heran, damit die Beute nicht im Fels liegen bleibt
- 2026-10-13 – Unterschlupf als Hütte mit offenen Ecken (10 Blöcke) oder eingegraben (0 Blöcke) –
  soll auch mit fast leerem Inventar gehen
- 2026-10-13 – `smelt` nimmt den Brennstoff, der für die ganze Menge reicht, statt stur den besten – im
  Dauerlauf legte der Bot mit einer Kohle neben 41 Brettern nur 8 von 21 Roheisen ein
- 2026-10-13 – Zuschauer-Kamera für Servertests als Client-Teil des Mods (`ShotClient`), aktiv nur mit
  `SMARTCRAFT_SHOT_PORT` – Bilder vom Bot ohne Eingriff ins normale Spiel; der Test stellt dafür
  `online-mode` vorübergehend aus
- 2026-10-13 – KI-Schleife von Hand statt mit dem Tool-Runner des SDK – Kostenlimit, Pause/Stopp und das
  Anhalten bei Gefahr greifen zwischen und während der Tool-Aufrufe ein
- 2026-10-13 – Bei nahem Gegner oder wenig Leben hält der Begleitprozess die laufende Aktion an und fragt
  das Modell; der Mod bekommt dafür keinen Reflex – in beiden Dauerläufen starb der Bot, weil während
  eines langen Befehls niemand reagierte, und die Entscheidung soll bei der KI bleiben
- 2026-10-13 – `pause` und `stop` halten die Aktion im Mod selbst an, das Event geht vorher hinaus – wirkt
  auch, wenn die KI gerade auf die API wartet, und die KI kennt den Grund des Abbruchs
- 2026-10-13 – Kein Kostenlimit mehr als Standard (vorher 2 $ pro Ziel), nur noch auf Wunsch über
  `SMARTCRAFT_COST_LIMIT` – Wunsch von Hannes; die Obergrenze von 200 Schritten pro Ziel bleibt
- 2026-10-13 – Die KI liest den Chat über das Event `chat_received`; eine Nachricht an den Bot
  (`direct`) beginnt im Leerlauf ein Gespräch und hält während eines Ziels die laufende Aktion an –
  Hannes will mit ihm schreiben, und eine Antwort erst nach einem langen `collect` wäre kein Gespräch
- 2026-10-13 – Notizblock als JSON im Begleitprozess (seit 2026-10-14 pro Welt: `companion/memory/<welt>_<seed>/notes.json`, Kennung aus `look`), nicht im Mod – die
  Notizen gehören der KI, nicht der Welt
- 2026-10-14 – Ein gemeldeter Gegner unterbricht 45 s lang keine Aktion mehr (Mob-ID gemerkt) – eingebaut
  zwischen Mobs brach sonst jede Aktion sofort wieder ab
- 2026-10-14 – `night_falling` trägt `underground`; der Prompt sagt, dass die Nacht in Höhlen nichts ändert –
  der Bot baute sich in Höhlen nachts ein
- 2026-10-14 – Nach einem Tod schickt der Begleitprozess das Modell bis zu 3-mal zurück ans Ziel; der Prompt
  nennt kein Kostenrisiko mehr – das Modell gab nach dem Tod auf und vermutete ein Kostenlimit
- 2026-10-14 – `attack` meldet bei `entity_not_found` das nächste passende Wesen bis 128 Blöcke (`nearest`),
  der Begleitprozess sammelt erfolglose Suchorte (`already_searched`) – die KI suchte im Kreis um die Basis
- 2026-10-14 – Notizen pro Welt (`memory/<welt>_<seed>/`), `look` meldet `world` und `seed` – im ersten
  echten Lauf bekam der Bot die Basis aus einer anderen Welt genannt
- 2026-10-14 – Herzschlag im Begleitprozess: alle 15 Schritte Erinnerung ans Ziel, Anstoß nach 4 Schritten
  Leerlauf, einmalige Nachfrage vor dem Ende eines Ziels – Wunsch von Hannes; der Bot wartete im ersten Lauf
  20 Minuten mit Hunger 17 auf Heilung
- 2026-10-14 – `collect` baut bei Erz die ganze Ader ab und gibt nach 8 Unerreichbaren nacheinander auf –
  Wunsch von Hannes; ein `collect` hing 15 Minuten an 39 unerreichbaren Erzen
- 2026-10-14 – `shelter` setzt am Hang Stützblöcke und gräbt sich ein, wenn die Hütte scheitert – Wunsch von
  Hannes; die Hütte scheiterte an jeder Kante mit `no_support`
- 2026-10-14 – Rezepte: Umfärbe-Rezepte zuletzt, „fehlt am wenigsten“ vor „ohne Werkbank“; `craft` läuft
  höchstens 24 Blöcke zu einer alten Werkbank, wenn er eine neue craften kann – `craft white_bed` verlangte
  Farbstoff, `craft` lief 60 Blöcke zurück
- 2026-10-14 – Kampf gibt auf, wenn das Ziel 10 s lang nah, aber nicht treffbar ist – `defend` und `attack`
  liefen gegen eine Spinne hinter einer Wand je 90 bis 180 s bis zum Zeitlimit
- 2026-10-14 – Neuer Befehl `descend` (senkrecht bis `to_y`, hält vor Lava, Wasser und Hohlraum an) – im
  zweiten Lauf kostete jeder Block Schacht einen Modellschritt, zusammen rund 150 Schritte
- 2026-10-14 – `shelter` lehnt im Wasser ab (`in_water`); `goto` schwimmt ohne Startfeld erst zum nächsten
  Ufer (8 Blöcke) – der Bot ertrank in einer Hütte im Wasser und kam danach 8 Minuten nicht aus einem Teich
- 2026-10-14 – Prompt: Der Bot ist auf sich gestellt (keine Bitte um Hilfe), Kosten sind kein Grund aufzuhören –
  er wartete auf Hilfe vom Menschen und wollte wegen „teurer“ Schritte aufgeben
- 2026-10-14 – Servertests optional mit höherer Tickrate (`SMARTCRAFT_TEST_TICK_RATE`, `/tick rate`) – `test_survival`
  in 3 statt 6 Minuten, gleiche Ergebnisse; für KI-Läufe nicht gedacht (dort bremst die API, nicht das Spiel)
- 2026-10-14 – `descend` hält an, wenn bis 3 Blöcke über dem Kopf loser Kies oder Sand ohne festen Block dazwischen
  hängt (`falling_block_above`) – Wunsch von Hannes; schwebender Kies aus der Weltgenerierung kann nachrutschen
- 2026-10-14 – Sprachausgabe im Begleitprozess (`companion/speech.py`, Windows-SAPI über PowerShell, keine
  Zusatzbibliothek): Jede `chat`-Nachricht des Bots wird vorgelesen, der Prompt verlangt humorvolle Ansagen bei
  Funden wie Diamanten – Wunsch von Hannes; der Mod bleibt ohne Audio, nur der Host hört die Stimme
- 2026-10-14 – Stimme über Piper (`piper-tts`, lokal, Modell `de_DE-thorsten-high` in `companion/voices/`), Windows-
  Sprachausgabe nur noch als Rückfall – Wunsch von Hannes nach einer realistischeren Stimme ohne Cloud-Dienst
- 2026-10-14 – Plauder-Anstoß im Begleitprozess (`[Publikum]` nach 2 stillen Schritten und bei Funden wie Diamanten)
  plus Beispielsprüche im Prompt – Wunsch von Hannes; der Prompt allein reichte nicht, Haiku redete selten und sachlich
- 2026-10-14 – Neuer Befehl `bucket` (füllen/ausgießen, `cancelOnDanger` aus, Lava nie über die Füße, auf gleicher Höhe 4 Blöcke Abstand) – im KI-Lauf
  scheiterte der Lavaeimer für das Netherportal daran, dass kein Befehl einen Eimer benutzen konnte; ohne Servertest
  auf Wunsch von Hannes
- 2026-10-14 – Allgemeiner Befehl `use` (Rechtsklick auf Block, Wesen oder in die Luft) statt je eines Befehls pro Item;
  `store`/`take` mit `pos` für jeden Behälter – Wunsch von Hannes („alles benutzen, was ein Spieler benutzen kann“),
  der Bot konnte das Netherportal nicht anzünden; ohne Servertest
- 2026-10-14 – `attack` greift friedliche Zombie-Piglins nicht an (`would_anger_group`), `attack`/`defend` brechen im
  Wasser gegen Schützen ab (`in_water`); Nether-Abschnitt im Prompt (Vorbereitung, Regeln, Mobs) – Fehlerliste von Hannes
- 2026-10-14 – Eimer gießt direkt an der geprüften Stelle aus (`BucketItem.emptyContents`) statt über den Blickstrahl –
  im Test landete Lava sonst an einer Kante davor; Wasser auf Lava geht über oder neben die Quelle, nicht hinein
- 2026-10-14 – Neuer Befehl `tunnel`, Event `items_received` (Mixin auf `ItemEntity.playerTouch`), Sprinten in der
  Wegfindung auf geraden Stücken, `/smartcraft tts on|off`, kein Feuer direkt neben dem Bot, Brennbares nicht neben Lava/Feuer
- 2026-10-14 – `look` meldet im Nether `fortress_in_sight` (Festung bis 100 Blöcke, über die Strukturdaten des Spiels) –
  der Bot fand keine Festung; ein Spieler sieht sie über die offenen Flächen, der Block-Umkreis von 8 reicht dafür nicht
- 2026-10-14 – Systemprompt neu gegliedert (Rangfolge, Fahrplan bis zum Drachen, ohne Dopplungen, 12,7 statt 18,4 Tsd.
  Zeichen); Tokens sparen im Begleitprozess: Debug-Felder (`ticks`, `search_*`, `replans`, `distance_left`) und der
  eigene Chattext gehen nicht ans Modell, Tool-Ergebnisse älter als 8 Schritte werden alle 10 Schritte auf Status
  gekürzt (`old: true`), gemeinsame Fehlergründe nicht je Tool – im zweiten Lauf lag die Eingabe bei Schritt 76 bei 70 Tsd. Tokens
- 2026-10-14 – Wasser: Befehl `seal_water` (zubauen, danach seitlich heraustreten) und Reflex `WaterGuard` (Wasser,
  das in eine frisch abgebaute Lücke läuft, sofort zubauen; nicht, wenn der Bot selbst im Wasser steht) – der Bot steckte
  15 Schritte in einer Wassertasche und versuchte es mit dem Eimer
- 2026-10-14 – Ess-Reflex `top_up`: Fehlt Leben und ist der Hunger unter 20, isst er nebenbei (nur Unbedenkliches, kein
  Gegner bis 10 Blöcke); Giftiges nur im Notfall – Wunsch von Hannes
- 2026-10-14 – Im Nether bricht Lava/Feuer eine Aktion nur bis 2 Blöcke Abstand ab; Prompt: Hauptrichtung halten,
  seitlich ausweichen, Lohen am Spawner – im End-Lauf brachen im Nether fast alle Aktionen mit `danger` ab, und
  die Festungssuche lief im Zickzack
- 2026-10-14 – `fluid_spotted` nennt `size`; bei Lava die größte Fläche im Umkreis statt der nächsten, ungeladene Chunks
  überspringt der Scan; Prompt: Lava ab `size` 10 für Obsidian, mit 10 Obsidian sofort das Portal bauen – der Bot lief zu
  einzelnen Lavablöcken (1 Obsidian), und der Scan erzwang beim Dimensionswechsel Chunk-Laden (81 ms in einem Tick)
- 2026-10-14 – Sprachausgabe standardmäßig aus (`SMARTCRAFT_TTS=1` oder `/smartcraft tts on`); `fluid_spotted` meldet auch
  Spawner und Lohen, im Nether im Umkreis 32; `search_for` kann `blaze` und `spawner` – im End-Lauf griff der Bot in
  der Festung zehnmal blind `attack blaze` an (`entity_not_found`)
- 2026-10-14 – Chat des Bots vorübergehend aus (`SMARTCRAFT_CHAT=1` schaltet ihn ein): kein `chat`-Tool, keine
  `[Publikum]`-Anstöße – Wunsch von Hannes, Tokens sparen; im End-Lauf war etwa jeder dritte Schritt ein Chat
- 2026-10-14 – Chat-Modus `plans` als Standard (nur kurze Ansagen, was der Bot vorhat), `full` und `off` über
  `SMARTCRAFT_CHAT`; `tunnel` bis 200 Blöcke, im Nether mindestens 200 Blöcke je Richtung – Wunsch von Hannes (Tokens)
- 2026-10-14 – Der Test-Harness sichert `server.properties` in `server.properties.vor-test` und holt sie beim nächsten
  Start zurück – ein abgebrochener End-Lauf ließ die Datei auf der End-Welt, und die Servertests liefen dort
- 2026-10-14 – Weg ins End: Holztüren/Zauntore öffnen in der Wegfindung, `shoot` (Bogen mit Vorhalt, auch Endkristall
  und Drache), `throw_eye` (Flugrichtung, Schnittpunkt zweier Würfe als `estimated_stronghold`), `fill_portal`,
  `enter_portal`, Rundum-Blick meldet Endportalrahmen, Prompt-Abschnitt „End“ – Projektanalyse: ab der
  Netherfestung fehlten alle Bausteine bis zum Drachen
- 2026-10-15 – Nach einem Gespräch nimmt der Begleitprozess das letzte Ziel wieder auf (bis `/smartcraft stop`); im Nether
  bricht Gefahr nur noch direkt daneben ab; Prompt: Ersatz-Spitzhacke und Steinspitzhacke für Tunnel, Bogen craften,
  `~` heißt eigene Höhe, lange Nether-Wege mit `allow_dig` oder `tunnel` – Auswertung von Hannes' Lauf am 15.10.:
  21 Gefahrenabbrüche, 14-mal `no_path`, Diamantspitzhacke in 14 Minuten Tunnel verbraucht, nach Hilfe im Chat
  fragte der Bot „Was soll ich als Nächstes tun?“
- 2026-10-15 – Der Rundum-Blick prüft im Nether alle 20 Sekunden auf eine Festung bis 100 Blöcke (`fluid_spotted`
  mit `kind: fortress`, `search_for` hält dafür an) – Wunsch von Hannes: beim Suchen und Wegebauen regelmäßig prüfen
- 2026-10-15 – `fortress_in_sight` misst bis zum nächsten Teil der Festung statt zum Startpunkt; `shoot` senkt einen
  gehobenen Schild und zählt nur Schüsse mit verbrauchtem Pfeil (`cannot_draw`) – im KI-Lauf stand der Bot vor einem
  Festungsgang ohne Meldung und „schoss“ zehnmal mit Schild oben, ohne dass ein Pfeil flog
- 2026-10-15 – Schild-Reflex hebt den Schild nur bei Gefahr (Schütze bis 16, sonst Feind bis 6 Blöcke) – Wunsch von
  Hannes: mit dauerhaft gehobenem Schild lief der Bot bei den Lohen im Schleichtempo
- 2026-10-15 – Ess-Reflex isst schon, wenn kein Gegner bis 4 Blöcke ist (vorher 10); `wait` wird bei Gegnern in der
  Nähe einmal abgelehnt (`enemies_near`); Prompt: nach jedem Kampf Beute aufheben und zurück zum Ziel – bei den Lohen
  stand der Bot nach Treffern still und heilte nicht, nach dem ersten Witherskelett wusste er nicht weiter
- 2026-10-15 – Befehl `locate` (Festung, Bastion, Festung der Oberwelt, Endsiedlung bis 1.600 Blöcke über die Weltdaten),
  nur nach `/smartcraft locate on`, Standard aus – Wunsch von Hannes: die Festungssuche im Nether kostete die meiste Zeit;
  schummeln soll der Bot nur, wenn der Mensch es erlaubt
- 2026-10-15 – Steht der Bot auf Magma, plant die Wegfindung über Magma als Boden, und `hot_floor` bricht keine Aktion
  mehr ab (wie Ertrinken) – im KI-Lauf stand er 28 s auf einem Magmablock: `flee`/`attack` scheiterten mit
  `no_path: no_start`, er verbrannte zwischen Magmawürfeln
- 2026-10-15 – Festungsblöcke (Netherziegel und alles daraus, nur im Nether) gräbt weder `tunnel` noch `goto` mit
  `allow_dig` ab; `tunnel` endet am Festungsrand (`fortress_reached`), an ihrer Wand (`fortress_wall`) oder beim
  Verlassen (`left_fortress`), `look` meldet `in_fortress` – im KI-Lauf grub er sich mit `tunnel` in die Festung, quer
  hindurch und wieder hinaus (rund 110 Netherziegel); `mine` auf einen einzelnen Block geht weiterhin
- 2026-10-15 – Befehl `farm_spawner`: zum Spawner, alles töten (Lohen in Reichweite mit Schwert, hoch schwebende mit Bogen), Feuer am Boden löschen, Beute aufheben, am Spawner bleiben bis `count` (Standard
  7 Lohenruten) – Wunsch von Hannes; am Spawner kostete jede Lohe einen Modellschritt, und der Bot lief Lohen hinterher
- 2026-10-15 – Reflex `LavaEscape` (in Lava zum nächsten sicheren Feld, Event `reflex_left_lava`); Brennen und Lava
  brechen keine Aktion mehr ab – Drachenlauf 1: der Bot verbrannte, weil jeder Brandtreffer `goto` abbrach und die
  Wegfindung in Lava keinen Start fand
- 2026-10-15 – Reflex: Trank der Feuerresistenz in Lava sofort, brennend ab 8 Leben (`reflex_ate` mit `fire_resistance`);
  Drachenläufe bekommen 6 solche Tränke – Drachenlauf 2: ein Ghast warf den Bot von einer Brücke in einen Lavasee ohne Ufer
- 2026-10-15 – `locate blaze_spawner` (Spawnerraum der nächsten Festung aus den Strukturdaten), `attack`/`defend` brechen
  nicht mehr wegen Lava ab – Drachenlauf 3: 13 Minuten Lohensuche in der Festung, Kämpfe gegen Magmawürfel brachen ständig ab
- 2026-10-15 – Befehl `build_portal` (freien Platz suchen, 10 Obsidian, Ecken aus Bausteinen, anzünden); `farm_spawner` sucht den
  Spawner bis 24 Blöcke – Drachenlauf 5: 7 Lohenruten in 161 s, danach verbaute er 6 Obsidian Block für Block in eine Festungswand
- 2026-10-15 – `build_portal` gräbt den Platz frei, `farm_spawner` wirft bei vollem Inventar Netherrack weg (sonst
  `inventory_full`) – Drachenlauf 6: 53 Lohen getötet, 0 Ruten aufgehoben; `build_portal` fand im Netherrack keinen Platz
- 2026-10-15 – `farm_spawner` mit Leine (16 Blöcke um den Spawner) und `min_health` 12 – Drachenlauf 7: Er jagte einen
  Gegner 40 Blöcke weit in eine Gruppe Witherskelette und starb
- 2026-10-15 – `farm_spawner` und `defend` wechseln auf den, der den Bot gerade trifft (bis 6 Blöcke); `eat` bricht nicht mehr
  wegen Lava ab – Drachenlauf 8: Ein Witherskelett traf ihn viermal von hinten, er schlug nie zurück und starb
- 2026-10-15 – `farm_spawner` darf zum Spawner Festungsblöcke durchbrechen, schießt Lohen auch unter sich; Piglins gelten nur
  als feindlich, wenn sie den Bot angreifen – Drachenlauf 10: Spawnerraum hinter Festungsmauern unerreichbar, zwei Piglins erschlagen
- 2026-10-15 – `enter_portal` geht auch zu Fuß weiter, wenn die Wegfindung „angekommen“ meldet – im KI-Lauf stand der Bot
  38 s neben dem frisch gebauten Portal
- 2026-10-15 – `shoot` wechselt nach 3 Fehlschüssen in Folge bei einem Ziel bis 14 Blöcke zum Schwert – Wunsch von Hannes
- 2026-10-15 – `flee` bricht nicht mehr wegen Gefahr ab, `shelter` löscht zuerst Feuer im Innenraum – im KI-Lauf brach
  die Flucht vor Lohen wegen Feuer unter den Füßen ab, danach baute er die Hütte samt Feuer um sich und verbrannte
- 2026-10-16 – Lava-Reflex schiebt den Bot außerhalb der Lava nur noch zu Feldern mit Boden bis 3 Blöcke darunter, die
  Richtung zum fernen Ufer gilt nur in der Lava – Nether-Lauf 29: Ein Lohenfeuerball warf ihn am Spawner in Lava,
  danach schob ihn der Reflex im Sprung über eine 24 Blöcke tiefe Kante (Sturztod mit 14 Leben)
- 2026-10-16 – Begleitprozess: `mine` und `place` werden nicht mehr angehalten; nach 2 Befehlen in Folge, die ein naher
  Gegner anhielt, läuft der nächste durch (nur `health_low` hält ihn noch an); Prompt: am Spawner keine Hütte, nur
  `farm_spawner` – Nether-Lauf 33: Er mauerte sich am Spawner ein, ständig neue Lohen brachen jeden Befehl ab
- 2026-10-16 – Reflex: Steckt der Bot in einem Block, baut er ihn ab (Event `reflex_dug_out`); Prompt: Enderperlen nur
  zur Flucht, bei `no_blocks` Netherrack abbauen – Nether-Lauf 34: Ohne Bausteine warf er Perlen 4 Blöcke weit zum
  Spawner hinauf, eine setzte ihn in Basalt ab, er erstickte
- 2026-10-16 – `build_portal` lehnt bis 20 Blöcke um einen Spawner ab (`near_spawner`), Schaden bricht `enter_portal` nicht
  mehr ab – Nether-Lauf 38: Portal direkt am Lohenspawner, 13 Versuche hineinzugehen scheiterten an Treffern, dann tot (21 Ruten)
- 2026-10-16 – Neuer Testlauf `tests/server/run_pearls.py` (Start Oberwelt, 64 Gold, Ziel 12 Enderperlen, Weg wählt die KI) –
  nächster Baustein zum End nach den Lohenruten, Wunsch von Hannes
- 2026-10-16 – Prompt: Tauschen mit Piglins im Freien, nie in Bastionen; gegen Brutes `defend` statt `flee`; fehlt Gold,
  Nethergolderz abbauen – Perlen-Lauf 1: 6 Perlen in 4 Minuten bei Piglins am Start, dann ging er in eine Bastion, ein
  Brute erschlug ihn auf der Flucht
- 2026-10-16 – Neuer Befehl `barter` (Gold an Piglins bis 16 Blöcke, jeder Barren per `ItemEntity.setTarget` nur für
  seinen Piglin reserviert, Ware einsammeln) – Perlen-Lauf 2: Mit `drop` fiel das Gold vor die Füße, der Bot hob es
  nach 2 s selbst wieder auf; 15 Würfe, 1 Perle, das Modell hielt die Piglins für tauschunwillig
- 2026-10-16 – Prompt: Mit Gold zuerst in den Nether und `barter`, Endermen nur nachts an der Oberfläche oder im Warped-Wald –
  Perlen-Läufe 2 und 3: Der Bot suchte am Tag und in Höhlen Endermen, je 10 bis 20 Minuten ohne Perle
- 2026-10-16 – `build_portal` räumt Gras, Blumen und Schnee im Rahmen weg (vorher galt jede Wiese als `no_space`); Geländer auf
  Brücken auch bei einem Ghast bis 64 Blöcke – Perlen-Lauf 4: 9-mal `no_space` in der Oberwelt, danach baute er das Portal von
  Hand; im Nether stieß ihn ein Ghast-Feuerball von einer Brücke auf Höhe 98
- 2026-10-16 – `locate` kennt Nether-Biome (`crimson_forest`, `nether_wastes`, `warped_forest`, bis 800 Blöcke) – Perlen-Lauf 5:
  15 Minuten durch Basalt und Lava ohne einen Piglin, er wusste nicht, wo sie leben
- 2026-10-16 – `attack` auf einen Enderman außer Schlagweite: erst ansehen (lockt ihn an), dann warten, bis er kommt; `bucket` lehnt
  Wasser im Nether ab (`evaporates`); `goto_entity` nimmt auch Wesen-IDs – Perlen-Lauf 6: im Warped-Wald jeder Angriff `no_path`,
  danach goss er Wasser im Nether und bekam `ok`
- 2026-10-16 – `barter` wird vom Begleitprozess nicht mehr angehalten; `no_piglin` nennt `babies_nearby`, `entity_nearby` markiert
  Jungtiere (`baby`) – Perlen-Lauf 7: Jeder Tausch brach nach dem ersten Barren an `entity_nearby` (Piglins) ab; vorher 10-mal
  `no_piglin` neben einem Baby-Piglin, das die KI für tauschbereit hielt
- 2026-10-16 – Neuer Befehl `farm_endermen` (Endermen bis 48 Blöcke nacheinander anlocken und töten, Perlen aufheben), im Prompt
  Hauptweg zu den Perlen (Warped-Wald); `attack` kämpft trotz `min_health` weiter, wenn ein Schlag reicht – Wunsch von Hannes
  („am besten Endermen farmen“); Perlen-Lauf 8: 44 Barren getauscht, 0 Perlen (Quote etwa 1 zu 46), dann starb er beim Essen
  neben einem Witherskelett mit 0,5 Leben, weil `attack` bei 4,8 Leben abbrach
- 2026-10-16 – `farm_endermen` zieht umher (Endermen bis 128 Blöcke ansteuern, sonst Etappen von 40 Blöcken, Aufgabe nach 3 min
  ohne Enderman); Prompt: nachts nicht verstecken – Perlen-Lauf 9: nachts zweimal `no_enderman` bis 48 Blöcke, dann mauerte er sich
  bis zum Morgen ein
- 2026-10-16 – Kein Schwungschlag und kein `attack` auch neben normalen Piglins (vorher nur Zombie-Piglins) – Perlen-Lauf 10:
  Im Kampf gegen einen Hoglin mitten unter Piglins wurde die Gruppe wütend und erschlug ihn trotz Goldstiefeln nach 2 Minuten
- 2026-10-16 – `barter` gräbt und baut auf dem Weg zu Piglins, bricht bei einem Brute bis 24 Blöcke ab (`brute_nearby`); Prompt:
  gegen Brutes `shelter` statt Kampf – Perlen-Lauf 11: viermal `piglins_unreachable`, dann `attack` auf einen Brute (13 pro Schlag), tot
- 2026-10-16 – Chat-Modus `plans` (Standard): Planansagen mit Witz statt sachlich, gleich selten – Wunsch von Hannes
  („TTS-Ausgabe humorvoll“), ohne die vielen Chat-Schritte von `full`
- 2026-10-16 – `farm_endermen` schlägt zuerst zurück, wenn ihn etwas anderes trifft (wie `farm_spawner`) – Perlen-Lauf 12:
  4 Perlen im Warped-Wald, dann traf ihn ein Witherskelett zehnmal von hinten, er wehrte sich nicht und starb
- 2026-10-16 – Prompt: Lava für Obsidian unten beim Minen suchen (`search_for lava` immer wieder setzen), an der Oberfläche nur im
  Notfall; Wassereimer vor dem Abstieg füllen – Wunsch von Hannes
- 2026-10-16 – Neuer Befehl `make_obsidian` (liegendes Obsidian abbauen, sonst Lavaquellen mit dem Wassereimer umwandeln, Wasser
  zurückholen, bis `count`) – Hannes' Lauf: vor einem Lavasee mit 64 Quellen neunmal geratene `bucket`-Koordinaten, dann Aufgabe
- 2026-10-16 – `enter_portal` in den Nether nur mit Diamantrüstung und einem Goldteil (`not_prepared`, `force` für Notfälle), zieht
  beides selbst an; `equip_best_armor` behält im Nether das Goldteil – Wunsch von Hannes; sein Bot ging mit 2 Äpfeln und
  ungetragener Eisenrüstung hinein, ein Piglin erschlug ihn brennend
- 2026-10-16 – `farm_endermen` sammelt Perlen auch um die Todesstelle des Endermans ein, übersprungene Endermen sind 60 s gesperrt (auch
  beim Umherziehen) – Perlen-Lauf 13: 7 Endermen, 1 Perle; danach band ihn ein unerreichbarer Enderman 6 Blöcke tiefer 8 Minuten
- 2026-10-16 – `make_obsidian` räumt bei zugedeckter Lava den Block über einer Quelle weg (Abbau mit Lava direkt darunter ist dafür
  erlaubt, sonst nie), holt das Wasser nach jedem Guss zurück und wählt nur Quellen mit freiem Feld direkt daneben – in Hannes'
  Spiel dreimal `lava_unreachable`; im Servertest ging bei zugedeckter Lava der Wassereimer verloren
- 2026-10-16 – `test_barter` hält Nacht und klares Wetter fest – bei Regen oder Sonne teleportierten die Endermen sofort weg, vier
  Prüfungen scheiterten ohne Fehler im Mod
- 2026-10-16 – `tool_low_durability` spätestens bei 50 übrigen Schlägen (vorher nur 10 %), der Begleitprozess hält dafür die
  laufende Aktion an; Prompt: Essen von Anfang an (jedes Tier unterwegs mitnehmen, früh garen, 10 Stück vor jedem Abstieg) –
  Wunsch von Hannes
- 2026-10-16 – `farm_endermen`: Kampf gräbt sich zum Enderman durch (bauen nur mit Bausteinen im Inventar), Anlocken nur bei freier
  Sicht, Ergebnis nennt `fights` (Ausgänge je Kampf) – Perlen-Lauf 14: 6 Minuten im Warped-Wald ohne Kill; Nachstellung: 10 von 13
  Kämpfen `no_blocks`, danach 3 Perlen in einer Minute
- 2026-10-16 – Mehrere Testserver gleichzeitig: `-PaiRunDir` in `build.gradle`, Harness mit `SMARTCRAFT_TEST_RUNDIR`,
  `SMARTCRAFT_TEST_PORT`, `SMARTCRAFT_TEST_GAMEPORT` (Instanz k: `run/inst<k>`, Ports 25600+k und 25570+k) – Wunsch von Hannes,
  drei Perlen-Läufe parallel
- 2026-10-16 – Neuer Testlauf `tests/server/run_stronghold.py` (Start Oberwelt mit 12 Perlen und 8 Lohenruten, Ziel: ins End;
  `--ohne-locate` zwingt `throw_eye`) – nächster Abschnitt nach den Perlen, Plan mit Hannes
- 2026-10-16 – `goto`-Toleranz bis 64 (vorher 16); Prompt: mit `locate` kein Enderauge werfen, weite Wege in Etappen –
  Festungsläufe 1–6: 1 Erfolg; die KI gab fünfmal `tolerance` 24 bis 40 an (`invalid_args`), Läufe 1 und 5 warfen mit
  bekannter Position Augen und hatten am Portal 1 bzw. 2 zu wenig
- 2026-10-17 – `locate end_portal` (Portalraum aus den Strukturdaten, mit Höhe); `throw_eye` lehnt bei freigeschaltetem
  `locate` ab (`use_locate`, `force` wirft trotzdem) – Festungsläufe 7–12: 2 Erfolge; Lauf 12 suchte 20 Minuten über der
  Festung nach dem Portalraum und warf dabei 3 Augen, trotz Prompt-Regel
- 2026-10-17 – API-Aufrufe im Begleitprozess mit 90 s Timeout und 4 Wiederholungen (vorher SDK-Standard 10 Minuten) – Perlen-
  Lauf 21 und Festungslauf 16 hingen nach einem Ergebnis ohne weiteren Modellschritt, bis der Wächter sie stoppte
- 2026-10-17 – Neuer Testlauf `tests/server/run_full.py` (neue normale Welt, leeres Inventar, Ziel: Enderdrache; Tode beenden
  den Lauf nicht, Erfolg über den Fortschritt „Free the End“, Ergebnisse in `run/logs/full_results.log`) – Wunsch von Hannes,
  parallel als Instanz 4 zu den Festungsläufen
- 2026-10-17 – `enter_portal` läuft auf die Mitte der ganzen Portalfläche zu und springt auf den letzten Metern (vorher auf die
  nächste Portalkachel) – Festungsläufe 15 und 18: offenes Portal, aber `not_teleported` aus der Ecke zwischen zwei Rahmen
- 2026-10-17 – Schwimmen in der Wegfindung teurer (Kosten 4 statt 2, getaucht 5 statt 3); Prompt: nachts nie ins Meer – Festungsläufe
  9, 13, 14: dreimal nachts im Ozean von Ertrunkenen erstochen, im Wasser scheiterten `flee` und `attack`; `test_movement` 22 von 22
- 2026-10-17 – `enter_portal` setzt den letzten Schritt nach 3 s selbst, wenn der Bot bis 3 Blöcke von der Portalmitte steht –
  Festungslauf 19: trotz Anlauf auf die Mitte und Sprung wieder `not_teleported` aus der Ecke (im Servertest ging es)
- 2026-10-17 – Prompt: Festung unter dem Meer am Ufer anfahren, absteigen und unterirdisch hin – Festungslauf 20: am Tag über den Ozean
  zur Festung geschwommen, ein Ertrunkener mit Dreizack tötete ihn (8 Schaden je Treffer, im Wasser traf der Bogen nicht)
- 2026-10-17 – `run_full.py` setzt das Ziel neu, wenn das Modell es vorzeitig beendet (bis 5-mal) – Gesamtlauf 1: nach 62 Minuten
  (Eisenrüstung, 4 Diamanten, kein Tod) gab Haiku in einer Lavazone auf, statt `make_obsidian` zu nutzen
- 2026-10-17 – Laufskripte starten den Begleitprozess ungepuffert (`PYTHONUNBUFFERED`) – das Agent-Log kam schubweise, der Wächter
  hielt Festungslauf 22 (nachts in der Hütte, Schritte liefen) für festgefahren und stoppte ihn
- 2026-10-17 – `make_obsidian` nennt bei `no_lava` die nächste Lavaquelle bis 64 Blöcke (`nearest_lava`) – Gesamtläufe 1 und 2: nach
  `no_lava` suchte der Bot 20 Minuten und länger an der Oberfläche (Lauf 1 gab danach auf)
- 2026-10-17 – Wasser-Reflex schweigt 5 s nach eigenem Ausgießen (`bucket`) – `make_obsidian` bei zugedeckter Lava: der Reflex
  mauerte das ausgegossene Wasser zu, der Eimer kam nicht zurück (`no_water_bucket`); `test_obsidian` 7 von 7
- 2026-10-17 – Harness: Instanz-Ordner übernehmen `server.properties.vor-test` aus `run/`, wenn vorhanden – sonst lief ein Servertest
  auf Instanz 5 in der Welt des gerade laufenden Festungslaufs statt in der flachen Testwelt
- 2026-10-17 – `use` mit `pos` weiter als 24 Blöcke lehnt ab (`invalid_args` mit eigener Position) – Festungslauf 34: `use` mit
  `[-4,-1,4]` (relativ gemeint) lief 180 Blöcke zum Weltursprung ins Meer, der Bot ertrank
- 2026-10-17 – `seal_water` bricht mit `low_air` ab, wenn der Kopf unter Wasser ist und die Luft unter ein Drittel fällt –
  Festungslauf 36: grub sich vom Meereis senkrecht zur Festung hinab und verbaute unter Wasser 131 Blöcke, bis er ertrank
- 2026-10-17 – `make_obsidian` wird vom Begleitprozess nicht mehr angehalten – Gesamtlauf 3: `fluid_spotted` (Lava) brach den Befehl ab,
  der gerade die Lava umwandeln sollte
- 2026-10-17 – Begleitprozess schreibt den Start jedes Befehls und Modellaufrufe über 20 s (oder mit Fehler) ins Log – Gesamtläufe 2
  und 3 stoppte der Wächter nach 5 Minuten ohne Logzeile; unklar war, ob ein langer Befehl lief oder die API hing
- 2026-10-17 – `run_full.py` hält standardmäßig dauerhaft Mittag (`advance_time false`, `--mit-nacht` schaltet ab) – Wunsch von Hannes;
  in Gesamtlauf 3 ging grob ein Viertel der Zeit mit Warten in Nachthütten verloren. Endermen gibt es so nur im Nether (Warped-Wald)
- 2026-10-17 – Prompt: bis zur Eisenrüstung Eisen nur im eigenen Schacht, nicht in offenen Höhlen; gegen mehrere Skelette Deckung statt
  Stellungskampf – Gesamtlauf 5: ohne Rüstung in einer Höhle, zwei Skelette brachten ihn in 7 s von 20 auf 5,6 Leben, Flucht zu spät
- 2026-10-17 – Prompt: Bei Ankunft im Nether erst `look`; Lohen/Ghasts/mehrere Gegner am Portal oder brennend: sofort zurück, Portal
  abbauen und mindestens 200 Blöcke weiter neu bauen – Wunsch von Hannes; Gesamtlauf 6 kam neben einem Lohenspawner heraus, räumte
  brennend das Inventar auf, kämpfte gegen sechs Lohen und verbrannte
- 2026-10-17 – Begleitprozess behält höchstens 60 Schritte im Verlauf; ältere fallen weg, die Eröffnung bekommt Ziel und aktuelle Notizen
  neu – Gesamtläufe kosteten pro Schritt 40 % mehr als kurze Läufe, weil jeder Aufruf alle bisherigen Befehle mittrug (Wunsch von Hannes)
- 2026-10-17 – Reflex `FireballGuard` (Ghast-Feuerbälle in Schlagweite zurückschlagen, Event `reflex_deflected_fireball`) und
  Kanten-Kosten in der Wegfindung bei einem Ghast bis 64 Blöcke; Kit-Läufe (`run_kit.py`) ohne Feuerresistenz-Tränke, Harness ohne
  Spawnschutz, `collect` höchstens 4,5 Minuten (Prompt-Cache) – Kit-Lauf 2: ein Feuerball stieß den Bot von Höhe 57 in einen Lavasee
  ohne Ufer; Kit-Lauf 1 konnte am Spawn nichts abbauen, weil `ops.json` einen Op hatte
- 2026-10-17 – Reflex `JunkDropper` (bei vollem Inventar Abraum weg, Bausteine bis 192, Erze bleiben), `drop` sperrt das Item 60 s
  für den Bot, Steinspitzhacke nicht mehr im Nether-Kit – Wunsch von Hannes; Kit-Lauf 5: Inventar nach kurzem Graben voll, die KI
  warf Granit/Diorit/Andesit zweimal weg, weil der Bot sie nach 2 s wieder aufhob
- 2026-10-17 – `collect` bei Holz und Stein erlegt in der Oberwelt nebenbei erwachsene Nutztiere bis 16 Blöcke (Kuh, Schwein, Schaf,
  Huhn, Kaninchen, Pilzkuh) und hebt die Beute auf, Ergebnis `animals_killed`; nicht bei Erz, nicht unter 10 Leben – Wunsch von Hannes
- 2026-10-17 – Ortsgedächtnis merkt sich Baumstellen (`trees`, ein Eintrag je 32 Blöcke, beim Fällen mit `collect`); `look` nennt die
  zwei nächsten (`known_trees`, nur Oberwelt), `collect` bei Holz nennt bei `none_found` bis zu 3 – Wunsch von Hannes
- 2026-10-17 – Keine Schrittgrenze mehr pro Ziel (`SMARTCRAFT_MAX_STEPS` Standard 0 = unbegrenzt, vorher 200) – Wunsch von Hannes: sein
  Lauf endete nach 200 Schritten (0,23 $) mitten im Ziel
- 2026-10-17 – `JunkDropper` wirft auch Ersetztes weg (schwächeres Werkzeug oder Doppel derselben Art, Rüstung schlechter als die
  getragene, Goldteile bleiben), Kies und Feuerstein bleiben; Prompt: Stämme, Kies, Feuerstein nie wegwerfen – Hannes' Lauf: die KI warf
  nach Prompt-Vorgabe 43 Stämme und den Feuerstein weg, das Inventar war voll mit Eisenrüstung unter Diamant und Doppelwerkzeug
- 2026-10-17 – Im Nether hält sich die Wegfindung mit `allow_place` an die Luftlinie vom ersten Start zum Ziel (jeder Block über 4
  seitlich kostet je Schritt 1 extra), und `goto` mit `allow_place` bricht dort nicht mehr wegen Lava ab – Hannes' Lauf: auf dem Weg
  zur Festung zog der Bot von Plattform zu Plattform seitlich weg, 10 `goto` brachen mit Lava 1 Block neben der Brücke ab
- 2026-10-17 – Lava-Reflex baut eine Insel (nicht brennbare Bausteine auf Fußhöhe an festen Halt, nach 1,5 s oder ohne Ufer,
  `reflex_left_lava` mit `blocks_placed`); `farm_endermen` zieht nicht zu Etappen über Lava oder über 12 Blöcke tiefer und lehnt im
  Nether unter 16 nicht brennbaren Bausteinen ab (`few_blocks`); Prompt: Netherrack/Schwarzstein nicht wegwerfen, Bretter brennen,
  an der Festung nicht auf `search_for` warten – Hannes' Lauf: 10 Lohenruten in 11 Minuten, dann im Warped-Wald mit den letzten
  Brettern über einen Abgrund und in einen Lavasee, 10 s ohne Ausweg, tot
- 2026-10-17 – `/smartcraft stats on|off`: Herzen und Hunger unter dem Namen des Bots über die Vanilla-Anzeigetafel (below_name,
  Text per FixedFormat, andere Spieler leer), alle 10 Ticks – Wunsch von Hannes; geht ohne Mod beim Client
- 2026-10-18 – Cache: eigener Haltepunkt hinter Tools und Systemprompt, Kürzen alter Ergebnisse nur alle 30 Schritte, bei über
  60 Schritten Schnitt auf 30; `wait` lehnt bei fehlendem Leben und Hunger unter 18 einmal ab (`no_regen_hunger`); der
  Begleitprozess merkt sich gegartes Essen im Ofen (`food_in_furnace` bei Hunger/`health_low`/`eat no_food`/`look`); direkte
  Chatnachrichten tragen `chat_hint` (Rat umsetzen) – Lauf 18.10.: alle 10 Schritte ein Cache-Fehlschlag (0,0065 $ statt
  0,0007 $), 9 gegarte Rindfleisch nie abgeholt, mit Hunger 16 in der Hütte auf Heilung gewartet, Hinweise im Chat nur beantwortet
- 2026-10-19 – `make_obsidian` lässt das Wasser nach dem Guss liegen (3 s Fließen, Wasser-Reflex still), baut auch Obsidian über
  Lava ab, wenn Wasser daneben oder darüber steht, und holt das Wasser erst danach zurück, dann `pickup_items` – Wunsch von Hannes:
  vorher wurde je Guss nur eine Quelle zu Obsidian, und Obsidian über Lava blieb liegen (das Item wäre verbrannt);
  `bucket fill` zählt Fließwasser/Gras neben einer Quelle als freie Seite (heutiger Lauf: viermal `none_found` neben einer Quelle)
- 2026-10-28 – Neuer Testlauf `tests/server/run_end_fight.py` (Start auf der Endinsel, Diamantausrüstung, Bogen; Erfolg über
  end/kill_dragon) – die Festungsläufe endeten beim Betreten des End, der Drachenkampf war nie geprüft
- 2026-10-28 – `shoot`: Flugbahn simuliert (Luftwiderstand, Schwerkraft), Halten nur bei bewegten Zielen, bei `entity_not_found`
  `not_in_sight` mit `caged`; Reflex `DragonBreathGuard` (aus Säurewolken des Drachen sprinten, `reflex_left_dragon_breath`) –
  End-Läufe 1–4: Kristalle nicht getroffen (`timeout`), zweimal im Atem gestorben, Kristalle hinter Türmen aufgegeben
- 2026-10-28 – Schild-Reflex nur, wenn der Gegner den Bot im Visier und freie Sicht hat, oder ein Geschoss anfliegt – Hannes' Lauf:
  ein Skelett hinter Festungswänden hielt den Schild oben, der Bot lief im Schleichtempo
- 2026-10-28 – `build_portal` (Oberwelt) und `enter_portal` lehnen ab, solange etwas für den Nether fehlt (`NetherKit.missingAll`:
  Rüstung mit Goldteil, Diamantschwert, 2 Spitzhacken, Bogen, 32 Pfeile, Schild, 16 gegartes Essen, 192 Bausteine, Feuerzeug;
  `force` für Notfälle) – Wunsch von Hannes: erst alles farmen, dann das Portal bauen
- 2026-10-28 – Wither und Gift brechen keine Aktion mehr ab (wie Ertrinken), friedliche Zombie-Piglins und nicht verfolgende
  Endermen machen `entity_nearby` nicht dringend (`neutral`) – Hannes' Lauf: nach den Lohenruten stand der Bot, jeder `goto`
  brach am Wither-Effekt oder an Zombie-Piglins ab, dann 40 s `wait`
- 2026-10-28 – `shoot` nennt bei verdeckten Zielen `shoot_from` (Platz am Boden mit freier Sicht); Hochbauen bekommt bei Gefahr
  (Drache, Schütze, Feind bis 16; nur Nether und End, nicht in der Oberwelt – Wunsch von Hannes) ein Geländer rundum auf Fußhöhe; Kämpfe mit Graben (`farm_endermen`) bauen nicht mehr senkrecht
  hoch – End-Läufe 4 und 6 bauten sich für die Sicht 12 bis 41 Blöcke hoch und wurden heruntergestoßen; in Hannes' Lauf stieß ein
  Enderman den Bot von seiner 1×1-Säule
- 2026-10-28 – Verbrauch der KI in der XP-Leiste der Menschen (Level = Cent, Balken = Bruchteil), darüber in der Aktionsleiste
  Tokens und $; Standard an bei jedem Join, `/smartcraft xpbar on|off`. Nur als Pakete an die Clients, echte XP bleiben; die Zahlen
  schickt der Begleitprozess mit dem Befehl `usage` (kein Tool für das Modell) – Wunsch von Hannes
- 2026-10-28 – Kostenrechnung im Begleitprozess genau nach Preisliste: Modell der Antwort (Fallback), Cache-Schreiben 5 min 1,25× /
  1 h 2×, Cache-Lesen je Modell (Opus 5.5 0,05×, Fable 5.1 0,025×, sonst 0,1×), Haiku 5.5 über 100.000 Tokens fünffach – Wunsch
  von Hannes: XP-Leiste soll Tokens und Kosten 1:1 zeigen; vorher überall 0,1× für Cache-Lesen und keine Haiku-Staffel
- 2026-10-28 – Einstellungen zentral im Mod (`AiSettings`), `/smartcraft info|humor|talkmode|dimensionfollow|gamemode|streamer`;
  Begleitprozess holt sie mit `settings` und bekommt Änderungen als `control_requested` (`action: settings`). Humor und Talkmode
  als `[Einstellung]`-Hinweis im Verlauf statt im Systemprompt (Cache bleibt); Talkmode ersetzt `SMARTCRAFT_CHAT`-Stufen (Startwert),
  Abstände in `TALK_SETTINGS`, Spam-Schutz `too_soon`. `events off` blendet nur die Chat-Anzeige aus (die KI braucht die Events).
  Dimension Follow teleportiert den Bot nach einem Dimensionswechsel des Spielers auf einen sicheren Platz neben ihn (nur der
  Wechsel des Spielers löst aus, keine Schleife); `gamemode` gilt für den, der ihn eingibt; `streamer off` setzt nichts zurück –
  Auftrag von Hannes, Entscheidungen per Rückfrage
- 2026-10-28 – `shelter` lehnt nachts in der Oberwelt bei voller Ausrüstung ab (`well_equipped`: Rüstungswert ab 15, Schwert aus
  Eisen oder besser), außer im Notfall (Leben bis 10, 3 Gegner bis 16 Blöcke) oder mit `force` – Wunsch von Hannes: ausgerüstet
  soll der Bot nachts weiterarbeiten statt sich einzumauern
- 2026-10-28 – `make_obsidian` legt zugedeckte Lava frei, auch wenn neben der Decke Lava liegt (Block unter den Füßen, keine Lava
  darüber) und gräbt bis 4 Blöcke Fels über der Decke von oben frei; eine gescheiterte Säule wird übersprungen – Hannes' Lauf:
  vor einem zugebauten Lavasee scheiterte `make_obsidian` sechsmal mit `uncover: blocked_unsafe`
- 2026-10-28 – Drachenatem bricht keine Aktion ab (der Reflex führt hinaus); `shoot` schießt nicht auf Endkristalle unter 12 Blöcken
  (`too_close`, seit Lauf 12: 12 Blöcke, die Explosion schadet bis 12) und zielt nicht durch einen Enderman (`enderman_in_line`) – End-Läufe 8 und 10
- 2026-10-28 – `look` und `inventory` melden in der Oberwelt `nether_missing` (gleiche Prüfung wie `build_portal`, plus 10 Obsidian)
  oder `nether_ready`; Prompt: nur holen, was dort steht. Spam-Schutz: 60 s nach einer Nachricht an den Bot gilt jeder Chat als
  Antwort – Hannes' Lauf: der Bot schmolz 16 Eisen trotz Diamantausrüstung, und seine Antwort auf eine Frage blieb an `too_soon` hängen
- 2026-10-28 – Neuer Befehl `destroy_crystals` (alle Endkristalle: freie vom Standplatz am Boden, Käfige und Reste von einem eigenen
  Turm 13 Blöcke daneben, Gitter in der Schusslinie abbauen, danach zum Boden; aus goto, mine und shoot zusammengesetzt) –
  Wunsch von Hannes; End-Läufe 1–11: Käfige blieben stehen, die KI baute sich frei in die Luft und kam nicht herunter
- 2026-10-28 – Geländer beim Hochbauen nur auf den obersten 6 Stufen vor dem Ziel (ab 24 Bausteinen) – End-Lauf 12: Geländer auf
  jeder Stufe eines 32 Blöcke hohen Turms verbrauchte die Bausteine, oben fehlte es, der Drache stieß ihn herunter
- 2026-10-28 – `make_obsidian` gräbt und baut sich selbst zum nächsten Lavasee durch (bis 64 Blöcke, 3 Anläufe), wenn im Umkreis keine
  Quelle mit freier Seite ist – Wunsch von Hannes: vor großen eingeschlossenen Lavaseen meldete er `lava_unreachable` und suchte woanders
- 2026-10-28 – Stämme sind keine Bausteine mehr; fehlen Bausteine, werden bis 16 Stämme zu Brettern (4 je Stamm, ohne Werkbank);
  `descend` nimmt am Blockrand den Block, auf dem der Bot steht; `destroy_crystals` schlägt zuerst Angreifer zurück und geht zum
  Turm über den Boden darunter – Hannes' Lauf (Eichenstamm-Turm im Nether ohne Bausteine, `descend` „no_block“) und End-Lauf 13
  (Endermen, zweimal `tower_no_path`)
- 2026-10-29 – Wegfindung im End meidet Kanten und die Mitte (Radius 16 um den Brunnen), solange der Drache lebt – End-Lauf 16
- 2026-10-29 – Atem-Reflex gräbt sich unter die Wolke (Block unter den Füßen), wenn er seitlich nicht herauskommt – End-Lauf 17: ummauerter Turm, dreimal 6 Schaden
- 2026-10-29 – Atem-Reflex: freie Fluchtrichtung aus 8, größerer Rand beim Hinauslaufen, Graben nur ringsum eingeschlossen; Geländer auf jeder Stufe bei lebendem Drachen, destroy_crystals baut unter 160 Bausteinen Endstein ab, Turm zur Inselmitte – End-Läufe 18–20
- 2026-10-29 – Atem-Reflex schiebt den Bot (setDeltaMovement wie LavaEscape) und flieht vom Brunnen, wenn der Drache hockt – End-Läufe 20–24: Reflex bis 101 Ticks aktiv, der Fake Player bewegte sich mit Eingabe allein kaum
- 2026-10-29 – Reflex `SelfDefense`: ohne laufende Aktion schlägt der Bot einen Nahkampf-Angreifer zurück (Event `reflex_struck_back`) – End-Lauf 25: zwölf Enderman-Treffer, während die KI noch dachte
- 2026-10-29 – Strategiewechsel: `destroy_crystals` baut keine Türme mehr (End-Läufe 12–27: auf jedem Turm gestorben), nur Kristalle vom Boden; Prompt: Drache beim Hocken auf dem Brunnen angreifen, Rest-Kristalle heilen nur bis 32 Blöcke
- 2026-10-29 – Neuer Befehl `fight_dragon` (wartet am Boden 24 Blöcke vom Brunnen, schlägt beim Hocken auf den Kopf) – End-Lauf 29: 81 Pfeile auf den fliegenden Drachen ohne Schaden, das Hocken verging zwischen zwei Modellschritten
- 2026-10-29 – Atem-Reflex gibt die Wegführung der laufenden Aktion frei (PathFollower.release), lässt Bogen/Essen los, springt und gräbt bei Stillstand – End-Läufe 40–42: die Wegführung schob den Bot gegen den Reflex in die Wolke
- 2026-10-29 – Atem-Reflex flieht mit der Wegfindung (PathFollower) zu einem begehbaren Platz außerhalb aller Drachenwolken, roher Schub nur noch als Rückfall – End-Läufe 40–46: der Schub lief gegen Blöcke, pendelte und wurde von anderen Reflexen verdreht
- 2026-10-29 – Atem-Reflex schaltete sich nach einer Flucht über 100 Ticks ab, solange irgendeine Wolke in der Nähe lag; jetzt sofort wieder bereit – End-Läufe 50–52: tödliche Treffer ohne Reaktion
- 2026-10-29 – Strategiewechsel nach 65 End-Läufen ohne Sieg (Wunsch von Hannes): neuer Befehl `bed_bomb` (Betten unter dem Kopf des hockenden Drachen sprengen, hinter einer Wand), End-Lauf mit 12 Betten; aufgeräumt: `destroy_crystals` ohne Türme (nur vom Boden), Atem-Reflex nur noch Wegfindung aus allen Wolken plus Schub als Rückfall (kein Graben, keine Pauschalflucht vom Brunnen), Messzeilen entfernt; Brunnen-Vorsicht der Wegfindung entfällt, wenn das Ziel am Brunnen liegt – mit Schwert und Bogen kam der Drache nie unter 172 Leben, auf Türmen starb der Bot immer wieder
- 2026-10-29 – `bed_bomb`: Bett auf einer eigenen Säule etwa 2 Blöcke unter dem Kopf (wie Speedrunner), Plätze einmal je Landung festgelegt, nach jedem Krater neu, Zeitgrenze 30 min, schlägt Angreifer zurück – End-Läufe 66–68: am Boden 6 Blöcke unter dem Kopf nur 6 Schaden je Bett, im Servertest jetzt 35 bis 50
- 2026-10-29 – Weg D (Wunsch von Hannes): `destroy_crystals` baut für Kristalle ohne Bodenplatz eine Röhre direkt am Obsidianturm, Dach aus 2 Blöcken, Bett darauf zünden; dazu Schussplatz nie unter 14 Blöcken zur Mitte, Atem-Reflex flieht mit Graben, Deckel beim Klettern, Endstein nach Bedarf, Gegenwehr auf dem Weg zum Turm; Prompt: gegen Endermen `defend` – End-Läufe 84–93, kein Sieg; Übergabe in `docs/END_FIGHT_HANDOFF.md`
- 2026-10-31 – `bed_cycle` als Hauptweg im End: Betten im Nether/End trotz Feuer setzbar (`BlockPlacer`), Bot mittig im Käfig, Käfig ohne Anhebung, Annäherung an den Kopf beim Hocken; `destroy_crystals` mit `ground_only` (keine Türme) vor `bed_cycle`; `bed_bomb` nicht mehr als Tool; Atem-Reflex bricht eingeschlossen die Käfigwand auf; `run_end_fight.py` erkennt Bett-Siege (im End, kein Tod, `dragon_dead`) – End-Läufe 94–105: 5 Siege (94, 96, 97, 102, 104), Tode im Atem (98, 100, 105), `bed_bomb` (99), Sturz (103). Serie am 01.11. gestoppt
- 2026-11-01 – Käfig 10,3 statt 8,3 Blöcke vom Brunnen, Atem-Reflex greift auch nach frischem Atemtreffer knapp außerhalb der Wolke, Prompt: nach `no_beds` nur vom Boden, `run_end_fight.py` löscht eine alte Welt gleichen Namens – Läufe 101 (Welt ohne Drachen), 103, 105. Nur ein Messlauf (`test_bed_cycle_bot combo 36`: Drache 200 → 103, Bot lebt), kein echter Lauf; nächste Session: 5 parallele `run_end_fight.py` (106–110)
- 2026-11-01 – `shoot`/`destroy_crystals` schießen Kristalle auf hohen Türmen im hohen Bogen (`lobClear`, Bahn gegen Blöcke geprüft); `bed_cycle` geht nie in Atemwolken zum Kopf – Läufe 107–109: 4–5 Kristalle `no_ground_spot`, der Drache heilte zwischen den Vorbeiflügen zurück (12 Betten reichten nicht), 108 lief beim Hocken in den Atem. Danach 7 statt 3–4 Kristalle, Läufe 112–116: 5 Siege in Folge
- 2026-11-01 – Drachenläufe aus dem Nether (`run_dragon.py`, `/smartcraft kit nether`, keine Tränke): `end_missing`/`end_ready` in `look`/`inventory`
  (12 Betten, 12 Enderaugen, Bogen, 64 Pfeile, 128 Bausteine, 16 Essen; Wolle, Fäden, Perlen, Lohenstaub zählen mit) – Wunsch von Hannes.
  Fixes aus den Läufen 11–15: `farm_endermen` prüft Angreifer und `min_health` vor dem Perlensammeln (11: beim Sammeln erschlagen),
  `JunkDropper` behält die zweite Spitzhacke, `farm_spawner` bricht nach 90 s ohne Fortschritt ab (`stalled`, 12: 10 min ohne Kampf),
  `goto_entity`/`use` überspringen geschorene Schafe (14: eine Stunde dasselbe Schaf, 25 min `wait`), Bot-Chat als Systemnachricht
  (unsigniert gab beim Client „Chatübertragungsfehler“), Creeper früher melden: `entity_nearby` dringend ab 12 Blöcken, bei 4 noch einmal, auch nach der 45-s-Ruhe; ausweichen entscheidet die KI (15: Creeper erst bei 2 Blöcken gemeldet, tot; Fluchtreflex auf Wunsch von Hannes wieder entfernt)
- 2026-11-01 – Die Wegführung (`PathFollower`, also goto, collect, attack …) verbaut die letzten 32 Bausteine nie (`no_blocks` mit Hinweis
  auf die Reserve; goto meldet `few_blocks`; mit höchstens 32 beim Start nur laufen und graben); Lauf 27: collect verbaute nach `few_blocks` 319 Blöcke;
  `run_dragon.py` setzt das Ziel neu, wenn das Modell aufgibt, und erkennt Bett-Siege (`dragon_dead`, kein „Free the End“) – Drachenlauf 17:
  225 Blöcke über einem Lavasee verbaut, am Brückenende ohne Ausweg; Lauf 23: nach `few_blocks` sofort neues goto, Rest verbaut, gestrandet
- 2026-11-01 – Kein Krit-Sprung im Nahkampf, wenn bis 2 Blöcke neben dem Bot ein Abgrund (4+ Blöcke) ist – Drachenlauf 18: Witherskelett-Treffer
  auf einer Festungsbrücke, Rückstoß in der Luft, 19 Blöcke Sturz, Tod am Wither-Effekt
- 2026-11-01 – Lava-Reflex: Ufersuche bis 7 Blöcke über den Füßen (vorher 3), nach 1,5 s ohne Feuerresistenz Enderperle zum Ufer bis
  40 Blöcke (`pearls_thrown`) – Drachenlauf 19: 3 Blöcke tief in einem Lavasee eingesunken, kein Ufer gefunden, tot mit 12 Perlen
- 2026-11-01 – Nahkampf weicht erst von Lava bis 2 Blöcke weg (höchstens 2 s am Stück), dann wird geschlagen – Drachenlauf 25: ein Hoglin
  stieß den Bot in die Lava, 7 s darin, danach mit 3 Leben verbrannt
- 2026-11-01 – Im Nether bricht Schaden ein `goto` mit `allow_place` nicht mehr ab – Drachenlauf 24: ein Lohen-Feuerball brach den goto
  mitten im Brückenschritt ab, der Bot trat in die Lücke und fiel 22 Blöcke
- 2026-11-01 – Prompt: mit `end_ready` nicht mehr in den Nether, sondern `locate end_portal`/`throw_eye`; neben einem Witherskelett `defend`
  statt `flee` – Drachenlauf 22: mit 12 Augen und 10 Betten zurück in den Nether, auf der Flucht vor einem Witherskelett erschlagen
- 2026-11-01 – `farm_endermen` bricht nach 3 Minuten ohne Kill und ohne Bewegung ab (`stalled`), Kampf-Zeitüberschreitungen stehen im Log –
  Drachenlauf 26: 30 Minuten an derselben Stelle im Fels, 18 Kämpfe mit timeout
- 2026-11-01 – Runde 2 (Läufe 28–30, alle durch Nether-Brücken): Brückenblock im Nether kostet in der Wegfindung 14 extra (Graben 8),
  `goto` hält an einer Kante unter Beschuss einer Lohe/eines Ghasts bis 24 Blöcke an (`under_fire`), `tunnel` beachtet die Reserve,
  `farm_spawner` geht an einer Kante erst zum Spawner auf festen Boden; Prompt: Nether-Wege graben statt bauen – Läufe 28, 30:
  Feuerball-Rückstoß von der Brücke bzw. vom Brückenende am Spawner; Lauf 29: 198 Blöcke Brücke, Rest von tunnel verbaut, gestrandet
- 2026-11-01 – Runde 3 (Läufe 31–33, alle im Nahkampf gestorben): `flee` schlägt zurück, wenn ein Verfolger zweimal trifft und bis 3,5 Blöcke
  dran ist (`fought_back`), danach weiter; gegen Lohen kein Krit-Sprung (Schild bleibt zwischen den Schlägen oben); `farm_endermen` bricht
  bei zwei oder mehr wütenden Endermen schon bei 14 Leben ab (`angry_endermen`) – Lauf 33: Witherskelett traf auf der Flucht sechsmal
  (je 8); Lauf 32: farm_spawner brach dreimal nach 15–19 s mit health_low ab; Lauf 31: zwei Endermen zugleich
- 2026-11-02 – Drachenläufe 38–46 (3 parallel, Start mit Netherkit): `farm_endermen` gibt Endermen über 20 Blöcke nach 5 s auf
  (`teleported_away`, Lauf 38: 8 min ohne Kill; danach 12 Kills/6 Perlen in 10 min), `JunkDropper` kennt Nether-Kram (Lauf 40: 21 Kills,
  0 Perlen bei vollem Inventar), `shoot` geht bei Lohe/Ghast/Skelett erst von der Kante weg (Lauf 39), Reflex löscht Feuer um den
  brennenden Bot (Lauf 41), `descend` bricht schwimmend nach 5 s ab (`in_water`, Lauf 38: 19 min über dem Portalraum im Meer),
  Notwehr- und Feuerball-Reflex ohne Schwungschlag neben friedlichen Piglins (Lauf 43), `goto` hält an Kanten auch bei zielenden
  Skeletten an (Lauf 45); `look`/`fluid_spotted` melden Dörfer (`village_in_sight`, nur Oberwelt, solange Betten fehlen) und der
  Prompt sagt: Betten im Dorf abbauen, geschorene Herde nach 60 s nachscheren – Wunsch von Hannes (Lauf 40: 75 min für 4 Betten Wolle)
- 2026-11-02 – `farm_endermen` nur noch im Nether (`not_in_nether`), Prompt ohne Endermen-Jagd in der Oberwelt; nimmt Creeper bis 7 Blöcke zuerst als Ziel – Entscheidung von Hannes nach Drachenlauf 44 (1 Perle in einer Stunde, Creeper-Tod)
- 2026-11-03 – `/smartcraft stats` zeigt Leben und Hunger als 10 Herzen und 10 Keulen mit den Sprites des Spiels (Textkomponente `object`,
  GUI-Atlas `hud/heart/*`, `hud/food_*`; Hunger füllt wie im Spiel von rechts) statt als Zahlen – Wunsch von Hannes
- 2026-11-03 – `fight_dragon` darf auf allen Wegen graben (Warteplatz, Kopf, Ausweichen vor dem Atem); eine direkte Chatnachricht hält
  auch lange Befehle ohne Gefahrenabbruch an (`CHAT_INTERRUPTIBLE`: farm_*, make_obsidian, destroy_crystals, fight_dragon, bed_*,
  barter) – Hannes' Lauf (josef): nach `bed_cycle` (Drache 9,4 Leben) stand er im eigenen Käfig, `fight_dragon` fand ohne Graben
  keinen Weg hinaus, und „Was machst du?“ blieb unbeantwortet
- 2026-11-03 – `/smartcraft inventar` öffnet das Inventar des Bots als Vanilla-Doppelkiste, live verbunden (Reihe 1 Rüstung und zweite
  Hand, Reihe 2 graue Glasscheiben, Reihen 3–6 die 36 Plätze, darunter das eigene Inventar; Rüstungsplätze nur mit passender
  Rüstung) – Wunsch von Hannes; geht ohne Mod beim Client
- 2026-11-03 – `/smartcraft stats`: Herzen und Hunger untereinander über dem Namen, als Vanilla-Textanzeige (Tag `smartcraft_stats`, jeden
  Tick an den Bot gesetzt, kein Mitfahrer wegen Portalen) statt in der einzeiligen Anzeige unter dem Namen – Wunsch von Hannes
- 2026-11-03 – `JunkDropper` räumt auf dem Weg ins End (Oberwelt mit `end_ready`, oder im End) sofort Unnötiges weg (leere Eimer, Äxte,
  Schaufeln, Hacken, Ofen, Werkbank, Kohle, Stöcke, Fackeln, Rohmetall u. a.) und bei mehreren Essenssorten die schwächste (Nährwert
  mal Anzahl), solange 96 Hungerpunkte bleiben; Goldäpfel bleiben – Wunsch von Hannes: Platz für Betten
- 2026-11-03 – Ein gehobener Schild, den niemand will (kein `shield on`, keine Gefahr), wird gesenkt – Hannes' Lauf (alina): auf der
  Suche nach dem Endportal ohne `locate` lief der Bot im Schleichtempo; Ursache nicht sicher belegt (kein Schild-Event im Log)
- 2026-11-03 – Neuer Befehl `sprint` (on/off, gilt für alle Wege, Standard an) – Wunsch von Hannes: die KI entscheidet übers Sprinten
- 2026-11-03 – `use` mit Enderperle wirft nur mit `look_at` auf festen Boden ohne Lava (bis 12 Blöcke unter dem Zielpunkt), sonst
  `unsafe_landing`; `force` für die Flucht – Drachenlauf 62: von einer Nether-Brücke ins Ungewisse geworfen, Landung im Lavasee
- 2026-11-03 – Sprinten in der Wegführung schon bei grob geradem Weg (bis 45° Abweichung, je Schritt 1 Block hoch/runter, auch im
  Sprung) statt nur exakt gerade auf gleicher Höhe; Reflex setzt ein hängendes Ducken zurück und meldet `slow_state` (Messung) –
  Hannes' Lauf (alina): „er schleicht allgemein“, Ursache noch nicht belegt
- 2026-11-03 – Nach `throw_eye` lässt der Bot das Enderauge los (`stopUsingItem`, dazu ein Reflex als Rückfall) – Ursache des Schleichens
  in Hannes' Lauf (alina): `slow_state` zeigte `using_item: ender_eye`, das Spiel bremste ihn wie beim Rechtsklick-Halten
- 2026-11-03 – Statusanzeige sitzt als Mitfahrer auf dem Bot (0,45 über dem Kopfpunkt) statt jeden Tick versetzt – beim Springen ruckelte
  sie hinterher (Hannes); prüfen: Portale mit Mitfahrer (beim Wechsel wird sie neu angelegt)
- 2026-11-03 – Hannes' Lauf (xyz): `destroy_crystals` direkt nach dem Endportal zerstörte 0 von 7 (Wege zu den Schussplätzen ohne Graben
  und Bauen, die Spawnplattform liegt getrennt von der Insel) und meldete `ok` – das Modell behauptete „sieben Kristalle sind hinüber“,
  `bed_cycle` gegen voll geheilten Drachen. Jetzt: Wege mit Graben und Bauen, `failed: none_destroyed`, wenn keiner fiel. Chat hält
  Drachenbefehle nicht mehr an (fünf Abbrüche durch Chatzeilen, Drache heilte 127 → 200). `throw_eye` nennt `pickup_at` (Boden unter
  dem Endpunkt), goto zu `ended_at` baute einen Turm in die Luft
- 2026-11-04 – Endportal-Läufe (`tests/server/run_portal_dragon.py`, Start Oberwelt mit `/smartcraft kit end`, ohne `locate`, Ziel
  Drache): 18 Enderaugen Pflicht (`END_EYES`), nach dem ersten Wurf reichen 12; `throw_eye` zählt verlorene Augen (`eyes_lost`,
  ab 4 `eye_budget`), Prompt: einmal werfen, 200–300 Blöcke gehen, liegende Augen immer aufheben; `eye_in_fluid` statt
  hinterhertauchen; Ersticken (`in_wall`) bricht keine Aktion mehr ab – Wunsch von Hannes; Läufe 31–69: 6 Siege, Lauf 31 warf
  9 Augen von einer Linie, Lauf 35 ging für neue Augen in den Nether, Läufe 31/32 erstickten beim Abbau von nachrutschendem Kies
- 2026-11-05 – `look`/`fluid_spotted` melden an der Festung den Portalraum (`portal_room_near`, bis 96 Blöcke, Strukturdaten);
  Luft-Reflex schon bei 200 von 300 Luft und bis zum Ende (vorher 150, Abbruch nach 12 s), Suche 12.000 Felder; `kit end` mit
  16 Betten, Prompt: nach `no_beds` sofort `fight_dragon` – Wunsch von Hannes; Läufe 31–69: 6-mal Portalraum nicht gefunden
  (Spitzhacken im Tunnel verbraucht), 5-mal ertrunken, zweimal heilte der Drache nach den Betten von etwa 20 auf 200
- 2026-11-10 – Ortsgedächtnis für wichtige Orte (Etappe 6), `craft` mit Zwischenprodukten (bis 3 Stufen), Abbauen nur mit
  Sichtlinie (`mine`/`collect` mit `allow_dig` räumen bis 4 Blöcke davor weg) – Wunsch von Hannes; Etappe 4d wird über die
  Testdurchläufe abgenommen statt im Spiel
- 2026-11-10 – Drachen-Serie (Läufe 63–99, 3 parallel, Netherkit): kein Sieg, 9 Tode am Lohenspawner; viele Einzelfixes
  (nur im echten Lauf erprobt) und Umbau `farm_spawner` mit geschütztem Stand (Entscheidung von Hannes, ungetestet). Übergabe und
  nächste Schritte: `docs/DRAGON_SERIES_2026-11-10.md`
- 2026-11-13 – Log-Auswertung über 364 KI-Läufe (177 h, 44.400 Befehle, nur 65 % `ok`) und fünf Folgerungen daraus (Auftrag von
  Hannes): Wegsuche mit zweitem, direktem Versuch (gewichtetes A*, Gewicht 20) nach `search_stalled`/`search_limit`, wenn Graben
  oder Bauen erlaubt ist (goto: 44 % ok, 1.145-mal `search_stalled`); `entity_nearby` nur dringend, wenn der Gegner den Bot sieht,
  als Ziel hat oder unter 3 Blöcke nah ist, sonst `behind_wall` (1.866 angehaltene Befehle, 17 % danach identisch wiederholt);
  `farm_endermen` geht von einer Kante selbst zu einem sicheren Platz und zieht nach 5 Kämpfen ohne Kill selbst weiter (96-mal
  `unsafe_spot`, 111-mal `stalled`); `flee` baut sich ohne Fluchtweg ein wie `shelter` (`outcome: sheltered`; 50 von 162 Toden
  während `flee`, Median 7 s von `health_low` bis zum Tod); neues Event `no_pickaxe` mit passendem Rezept (31 von 169 Läufen mit
  `wrong_tool` ohne Spitzhacke). Geprüft: Build, Unit-Tests, `test_movement` 22 von 22; kein echter KI-Lauf
- 2026-11-23 – `/smartcraft apikey <key>`: Der Mod reicht den Schlüssel per `control_requested` (`action: apikey`) oder, falls
  noch nicht verbunden, einmalig im Ergebnis von `settings` an den Begleitprozess, der ihn in `companion/api_key.txt` speichert;
  `EventBus` und `console.py` maskieren `api_key` – Wunsch von Hannes (Veröffentlichung auf Git), Kernprinzip 6 bleibt
- 2026-11-23 – Statusanzeige über dem Bot: durch Blöcke sichtbar und volle Helligkeit, beim Absteigen wieder aufsitzen statt
  neu anlegen – Hannes: HP/Hunger manchmal unsichtbar (in 2 Blöcke hohen Gängen steckte sie in der Decke)
- 2026-11-23 – Projekt heißt SmartCraft: Anzeigename, Jar, Mod-ID, Package `de.hannes.smartcraft`, Befehl `/smartcraft`, Umgebungsvariablen `SMARTCRAFT_*`, Datenordner; gemerkte Orte aus `<welt>/aiplayer/` werden beim Laden übernommen – Wunsch von Hannes (Veröffentlichung auf Git)
- 2026-11-23 – Der Mod startet den Begleitprozess selbst (`CompanionLauncher`, bei `join` und per `/smartcraft companion`), beendet
  ihn bei `leave` und Serverstopp, startet ihn nach Absturz bis 3-mal neu. Aus der Jar nach `config/smartcraft/` entpackt (companion
  und schemas) mit eigener venv und nur `anthropic`; in der Entwicklung `companion/` mit dem Python, das `anthropic` hat. Servertests
  setzen `SMARTCRAFT_COMPANION=off`, `bundled` erzwingt zum Testen die Jar-Variante – Wunsch von Hannes: Mod allein soll reichen

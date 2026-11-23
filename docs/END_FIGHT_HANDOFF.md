# Übergabe: Drachenkampf (Stand 29.10.2026, 17:00)

Für die nächste Claude-Sitzung. Hannes hat die Testserie nach Lauf 93 abgebrochen. Nicht von selbst wieder
starten, erst fragen.

## Auftrag und Stand

- Testplan von Hannes (Memory `end-testplan`): `tests/server/run_end_fight.py` wiederholen, bis der Bot den Drachen
  5-mal in Folge tötet. Danach höchstens 3 Gesamtläufe: neue Welt, Oberwelt, volles Diamant- und Nether-Kit ohne
  Obsidian, Betten dabei. Nach jedem Gesamtlauf `tests/server/analyze_stalls.py` auswerten und die Ursachen beheben.
- Wunsch von Hannes: So schnell wie Speedrunner. Mehr als 20 Minuten pro Kampf ist zu lang.
- **Ergebnis bis jetzt:** Kein Drachensieg in 93 End-Läufen, die Serie steht bei 0 von 5. Läufe 65 bis 93 liefen mit
  Betten; der Drache kam dabei höchstens auf etwa 1 Leben herunter (Lauf 83, mit 8 Kristallen noch stehend).
- Ergebnisse stehen in `run/logs/end_fight_results.log`. Die Logs je Lauf sind
  `run/logs/end_fight_<n>_agent.log` und `end_fight_<n>_server.log`.

## Lauf starten und stoppen

Aus dem Projektordner, über das Bash-Tool. Das erste Argument ist die **Laufnummer**, nicht die Anzahl der Läufe:

```bash
SMARTCRAFT_TEST_RUNDIR=run/inst1 SMARTCRAFT_TEST_PORT=25601 SMARTCRAFT_TEST_GAMEPORT=25571 \
  nohup python tests/server/run_end_fight.py <nr> 1.0 > /dev/null 2>&1 &
```

Stoppen über PowerShell. Der Filter trifft nicht die eigene Shell:

```powershell
Get-CimInstance Win32_Process | Where-Object { $_.Name -match "java|python" -and ($_.CommandLine -match "aiRunDir=run/inst1|run_end_fight" -or ($_.CommandLine -match "agent.py" -and $_.CommandLine -match "Enderdrachen")) } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

Vorsicht beim Bearbeiten:
- Dateien nie mit `Set-Content` oder `Get-Content -Raw` schreiben. Das hat BOM und Mojibake erzeugt.
- Python- und Java-Dateien mit Python (`encoding='utf-8'`, `newline=''`) oder mit dem Edit-Tool ändern.
- `companion/prompts/system.md` muss LF-Zeilenenden behalten.

Grep-Muster im Serverlog: `destroy_crystals phase|bed|roof|skip|no_bed_space`, `bed_bomb boom`, `damage_taken`,
`reflex_left_dragon_breath`.

## Gemessene Spielmechanik

- Bett explodiert etwa 2 Blöcke unter dem Kopf des hockenden Drachen: rund 45 bis 55 Schaden. Mehrere Betten im
  selben Tick zählen nicht doppelt, die erste Explosion zerstört die anderen.
- Nach etwa 50 Schaden je Landung fliegt der Drache wieder los (Annahme, passt zu den Messungen).
- **Stehende Kristalle heilen den Drachen zwischen zwei Landungen voll.** Mit vielen Kristallen landet er außerdem
  selten. Ohne alle Kristalle zu zerstören, ist ein Sieg nicht möglich.
- Biss des Drachen: 10 Schaden. Treffer schleudern den Bot weit, auch aus einer 2 Blöcke hohen Röhre mit Geländer.
- Endermen werden im End oft wütend (Ursache ungeklärt, vermutlich Blick oder Pfeile) und treffen mit 7 Schaden in
  schneller Folge. `flee` ist gegen sie wirkungslos.

## Was der Bot jetzt tut

1. `destroy_crystals` ([DestroyCrystalsAction.java](../src/main/java/de/hannes/smartcraft/action/DestroyCrystalsAction.java)),
   Konstante `TOWERS=false`:
   - Freie Kristalle schießt er vom Boden. Den Schussplatz liefert `ShootAction.shootFrom`, im End nie näher als
     14 Blöcke an der Mitte.
   - Andere Kristalle (Käfig oder kein Bodenplatz) bekommen **Weg D**:
     1. `besideTower()` wählt das Feld direkt neben dem Obsidianturm, zur Inselmitte hin, Füße 2 Blöcke unter dem
        Kristall.
     2. Hochklettern mit `PillarUp`, auf jeder Stufe ein Geländer.
     3. Fliegt der Drache bis 30 Blöcke heran, wartet er und setzt einen Deckel über den Kopf.
     4. Oben: Dach aus 2 Blöcken (`bedStage` 1 und 2), mit Stütze auf Kopfhöhe, falls der Halt fehlt.
     5. Bett auf das Dach (Stufe 3, die Position merkt er sich in `placedBed`), klicken (Stufe 4), 20 Ticks warten,
        dann `finishBed` und hinab (`DOWN`).
   - Fehlen Bausteine (etwa 5 je Stufe), baut er zuerst Endstein ab (`TO_GATHER`/`GATHER`, bis zu 6-mal). Die Zeit
     dafür zählt nicht zum Limit von 90 s je Kristall.
   - Angreifer bekämpft er zuerst (`FIGHT`), auch auf dem Weg zum Turmfuß.
2. `bed_bomb` ([BedBombAction.java](../src/main/java/de/hannes/smartcraft/action/BedBombAction.java)):
   - Wartet 18 Blöcke vom Brunnen.
   - Baut eine Bettsäule 6 Blöcke von der Mitte vor (Bett auf Höhe 65).
   - Setzt beim Hocken des Drachen ein Bett etwa 2 Blöcke unter den Kopf, steht hinter einer Wand und zündet.
   - Weicht nach Bissen zurück. Zeitgrenze 30 min.
   - Der Servertest `tests/server/test_bed_bomb.py` ist grün, mit Resistance und gelöschten Kristallen.
3. Reflex `DragonBreathGuard`:
   - Flucht per Wegfindung aus allen Drachenwolken, inzwischen mit Graben.
   - Fällt sonst auf Schub zurück.
   - Gräbt sich nur außerhalb von 12 Blöcken um die Mitte nach unten, und nur auf dem eigenen Turm oder wenn er
     eingeschlossen ist.
4. Prompt (`companion/prompts/system.md`, Abschnitt End): Erst alle Kristalle mit `destroy_crystals` (bei
   übersprungenen wiederholen), dann `bed_bomb`, ohne Betten `fight_dragon`. Gegen Endermen `defend`, nie `flee`.
5. Das Kit in `run_end_fight.py` hat Diamantrüstung, Bogen, 192 Bruchstein und 12 Betten.

## Verlauf der Strategien (kurz)

- **Läufe 1–64:** Schwert, Bogen und eigene Türme. Auf Türmen starb er immer wieder, der Drache kam nie unter
  172 Leben.
- **Ab Lauf 65:** `bed_bomb`. Der Bettschaden klappt, aber die Kristalle heilen den Drachen.
- **Weg A (Läufe 75–82):** wieder eigene Türme für alle Kristalle. Alle Läufe starben, er wurde heruntergeschleudert.
- **Weg C:** keine Türme, nur die vorgebaute Bettsäule. Die Kristalle heilen weiter.
- **Weg D (ab Lauf 84):** Röhre am Obsidianturm und Bett auf dem Dach. Bisher ist dabei **kein einziges Bett am Turm
  explodiert**.

## Läufe 84–93 (Weg D) und die Fixes daraus

| Lauf | Ausgang | Fix |
|---|---|---|
| 84 | Tod im Atem am Brunnen | Schussplatz nie unter 14 Blöcken zur Mitte; Atem-Reflex gräbt dort nicht |
| 85 | Atem traf 4-mal in der eigenen Röhre; 9 Kristalle blieben (`roof_failed`, `few_blocks`) | Flucht mit Graben, Stütze fürs Dach, Endstein sammeln |
| 86 | Tod durch Endermen nach `flee` | Prompt: `defend` |
| 87 | Bett stand, aber der Dachblock wurde geklickt (Bot war einen Block abgesackt) | `placedBed` merken |
| 88 | Dach `no_support`: kein Geländer auf Kopfhöhe | Stütze auf Kopfhöhe setzen |
| 89 | Klettern brach ab (Bausteine leer), 8 Kristalle blieben | Bedarf etwa 5 je Stufe, mehr sammeln |
| 90 | Tod durch Endermen auf dem Weg zum Turmfuß, keine Gegenwehr (`onTower` sperrte `FIGHT`) | `FIGHT` auch in `TO_TOWER_BASE` |
| 91 | Drache schleuderte ihn aus der offenen Röhre (Höhe 86) | Deckel über dem Kopf, solange der Drache nah ist |
| 92 | `too_long` nach dem Sammeln; `no_bed_space` in einer Turmecke | Sammeln zählt nicht; andere Bettrichtungen; Log |
| 93 | Von Hannes abgebrochen (kletterte gerade) | – |

**Alle Fixes ab Lauf 87 sind gebaut, aber im echten Lauf noch nicht bis zum Ende bestätigt.** Bestätigt ist nur
die Dachstütze aus Lauf 92.

## Offene Punkte und Ideen

- Explodiert ein Bett auf dem Dach überhaupt nahe genug am Kristall? Die neue Logzeile
  `destroy_crystals bed at … still_bed=… crystal=…` zeigt es. Ein Servertest dafür fehlt. Empfehlung: zuerst einen
  Servertest für Weg D schreiben (Turm mit Kristall, Bot daneben, Drache entfernt) statt weiter ganze Läufe zu
  brauchen.
- Das Endstein-Sammeln dauert lange, im Lauf 93 etwa 4 Minuten. Möglich wäre mehr Bruchstein im Kit; bei Hannes
  nachfragen, ob das noch realistisch ist (Memory `keine-schummel-hilfen`).
- `spot_no_path`, `tower_no_path` und `missed` kommen noch vor.
- Warum die Endermen wütend werden, ist ungeklärt.
- In CLAUDE.md fehlen die Einträge im Entscheidungslog für die Änderungen ab Lauf 84: Weg D,
  `DragonBreathGuard`-Änderungen, Prompt zu Endermen.
- Speedrunner-Ansatz zum Vergleich: Kristalle meist ignorieren oder nur die Käfige mit Bögen beschießen; den
  hockenden Drachen mit 1 bis 2 Betten je Landung treffen; One-Cycle beim Abflug-Perch. Bei uns scheitert das an der
  Heilung durch die Kristalle und am Timing der Landungen.

## Stand 01.11.2026 (nach End-Läufen 94–105)
Hauptweg ist jetzt `destroy_crystals` mit `ground_only: true` und danach ein `bed_cycle` (Käfig am Brunnen, Betten in Serie,
Annäherung an den Kopf beim Hocken). `bed_bomb` ist kein Tool mehr. Siege: 94, 96, 97, 102, 104. Gescheitert:
- 98, 100, 105: Tod im Drachenatem, im Käfig nahe am Brunnen (bis 4 × 6 Schaden in 3 s)
- 99: KI wählte `bed_bomb` (seitdem ausgeblendet)
- 103: 12 Betten, Drache bei 29,2; danach `destroy_crystals` mit Türmen, tödlicher Sturz
- 101: ungültig, die Welt von Instanz 1 hatte keinen Drachen mehr

Nächste Schritte: Käfig weiter vom Brunnen und erst beim Hocken herangehen; Atem-Reflex schon beim ersten Treffer;
nach `no_beds` nur vom Boden weiterkämpfen; Welt von `run/inst1` neu aufsetzen. Ziel bleibt 5 Siege in Folge,
danach höchstens 3 Gesamtläufe (siehe Memory `end-testplan`). ARCHITECTURE.md ist nachgetragen.

### Umgesetzt am 01.11. (nur Messlauf, kein echter Lauf)
- `BedCycleAction.CAGE_R` 10,3 (vorher 8,3); `approach()` führt beim Hocken zum Kopf
- `DragonBreathGuard`: frischer Atemtreffer (`indirect_magic` vom Drachen) zählt bis 4 Blöcke außerhalb des Wolkenrandes
- Prompt: nach `no_beds` nur `fight_dragon` vom Boden, keine Türme
- `run_end_fight.py` löscht vor dem Start die alte Welt gleichen Namens
- Messlauf `test_bed_cycle_bot.py combo 36`: Drache 200 → 103, Bot lebt (wie die Serie 30–35: Test endet nach 5 Landungen)

Start nächste Session (5 parallel, Instanzen 1–5):
`for k in 1 2 3 4 5; do SMARTCRAFT_TEST_RUNDIR=run/inst$k SMARTCRAFT_TEST_PORT=$((25600+k)) SMARTCRAFT_TEST_GAMEPORT=$((25570+k)) python tests/server/run_end_fight.py $((105+k)) 1.0 & sleep 20; done`
Ergebnisse in `run/logs/end_fight_results.log`. Bleibt der Atemtod, Käfigabstand mit `test_bed_cycle_bot.py` gegen 8,3 vergleichen.

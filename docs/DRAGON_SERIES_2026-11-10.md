# Drachen-Serie 10.11.2026 – Übergabe

Auftrag von Hannes: 3 Bots parallel (`tests/server/run_dragon.py`, Start im Nether mit `/smartcraft kit nether`,
Instanzen 1–3: `SMARTCRAFT_TEST_RUNDIR=run/inst<k>`, `SMARTCRAFT_TEST_PORT=2560<k>`, `SMARTCRAFT_TEST_GAMEPORT=2557<k>`),
Ziel: 3 Drachensiege in Folge. Alle 10 Minuten prüfen; bei Tod oder Hänger Ursache analysieren, beheben, Lauf neu starten.
Am 10.11. um ~15:55 auf Wunsch von Hannes gestoppt. Ergebnisse je Lauf: `run/logs/dragon_results.log` (Läufe 63–99).

## Ergebnis
- Kein Sieg. Einziger Lauf im End: 74 (mit 6 statt 12 Betten hinein, Drache 200 → 57, heilte an 7 Kristallen, Tod im Atem).
- Häufigster Todesort: **Lohenspawner** (9 Tode: 72, 78, 80, 81, 85, 86, 93, 94, 95), meist Dauerbrand und Feuerbälle,
  dazu Witherskelette. Hannes entschied: `farm_spawner` umbauen (geschützter Stand). Der Umbau ist gebaut, aber
  **noch ungetestet** (Läufe 98/99 liefen nur 2 Minuten).
- Weitere Engpässe: Betten (meist fehlen alle 12), Perlen (farm_endermen je nach Gelände 0 bis 17 Kills in 10 min),
  3 Stunden reichten nie (zuletzt 5 Stunden je Lauf).

## Vor dieser Serie gebaut (Auftrag von Hannes)
- Etappe 4d gilt als über die Testläufe abgenommen.
- Etappe 6, Ortsgedächtnis: `Places` merkt sich Netherportale, Lavaseen (ab 10 Quellen), Spawner, Festungen, Dörfer,
  Endportalräume, Betten; `look` → `known_places`, neuer Befehl `places` (Schema `schemas/commands/places.schema.json`).
- `craft` stellt Zwischenprodukte selbst her (bis 3 Stufen, `intermediates`). `test_craft` 30/30.
- Abbauen nur mit Sichtlinie; `BlockBreaker` räumt bis 4 Blöcke davor selbst weg. `test_blocks` 28/34
  (6 Fehlschläge nicht von der Sichtlinie, nicht weiter untersucht).

## Fixes während der Serie (alle nur im echten Lauf erprobt, KEIN Servertest)
| Lauf | Ursache | Fix |
|---|---|---|
| 65, 68 | Brücke übers Lavameer, Bausteine alle | `goto`: `way_back` (Brückenanfang, nur Nether, ≤8 hoch, ≤150 weit); im Nether keine Brücke, wenn Blöcke < Luftlinie (`bridging_off`) |
| 64 | farm_endermen an Brückenkante | `unsafe_spot` an Kanten |
| 67 | Hoglin schleudert über Geländer | Schild oben gegen Hoglin/Zoglin/Brute an Kanten |
| 63 | Perle über Ziel hinaus in Lava | `use ender_pearl` simuliert die Flugbahn |
| 69 | build_portal `no_line_of_sight` (eigene Regression) | Wegräumen in `BlockBreaker` |
| 69 | (Versuch farm_endermen nur graben) | zurückgenommen: 1 Kill in 10 min |
| 66 | Goldteil verbraucht, Piglin | Warnung `tool_low_durability` mit `gold_armor` |
| 72 | shoot wehrte sich nicht | shoot wechselt bei Nahkampf-Angreifer zum Schwert (`defended_against`) |
| 75 | unter dem Meeresgrund eingeschlossen | Wegfindung: senkrecht auftauchen bis 24 Blöcke (**ungetestet, test_movement fehlt**) |
| 76 | Magmawürfel, nie zurückgeschlagen | Notwehr-Reflex auch während goto/shelter (`Action#allowsSelfDefense`) |
| 77 | Feuerball über 1 hohes Geländer | Geländer 2 hoch (Brücke und `GotoAction.rail`) |
| 78, 80 | Witherskelett | kein Krit-Sprung, Schild oben, auch auf dem Weg (≤3,5) |
| 74 | ins End mit zu wenig Betten | `enter_portal` ins End nur ohne `end_missing` (`force`) |
| 79 | Spitzhacke weggeworfen, andere zerbrach | JunkDropper wirft nicht weg, wenn das bessere < 25 % hat |
| 81 | Schild fast kaputt | Warnung bei Schild ≤ 40 |
| 83, 73 | farm_endermen `ok` mit 0 Kills | `failed: no_kills` |
| 85 | Geländer verbrauchte die Reserve | `rail` hält 32 Bausteine |
| 86 | (Versuch: weiterkämpfen bei gescheiterter Hütte) | nach 93/95 zurückgenommen |
| 87 | Spawner ohne Lohen | `wrong_spawner` (Spawner-Typ geprüft) |
| 89 | farm_spawner `stalled`, eigentlich Bausteine fehlen | `failed: no_blocks` |
| 91 | Hoglin-Herde | Prompt: 3 Blöcke hochbauen |
| 90+ | `farm_endermen count 18` ungültig | Obergrenze 24 |
| alle | Lohenspawner | **Neu: geschützter Stand in `FarmSpawnerAction.bunkerTick`** |

Ungeklärt: Lauf 88 (Absturz vom Säulenkopf), 90 (wer die Zombie-Piglins wütend machte), 84 (Creeper; Fluchtreflex
hat Hannes abgelehnt). `run_dragon.py`: `EXTRA` (Goldäpfel, Perlen) wird nie ausgegeben; Tränke entfernt.

## Nächste Schritte
1. Servertests ohne laufende Serie: `test_farm.py` (Stand am Spawner), `test_movement.py` (Auftauchen),
   `test_survival.py`, `test_blocks.py`, `test_end.py`.
2. Danach neue Serie mit 5 h je Lauf; auf den Stand am Lohenspawner achten.
3. Doku: ARCHITECTURE.md und CLAUDE.md-Entscheidungslog um die Fixes oben ergänzen (bisher nur Ortsgedächtnis,
   craft, Sichtlinie eingetragen).

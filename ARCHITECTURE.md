# Architektur – KI-Minecraft-Spieler

Dieses Dokument beschreibt den Zielaufbau. Was davon schon existiert, steht in `CLAUDE.md`
unter „Aktueller Stand“ und hier in der Spalte „Ab Etappe“.

## Überblick

```
┌──────────── Minecraft-Server (integriert oder dedicated) ────────────┐
│  Menschliche Spieler          KI-Spieler („Claude“)                   │
│  (echte Clients)              echter ServerPlayer ohne Client         │
│                                 ▲ Aktionen      │ Wahrnehmung         │
│                                 │               ▼                     │
│                    ┌────────── Bridge ───────────┐                    │
│                    │ ← Befehls-Queue   Event-Queue →                  │
│                    └──────────────┬──────────────┘                    │
└───────────────────────────────────┼───────────────────────────────────┘
                                    │ TCP 127.0.0.1, eine JSON-Nachricht pro Zeile
┌───────────────────────────────────┼───────────────────────────────────┐
│  Begleitprozess (Python)                                              │
│  Events sammeln → Prompt bauen → Claude → Befehl                      │
└───────────────────────────────────────────────────────────────────────┘
```

Der Mod kennt weder API-Schlüssel noch Modell. Der Begleitprozess kennt keine
Minecraft-Interna, nur Events und Befehle.

## Grundentscheidungen

| Thema | Entscheidung | Begründung |
|---|---|---|
| Wen steuert die KI? | Einen eigenen, zweiten Spieler; der Mensch wird nie gesteuert | Mensch schaut zu, Bot hat eigenen Körper, Inventar und Spielstand |
| Wo läuft der Mod? | Serverseitig, nur beim Host | Andere LAN-Spieler brauchen ihn nicht; es gibt keine eigenen Pakete, Blöcke oder Items |
| Transport | TCP auf `127.0.0.1:25599`, eine JSON-Nachricht pro Zeile (UTF-8) | Braucht im Mod keine Zusatzbibliothek, lässt sich mit jedem Werkzeug mitlesen |
| Wer lauscht? | Der Mod lauscht, der Begleitprozess verbindet sich. Höchstens einer; ein neuer verdrängt den alten | Begleitprozess kann neu gestartet werden, ohne das Spiel zu beenden |
| Begleitprozess | Python 3.11 mit offiziellem `anthropic`-SDK | Ist installiert, kurzer Code, gutes SDK |
| Modell | Umgebungsvariable `AIPLAYER_MODEL`, Standard ein Sonnet-Modell | Laut Vorgabe |

## Der KI-Spieler (Etappe 1b)

Der Bot ist ein normaler `ServerPlayer`, den der Server für einen verbundenen Spieler hält.

- **Beitritt:** `/aiplayer join [name]` startet die Spawn-Vorbereitung des Spiels
  (`PrepareSpawnTask`: gespeicherten Spielstand lesen, Spawn-Chunks laden) und ruft danach
  den normalen Join des Servers auf (`PlayerList.placeNewPlayer`). Daher kommen Join-Nachricht,
  Tab-Liste, Sichtbarkeit und Spielstand ohne eigenen Code.
- **Verbindung:** `FakeConnection` ersetzt die Netzwerkverbindung. Pakete an den Bot werden
  verworfen; ein Abschiedspaket (z. B. durch `/kick`) beendet die Verbindung.
- **Identität:** Die UUID wird aus dem Namen abgeleitet (wie im Offline-Modus). Gleicher Name
  heißt gleicher Spielstand. Der Skin ist der Standard-Skin zu dieser UUID.
- **Tick:** Bei echten Spielern stößt die Netzwerkverbindung den Spieler-Tick an. Für den Bot
  macht das `BotManager` am Ende jedes Server-Ticks.
- **Bewegung:** Normalerweise rechnet der Client eines Spielers dessen Bewegung. `PlayerMixin`
  schaltet das für den Bot um, damit der Server Schwerkraft und Bewegung selbst berechnet.
- **Austritt:** `/aiplayer leave` geht denselben Weg wie ein echtes Verbindungsende
  (Leave-Nachricht, Speichern). Beim Stoppen des Servers wird der Bot wie jeder Spieler gespeichert.
- **Berechtigung:** Die Befehle dürfen Operatoren, die Konsole und der Host einer
  Einzelspieler-/LAN-Welt nutzen (auch ohne aktivierte Cheats).
- **Spielmodus:** Der Bot tritt immer im Überlebensmodus bei, auch in einer Kreativwelt.
  Im Kreativmodus wäre er unverwundbar und Mobs würden ihn ignorieren.
- Es gibt genau einen Bot gleichzeitig.

- **Tod und Respawn:** 20 Ticks nach dem Tod löst `BotManager` dasselbe aus wie der Klick auf
  „Respawn“ (Bett oder Weltspawn wählt das Spiel) und schreibt „<Name> ist wieder da“ in den
  Chat. Todesursache, Ort und verlorene Items stehen im Event `player_died` und in
  `/aiplayer status`.

### Bekannte Client-Abhängigkeiten

Stellen, an denen sich der Server auf eine Reaktion des Clients verlässt. Die `FakeConnection`
merkt sich die betroffenen Pakete; `applyClientReactions()` führt die Reaktion einmal pro Tick
vor dem Tick des Bots aus.

| Mechanik | Was ein Client täte | Stand |
|---|---|---|
| Bewegung und Schwerkraft | selbst rechnen, Position melden | Behoben: `PlayerMixin`, Server simuliert |
| Rückstoß nach Treffer (`ClientboundSetEntityMotionPacket`) | Geschwindigkeit übernehmen. Bei Spielerschlägen setzt der Server seine eigene Kopie danach zurück (`Player.causeExtraKnockback`) | Behoben: Geschwindigkeit wird gesetzt, nicht addiert. Getestet mit Schlag, Zombie, Pfeil |
| Explosionsstoß (`ClientboundExplodePacket`) | Stoß addieren | Kein Eingriff nötig: Der Server wendet den Stoß selbst an; das Paket wird bewusst ignoriert, sonst wäre er doppelt. Getestet mit TNT |
| Teleport (`ClientboundPlayerPositionPacket`) | bestätigen | Behoben: wird bestätigt |
| Respawn nach dem Tod | „Respawn“ klicken | Behoben: automatisch nach 20 Ticks |
| Abspann nach dem Endportal (`wonGame`) | Abspann beenden, Respawn anfordern | Behoben wie Respawn, ungetestet |
| „Client hat geladen“ nach Join/Respawn | melden; bis dahin ist der Spieler bis zu 3 s unverwundbar | Behoben: wird sofort gemeldet |
| Fahrzeuge (Boot, Pferd, Lore) | Client des Reiters rechnet die Bewegung | Sollte durch `PlayerMixin` serverseitig laufen, ungetestet; Steuerung kommt mit den Aktionen |
| Keep-Alive, Idle-Kick, Bewegungsprüfung | regelmäßig antworten | Entfällt: Diese Prüfungen laufen im Tick der Netzwerkverbindung, den der Bot nicht hat |
| Chunk-Versand mit Bestätigung | Chunk-Pakete bestätigen | Folgenlos: Der Bot braucht keine Chunk-Pakete; die Chunks um ihn bleiben wie bei jedem Spieler geladen |
| Geladene Chunks um den Spieler | Bewegungspakete senden; nur dann verschiebt der Server den geladenen Bereich | Behoben: `BotManager` meldet den Standort jeden Tick. Ohne das blieben die Chunks am alten Ort und der Bot liefe ins Ungeladene |
| Laufen, Springen, Schwimmen, Klettern | Eingaben senden | Behoben: `PathFollower` setzt die Eingaben direkt |
| Sprinten, Schleichen, Elytra, Menüs | Eingabepakete senden | Offen: kommt mit den weiteren Aktionen |

## Wahrnehmung (Etappe 2)

`Perception` hängt an den Spiel-Hooks, baut Events und gibt sie an den `EventBus`. Der vergibt die
laufende Nummer, schreibt ins Debug-Log und reicht ab Etappe 3 an die Bridge weiter.

| Event | Auslöser | Priorität | Begrenzung |
|---|---|---|---|
| `block_broken`, `block_placed` | Spieler baut ab oder setzt im Umkreis von 16 Blöcken; eigene Blöcke des Bots immer | normal | Fremde Serien werden pro Sekunde, Spieler und Blockart zusammengefasst (`count`) |
| `damage_taken` | Bot nimmt Schaden und überlebt | high | Pro Schadensart höchstens eines sofort, der Rest der Sekunde summiert |
| `player_died` | Bot stirbt | high | – |
| `player_respawned` | Bot ist wieder im Spiel | normal | – |
| `entity_nearby` | Feindliche Mobs im Umkreis von 16 Blöcken, Prüfung alle 10 Ticks | high, wenn einer näher als 6 Blöcke ist | Nur wenn ein Mob kommt, geht oder die Entfernungszone wechselt; höchstens 1 pro Sekunde |
| `danger` | Lava (3 Blöcke), Feuer (2 Blöcke oder Bot brennt), Abgrund über 3 Blöcke neben dem Bot; Prüfung alle 5 Ticks | high; „vorbei“ (leere Liste) normal | Nur bei Änderung; höchstens 2 pro Sekunde |
| `hunger_low` | Hunger 6 oder weniger; Prüfung alle 10 Ticks | high | Einmal, bis der Hunger wieder über 6 war. Nennt `best_food` |
| `health_low` | Leben 6 oder weniger | high | Einmal, bis das Leben wieder über 8 war |
| `night_falling` | Tageszeit 11500 in der Oberwelt; `monsters_in_ticks` bis 13000, `can_sleep_in_ticks` bis 12542 | normal | Einmal je Abend |
| `reflex_ate` | Ess-Reflex (`reason`): `emergency` bei Leben 4 oder weniger, auch Giftiges; `top_up`, wenn Leben fehlt und der Hunger unter 20 ist, kein Gegner bis 4 Blöcke, nur Unbedenkliches ohne goldene Äpfel | high | Notfall frühestens alle 10 s, Auffüllen alle 5 s |
| `reflex_surfaced` | Reflex: Unter Wasser war die halbe Luft verbraucht, der Bot ist zum nächsten Ort mit Luft geschwommen (`surfaced: false`, wenn er keinen erreicht hat) | high | Je Tauchgang |
| `reflex_left_lava` | Reflex: Der Bot stand in Lava und ist zum nächsten sicheren Feld gelaufen (`LavaEscape`: Breitensuche durch Lava, Schub zum Ufer wie beim Herausspringen; nach 1,5 s ohne Feuerresistenz Enderperle zum Ufer bis 40 Blöcke, `pearls_thrown`); die Aktion wartet so lange. `ticks_in_lava`, `health`, `on_fire` | hoch | Einmal je Ausstieg |
| `reflex_left_dragon_breath` | Reflex: Der Bot stand in einer Säurewolke des Enderdrachen (`AreaEffectCloud` mit dem Drachen als Besitzer) und ist vom Wolkenmittelpunkt weg hinausgesprintet (`DragonBreathGuard`, höchstens 5 s); die Aktion wartet so lange. Greift auch bei einem frischen Atemtreffer bis 4 Blöcke außerhalb des Wolkenrandes; eingeschlossen (Käfig) bricht er die Wand nach außen auf. `ticks`, `health`, `from` | hoch | Einmal je Wolke |
| `reflex_dug_out` | Reflex: Der Bot steckte in einem Block (Erstickungsschaden, z. B. nach einer Enderperle) und baut ihn mit `BlockBreaker` ab, erst am Kopf, dann an den Füßen; die Aktion wartet so lange. `ok`, `block`, `pos`, `reason`, `health` | hoch | Einmal je Block |
| `reflex_deflected_fireball` | Reflex (`FireballGuard`): Ein Ghast-Feuerball fliegt bis 3,2 Blöcke auf den Bot zu, er schlägt ihn zurück. Geht nicht ans Modell. Dazu meidet die Wegfindung bei einem Ghast bis 64 Blöcke Felder neben Abgründen ab 4 Blöcken Tiefe (Kosten +6) |
| `reflex_dropped_junk` | Reflex (`JunkDropper`): Höchstens 1 Platz frei → wirft allen Abraum weg (Granit, Diorit, Andesit, Tuff, Kies, Sand, Basalt …), danach Bausteine bis auf 192; Erze, Werkzeug, Essen bleiben. Weggeworfenes und `drop` sind 60 s für den Bot gesperrt. Geht nicht ans Modell |
| `reflex_lava_stuck` | Reflex: 15 s in Lava ohne Ausweg (nur mit Feuerresistenz). Der Lava-Reflex schwimmt weiter Richtung `nearest_shore` (grobe Suche bis 48 Blöcke), gibt aber die Aktion frei; die KI soll eine Enderperle werfen. Vorher sperrte der Reflex jede Aktion, auch ihr Zeitlimit (Nether-Lauf 26) |
| `smelting_done` | Ein vom Bot befüllter Ofen ist fertig oder ohne Brennstoff | normal | Einmal je Befüllung |
| `goal_requested` | Ein Mensch hat `/aiplayer goal <text>` gegeben (`text`, `by`) | normal | Bei jedem Befehl |
| `control_requested` | Ein Mensch hat `/aiplayer pause`, `resume` oder `stop` gegeben (`action`, `by`); bei `pause` und `stop` hält der Mod danach die laufende Aktion an. `action: settings` trägt alle Einstellungen (`tts`, `humor`, `talkmode`, `dimension_follow`, `streamer`) nach `/aiplayer tts|humor|talkmode|dimensionfollow|streamer` | normal | Bei jedem Befehl |
| `dimension_followed` | Dimension Follow: Der verfolgte Spieler hat die Dimension gewechselt, der Mod hat den Bot nach 3 s Ladezeit auf einen sicheren Platz neben ihn teleportiert und die Aktion angehalten (`DimensionFollow`; nur der Wechsel des Spielers löst aus, höchstens 5 Versuche je Wechsel). `from`, `to`, `pos`, `player` | hoch | Einmal je Wechsel |
| `chat_received` | Jemand anderes als der Bot schreibt in den Chat, als Spieler oder mit `/say`, auch von der Konsole (`from`, `text`, `direct`). `direct` ist wahr, wenn der Name des Bots fällt oder außer ihm höchstens ein Spieler im Spiel ist | normal | Bei jeder Nachricht |
| `tool_low_durability` | Werkzeug in der Hand hat höchstens 10 % Haltbarkeit | normal | Einmal je Werkzeug |
| `no_pickaxe` | Die letzte Spitzhacke ist weg (zerbrochen, weggeworfen, nach dem Tod verloren). `craft` nennt die beste Spitzhacke, für die das Material im Inventar reicht, `enough_wood`, ob das Holz für Stiel und Kopf da ist, dazu `hint`. Der Begleitprozess hält dafür die laufende Aktion an | hoch | Einmal je Verlust |
| `reflex_sealed_water` | Reflex `WaterGuard`: In eine Lücke, die der Bot in den letzten 30 Ticks abgebaut hat, lief Wasser; er setzt sofort einen Bau-Block hinein und nimmt danach sein Werkzeug wieder in die Hand (`pos`, `sealed`, `block` oder `reason`). Nicht im eigenen Feld und nicht, solange der Bot selbst im Wasser steht (sonst baute er sich den Ausweg zu) | normal | Einmal je Lücke |
| `fluid_spotted` | `FluidWatch`: Alle 100 Ticks sucht der Mod im Umkreis von 24 Blöcken (im Nether 32, ±10 in der Höhe) nach Wasser- und Lavaquellen, Obsidian (auch weinendes: zerfallene Portale), Spawnern, Lohen, Endportalrahmen und (im Nether, über die Strukturdaten wie `fortress_in_sight`) Netherfestungen bis 100 Blöcke und meldet eine Art, sobald sie neu in Sicht ist (`kind`, `pos`, `distance`, `size`: zusammenhängende gleichartige Blöcke bis 64). Bei Lava die größte Fläche im Umkreis statt der nächsten; kommt eine Fläche ab 10 Quellen neu in Sicht, meldet er sie auch dann, wenn vorher kleinere Lava zu sehen war. Der Begleitprozess hält eine Aktion an, wenn das Modell mit `search_for` danach sucht | normal | Je Art einmal, bis sie aus dem Umkreis verschwindet |
| `items_received` | Ein anderer Spieler hat dem Bot Items zugeworfen und er hat sie aufgehoben (`from`, `items`). Hook: `ItemEntityMixin` auf `ItemEntity.playerTouch`, Werfer aus `getOwner()` | normal | Würfe innerhalb einer Sekunde zusammengefasst |

`block_placed` kommt aus `BlockItemMixin`, weil Fabric API dafür kein Event hat. Blockänderungen
ohne Spieler (Explosion, Feuer, Wasser) erzeugen kein Event.

**Snapshot:** `/aiplayer look` gibt den kompakten Zustand aus (`schemas/snapshot.schema.json`):
Position, Blick, Dimension, Leben, Hunger, Tageszeit, Item in der Hand, die 3x3 Bodenblöcke und
interessante Blöcke im Umkreis (8 horizontal, 5 vertikal) mit Anzahl und den drei nächsten
Positionen relativ zum Bot. Gemessen: 120 bis 200 Tokens (Schätzung, Zeichen ÷ 3,5).

**Kosten:** Auf dem Testserver im Schnitt rund 75 µs pro Tick in den ersten 40 Sekunden
(0,15 % eines Ticks, Tendenz fallend), einmalig 12 ms beim allerersten Scan. Ein Snapshot
kostet 2 bis 15 ms und läuft nur auf Abruf. `/aiplayer status` zeigt die laufenden Werte.

**Testhilfen:** `/aiplayer debug punch` (Schlag von Osten über den Code eines echten
Spielerschlags) und `/aiplayer debug break` (Bot baut den Block unter sich ab).

## Aktionen (Etappe 4)

Eine Aktion ist ein Befehl mit Dauer. Für alle gelten dieselben Regeln, durchgesetzt vom `ActionRunner`:

- Sofort kommt `<befehl>_started`, am Ende genau ein `<befehl>_result` mit `ok`, `failed` oder
  `cancelled`. Jedes Ergebnis nennt in `data` den Standort danach (`pos`) und die Dauer (`ticks`).
- Es läuft höchstens eine Aktion. Ein neuer Befehl beendet die laufende mit `cancelled: superseded`,
  `stop` mit `cancelled: stopped`.
- Jede Aktion hat ein Zeitlimit (`failed: timeout`).
- **Gefahr unterbricht:** `damage_taken` beendet die Aktion mit `cancelled: damage`; ausgenommen sind
  `attack`, `defend`, `flee`, `eat` und `shelter`, die gerade für diesen Fall da sind. Ein `danger`-Event
  beendet sie mit `cancelled: danger`, wenn Lava oder Feuer neu auftaucht; `data.trigger` enthält das
  auslösende Event. Wer danach denselben Befehl erneut schickt, wird an derselben Gefahr nicht noch
  einmal gestoppt. Ein Abgrund neben dem Bot unterbricht nicht, die Wegfindung meidet ihn.
  Im Nether zählen Lava und Feuer dafür nur direkt daneben (1 Block): Dort sind sie überall, und im KI-Lauf brach
  sonst fast jede Aktion ab.
- Stirbt der Bot: `failed: bot_died`. Verlässt er das Spiel: `failed: no_bot`.

### Befehle und Fehlergründe

Diese Tabelle ist vollständig und die Werkzeugliste für Etappe 5: Jede Zeile wird ein Tool. Die Argumente stehen
maschinenlesbar in `schemas/commands/<befehl>.schema.json`.

Für alle Befehle außer `ping` gilt zusätzlich `failed: no_bot` (kein KI-Spieler im Spiel), für alle
mit Argumenten `failed: invalid_args` (mit `hint`), für alle Aktionen `failed: timeout`, `bot_died`
und `cancelled: stopped`, `superseded`, `danger`, `damage` (Ausnahmen siehe oben). Befehle, die sofort antworten
(`look`, `stop`, `recipe`, `locate`, `inventory`, `equip`, `drop`, `shield`, `sprint`, `equip_best_armor`), brechen eine laufende Aktion nicht ab.

| Befehl | Argumente | Was er tut | Eigene Fehlergründe (`failed`) |
|---|---|---|---|
| `ping` | – | Prüft die Verbindung | – |
| `look` | – | Liefert den Snapshot | – |
| `stop` | – | Bricht die laufende Aktion ab; `data.stopped` nennt sie | – |
| `fight_dragon` | – | Nur im End: wartet am Boden 24 Blöcke vom Brunnen; hockt der Drache (`isSitting`), läuft er neben den Kopf und schlägt mit der besten Waffe zu, sobald der Schlag aufgeladen ist; vor dem Atem weicht der Reflex aus. Endet bei totem Drachen (`dragon_dead`) | `not_in_end`, `health_low` (cancelled) |
| `bed_bomb` | – | Nur im End, braucht Betten: wartet am Boden 18 Blöcke vom Brunnen; hockt der Drache, geht er 3 Blöcke vor den Kopf, setzt eine Wand (Fuß- und Kopfhöhe), setzt ein Bett unter den Kopf und klickt es an (Explosion Stärke 5), solange er hockt. Endet bei totem Drachen (`dragon_dead`) | `not_in_end`, `no_beds`, `health_low` (cancelled) |
| `bed_cycle` | `mode` (`combo` Standard, `cage`, `climb`) | Nur im End, braucht Betten und Bausteine (drachenfeste wie Obsidian/Endstein zuerst). Kristalle bleiben stehen. `cage`/`combo`: Käfig auf dem Endstein 10,3 Blöcke vom Brunnen (bis 01.11. 8,3: lag im Atem), Bot mittig, Ring auf Fuß- und Kopfhöhe, Dach; hockt der Drache und ist der Kopf 3,5 bis 14 Blöcke entfernt, geht er bis 2,5 Blöcke vor den Kopf (`approach`). Betten im Nether/End sind von `flammable_near_fire` ausgenommen. `climb`: offene Säule bis Fußhöhe 67, baut beim Abheben mit hoch. Jeden Tick: Liegt ein Platz 0 bis 3 Blöcke unter dem Kopf (bis 2,2 seitlich) in Reichweite, setzt er eine Brücke (bis 3 Blöcke) als Halt, das Bett und zündet es; nur wenn der geschätzte Eigenschaden (Spielformel `getSeenPercent`) unter der Grenze liegt (`cage` 4, `combo` 10, immer Leben − 7). Danach 10 Ticks Pause (Unverwundbarkeit des Drachen). Ergebnis `hits`, `beds_used` | `not_in_end`, `no_beds`, `no_blocks`, `health_low` (cancelled) |
| `destroy_crystals` | `ground_only` (bool) | Nur im End: alle Endkristalle nacheinander. Mit `ground_only` nur freie vom Boden, alles andere `skipped` (kein Turm, kein Käfig; so nutzt es der Prompt vor `bed_cycle`). Freie vom Standplatz am Boden (`shoot_from`) mit dem Bogen; Käfige und Kristalle ohne Platz am Boden von einem eigenen Turm 13 Blöcke daneben auf Kristallhöhe (`goto` mit Bauen, Geländer im End), bei Käfigen vorher bis 5 Blöcke heran und die Gitter in der Schusslinie abbauen, dann zurück auf den Turm und schießen; danach zum Boden (`goto` mit Graben). Je Kristall 2 Versuche. Schaden und Gefahr brechen nicht ab, `health_low` unter 6 Leben | `not_in_end`, `crystals_left` (`skipped`), `stuck_on_tower` |
| `chat` | `text` (1–256 Zeichen) | Schreibt eine Nachricht in den Chat, für alle sichtbar wie von einem echten Spieler (`<Claude> …`). Steuerzeichen werden zu Leerzeichen. Bricht keine laufende Aktion ab | – |
| `usage` | `tokens`, `cost` | Nur vom Begleitprozess nach jedem Modellaufruf (kein Tool für das Modell): Gesamtverbrauch seit seinem Start. Der Mod zeigt ihn menschlichen Spielern als XP-Leiste (Level = Cent, Balken = Bruchteil) und darüber in der Aktionsleiste (Tokens, $), alle 20 Ticks als Pakete an die Clients; echte XP bleiben. Standard an bei jedem Join, `/aiplayer xpbar on\|off` | – |
| `goto` | `pos` [x,y,z], `tolerance` (0–16, Standard 1), `allow_dig`, `allow_place` (Standard aus) | Läuft zur Position. Springt 1 Block hoch, umgeht Hindernisse, schwimmt, klettert Leitern, geht über Platten und Treppen, fällt höchstens 3 Blöcke (ins Wasser bis 12), meidet Lava und Feuer. Mit `allow_dig` baut er Blöcke im Weg ab, mit `allow_place` überbrückt er Lücken und baut sich hoch | `no_path` mit `hint` (`goal_not_standable`, `enclosed`, `search_stalled`, `search_limit`, `no_start`) und `closest` (nächstes erreichbares Feld). `stuck` mit `hint` (`no_progress`, `blocked_drop_ahead`, `blocked_path_changed`, `pushed_off_path`, `dig_failed_…`, `place_failed_…`). `no_blocks` (nichts zum Bauen im Inventar) |
| `goto_entity` | `target` (Spielername oder Typ wie `cow`), `distance` (1–16, Standard 2) | Läuft zum Spieler oder zum nächsten Wesen dieses Typs im Umkreis von 48 Blöcken, folgt ihm dabei | `entity_not_found`, `target_lost`, `no_path`, `stuck` |
| `follow` | `target`, `distance` (Standard 3), `seconds` (1–600, Standard 120) | Bleibt in der Nähe, bis die Zeit um ist (`ok`) | wie `goto_entity` |
| `mine` | `pos` | Baut den Block mit dem besten vorhandenen Werkzeug ab, läuft vorher in Reichweite, hebt die Beute auf | `no_block`, `unbreakable`, `wrong_tool` (`hint`: was nötig ist), `out_of_reach`, `blocked_unsafe` (`hint`: `would_fall`, `lava_below`, `lava_next_to_block`, `falling_block_above`), `not_broken`, `inventory_full` |
| `collect` | `block` (ID wie `stone` oder Tag wie `#logs`), `count` (1–64, Standard 1), `radius` (1–48, Standard 32), `allow_dig`, `allow_place` (Standard aus) | Sucht die nächsten Blöcke der Art, baut sie ab und sammelt ein, bis die Anzahl erreicht ist. Mit `allow_dig`/`allow_place` gräbt bzw. baut er sich zu Blöcken, die zu Fuß nicht erreichbar sind (Erz im Fels). Bei `ok` kann `mined` kleiner als `requested` sein, wenn keine mehr erreichbar waren, und `gained` kleiner als `mined`, wenn Beute liegen blieb (`items_left`). Bei Erz baut er die angefangene Ader ganz ab, auch über `count` hinaus (`vein_extra`, je Block 400 Ticks mehr Zeit). Nach 8 unerreichbaren Blöcken nacheinander gibt er auf (`stopped_because: unreachable_streak`) | `unknown_block`, `none_found`, `none_reachable`, `wrong_tool`, `unbreakable`, `inventory_full` Bei Holz und Stein erlegt er in der Oberwelt nebenbei Nutztiere bis 16 Blöcke (`animals_killed`). |
| `enter_portal` (Nether-Prüfung) | `force` (Standard false) | Von der Oberwelt in den Nether nur mit einem Goldrüstungsteil und Diamant/Netherit in den drei anderen Plätzen (getragen oder im Inventar); zieht beides vor dem Durchgehen an (Gold zuerst in die Stiefel). `equip_best_armor` tauscht im Nether das einzige Goldteil nicht aus | `not_prepared` mit `missing` |
| `make_obsidian` | `count` (1–64, Standard 10), `radius` (4–24, Standard 16) | Baut liegendes Obsidian ab (nicht neben einem Netherportal), wandelt sonst die nächste Lavaquelle mit freiem Platz daneben um (`bucket` empty water), holt das Wasser zurück (`bucket` fill, 6 Blöcke) und baut ab; bis `count`. Ergebnis `obsidian_gained`, `lava_converted`, `mined`, `last_problem`; bei `no_lava` `nearest_lava` (nächste Lavaquelle bis 64 Blöcke, nur Chunk-Abschnitte mit Lava in der Palette) | `wrong_tool`, `no_water_bucket`, `no_lava`, `lava_unreachable` |
| `farm_endermen` | `count` (1–16, Standard 12), `min_health` (Standard 8) | Tötet Endermen bis 48 Blöcke nacheinander wie `attack` (ansehen lockt an, dann warten und Nahkampf), hebt Perlen auf; ist keiner da, steuert er einen bis 128 Blöcke an oder zieht in Etappen von 40 Blöcken umher (bis 3 min ohne Enderman). An einer Kante geht er zuerst selbst zu einem sicheren Platz bis 20 Blöcke; nach 5 Kämpfen in Folge ohne Kill zieht er selbst 40 Blöcke weiter (höchstens zweimal, dann `stalled`); `killed`, `pearls_gained`, `ender_pearls`, `fights` | `no_enderman`, `unsafe_spot` (kein sicherer Platz bis 20 Blöcke), `stalled`, `no_kills`, `few_blocks`, `not_in_nether`; `cancelled: health_low` |
| `barter` | `count` (1–128, Standard 16) | Tauscht Gold mit Piglins bis 16 Blöcke: wirft jedem freien erwachsenen Piglin einen Barren zu, der nur für ihn reserviert ist (`ItemEntity.setTarget`), sammelt die Ware ein; `gold_given`, `gold_left`, `ender_pearls`, `gained` | `missing_item`, `no_piglin`, `piglins_unreachable`, `piglins_angry` |
| `pickup_items` | `radius` (1–32, Standard 8) | Sammelt herumliegende Items ein; `left_behind` zählt unerreichbare | `inventory_full` |
| `place` | `block` (Item-ID), `pos` | Setzt den Block aus dem Inventar, läuft vorher in Reichweite | `missing_item`, `not_a_block`, `occupied`, `no_support`, `obstructed`, `out_of_reach`, `flammable_near_fire` (Brennbares bis 2 Felder neben Lava oder Feuer; beim Bauen unterwegs nimmt er Brennbares zuletzt), `place_failed` |
| `equip` | `item`, `slot` (`hand`, `offhand`, `head`, `chest`, `legs`, `feet`; Standard `hand`) | Nimmt ein Item in die Hand oder legt es an | `missing_item`, `wrong_slot` |
| `drop` | `item`, `count` (Standard alle) | Wirft Items auf den Boden | `missing_item` |
| `craft` | `item`, `count` (1–256, Standard 1: so viele Stück mindestens) | Stellt das Item nach den Rezepten des Spiels her: kleine Rezepte im eigenen 2×2-Raster, größere an einer Werkbank in der Nähe (läuft hin, auch zu einer gemerkten). Bei `ok` kann `crafted` kleiner als `requested` sein; dann nennt `stopped_because` den Grund und `missing`, was fehlt | `unknown_item`, `no_recipe` (`hint`, falls es aus dem Ofen kommt), `missing_ingredients` (mit `missing`: was und wie viel, und `needs`: Bedarf und Bestand), `no_crafting_table` (mit `has_crafting_table_item`, `can_craft_crafting_table`), `inventory_full`, `craft_failed` |
| `recipe` | `item` | Antwortet sofort: bis zu zwei Rezepte (`makes`, `needs_crafting_table`, `ingredients` mit `have`, `missing`, `can_craft_now`) und die Ofen-Wege (`smelt`: `from`, `have`, `ticks`). Stellt nichts her | `unknown_item`, `no_recipe` |
| `locate` | `structure` (`fortress`, `blaze_spawner`: Mitte des Lohenspawner-Raums der nächsten Festung, `bastion`, `stronghold`, `end_portal`: Mitte des Portalraums der nächsten Festung der Oberwelt, `end_city`) | Antwortet sofort. Nur nach `/aiplayer locate on` (bis zum Neustart des Servers, Standard aus): sucht wie `/locate` über die Platzierungsregeln der Welt die nächste Struktur bis 100 Chunks (1.600 Blöcke) und nennt den nächsten Teil davon (`approx_pos`; bei `fortress` und `stronghold` mit der Höhe des Teils, sonst die des Bots), `distance`, `direction`. Braucht keine Operator-Rechte | `locate_disabled`, `wrong_dimension` (`hint`: wo sie liegt), `not_found` |
| `locate` (Biome) | `structure`: `crimson_forest`, `nether_wastes`, `warped_forest` | Nur im Nether: nächster Punkt des Bioms bis 800 Blöcke (`findClosestBiome3d`), Höhe des Bots; Piglins zum Tauschen, Endermen | `locate_disabled`, `wrong_dimension`, `not_found` |
| `smelt` | `item` (Rohstoff), `count` (1–64, Standard 1), `fuel` | Geht zu einem Ofen in der Nähe (auch einem gemerkten), nimmt Fertiges mit, legt Rohstoff und Brennstoff ein und ist dann fertig (`loaded`, `fuel`, `ready_in_ticks`). Der Ofen läuft allein weiter. Teilerfolg mit `stopped_because` (`not_enough_items`, `not_enough_fuel`, `furnace_full`) | `unknown_item`, `not_smeltable`, `missing_item`, `no_fuel`, `no_furnace` (mit `has_furnace_item`, `can_craft_furnace`), `furnace_busy` (anderer Rohstoff liegt drin), `load_failed` |
| `smelt_collect` | – | Holt das Ergebnis aus dem zuletzt befüllten Ofen (sonst dem nächsten bekannten); `still_cooking` nennt, was noch drin liegt | `no_furnace`, `nothing_ready`, `inventory_full` |
| `place_utility` | `block` (`crafting_table`, `furnace`, `chest`), `near` [x,y,z] | Stellt den Block aus dem Inventar auf einen freien Platz mit festem Boden neben dem Bot (oder nahe `near`), nennt `block_pos` und merkt sich die Stelle | `missing_item` (mit `can_craft`), `no_space`, `out_of_reach` |
| `store` | `item` (ID oder `all`), `count` (Standard alle), `pos` (optional: genau dieser Behälter, jede Art) | Legt Items in die nächste Truhe (läuft hin, auch zu einer gemerkten). Ergebnis: `stored`, `chest`, `chest_contents`, `chest_free_slots`. Teilerfolg mit `stopped_because` (`chest_full`, `not_enough_items`) | `missing_item`, `no_chest` (mit `has_chest_item`, `can_craft_chest`), `chest_full`, `nothing_moved` |
| `take` | `item`, `count` (Standard alle), `pos` (optional, wie bei `store`) | Holt Items aus der nächsten Truhe. Ergebnis: `taken` und der Inhalt danach. Teilerfolg mit `stopped_because` (`inventory_full`, `not_enough_in_chest`) | `not_in_chest`, `no_chest`, `inventory_full`, `nothing_moved` |
| `eat` | `item` (optional) | Isst ein Item aus dem Inventar. Ohne `item` das beste Essen: am meisten Sättigung; Schädliches (verdorbenes Fleisch, Spinnenauge, rohes Huhn) nur, wenn nichts anderes da ist, Essen mit Sonderwirkung (goldener Apfel) erst nach normalem. Ergebnis: `ate`, `left`, `food_before`, `food`, `saturation`, `health`, bei Schädlichem `harmful`. Danach liegt wieder in der Hand, was vorher dort war. Schaden bricht nicht ab | `no_food`, `missing_item`, `not_edible`, `not_hungry` (Hunger ist voll), `interrupted` |
| `cook` | `item` (rohes Essen), `count` (1–64, Standard 1) | Wie `smelt`, nimmt aber nur an, was gegart etwas Essbares ergibt. Endet nach dem Befüllen; `smelting_done` meldet das Ende, `smelt_collect` holt ab | `not_cookable`, sonst wie `smelt`: `unknown_item`, `missing_item`, `no_fuel`, `no_furnace`, `furnace_busy`, `load_failed` |
| `attack` | `target` (Typ wie `zombie` für das nächste Wesen im Umkreis von 32 Blöcken, oder die `id` aus `entity_nearby`), `min_health` (0–19, Standard 6) | Bekämpft das Wesen, bis es tot ist (`ok`, `outcome: killed`). Nimmt die beste Waffe, verfolgt, schlägt nur voll geladen und wenn möglich im Fallen (kritisch). Sonderregeln für Creeper und Schützen siehe unten. Ergebnis: `target` (mit `health`), `hits`, `crits`, `health`, `food`, `weapon` (mit Haltbarkeit), `drops_nearby`. Schaden bricht nicht ab; fällt das Leben auf `min_health`, endet er mit `cancelled: health_low` | `entity_not_found`, `not_attackable`, `target_lost` (verschwunden oder über 48 Blöcke weg), `no_path`, `stuck`, `would_anger_group` (friedlicher Zombie-Piglin: ein Schlag hetzt die Gruppe auf), `in_water` (Bot im Wasser, Schütze weiter als 3 Blöcke) |
| `defend` | `radius` (2–32, Standard 12), `min_health` (Standard 6) | Bekämpft nacheinander alle feindlichen Mobs im Umkreis, den nächsten zuerst; ein Creeper näher als 6 Blöcke hat Vorrang. Neutrale Wesen (Enderman, Zombie-Piglin, Wolf) nur, wenn sie den Bot im Visier haben. `ok` mit `outcome: area_clear` (oder `no_enemies`, wenn von Anfang an keiner da war), `killed` (Typen), `remaining`. `cancelled: health_low` wie bei `attack` | `enemies_unreachable` (mit `remaining`), `in_water` (wie bei `attack`) |
| `flee` | `from` (Typ oder `id`, optional), `distance` (4–64, Standard 16) | Läuft weg, bis der Abstand zu allen Bedrohungen erreicht ist (`ok`, `outcome: fled`, `distance`, `threats`). Ohne `from` vor allen feindlichen Mobs im Umkreis von 24 Blöcken. Weg über die Wegfindung (kein Abgrund, keine Lava), sprintet ab Hunger 7. Schaden bricht nicht ab. Ohne Fluchtweg (dreimal kein Weg) baut oder gräbt er sich ein wie `shelter` mit `force` (`ok`, `outcome: sheltered`, `shelter` mit `exit`) | `entity_not_found`, `no_threat`, `cornered` (kein Weg und kein Unterschlupf, mit `hint` und `shelter_failed`) |
| `shield` | `on` (Standard `true`) | Antwortet sofort. Schaltet den Schild ein (holt ihn dafür aus dem Inventar in die Zweithand) oder aus. Eingeschaltet hebt der Bot ihn nur, wenn ein Schütze (auch Lohe, Ghast) bis 16 Blöcke oder ein anderer Feind bis 6 Blöcke nah ist, sonst trägt er ihn gesenkt; zum Zuschlagen, Schießen und Essen senkt er ihn kurz. Mit gehobenem Schild läuft der Bot so langsam wie ein Spieler | `no_shield` |
| `sprint` | `on` (Standard `true`) | Antwortet sofort. Erlaubt oder verbietet Sprinten auf allen Wegen, bis zum nächsten `sprint` (Standard: an). Sicherheitscheck: keiner nötig, aus ist immer sicher | – |
| `equip_best_armor` | – | Antwortet sofort. Legt je Platz das Teil mit dem höchsten Wert (Rüstung plus Härte) an und einen Schild in die Zweithand. Ergebnis: `changed` (leer, wenn nichts zu verbessern war), `worn`, `armor` (0–20) | `no_armor` |
| `sleep` | – | Geht zu einem Bett im Umkreis von 24 Blöcken und schläft; `ok` mit `slept: true`, sobald Morgen ist. Setzt den Spawnpunkt. Schaden weckt ihn (`cancelled: damage`) | `not_night` (mit `sleep_possible_in_ticks`), `no_bed`, `monsters_nearby` (Monster näher als 8 Blöcke am Bett), `bed_obstructed`, `bed_occupied`, `out_of_reach`, `not_possible_here` (nicht in der Oberwelt), `woken_up`, `night_not_skipped` (ein anderer Spieler ist wach), `sleep_failed` |
| `shelter` | – | Notunterkunft. Mit mindestens 10 Bau-Blöcken eine Hütte um sich herum (`kind: hut`: vier Seiten zwei hoch, Dach; vorhandene Wände zählen mit), sonst gräbt er sich drei Blöcke tief ein und verschließt das Loch (`kind: burrow`). Am Hang setzt er unter eine Wand ohne Halt erst einen Stützblock; geht die Hütte trotzdem nicht, gräbt er sich ein (`hut_failed` nennt den Grund). Ergebnis: `enclosed`, `blocks_placed`, `blocks_dug`, `exit` (wie er wieder herauskommt). Schaden bricht nicht ab | `not_on_ground`, `no_space` (etwas Festes steht im Weg), `obstructed` (ein Wesen steht in der Wand), `no_blocks` (mit `blocks_needed`, `blocks_available`), `unsafe_ground` (Eingraben ginge in Höhle, Wasser oder losen Sand), `in_water`, `no_pickaxe` (ohne Spitzhacke käme er aus Stein nicht heraus) (im Wasser baut er nicht, er würde ertrinken), `dig_failed`, `place_failed` |
| `descend` | `to_y` (−64 bis 320) | Gräbt senkrecht nach unten bis zur Zielhöhe, ein Befehl statt eines `mine` pro Block. Vor jedem Block prüft er den Block, die vier daneben und zwei darunter auf Lava und Wasser darunter auf mehr als 3 Felder Luft und bis 3 Felder über dem Kopf auf losen Kies oder Sand; dann hält er vorher an und nennt den Fund (`found`). Ergebnis: `blocks_dug`, `gained` | `invalid_args` (schon tief genug), `not_on_ground`, `lava_below`, `fluid_below`, `cave_below`, `falling_block_above`, `in_water` (schwimmt 5 s ohne Boden), `dig_failed`, `wrong_tool` |
| `seal_water` | `radius` (1–4, Standard 2) | Setzt Bau-Blöcke in jedes erreichbare Wasserfeld im Umkreis, Quellen zuerst, das eigene Feld ausgenommen; wartet danach kurz, bis Fließwasser abgelaufen ist. Steht er selbst noch im Wasser, baut er seitlich Kopf- und Fußblock ab, tritt hinein und baut die alten Felder zu (höchstens zweimal). Ergebnis: `blocks_placed`, `water_left`, `in_water`, `blocks_left`. Ertrinken bricht nicht ab | `no_water`, `no_blocks`, `nothing_sealed` (mit `hint`), `step_out_failed` |
| `seal_lava` | `radius` (1–5, Standard 3) | Wie `seal_water`, für Lava: setzt nicht brennbare Bau-Blöcke in Lavafelder, Quellen zuerst; bricht nicht wegen Lava ab | `no_lava`, `no_blocks`, `nothing_sealed` |
| `enclose_spawner` | – | Geht zum nächsten Spawner (Umkreis 16) und baut einen Raum: Ring in 2 Blöcken Abstand auf Spawnerhöhe und darüber, Dach eine Ebene höher (5 x 5), Boden unter Lücken. Der Bot steht innen; Lohen erscheinen im Raum. Ergebnis: `spawner`, `blocks_placed`, `gaps_left` | `no_spawner`, `out_of_reach`, `no_blocks`, `not_enclosed` (mit `gaps_left`) |
| `build_portal` | – | Sucht bis 8 Blöcke weit einen Platz (4 × 5 Felder, fester Boden, Standplatz 2 Felder davor; freie Plätze zuerst, sonst gräbt er Netherrack und Stein frei, nie Festungsblöcke, `blocks_dug`), setzt 10 Obsidian und die 4 Ecken aus Bausteinen, zündet mit Feuerzeug oder Feuerkugel an. Ergebnis: `portal_pos`, `portal_open`, `blocks_placed` | `missing_obsidian`, `no_igniter`, `no_blocks`, `no_space`, `out_of_reach`, `place_failed`, `not_lit` |
| `farm_spawner` | `item` (Standard `blaze_rod`), `count` (1–64, Standard 10), `min_health` (Standard 12), `seconds` (10–1800, Standard 600) | Geht zum nächsten Spawner (Umkreis 24, ±12 in der Höhe), bekämpft jeden Feind bis 12 Blöcke (nicht weiter als 16 vom Spawner; läuft ein Ziel weiter weg, lässt er es: Leine), Lohen in Reichweite mit dem Schwert, nur höher als 2,5 oder weiter als 6 Blöcke oder nach gescheitertem Nahkampf mit Bogen und Pfeilen (je Salve 4 Schüsse), löscht Feuer am Boden (in Armreichweite sofort, in ruhigen Momenten geht er bis 8 Blöcke hin; im Kampf nur an den Füßen; `fires_out`), hebt nach jedem Kill die Beute auf (8 Blöcke) und kehrt bis 3 Blöcke an den Spawner zurück. Endet mit `ok`, wenn `count` Stück im Inventar sind (`outcome: enough`) oder die Zeit um ist (`time_up`). Schaden und Feuer brechen nicht ab, `cancelled: health_low` bei `min_health`. Ergebnis: `spawner`, `have`, `gained`, `killed` (je Typ), `hits`, `seconds` | `no_spawner`, `out_of_reach` |
| `tunnel` | `direction` (`north`, `south`, `east`, `west`), `length` (1–200, Standard 16), `stop_at_ore` (Standard `true`) | Gräbt einen Gang 1 breit und 2 hoch geradeaus, oberen Block zuerst (Kies fällt nicht auf ihn). Vor jedem Schritt prüft er den Abschnitt, seine Wände, Decke, Boden und einen Block weiter auf Lava und Wasser. Löcher im Boden überbrückt er mit einem Block (Brennbares zuletzt). Ergebnis: `length_done`, `blocks_dug`, `blocks_placed`, `ores_seen` (Erz und Antiker Schutt bis 2 Blöcke um den Gang), `gained`; neu entdecktes Erz beendet ihn mit `ok` und `stopped_because: ore_found`. Festungsblöcke (Netherziegel und alles daraus, nur im Nether) baut er nie ab, auch `goto` mit `allow_dig` nicht: Vor ihnen oder beim Betreten einer Festung endet er mit `ok` und `stopped_because: fortress_reached` (`fortress_block`); in einer Festung begonnen endet er an ihrer Wand (`fortress_wall`) oder beim Verlassen (`left_fortress`) | `not_on_ground`, `lava_ahead`, `fluid_ahead` (mit `found`), `no_floor`, `wrong_tool`, `unbreakable`, `dig_failed`, `stuck`, `tool_worn` (beste Spitzhacke unter 50 Nutzen, hält vorher an); auf Brücken über einem Abgrund setzt er ab 96 Bausteinen ein Geländer |
| `shoot` | `target` (Typ oder `id`, auch `end_crystal`, `ender_dragon`), `shots` (1–32, Standard 5) | Bogen voll spannen (22 Ticks), mit Vorhalt zielen (Pfeil etwa 3 Blöcke je Tick, Fall 0,05 je Tick²), schießen; beim Drachen auf den Kopf. Endet, wenn das Ziel weg ist (`outcome: destroyed`) oder nach `shots`. Ergebnis: `target`, `damage_dealt`, `arrows_shot`, `arrows_left`. Schaden bricht nicht ab | `entity_not_found`, `no_bow`, `no_arrows`, `no_line_of_sight`, `cannot_draw` (Bogen ließ sich nicht spannen oder drei Schüsse ohne Pfeil). Ein gehobener Schild wird fürs Schießen gesenkt und danach wieder gehoben. Nach 3 Fehlschüssen in Folge (Leben des Ziels unverändert) auf ein Lebewesen bis 14 Blöcke wechselt er zum Schwert (`switched_to_melee`, `outcome: killed_melee`, sonst die Fehlergründe von `attack`) |
| `throw_eye` | – | Wirft ein Enderauge (Oberwelt) und verfolgt das `EyeOfEnder` bis 100 Ticks. Ergebnis: `from`, `ended_at`, `direction`, `yaw`, `descending` (abwärts geflogen: Festung bis etwa 12 Blöcke), `eye_dropped`; mit einem früheren Wurf in mindestens 4 Grad anderer Richtung `estimated_stronghold` (Schnittpunkt der Geraden) | `wrong_dimension`, `no_eye`, `no_stronghold` | Mit freigeschaltetem `locate` lehnt er ab (`use_locate`), außer mit `force`.
| `fill_portal` | – | Setzt Enderaugen in alle leeren Endportalrahmen im Umkreis 16, läuft jeweils in Reichweite. Ergebnis: `filled_now`, `frames`, `frames_with_eye`, `eyes_needed`, `eyes_left`, `portal_open` | `no_frames`, `no_eye`, `out_of_reach` |
| `enter_portal` | – | Geht zum nächsten Nether- oder Endportal (Umkreis 16), erst an den Rand (2,5 Blöcke), dann ohne Wegfindung auf die Mitte, bis die Dimension wechselt (Netherportal: bis 8 s). Ergebnis: `dim`, `portal` | `no_portal`, `out_of_reach`, `not_teleported` |
| `light` | `pos` (optional) | Setzt eine Fackel neben den Bot oder an `pos` (auch an eine Wand; läuft in Reichweite). Ohne Fackel craftet er vier aus Kohle oder Holzkohle und einem Stock (`crafted`). Ergebnis: `block_pos`, `torches_left` | `no_torch` (mit `missing`), `no_space`, `occupied`, `no_support`, `out_of_reach`, `obstructed`, `place_failed` |
| `bucket` | `action` (`fill`, `empty`), `fluid` (`lava`, `water`), `pos`, `radius` (1–32, Standard 16), `anywhere` | Füllt einen leeren Eimer an einer Quelle oder gießt einen vollen aus, wie ein Spieler (hinschauen, rechtsklicken), läuft vorher in Reichweite. `fill` ohne `pos` sucht die nächsten Quellen mit freier Seite und probiert bis zu 6. `empty` setzt an der Seite eines festen Nachbarblocks an; Ist bei Wasser `pos` eine Lavaquelle, gießt er in das freie Feld darüber (sonst daneben): Die Quelle wird zu Obsidian (`lava_pos`, `lava_now`). Direkt in die Quelle gegossen würde das Wasser die Lava nur ersetzen. Ergebnis: `block_pos`, `now_there`, `buckets`, `water_buckets`, `lava_buckets`. Neue Lava bricht nicht ab (`cancelled: danger` gibt es hier nicht). Nennt `pos` bei `empty` einen festen Block, landet die Flüssigkeit auf seiner Oberseite. Sicherheitsnetz für Lava: nie höher als die Füße, nie in die eigene Spalte, auf gleicher Höhe mindestens 4 Blöcke Abstand; einen solchen Standplatz sucht er sich selbst | `no_lava_here` (Wasser ohne Lava an oder neben `pos`; mit `anywhere: true` erlaubt), `no_bucket`, `no_filled_bucket`, `not_a_source` (fließend oder keine Flüssigkeit), `none_found`, `none_reachable`, `out_of_reach`, `obstructed` (Sicht verdeckt), `occupied`, `no_support`, `unsafe`, `pour_failed` |
| `use` | `item` (optional, sonst leere Hand), `pos` oder `entity` (Typ oder `id`) oder keins, `face`, `look_at`, `sneak` | Rechtsklick wie ein Spieler. Auf einen Block (`useItemOn`: Feuerzeug, Tür, Hebel, Knopf, Zauntor, Knochenmehl, Hacke, Samen, Enderauge in den Rahmen, Behälter), auf ein Wesen (`interactOn`: melken, scheren, füttern, besteigen) oder in die Luft (`useItem`: Enderperle, Enderauge, Bogen, Trank). Items mit Benutzdauer hält er gedrückt (Bogen und Dreizack 25 Ticks, dann Schuss; sonst bis fertig, höchstens 100 Ticks). Geht ein Fenster auf, nennt er `opened` und `contents` und schließt es. Ergebnis: `block_before`/`after`, `beside_before`/`after` (Feld vor der Seite), `gained`, `used_up`, `riding`. Sicherheitsnetz: kein Feuer im eigenen oder einem angrenzenden Feld (außer an Obsidian, für das Portal), Lavaeimer nur über `bucket` | `missing_item`, `use_bucket_command`, `entity_not_found`, `target_lost`, `out_of_reach`, `unsafe`, `nothing_to_use`, `nothing_happened` (nichts angenommen, nichts verändert) |
| `inventory` | – | Das ganze Inventar mit Slots, Rüstung und freien Plätzen | – |
| jeder andere | | | `unknown_command` |

`mine`, `collect`, `place`, `equip` und `drop` nennen im Ergebnis `held_item`: das Item in der Hand mit
Anzahl und, bei Werkzeug, `durability` (`left`, `max`). `mine`, `collect` und `pickup_items` nennen
`gained`: was neu ins Inventar kam. Fällt ein Werkzeug in der Hand auf höchstens 10 % Haltbarkeit,
kommt einmal das Event `tool_low_durability`.

### Abbauen, Setzen, Sammeln

- **Wie ein Spieler:** Abgebaut wird über denselben Weg wie bei einem Client (anfangen, die nötige
  Zeit halten, loslassen); der Server prüft Reichweite und Dauer. Gesetzt wird per Klick auf die
  Seite eines Nachbarblocks. Der Bot kann also nichts, was ein Spieler nicht könnte, mit einer
  Ausnahme: Er braucht keine freie Sicht auf den Block.
- **Werkzeugwahl:** das schnellste Werkzeug im Inventar, das dem Block auch etwas entlockt. Gibt es
  keins und der Block verlangt eins, kommt `wrong_tool`, bevor der Bot losläuft.
- **Sicherheitsnetz beim Abbauen** (`BlockBreaker`): kein Block, an den Lava grenzt; der Block unter
  den eigenen Füßen nur, wenn innerhalb von 3 Blöcken darunter fester Boden oder Wasser ist; kein
  Block über dem Kopf, auf dem Kies oder Sand liegt.
- **Hohe Bäume** (`collect`): Erst vom Boden aus, was erreichbar ist. Für den Rest stellt sich der Bot
  darunter, baut sich mit einfachen Blöcken aus dem Inventar hoch (meist den gerade geernteten
  Stämmen, höchstens 8 hoch), räumt Laub über dem Kopf weg und baut den Turm am Ende wieder ab.
  Bleibt der Turm stehen (Abbruch von außen), sagt das Ergebnis `standing_on_own_pillar`.
- **Einsammeln** (`ItemGatherer`): Der Bot stellt sich so, dass das Spiel das Item aufhebt (bis einen
  Block daneben, auch schräg, und bis zwei Blöcke über den Füßen); das Item muss nicht betretbar
  liegen. Findet er keinen freien Weg, etwa weil Laub davor ist, darf er sich in einem zweiten
  Versuch durchgraben. `collect` sucht am Ende noch einmal um jede Abbaustelle. Bleibt trotzdem
  etwas liegen, nennt das Ergebnis `items_left` und `items_left_reason`.
- **Unterwegs graben und bauen** (`goto` mit `allow_dig`/`allow_place`): Die Suche plant solche
  Schritte mit hohem Preis ein, ein kurzer Umweg ist also billiger. Gegraben wird nur, was sich
  gefahrlos abbauen lässt: nichts Unzerstörbares oder sehr Hartes, keine Truhen, nichts neben
  Wasser oder Lava, nichts unter Kies. Gebaut wird mit vollen, einfachen Blöcken (keine Erze,
  kein Sand). Die Suche weiß nicht, welches Werkzeug da ist: Fehlt die Spitzhacke für Stein,
  endet der Weg mit `stuck` und `hint: dig_failed_wrong_tool`.

### Crafting, Schmelzen, Truhen (4c)

- **Rezepte aus dem Spiel** (`Recipes`): Der Mod liest die Rezepte des laufenden Servers und trägt
  nichts fest ein; Datenpakete und Tags wirken mit. Zutaten heißen wie im Spiel: ein Tag steht als
  `#minecraft:planks` (jede Holzart zählt), dazu ein paar Beispiele in `any_of`. Gibt es mehrere
  Rezepte, nimmt `craft` das, was jetzt herstellbar ist, sonst das ohne Werkbank, sonst das, bei
  dem am wenigsten fehlt.
- **Wie ein Spieler** (`Menus`): Gecraftet wird über das Fenster des Spiels (Rezept ins Raster
  legen, Ergebnis per Umschalt-Klick nehmen), ein Durchgang pro Tick. Ofen und Truhe bedient der
  Bot mit denselben Klicks wie ein Client; der Server prüft sie.
- **Arbeitsblöcke** (`Station`): `craft`, `smelt`, `store` und `take` suchen Werkbank, Ofen oder
  Truhe im Umkreis von 16 Blöcken, nehmen die gemerkten im Umkreis von 64 dazu und laufen zum
  nächsten (höchstens drei Versuche). Aufgestellt wird nie von selbst: Fehlt der Block, sagt das
  Ergebnis, ob einer im Inventar liegt oder gecraftet werden könnte; `place_utility` stellt ihn auf.
- **Schmelzen blockiert nicht:** `smelt` endet nach dem Befüllen. `FurnaceWatch` sieht einmal pro
  Sekunde nach den befüllten Öfen und meldet einmal `smelting_done` (`furnace`, `ready`,
  `complete`; bei leerem Brennstoff `complete: false` mit `unsmelted`). Ein Ofen arbeitet nur,
  solange sein Chunk geladen ist. Die Liste der befüllten Öfen gilt bis zum Neustart der Welt;
  danach findet `smelt_collect` sie über das Ortsgedächtnis.
- **Brennstoff:** der verlangte, sonst Kohle, Holzkohle, Bretter, Stämme, anderes Brennbares; nie
  Werkzeug und nie der Rohstoff selbst. Reicht der bevorzugte Vorrat nicht für die ganze Menge, nimmt er
  den nächsten, der reicht (eine Kohle neben 30 Brettern: Bretter); reicht keiner, den mit der
  längsten Brenndauer. Eingelegt wird nur, was die Menge braucht (eine Kohle für
  bis zu 8 Stück); was schon im Ofen liegt, zählt mit.
- **Ortsgedächtnis** (`Places`): Werkbänke, Öfen und Truhen, die irgendein Spieler setzt oder die
  der Bot bei einer Suche sieht, stehen mit Position und Dimension in `aiplayer/places.json` im
  Weltordner und überstehen Neustarts. Ausgetragen wird, wenn ein Spieler den Block abbaut oder
  der Bot beim Hinlaufen feststellt, dass er fehlt (Explosion, Kolben, Befehl).
- **Grenzen:** Nur der normale Ofen (kein Schmelzofen, kein Räucherofen), nur einfache Truhen
  (keine Fässer, Shulker-Kisten). Rezepte mit Sonderlogik (Feuerwerk, Färben von Rüstung, Karten
  kopieren) kennt `craft` nicht.

- **Wichtige Orte (Etappe 6)**: `Places` merkt sich auch Gebiete, je Art ein Eintrag pro Umkreis: Baumstellen (32),
  Netherportale (16), Lavaseen ab 10 Quellen (48), Spawner (16), Netherfestungen (96), Dörfer (96), Endportalräume (64),
  dazu Betten als Block. Eingetragen vom Rundum-Blick (`FluidWatch`), sobald der Bot sie sieht; Abbauen trägt Gebiete nicht aus.
  `look` meldet `known_places` (je Art der nächste in der Dimension bis 2000 Blöcke), der Befehl `places` listet alle.
- **Zwischenprodukte**: `craft` stellt fehlende Zutaten mit eigenem Rezept vorher her (bis 3 Stufen, höchstens 8 Schritte,
  Ergebnis `intermediates`). Varianten eines Tags: zuerst eine, die sofort geht, sonst die mit dem kleinsten Rest.
- **Sichtlinie**: `BlockBreaker` baut nur ab, was ein Strahl von den Augen (zur Mitte oder einer Seitenmitte) als ersten
  Block trifft, sonst `no_line_of_sight` mit dem Block davor. `mine` und `collect` mit `allow_dig` bauen diesen zuerst ab
  (höchstens 4).

### Überleben und Kampf (4d)

- **Kampf** (`Fighter`, genutzt von `attack` und `defend`): beste Waffe nach Schaden mal Tempo
  (Schwert vor Axt), nur volle Schläge (Abklingzeit des Spiels), Absprung kurz vor dem vollen
  Schlag und Treffer im Fallen für den kritischen Treffer. Bis 10 Blöcke mit Sicht steuert der Bot
  direkt auf das Ziel zu und prüft jeden Schritt auf Abgrund, Lava und Wasser (`Combat.safeStep`);
  weiter weg oder ohne Sicht übernimmt die Wegsuche.
  - **Creeper:** nur mit geladenem Schlag heran, im Sprint treffen (mehr Rückstoß), dann zurück, bis
    er nicht mehr zischt (über 7 Blöcke). Kein Sprung-Krit, weil der Sprung den Rückzug verzögert.
    Mit Schild in der Zweithand weicht er dem Creeper zugewandt zurück und blockt die Explosion.
  - **Skelett und andere Schützen:** Annäherung im Zickzack (Seitenwechsel jede halbe Sekunde).
    Zwischen den Schlägen hebt er den Schild, wenn einer in der Zweithand liegt.
  - **Enderman:** `GazeGuard` senkt bei jeder Aktion den Blick, sobald er einen Enderman träfe
    (gleiche Rechnung wie im Spiel, etwas großzügiger). Nur `attack` auf einen Enderman darf hinsehen.
- **Schaden bricht nicht jede Aktion ab:** `attack`, `defend`, `flee`, `eat` und `shelter` laufen
  weiter, wenn der Bot getroffen wird (`Action.cancelOnDamage`). Ertrinken, Verhungern, Brennen (`on_fire`, `in_fire`), Lava und Magma unter
  den Füßen (`hot_floor`) brechen gar keine Aktion ab: Sie treffen jede Sekunde erneut, und nur die laufende
  Aktion bringt den Bot heraus. Steht der Bot auf Magma, plant die Wegfindung (`PathFollower`) bis zum nächsten
  Start Magma als Boden, sonst gäbe es kein Startfeld (`no_start`). Dafür enden `attack` und `defend`
  mit `cancelled: health_low`, sobald das Leben auf `min_health` fällt. Lava und Feuer brechen
  weiterhin alles ab.
- **Essen** (`Eater`): wie ein Spieler, also Item in die Hand, 1,6 Sekunden kauen, Hand zurück.
  Ob ein Essen schadet, liest der Mod aus dessen Wirkungen (jede nicht nützliche Wirkung oder ein
  Teleport), nicht aus einer Liste.
- **Reflexe** (`Reflexes`, laufen ohne Befehl, um die Aktion des Ticks herum):
  - *Notfall-Essen:* Leben 4 oder weniger, unschädliches Essen im Inventar, Hunger nicht voll. Der
    Bot isst, eine laufende Aktion wartet so lange, danach kommt `reflex_ate`. Frühestens alle
    10 Sekunden. Sonst isst der Mod nie von selbst.
  - *Luft holen* (`AirSeeker`): Ist unter Wasser die halbe Atemluft verbraucht, sucht der Bot mit
    einer eigenen kleinen Suche den nächsten Ort mit Luft, auch seitlich unter einer Decke hindurch,
    und schwimmt hin; danach kommt `reflex_surfaced`. Die Wegfindung selbst plant seit 4d keine Wege
    mehr mit dem Kopf unter Wasser (geschwommen wird an der Oberfläche), weil sie keine Atemluft
    einrechnet. Im Parcours war der Bot vorher beim Graben zum Erz zweimal ertrunken.
  - *Blickschutz* gegen Endermen (siehe oben).
  - *Schild oben halten* nach `shield`, und das Bremsen beim Essen und Blocken, das sonst der
    Client übernimmt.
- **Unterschlupf:** Die Hütte hat 3×3 Grundfläche und innen ein Feld; die Ecken bleiben offen, weil
  diagonal nichts durchkommt (10 Blöcke statt 18). Blumen und Ähnliches räumt er vorher weg. Zum
  Eingraben prüft er vorher, dass die Wände des Lochs dicht sind und kein Wasser, keine Lava und
  kein loser Sand angrenzen; den Deckel nimmt er aus dem Aushub.
- **`collect` mit `allow_dig`/`allow_place`:** Kommt der Bot zu Fuß nicht an den Block, gräbt er
  sich hin bzw. überbrückt Lücken (`dug_on_the_way`, `placed_on_the_way`). Damit erreicht er Erz
  im Fels. Ohne die Schalter bleibt es bei `none_reachable`. Mit Graben geht er dicht an den Block
  heran (sonst fiele die Beute in einen Hohlraum im Fels) und hört vor dem Zeitlimit selbst auf
  (`ok` mit `stopped_because: time_limit`). Erz unter Wasser oder neben Wasser und Lava lässt er
  liegen; im Parcours fand er je nach Gelände 0 bis 27 der 27 Erze.
- **Grenzen:** Kein Fernkampf (Bogen), kein Ausweichen vor einzelnen Pfeilen, keine Taktik gegen
  mehrere Gegner zugleich außer „einen nach dem anderen“. `flee` sucht Abstand, kein bestimmtes
  sicheres Ziel. `sleep` überspringt die Nacht nur,
  wenn kein anderer Spieler wach ist (Zuschauer zählen nicht).

### Wegfindung

- **Türen:** Holztüren und Zauntore zählen für die Suche als offen (`Doors`). Steht eine geschlossene im nächsten Feld, öffnet der Bot sie wie ein Spieler (Rechtsklick) und geht weiter. Eisentüren bleiben Hindernis.
- **Sprinten:** Auf geraden, ebenen Stücken (die nächsten 3 Felder in gleicher Richtung und Höhe), an Land, ohne Sprung und ab Hunger 7 sprintet der Bot.
- **Suche:** A* auf Blockebene (`PathSearch`), getrennt von den Spielklassen und mit Unit-Tests.
  Die Bewegungsregeln stehen an einer Stelle (`Moves`).
- **Zeitbudget:** Die Suche bekommt 2 ms pro Tick und sieht nach jedem untersuchten Feld auf die
  Uhr. Gemessen über die Testparcours: meist unter 3 ms pro Tick, einzelne Ausreißer bis rund 5 ms
  (Pausen der Java-Laufzeit, die sich im Server-Thread nicht ganz vermeiden lassen).
- **Unerreichbare Ziele:** Kommt die Suche 3.000 Felder lang dem Ziel nicht näher, gilt es als
  unerreichbar (`search_stalled`); das dauert unter einer Sekunde. Das Ergebnis nennt mit `closest`
  das erreichbare Feld, das dem Ziel am nächsten liegt.
  Mit `allow_dig` oder `allow_place` folgt vorher ein zweiter Versuch, der direkt aufs Ziel zugeht (gewichtetes A*,
  Gewicht 20, `PathSearch.setWeight`): Umbauten kosten 8 je Block, die normale Suche lief deshalb erst alles ab, was sich
  billiger erlaufen lässt, und fand Ziele senkrecht über dem Bot oder hinter einer Wand nicht.
- **Weite Ziele:** Nach 10.000 Feldern läuft der Bot das beste Teilstück und sucht von dort neu,
  solange jedes Teilstück mindestens 4 Blöcke näher heranführt.
- **Steuerung:** `PathFollower` (Paket `skill`) setzt wie ein Spieler nur Blickrichtung, „vorwärts“ und „springen“;
  Physik und Kollision rechnet das Spiel.
- **Sicherheitsnetz:** Die nächsten zwei Schritte werden jeden Tick neu geprüft (die Welt kann sich
  geändert haben). Führt ein Schritt abseits des Wegs ins Leere oder in Lava, bleibt der Bot stehen
  und sucht neu. Ungeladene Chunks betritt die Suche nicht.
- **Steckt fest:** 40 Ticks ohne Fortschritt lösen eine neue Suche aus, nach drei Versuchen ohne
  erreichten Wegpunkt endet die Aktion mit `stuck`.
- **Teilblöcke:** Niedrige Blöcke mit voller Grundfläche (untere Platten, Teppich, Schneeschichten,
  Betten) sind begehbar; der Bot steht dann innerhalb des Feldes. Treppen, obere Platten, Ackerboden
  und Trampelpfade zählen als Boden. Halbe Blöcke nimmt der Bot im Laufen, erst darüber springt er.
- **Noch Hindernisse:** Zäune, Zauntore, Mauern, Türen, Falltüren, Glasscheiben und Truhen werden
  umgangen. Türen und Zauntore öffnen kann der Bot noch nicht.

### Tests

Die Servertests starten den Testserver ohne Fenster und steuern ihn über Konsole und Brücke
(`tests/server/harness.py`). Sie nutzen einen eigenen Port (25598), damit ein gleichzeitig
laufendes Spiel keine Testbefehle bekommt.

| Test | Was er prüft | Stand |
|---|---|---|
| `test_movement.py` | Flache Welt: Mauer, Stufen, Abstieg, Turm, Lavagraben, Wassergraben, Leiter, Platten, Treppe, unerreichbare Ziele, Stopp, Verdrängen, Unterbrechung durch Schaden, `goto_entity`, `follow` | 22 von 22 |
| `test_blocks.py` | Flache Welt: `inventory`, `equip`, `drop`, `pickup_items`, `place`, `mine` mit Werkzeugwahl und Sicherheitsnetz, `tool_low_durability`, `collect`, hoher Baum, `goto` mit `allow_dig` und `allow_place` | 34 von 34 (mit `collect` und `allow_dig`) |
| `test_craft.py` | Flache Welt: `recipe`, `craft` mit Fehlergründen und Teilerfolg, `place_utility`, `smelt` mit Brennstoffwahl, `smelting_done`, `smelt_collect`, `store`, `take`, Ortsgedächtnis; dazu dreimal der Parcours vom leeren Inventar zur Eisenspitzhacke | 32 von 32, Parcours 3 von 3 |
| `test_agent.py` | KI-Schleife ohne API (ein Platzhalter spielt das Modell): Tools aus den Schemas, `chat`, `remember`, `wait`, Fehler mit `is_error`, Kostenrechnung und Kostenlimit, `/aiplayer goal` und `/aiplayer stop`, Chat im Leerlauf und während einer Aktion | 16 Prüfungen (dazu Herzschlag am Ende und im Leerlauf) |
| `test_magma.py` | Flache Welt: `goto` aus einem Magmafeld, `flee` von einem Magmablock, Lava-Reflex (`reflex_left_lava`) aus einem Becken | 3 von 3 |
| `test_portal.py` | Flache Welt: `build_portal` ohne Obsidian (`missing_obsidian`), mit allem (Portal brennt), im Fels (gräbt frei); `farm_spawner` mit vollem Inventar wirft Netherrack weg | 4 von 4 |
| `test_farm.py` | Flache Welt: `farm_spawner` ohne Spawner, gegen 3 Zombies (alle tot, bleibt am Spawner), schwebende Lohe mit dem Bogen, Lohe in Reichweite mit dem Schwert, Feuer löschen, `enough` bei vollem Bestand | 6 von 6 |
| `test_fortress.py` | Nether: `tunnel` hält vor Netherziegeln (`fortress_reached`), `goto` mit `allow_dig` lässt eine Ziegelwand heil, `locate` erst nach `/aiplayer locate on`, `look` meldet `in_fortress` in einer echten Festung | 5 von 5 |
| `test_tools.py` | Flache Welt: Obsidian aus Wasser und Lava (Wasser zurück, abbauen), Lava schöpfen und sicher ausgießen, Netherportal anzünden, Feuer neben sich, Tür, Kuh melken, Fass mit `store`/`take`/`use`, Bretter neben Lava, Zombie-Piglin, `tunnel` (Erz, Lava), Sprinten, `fortress_in_sight` im Nether | 26 von 26 |
| `test_equip.py` | `equip` und `tool_low_durability`, eingegeben wie in der Konsole | 7 Prüfungen |
| `test_survival.py` | Flache Welt: `eat` mit Auswahl und Fehlergründen, `hunger_low`, `health_low`, Notfall-Reflex, `cook`, `equip_best_armor`, `shield` gegen Pfeile, `attack` auf Kuh, Zombie, Creeper (viermal), Skelett, per id, Abbruch bei wenig Leben, `flee`, `defend` gegen drei Gegner, Blickschutz vor dem Enderman, `night_falling`, `sleep`, `shelter` als Hütte und eingegraben, `light` | 51 von 51 |
| `test_fixes.py` | Flache Welt: Fehler aus dem ersten echten KI-Lauf: `shelter` am Hang und mit Eingraben als Ausweg, `collect` mit ganzer Erzader und Aufgabe nach 8 Unerreichbaren, `attack` mit `nearest` und Aufgabe hinter Glas, Bett-Rezept, Werkbank nicht weit anlaufen, `night_falling` mit `underground`, `look` mit `world`/`seed`, `descend` (Ziel, Lava, Hohlraum), `shelter` im Wasser, `goto` aus tiefem Wasser, `descend` unter schwebendem Kies | 17 von 17 |
| `test_course.py` | Normale Welt mit festem Seed (`run/world_course`): vom leeren Inventar über Holz, Stein, Ofen, Jagd, Eisen, Eisenrüstung durch eine ganze Nacht mit Zombie, Skelett und Creeper. Wo das Skript nachhilft, steht im Kopf der Datei | 2 von 3 Läufen (Serie davor 1 von 3, zweimal ertrunken; seit der Korrektur einmal von einem Creeper gesprengt, unbewaffnet beim Holzsammeln) |
| `test_forest.py` | Normale Welt mit festem Seed (`run/world_forest`): an mehreren Stellen im Wald 8 Stämme sammeln und danach wieder auf dem Boden stehen | 5 von 5 |

## KI-Anbindung (Etappe 5)

Die KI läuft im Begleitprozess `companion/agent.py`; im Mod gibt es weiterhin keinen API-Aufruf und
keinen Schlüssel. Der Mod trägt nur vier Dinge bei: den Befehl `chat`, das Event `chat_received`,
die Spielbefehle `/aiplayer goal <text> | pause | resume | stop` und die Events `goal_requested`
und `control_requested`, mit denen er sie weiterreicht. Ist kein Begleitprozess verbunden, sagt der
Spielbefehl das, statt das Ziel zu verschlucken.

- **Schleife:** Pro Ziel ein Gespräch mit dem Modell (Messages API mit Tool Use, offizielles
  Python-SDK `anthropic`). Das Modell wählt Tools, der Begleitprozess schickt sie als Befehle an den
  Mod und gibt `status`, `reason` und `data` des Ergebnisses zurück; ein `failed` geht als
  `is_error` zurück. Antwortet das Modell ohne Tool, ist das Ziel beendet.
- **Tools:** jeder Befehl aus `schemas/commands/` (außer `ping`), Beschreibung und Argumente direkt
  aus dem Schema, dazu die Fehlergründe. Zwei Tools laufen nur im Begleitprozess: `remember`
  (Notizblock pro Welt in `companion/memory/<welt>_<seed>/notes.json`, Kennung aus `world` und `seed` von `look`, höchstens 30 Notizen, steht am Anfang jedes Ziels)
  und `wait` (bis zu 120 s warten; endet früher bei `smelting_done`, Schaden oder Gegner).
- **Was das Modell sieht:** am Anfang das Ziel, die Notizen und einen `look`; danach nur die
  Ergebnisse. Events, die während eines Befehls eintreffen, hängen gekürzt am Ergebnis (`events`):
  nur Schaden, Gefahr, Gegner, Leben, Hunger, Nacht, Tod, Reflexe, Ofen, Werkzeug; von
  `entity_nearby` nur der letzte Stand, höchstens zehn.
- **Gefahr während einer Aktion:** Meldet der Mod `health_low` oder ein `entity_nearby` mit hoher
  Priorität (Gegner näher als 6 Blöcke), schickt der Begleitprozess `stop` und gibt das abgebrochene
  Ergebnis mit `interrupted_by` zurück. Das Modell entscheidet dann über Kampf oder Flucht. Nicht
  angehalten werden `attack`, `defend`, `flee`, `shelter`, `eat`, `shield` und alles ohne Dauer.
  Derselbe Gegner (Mob-ID) hält 45 s lang keine weitere Aktion und kein `wait` an, sonst käme der
  Bot eingebaut zwischen Mobs nie voran. Der Mod selbst hat dafür weiterhin keinen Reflex.
- **Kosten:** aus den gemeldeten Tokens je Antwort berechnet (Preistabelle im Skript, Cache-Lesen
  10 %, Cache-Schreiben 125 % der Eingabe). Ein Kostenlimit gibt es standardmäßig nicht. Ist
  `AIPLAYER_COST_LIMIT` gesetzt, wird es vor jedem Aufruf geprüft; überschritten wird es also
  höchstens um einen Aufruf. Unabhängig davon gilt eine Obergrenze von 200 Schritten. Systemprompt und Tools stehen vorn und ändern sich nicht, damit der Prompt-Cache greift.
- **Herzschlag:** Alle 15 Schritte (`AIPLAYER_HEARTBEAT_STEPS`) hängt am Ergebnis ein Text, der an
  das Ziel erinnert. Nach 4 Schritten nur `wait`/`look`/`inventory` kommt ein Anstoß (Warten heilt nur
  ab Hunger 18; `chat` und `remember` zählen dabei nicht als Arbeit). Will das Modell ein Ziel
  beenden, fragt der Prozess bis zu dreimal nach, ob es wirklich erreicht ist. Stirbt der Bot und will
  das Modell gleich danach aufhören, schickt er es zurück ans Ziel; nach 3 Schritten gilt ein Tod als
  verarbeitet (Gespräche ausgenommen).
- **Suche:** Findet `attack` nichts im Umkreis von 32, nennt der Mod das nächste passende Wesen bis
  128 Blöcke (`nearest`), und der Begleitprozess hängt die bisher erfolglosen Suchorte an
  (`already_searched`).
- **Steuerung:** `pause` hält die Schleife nach dem laufenden Befehl an (der Mod bricht ihn ab),
  `resume` macht weiter, `stop` und ein neues `goal` beenden das laufende Ziel.
- **Protokoll:** `companion/logs/<zeit>.jsonl` mit jedem Modellaufruf (Tokens, Kosten), jedem Tool
  samt Ergebnis und dem Ausgang. Der Schlüssel steht nirgends.
- **Geschenke:** `items_received` beginnt im Leerlauf ein Gespräch wie eine Chatnachricht; während eines Ziels steht es bei den Events, und der Begleitprozess stößt das Modell an, sich zu bedanken.
- **Sprachausgabe:** `companion/speech.py` liest jede `chat`-Nachricht vor (Piper, sonst Windows-Stimme). `/aiplayer tts on|off` schickt `control_requested` mit `action: settings`.
- **Einstellungen (`AiSettings`):** Der Mod hält TTS, Humor, Talkmode, Dimension Follow und Streamer-Modus; `/aiplayer info` zeigt sie samt Locate, Events, Spielmodus und XP-Leiste. Der Begleitprozess holt sie beim Verbinden mit dem Befehl `settings` (kein Tool) und bekommt jede Änderung als `control_requested`. Humor und Talkmode gehen als `[Einstellung]`-Hinweis in den Verlauf, nicht in den Systemprompt (Prompt-Cache). Die Talkmode-Abstände stehen zentral in `TALK_SETTINGS` (`companion/agent.py`): Anstoß nach stillen Schritten, Mindestabstand zwischen eigenen Nachrichten (`chat` scheitert sonst mit `too_soon`; Antworten auf Nachrichten an den Bot gehen immer). `/aiplayer streamer` setzt TTS an, Humor crazy, Talkmode hard, Locate an, Events aus, den eigenen Spielmodus auf Zuschauer und Dimension Follow an; `streamer off` schaltet nur den Streamer-Modus aus.
- **API-Schlüssel (`/aiplayer apikey <key>`):** Der Mod speichert und benutzt ihn nicht. Mit verbundenem Begleitprozess geht er sofort als `control_requested` (`action: apikey`, Feld `api_key`) hinaus, sonst einmalig im Ergebnis des nächsten `settings`. Der Begleitprozess schreibt ihn in `companion/api_key.txt` und legt den API-Client neu an. `EventBus` schreibt `api_key` als `***` ins Debug-Log und Server-Log, `console.py` zeigt ihn ebenfalls maskiert.
- **Chat:** Der Mod meldet jede fremde Chatzeile als `chat_received`. Im Leerlauf beginnt eine
  Nachricht mit `direct` ein Gespräch (dieselbe Schleife wie ein Ziel, die erste Zeile nennt Absender
  und Text). Während eines Ziels hängt die Nachricht am nächsten Ergebnis; mit `direct` hält der
  Begleitprozess eine längere Aktion dafür an (`interrupted_by`) und weckt `wait`, außer im Kampf
  und bei den übrigen Befehlen, die auch eine Gefahr nicht anhält. Die letzten 12 Chatzeilen (fremde
  und eigene) stehen am Anfang jedes Ziels und Gesprächs; darüber hinaus erinnert sich die KI nur
  über den Notizblock. Der Systemprompt verlangt, Pläne im Chat anzusagen, und zwar mit Humor.
- **Nicht gebaut:** Private Nachrichten (`/msg`) liest der Bot nicht. Das Gespräch wird nicht gekürzt;
  bei sehr langen Zielen wächst es, bis die Obergrenze der Schritte greift.

## Module im Mod (`de.hannes.aiplayer`)

| Paket | Aufgabe | Ab Etappe |
|---|---|---|
| (Wurzel) | Einstiegspunkt `AiPlayerMod` | 1 |
| `bot` | `BotManager` (Join, Tick, Respawn), `FakeConnection`, Befehl `/aiplayer` | 1b |
| `mixin` | `PlayerMixin` (serverseitige Bewegung des Bots), `BlockItemMixin` (Block gesetzt) | 1b, 2 |
| `debug` | Debug-Log nach `logs/aiplayer/`, eine JSON-Zeile pro Eintrag | 1b |
| `perception` | `Perception` (Hooks → Events), `SurroundingsScanner` (Mobs, Gefahren), `Snapshot`, `EventCoalescer`, `EventBus` | 2 |
| `protocol` | `Event` (auch Ergebnis eines Befehls), `Command` mit Prüfung, `Priority`, Protokollversion | 2, 3 |
| `bridge` | `BridgeServer`: Socket auf eigenen Threads, Event-Queue raus, Befehls-Queue rein | 3 |
| `action` | `CommandDispatcher` (Befehl annehmen, Argumente prüfen), `ActionRunner` (gemeinsame Regeln), je eine Klasse pro Aktion (`GotoAction`, `PursueAction`, `MineAction`, `CollectAction`, `PlaceAction`, `PickupAction`, `CraftAction`, `SmeltAction`, `SmeltCollectAction`, `PlaceUtilityAction`, `ChestAction`, `EatAction`, `AttackAction`, `DefendAction`, `FleeAction`, `SleepAction`, `ShelterAction`, `LightAction`) | 3, 4a–4d |
| `reflex` | `Reflexes` (Notfall-Essen, Schild oben halten, Luft holen, Bremsen beim Essen und Blocken), `GazeGuard` (keinen Enderman ansehen), `AirSeeker` (unter Wasser Luft suchen) | 4d |
| `memory` | `Places` (Ortsgedächtnis für Werkbank, Ofen, Truhe, Bett und wichtige Orte, pro Welt gespeichert), `FurnaceWatch` (befüllte Öfen, Event `smelting_done`) | 4c |
| `skill` | Handgriffe, aus denen Aktionen bestehen: `PathFollower` (Steuerung, Sicherheitsnetz, Umbauten unterwegs), `BlockBreaker`, `BlockPlacer`, `PillarUp`, `ItemGatherer`, `BlockScan`, `Inv`, `Recipes` (Rezepte lesen), `Station` (Arbeitsblock finden, anlaufen, aufstellen), `Menus` (Klicks in Fenstern), `Fighter` (Kampf gegen ein Wesen), `Combat` (Waffe, Feinderkennung, direktes Steuern, Schild), `Eater` (Essen wählen und essen) | 4a–4d |
| `nav` | Reine Wegsuche ohne Spielklassen: `PathSearch` (A*), `Moves` (Bewegungsregeln), `Goal` (Zielarten); dazu `WorldCells` (Blöcke der Welt einordnen) | 4a |

Begleitprozess unter `companion/`: bisher `console.py` (Events anzeigen, Befehle von Hand
schicken, auch in Kurzform wie `goto 10 64 -3`; verbindet sich selbstständig neu). Ab Etappe 5 kommt der Agent dazu (Prompt,
Modellaufruf, Antwort → Befehl).

## Threads

- Spielzustand wird nur auf dem Server-Thread angefasst. Wahrnehmung und Aktionen des Bots
  laufen beide dort.
- Der Socket hat eigene Threads. Zwischen Socket und Spiel liegen zwei Queues;
  der Server-Thread blockiert nie auf Netzwerk.
- Befehle werden zu Beginn eines Server-Ticks aus der Queue geholt. Die laufende Aktion tickt
  direkt vor dem Bot und setzt dessen Eingaben für diesen Tick.
- Ist kein Begleitprozess verbunden, werden Events verworfen, nicht aufgestaut. Nach dem
  Verbinden kommt als Erstes `bridge_connected`; den aktuellen Stand liefert `look`.

## Nachrichtenformat (Version 1)

Jede Nachricht ist ein JSON-Objekt in einer Zeile. `v` ist die Protokollversion;
unbekannte Versionen werden abgelehnt.

**Event** (Mod → Begleitprozess)

```json
{"v":1,"type":"event","name":"block_broken","seq":412,"tick":88213,"prio":"normal",
 "data":{"block":"minecraft:oak_log","pos":[104,67,-33],"by":"self","held_item":null}}
```

- `seq`: fortlaufende Nummer, damit Lücken auffallen
- `tick`: Spiel-Tick der Welt
- `prio`: `normal` oder `high` (Gefahren). `high` wird im Begleitprozess vorgezogen
  und kann einen laufenden Befehl abbrechen.
- Namen in `snake_case`, Vergangenheitsform (`block_broken`, `damage_taken`)

**Befehl** (Begleitprozess → Mod)

```json
{"v":1,"type":"command","id":"c-17","name":"mine","args":{"pos":[104,67,-33]}}
```

**Ergebnis** (Mod → Begleitprozess), immer genau eines pro Befehl

```json
{"v":1,"type":"event","name":"mine_result","seq":413,"tick":88260,"prio":"normal",
 "cmd_id":"c-17","status":"failed","reason":"out_of_reach","data":{"distance":6.2}}
```

- `status`: `ok`, `failed` oder `cancelled`; außer bei `ok` ist `reason` Pflicht und stammt
  aus einer festen Liste je Befehl
- `data`: der bestätigte Zustand nach der Aktion (z. B. Item in der Hand mit Anzahl)
- Blockiert das Sicherheitsnetz einen Befehl: `failed`, `reason` beginnt mit
  `blocked_` (z. B. `blocked_void_ahead`)
- Aktionen mit Dauer melden zusätzlich sofort `<befehl>_started`

**Fehlerhafte Zeilen** werden verworfen und als Event `protocol_error` gemeldet (`reason`:
`invalid_json`, `not_an_object`, `unsupported_version`, `not_a_command`, `invalid_id`,
`invalid_name`, `invalid_args`).

Die Befehle stehen im Abschnitt „Aktionen“.

Die formalen JSON-Schemas liegen in `schemas/` (eine Datei pro Event und Befehl).
Regel: erst Schema ändern, dann Code.

## Debug-Log

`run/logs/aiplayer/<Startzeit>.jsonl`, eine Zeile pro Vorgang: Zeitstempel, Tick, Art
(`session`, `perception`, `command_in`, `result`, `safety`) und die
Nachricht selbst. Dort stehen Beitritt und Austritt des Bots, der Seed beim Welteintritt eines
Spielers, jedes Event und jeder Snapshot.
Der Begleitprozess schreibt seine Entscheidungen (inklusive Modellantwort, ohne Schlüssel)
in eine eigene Datei im selben Ordner.

# SmartCraft – KI-Minecraft-Spieler (Fabric-Mod)

Ein Fabric-Mod für Minecraft Java Edition 26.2, über den Claude (Anthropic API) eine eigene, zweite Spielfigur steuert –
mit dem langfristigen Ziel, selbstständig bis zum Enderdrachen zu kommen.

Die KI tritt als „Fake Player“ bei: Join-Nachricht, Tab-Liste, Skin, sichtbar für alle LAN-Spieler. Der Mod muss nur beim
Host installiert sein. Wahrnehmung, Events, Wegfindung und Aktionen stecken im Mod; ein Python-Begleitprozess spricht
über einen lokalen Socket (`127.0.0.1`) mit dem Mod und ruft das Modell auf. Der Mod selbst macht keine API-Aufrufe.

```
Minecraft ── Fabric-Mod (Java) ──Socket/JSON── Begleitprozess (Python) ──API── Claude
```

## Voraussetzungen

- Minecraft Java Edition 26.2 mit Fabric Loader und Fabric API
- Java 25 (zum Bauen), Python 3.11
- Ein Anthropic-API-Schlüssel

## Schnellstart

```bash
./gradlew build                          # Mod bauen (Jar in build/libs/)
./gradlew runClient                      # oder: Spiel mit Mod direkt starten
pip install -r companion/requirements.txt
python companion/agent.py                # Begleitprozess starten
```

Im Spiel:

```
/smartcraft join                  # die KI tritt bei
/smartcraft apikey sk-ant-...     # API-Schlüssel festlegen (wird in companion/api_key.txt gespeichert)
/smartcraft goal Baue eine Steinspitzhacke
```

Weitere Befehle: `/smartcraft pause|resume|stop`, `/smartcraft info`, `/smartcraft stats on|off`, `/smartcraft inventar`,
`/smartcraft leave` – vollständige Liste in [CLAUDE.md](CLAUDE.md).

## Dokumentation

- [ARCHITECTURE.md](ARCHITECTURE.md) – Aufbau, Events, Befehle, Reflexe
- [companion/README.md](companion/README.md) – Begleitprozess, Konsole, Sprachausgabe
- [schemas/](schemas/) – JSON-Schemas aller Events und Befehle
- [tests/server/](tests/server/) – automatische Servertests

## Hinweise

- Nur für Einzelspieler und lokale Testwelten gedacht, nicht für öffentliche Server.
- API-Aufrufe kosten Geld; ein Limit lässt sich mit `SMARTCRAFT_COST_LIMIT` setzen.
- Den API-Schlüssel nie committen (`companion/api_key.txt` steht in `.gitignore`).

## Lizenz

MIT, siehe [LICENSE](LICENSE).

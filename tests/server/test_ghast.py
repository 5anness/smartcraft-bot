"""Servertest: Schutz vor Ghast-Feuerbällen (Kit-Lauf 2: ein Feuerball stieß den Bot in einen Lavasee).

Aufruf:  python tests/server/test_ghast.py
Flache Testwelt, ca. 1 Minute. Geprüft wird, dass der Bot einen anfliegenden Feuerball zurückschlägt und dass die
Wegfindung bei einem Ghast in der Nähe nicht an der Kante eines Abgrunds entlangläuft.
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, FLOOR, STAND, arena  # noqa: E402


def walk_on_edge(server, x, z, high):
    """Läuft auf dem Steg von x bis x+20 in der Kantenreihe z; zählt Messungen in der Kantenreihe unterwegs."""
    server.console(f"tp {BOT} {x}.5 {high} {z}.5", wait=1)
    cmd_id = server.send("goto", {"pos": [x + 20, high, z], "tolerance": 0})
    edge = total = 0
    deadline = time.time() + 30
    while time.time() < deadline:
        look = server.look()
        px, _, pz = look["pos"]
        if x + 4 <= px <= x + 16:
            total += 1
            edge += pz == z
        if px >= x + 20:
            break
        time.sleep(0.25)
    server.send("stop")
    return edge, total, cmd_id


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "aiplayer-test-ghast.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console("difficulty normal")
        server.console(f"aiplayer join {BOT}", wait=5)
        server.console(f"clear {BOT}")

        # --- Feuerball von Osten auf Augenhöhe: zurückschlagen.
        x, z = 760, 520
        arena(server, x, z)
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5 facing {x + 10} {STAND + 1} {z}", wait=1)
        mark = server.output_mark()
        server.console(f"summon fireball {x + 9}.5 {STAND + 1.6} {z}.5 "
                       "{Motion:[-0.6d,0d,0d],acceleration_power:0.1d,ExplosionPower:1b}", wait=3)
        look = server.look()
        report.check("Feuerball: Reflex schlägt zurück", server.find_in_output("reflex_deflected_fireball", mark),
                     f"Leben {look['health']}")
        report.check("Feuerball: kein Schaden", look["health"] >= 20, f"Leben {look['health']}")
        server.console("kill @e[type=fireball]")

        # --- Steg 4 breit auf 10 Blöcken Höhe, im Norden ein Abgrund: ohne Ghast die Kante entlang, mit Ghast innen.
        x, z = 760, 580
        arena(server, x, z)
        high = STAND + 10
        server.console(f"fill {x - 2} {FLOOR + 1} {z + 2} {x + 22} {high - 1} {z + 3} stone")
        server.console(f"fill {x - 2} {high - 1} {z} {x + 22} {high - 1} {z + 1} stone")
        edge, total, _ = walk_on_edge(server, x, z, high)
        report.check("Steg ohne Ghast: geht an der Kante entlang (Vergleich)", total > 0 and edge * 2 >= total,
                     f"Kante {edge} von {total}")
        server.console(f"summon ghast {x + 10} {high + 12} {z + 30} {{NoAI:1b,PersistenceRequired:1b}}", wait=0.5)
        edge, total, _ = walk_on_edge(server, x, z, high)
        report.check("Steg mit Ghast: meidet die Kante", total > 0 and edge * 4 <= total, f"Kante {edge} von {total}")
        server.console("kill @e[type=ghast]")
    finally:
        server.close()
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

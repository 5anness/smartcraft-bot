"""Servertest: goto mit allow_place im Nether hält die Richtung (Hannes' Lauf 17.10.: der Bot zog von Plattform zu
Plattform seitlich weg) und bricht neben Lava unter der Brücke nicht ab.

Aufruf:  python tests/server/test_corridor.py
Ca. 1 Minute. Ein Lavasee 40 Blöcke lang; außen herum führt ein Steg mit 15 Blöcken Umweg zur Seite.
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT  # noqa: E402

NETHER = "execute in minecraft:the_nether run"
Y = 100


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-corridor.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console(f"smartcraft join {BOT}", wait=5)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} cobblestone 64")
        server.console(f"give {BOT} cobblestone 64")
        x, z = 4000, 4000
        server.console(f"{NETHER} tp {BOT} {x}.5 {Y} {z}.5", wait=2)
        server.console(f"{NETHER} fill {x - 3} {Y - 2} {z - 20} {x + 43} {Y - 2} {z + 20} netherrack")
        server.console(f"{NETHER} fill {x - 3} {Y - 1} {z - 20} {x + 43} {Y - 1} {z + 20} lava")
        server.console(f"{NETHER} fill {x - 3} {Y} {z - 20} {x + 43} {Y + 8} {z + 20} air")
        # Start- und Zielinsel, dazu ein Steg außen herum (z + 15).
        server.console(f"{NETHER} fill {x - 2} {Y - 1} {z - 2} {x + 2} {Y - 1} {z + 2} netherrack")
        server.console(f"{NETHER} fill {x + 38} {Y - 1} {z - 2} {x + 42} {Y - 1} {z + 2} netherrack")
        server.console(f"{NETHER} fill {x} {Y - 1} {z + 3} {x} {Y - 1} {z + 15} netherrack")
        server.console(f"{NETHER} fill {x} {Y - 1} {z + 15} {x + 40} {Y - 1} {z + 15} netherrack")
        server.console(f"{NETHER} fill {x + 40} {Y - 1} {z + 3} {x + 40} {Y - 1} {z + 15} netherrack")
        server.console(f"effect give {BOT} fire_resistance 300 1")
        server.console(f"{NETHER} tp {BOT} {x}.5 {Y} {z}.5", wait=1.5)

        cmd = server.send("goto", {"pos": [x + 40, Y, z], "tolerance": 1, "allow_place": True})
        widest = 0
        deadline = time.time() + 120
        result = None
        while time.time() < deadline:
            look = server.look()
            widest = max(widest, abs(look["pos"][2] - z))
            result = server.result_for(cmd) if hasattr(server, "result_for") else None
            if result or abs(look["pos"][0] - (x + 40)) <= 1 and abs(look["pos"][2] - z) <= 1:
                break
            time.sleep(0.5)
        time.sleep(1)
        look = server.look()
        report.check("goto über den Lavasee: angekommen", abs(look["pos"][0] - (x + 40)) <= 1, f"pos {look['pos']}")
        report.check("hält die Richtung (höchstens 5 Blöcke seitlich, Steg läge 15 daneben)", widest <= 5,
                     f"weitester Abstand {widest}")
        mark_danger = server.find_in_output('"goto_result"') and server.find_in_output('"reason":"danger"')
        report.check("kein Abbruch wegen Lava neben der Brücke", not mark_danger, "")
    finally:
        server.close()
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

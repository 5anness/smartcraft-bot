"""Servertest: Lava-Reflex baut eine Insel, wenn kein Ufer erreichbar ist (Hannes' Lauf 17.10.: 10 s im Lavasee, tot),
und farm_endermen lehnt im Nether ohne Bausteine ab.

Aufruf:  python tests/server/test_lava_island.py
Ca. 1 Minute. Eine Lavagrube 2 tief mit 3 Blöcke hohen Wänden: Herausschwimmen geht nicht.
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
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-lava-island.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console(f"smartcraft join {BOT}", wait=5)
        server.console(f"clear {BOT}")
        x, z = 4200, 4200
        server.console(f"{NETHER} tp {BOT} {x}.5 {Y + 5} {z}.5", wait=2)
        server.console(f"{NETHER} fill {x - 6} {Y - 3} {z - 6} {x + 6} {Y + 6} {z + 6} netherrack")
        server.console(f"{NETHER} fill {x - 4} {Y - 2} {z - 4} {x + 4} {Y + 6} {z + 4} air")
        server.console(f"{NETHER} fill {x - 4} {Y - 2} {z - 4} {x + 4} {Y - 1} {z + 4} lava")
        # Wände 3 hoch über der Lava: hinaus nur über eine Insel.
        server.console(f"{NETHER} fill {x - 4} {Y + 2} {z - 4} {x + 4} {Y + 6} {z + 4} air")

        # --- Ohne Bausteine: farm_endermen lehnt ab.
        server.console(f"{NETHER} fill {x - 1} {Y + 2} {z + 8} {x + 1} {Y + 4} {z + 10} air")
        server.console(f"{NETHER} tp {BOT} {x}.5 {Y + 2} {z + 9}.5", wait=1)
        result, _ = server.run("farm_endermen", {"count": 1}, timeout=20)
        report.check("farm_endermen ohne Bausteine im Nether: few_blocks", result.get("reason") == "few_blocks", summary(result))

        # --- In die Lavagrube, mit Bausteinen und Feuerresistenz (damit der Test nicht am Tod scheitert).
        server.console(f"give {BOT} netherrack 64")
        server.console(f"effect give {BOT} fire_resistance 120 1")
        mark = server.output_mark()
        server.console(f"{NETHER} tp {BOT} {x}.5 {Y - 1} {z}.5", wait=1)
        deadline = time.time() + 30
        while time.time() < deadline and not server.find_in_output("reflex_left_lava", mark):
            time.sleep(0.5)
        line = server.find_in_output("reflex_left_lava", mark)
        look = server.look()
        report.check("Reflex kommt aus der Grube (reflex_left_lava)", line is not None, f"pos {look['pos']}")
        report.check("dabei Blöcke gesetzt (Insel)", line is not None and "blocks_placed" in str(line), str(line)[-160:])
    finally:
        server.close()
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

"""Servertest: collect bei Holz erlegt nebenbei Nutztiere (Wunsch von Hannes), bei Erz nicht.

Aufruf:  python tests/server/test_hunt.py
Flache Testwelt, ca. 1 Minute.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, STAND, arena  # noqa: E402


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-hunt.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console(f"smartcraft join {BOT}", wait=5)
        x, z = 880, 520
        arena(server, x, z)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} minecraft:iron_sword")
        server.console(f"fill {x + 6} {STAND} {z} {x + 6} {STAND + 2} {z} oak_log")
        for dx, dz in ((3, 8), (-3, -6)):
            server.console(f"summon cow {x + dx}.5 {STAND} {z + dz}.5 {{PersistenceRequired:1b}}")
        server.console(f"summon cow {x + 2}.5 {STAND} {z - 4}.5 {{Age:-24000,PersistenceRequired:1b}}")
        result, _ = server.run("collect", {"block": "oak_log", "count": 3}, timeout=90)
        report.check("collect oak_log: ok", result["status"] == "ok", summary(result))
        report.check("2 Kühe erlegt, Kalb nicht", result["data"].get("animals_killed") == 2, summary(result))
        answer, _ = server.run("inventory", timeout=10)
        ids = {item["id"] for item in answer["data"]["items"]}
        report.check("Rindfleisch aufgehoben", "minecraft:beef" in ids, str(sorted(ids)))

        # --- Baumstelle gemerkt: look nennt sie, weit weg nennt collect sie bei none_found.
        look = server.look()
        report.check("look: known_trees enthält die Holzstelle",
                     any(abs(t["pos"][0] - (x + 6)) <= 2 and abs(t["pos"][2] - z) <= 2 for t in look.get("known_trees", [])),
                     str(look.get("known_trees")))
        arena(server, x + 200, z)
        result, _ = server.run("collect", {"block": "oak_log", "count": 1, "radius": 8}, timeout=30)
        report.check("collect weit weg: none_found mit known_trees",
                     result.get("reason") == "none_found" and result["data"].get("known_trees"), summary(result))

        # --- Erz: keine Jagd.
        arena(server, x, z + 40)
        server.console(f"setblock {x + 3} {STAND} {z + 40} iron_ore")
        server.console(f"give {BOT} minecraft:stone_pickaxe")
        server.console(f"summon cow {x - 3}.5 {STAND} {z + 46}.5 {{PersistenceRequired:1b}}")
        result, _ = server.run("collect", {"block": "iron_ore", "count": 1}, timeout=60)
        report.check("collect iron_ore: keine Jagd", not result["data"].get("animals_killed"), summary(result))
        server.console("kill @e[type=cow]")
    finally:
        server.close()
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

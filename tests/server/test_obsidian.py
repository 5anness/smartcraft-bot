"""Servertest make_obsidian: Obsidian aus Lava machen, ein Befehl (Hannes' Lauf 16.10.2026: neun geratene bucket-Versuche).

Aufruf:  python tests/server/test_obsidian.py
Flache Testwelt, ca. 3 Minuten.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, STAND, arena  # noqa: E402

FLOOR = STAND - 1


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-obsidian.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console(f"smartcraft join {BOT}", wait=5)

        # --- Ohne Diamantspitzhacke: wrong_tool.
        x, z = 1300, 520
        arena(server, x, z)
        server.console(f"clear {BOT}", wait=0.3)
        server.console(f"give {BOT} water_bucket")
        result, _ = server.run("make_obsidian", timeout=20)
        report.check("make_obsidian ohne Diamantspitzhacke: wrong_tool",
                     result["status"] == "failed" and result.get("reason") == "wrong_tool", summary(result))

        # --- Ohne Wassereimer und ohne liegendes Obsidian: no_water_bucket.
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} diamond_pickaxe", wait=0.3)
        result, _ = server.run("make_obsidian", timeout=20)
        report.check("make_obsidian ohne Wassereimer: no_water_bucket",
                     result["status"] == "failed" and result.get("reason") == "no_water_bucket", summary(result))

        # --- Liegendes Obsidian wird zuerst abgebaut (ohne Wassereimer).
        server.console(f"setblock {x + 3} {STAND} {z} obsidian")
        server.console(f"setblock {x + 3} {STAND} {z + 1} obsidian", wait=0.3)
        result, _ = server.run("make_obsidian", {"count": 2}, timeout=90)
        report.check("make_obsidian: 2 liegende Obsidian abgebaut",
                     result["status"] == "ok" and result["data"].get("obsidian_gained") == 2, summary(result))

        # --- Lavabecken im Boden (3x3, eine Ebene tief): Wasser drauf, zurückholen, abbauen.
        x, z = 1300, 580
        arena(server, x, z)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} diamond_pickaxe")
        server.console(f"give {BOT} water_bucket")
        server.console(f"fill {x + 4} {FLOOR} {z - 1} {x + 6} {FLOOR} {z + 1} lava", wait=0.5)
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=0.5)
        result, _ = server.run("make_obsidian", {"count": 4}, timeout=300)
        data = result.get("data", {})
        report.check("make_obsidian aus einem Lavabecken: 4 Obsidian, Wassereimer wieder voll",
                     result["status"] == "ok" and data.get("obsidian_gained", 0) >= 4 and data.get("water_buckets") == 1,
                     summary(result))
        server.console(f"fill {x + 3} {FLOOR - 1} {z - 2} {x + 7} {STAND + 2} {z + 2} air")
        server.console(f"fill {x + 3} {FLOOR} {z - 2} {x + 7} {FLOOR} {z + 2} grass_block")

        # --- Zugedeckter Lavasee (wie in Hannes' Lauf): nur eine Quelle hat eine freie Seite.
        x, z = 1300, 640
        arena(server, x, z)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} diamond_pickaxe")
        server.console(f"give {BOT} water_bucket")
        server.console(f"fill {x + 4} {FLOOR - 1} {z - 1} {x + 6} {FLOOR - 1} {z + 1} lava")
        server.console(f"fill {x + 4} {FLOOR} {z - 1} {x + 6} {FLOOR} {z + 1} stone")
        server.console(f"setblock {x + 4} {FLOOR} {z} air", wait=0.5)
        result, _ = server.run("make_obsidian", {"count": 1}, timeout=180)
        report.check("make_obsidian bei zugedeckter Lava: findet die freie Quelle",
                     result["status"] == "ok" and result["data"].get("obsidian_gained", 0) >= 1
                     and result["data"].get("water_buckets") == 1, summary(result))
        server.console(f"fill {x + 3} {FLOOR - 2} {z - 2} {x + 7} {STAND + 2} {z + 2} air")

        # --- Keine Lava: no_lava (frischer Eimer, damit nicht no_water_bucket zuerst greift).
        server.console(f"clear {BOT} water_bucket")
        server.console(f"clear {BOT} bucket")
        server.console(f"give {BOT} water_bucket")
        server.console(f"fill {x + 3} {FLOOR - 2} {z - 2} {x + 7} {FLOOR} {z + 2} stone", wait=0.3)
        result, _ = server.run("make_obsidian", {"count": 64}, timeout=30)
        report.check("make_obsidian ohne Lava: no_lava",
                     result["status"] == "failed" and result.get("reason") in ("no_lava", "lava_unreachable"), summary(result))

        # --- Gesamtlauf 2: Lava 40 Blöcke weg, außerhalb des Radius; no_lava nennt sie als nearest_lava.
        server.console(f"fill {x - 2} {FLOOR - 3} {z - 30} {x + 50} {FLOOR - 1} {z + 30} stone")
        server.console(f"setblock {x + 40} {FLOOR - 1} {z} lava", wait=0.5)
        result, _ = server.run("make_obsidian", {"count": 1, "radius": 8}, timeout=30)
        near = result.get("data", {}).get("nearest_lava")
        report.check("make_obsidian: Lava außerhalb des Radius als nearest_lava gemeldet",
                     result.get("reason") == "no_lava" and near == [x + 40, FLOOR - 1, z], summary(result))
        server.console(f"setblock {x + 40} {FLOOR - 1} {z} stone")
    finally:
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

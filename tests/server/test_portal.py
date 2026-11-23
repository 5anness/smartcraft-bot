"""Servertest build_portal: Netherportal mit einem Befehl bauen und anzünden (Drachenlauf 5, 15.10.2026).

Aufruf:  python tests/server/test_portal.py
Flache Testwelt, ca. 1 Minute.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, STAND, arena  # noqa: E402


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "aiplayer-test-portal.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console(f"aiplayer join {BOT}", wait=5)

        # --- Ohne Obsidian: missing_obsidian.
        arena(server, 900, 520)
        server.console(f"clear {BOT}", wait=0.3)
        result, _ = server.run("build_portal")
        report.check("build_portal ohne Obsidian: missing_obsidian",
                     result["status"] == "failed" and result.get("reason") == "missing_obsidian", summary(result))

        # --- Mit allem: Portal steht und brennt.
        arena(server, 900, 560)
        server.console(f"give {BOT} obsidian 10")
        server.console(f"give {BOT} flint_and_steel 1")
        server.console(f"give {BOT} cobblestone 16", wait=0.5)
        # --- Oberwelt ohne Nether-Ausrüstung: not_prepared (Wunsch von Hannes, 28.10.: erst alles farmen).
        result, _ = server.run("build_portal", timeout=30)
        report.check("build_portal ohne Nether-Ausrüstung: not_prepared mit missing",
                     result["status"] == "failed" and result.get("reason") == "not_prepared"
                     and any("food" in m for m in result["data"].get("missing", [])), summary(result))
        result, _ = server.run("build_portal", {"force": True}, timeout=60)
        report.check("build_portal: ok, portal_open, 10 Obsidian verbaut",
                     result["status"] == "ok" and result["data"].get("portal_open") is True
                     and result["data"].get("obsidian_left") == 0, summary(result))

        # --- Ohne Diamantrüstung und Goldteil: not_prepared (Hannes' Lauf 16.10.: mit 2 Äpfeln in den Nether, tot).
        result, _ = server.run("enter_portal", timeout=30)
        report.check("enter_portal ohne Nether-Rüstung: not_prepared mit missing",
                     result["status"] == "failed" and result.get("reason") == "not_prepared"
                     and len(result["data"].get("missing", [])) >= 4, summary(result))

        # --- Mit Diamant und Goldstiefeln im Inventar: zieht selbst an und geht hinein
        # (KI-Lauf 15.10.: Bot stand 38 s neben dem Portal, ARRIVED wurde übergangen).
        for item in ("diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots", "golden_boots",
                     "diamond_sword", "diamond_pickaxe", "iron_pickaxe", "bow", "arrow 32", "shield",
                     "cooked_beef 16", "cobblestone 192", "flint_and_steel"):
            server.console(f"give {BOT} {item}", wait=0.1)
        server.drain(0.5)
        result, _ = server.run("enter_portal", timeout=60)
        report.check("enter_portal nach build_portal: im Nether",
                     result["status"] == "ok" and result["data"].get("dim") == "minecraft:the_nether", summary(result))
        mark = server.output_mark()
        server.console(f"execute if entity @a[name={BOT},nbt={{equipment:{{feet:{{id:\"minecraft:golden_boots\"}},head:{{id:\"minecraft:diamond_helmet\"}}}}}}]", wait=0.6)
        report.check("enter_portal: Goldstiefel und Diamanthelm angezogen", server.find_in_output("Test passed", mark) is not None, "")
        server.console(f"execute in minecraft:overworld run tp {BOT} 900.5 {STAND} 560.5", wait=1)

        # --- Im Fels: nur ein Gang, das Portal muss frei gegraben werden.
        x, z = 960, 600
        arena(server, x, z)
        server.console(f"fill {x - 11} {STAND} {z - 11} {x + 11} {STAND + 7} {z + 11} stone")
        server.console(f"fill {x} {STAND} {z} {x} {STAND + 1} {z} air")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=0.5)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} diamond_pickaxe 1")
        server.console(f"give {BOT} obsidian 10")
        server.console(f"give {BOT} flint_and_steel 1")
        server.console(f"give {BOT} cobblestone 16", wait=0.5)
        result, _ = server.run("build_portal", {"force": True}, timeout=120)
        report.check("build_portal im Fels: gräbt frei, Portal brennt",
                     result["status"] == "ok" and result["data"].get("portal_open") is True
                     and result["data"].get("blocks_dug", 0) > 0, summary(result))

        # --- farm_spawner mit vollem Inventar: wirft Netherrack weg statt Beute liegen zu lassen.
        x, z = 900, 640
        arena(server, x, z)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} netherrack 2304")
        server.console(f"setblock {x + 4} {STAND} {z} spawner", wait=0.5)
        result, _ = server.run("farm_spawner", {"item": "diamond", "count": 1, "seconds": 10}, timeout=40)
        report.check("farm_spawner mit vollem Inventar: Netherrack weggeworfen",
                     result["data"].get("dropped_netherrack") is True, summary(result))
        server.console(f"setblock {x + 4} {STAND} {z} air")

        # --- Perlen-Lauf 4: auf einer Wiese (Gras und Blumen überall) gab es nie einen Platz.
        x, z = 900, 720
        arena(server, x, z)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} obsidian 10")
        server.console(f"give {BOT} flint_and_steel 1")
        server.console(f"give {BOT} cobblestone 16")
        server.console(f"fill {x - 8} {STAND} {z - 8} {x + 8} {STAND} {z + 8} short_grass replace air")
        server.console(f"fill {x - 2} {STAND} {z - 2} {x - 1} {STAND} {z - 1} poppy replace short_grass")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=0.5)
        result, _ = server.run("build_portal", {"force": True}, timeout=90)
        report.check("build_portal auf einer Wiese: Gras weg, Portal brennt",
                     result["status"] == "ok" and result["data"].get("portal_open") is True, summary(result))
        server.console(f"fill {x - 8} {STAND} {z - 8} {x + 8} {STAND + 5} {z + 8} air")

        # --- Nether-Lauf 38: kein Portal neben einem Spawner.
        x, z = 900, 680
        arena(server, x, z)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} obsidian 10")
        server.console(f"give {BOT} flint_and_steel 1")
        server.console(f"give {BOT} cobblestone 16")
        server.console(f"setblock {x + 6} {STAND} {z} spawner", wait=0.5)
        result, _ = server.run("build_portal", timeout=60)
        report.check("build_portal neben einem Spawner: near_spawner",
                     result["status"] == "failed" and result.get("reason") == "near_spawner", summary(result))
        server.console(f"setblock {x + 6} {STAND} {z} air")
    finally:
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

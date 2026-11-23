"""Servertest farm_spawner: zum Spawner, alles töten, am Spawner bleiben (Wunsch von Hannes, 15.10.2026).

Aufruf:  python tests/server/test_farm.py
Flache Testwelt, ca. 2 Minuten.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, FLOOR, STAND, arena  # noqa: E402


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "aiplayer-test-farm.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console("difficulty normal")
        server.console(f"aiplayer join {BOT}", wait=5)
        server.console(f"clear {BOT}", wait=0.3)

        # --- Kein Spawner in der Nähe.
        x, z = 800, 520
        arena(server, x, z)
        result, _ = server.run("farm_spawner", {})
        report.check("farm_spawner ohne Spawner: no_spawner",
                     result["status"] == "failed" and result.get("reason") == "no_spawner", summary(result))

        # --- Spawner 10 Blöcke weiter, drei Zombies darum (ohne KI): hin, alle töten, dort bleiben.
        x, z = 800, 560
        arena(server, x, z)
        server.console(f"give {BOT} iron_sword 1")
        server.console(f"setblock {x + 10} {STAND} {z} spawner", wait=0.3)
        for dz in (-2, 0, 2):
            server.console(f"summon zombie {x + 12}.5 {STAND} {z + dz}.5 {{NoAI:1b,PersistenceRequired:1b}}")
        server.console(f"effect give {BOT} resistance 60 4", wait=0.5)
        result, _ = server.run("farm_spawner", {"item": "diamond", "count": 1, "seconds": 20}, timeout=60)
        data = result["data"]
        pos = data.get("pos", [0, 0, 0])
        near = abs(pos[0] - (x + 10)) <= 4 and abs(pos[2] - z) <= 4
        report.check("farm_spawner: alle 3 Zombies tot, time_up, steht am Spawner",
                     result["status"] == "ok" and data.get("outcome") == "time_up"
                     and data.get("killed", {}).get("minecraft:zombie") == 3 and near, summary(result))

        # --- Lohe schwebt 4 Blöcke über dem Boden am Spawner: mit Bogen abschießen.
        server.console(f"give {BOT} bow 1")
        server.console(f"give {BOT} arrow 32")
        server.console(f"summon blaze {x + 13}.5 {STAND + 4} {z}.5 {{NoAI:1b,PersistenceRequired:1b}}", wait=0.5)
        result, _ = server.run("farm_spawner", {"item": "diamond", "count": 1, "seconds": 25}, timeout=60)
        report.check("farm_spawner: schwebende Lohe mit dem Bogen getötet",
                     result["status"] == "ok" and result["data"].get("killed", {}).get("minecraft:blaze") == 1,
                     summary(result))

        # --- Lohe in Schwerthöhe: Nahkampf, kein Pfeil verschossen. Dazu Feuer auf Netherrack: wird gelöscht.
        server.console(f"clear {BOT} arrow")
        server.console(f"give {BOT} arrow 32")
        server.console(f"summon blaze {x + 12}.5 {STAND} {z + 1}.5 {{NoAI:1b,PersistenceRequired:1b}}")
        for dz in (-2, 2):
            server.console(f"setblock {x + 8} {FLOOR} {z + dz} netherrack")
            server.console(f"setblock {x + 8} {STAND} {z + dz} fire")
        server.console(f"effect give {BOT} fire_resistance 60 1", wait=0.5)
        result, _ = server.run("farm_spawner", {"item": "diamond", "count": 1, "seconds": 20}, timeout=60)
        data = result["data"]
        arrows = server.run("inventory")[0]["data"]
        arrows_left = sum(s.get("count", 0) for s in arrows.get("items", []) if s.get("id") == "minecraft:arrow")
        report.check("farm_spawner: Lohe in Reichweite mit dem Schwert (kein Pfeil weg)",
                     data.get("killed", {}).get("minecraft:blaze") == 1 and data.get("hits", 0) > 0
                     and arrows_left >= 32, summary(result) + f" arrows={arrows_left}")
        report.check("farm_spawner: zwei Feuer am Boden gelöscht", data.get("fires_out") == 2, summary(result))

        # --- Genug im Inventar: endet sofort mit enough.
        server.console(f"give {BOT} blaze_rod 10", wait=0.5)
        result, _ = server.run("farm_spawner", {}, timeout=30)
        report.check("farm_spawner mit 10 Lohenruten: ok, enough",
                     result["status"] == "ok" and result["data"].get("outcome") == "enough", summary(result))
        server.console(f"setblock {x + 10} {STAND} {z} air")
    finally:
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

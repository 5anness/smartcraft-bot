"""Servertest barter: Gold mit Piglins tauschen (Perlen-Lauf 2, 16.10.2026: der Bot hob sein Gold selbst wieder auf).

Aufruf:  python tests/server/test_barter.py
Flache Testwelt, ca. 2 Minuten. Die Piglins sind gegen Zombifizierung geschützt (Oberwelt).
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, STAND, arena  # noqa: E402

PIGLIN = "{IsImmuneToZombification:1b,PersistenceRequired:1b}"


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "aiplayer-test-barter.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        # Endermen teleportieren sich bei Regen und in der Sonne ständig weg: Nacht und klares Wetter festhalten.
        server.console("weather clear 1000000")
        server.console("time set midnight")
        server.console("gamerule advance_time false")
        server.console(f"aiplayer join {BOT}", wait=5)

        # --- Ohne Gold: missing_item.
        x, z = 1100, 520
        arena(server, x, z)
        server.console(f"clear {BOT}", wait=0.3)
        result, _ = server.run("barter")
        report.check("barter ohne Gold: missing_item",
                     result["status"] == "failed" and result.get("reason") == "missing_item", summary(result))

        # --- Kein Piglin, nur ein Brute und ein Baby: no_piglin.
        server.console(f"give {BOT} gold_ingot 4", wait=0.3)
        server.console(f"summon piglin_brute {x + 5}.5 {STAND} {z}.5 {{IsImmuneToZombification:1b,NoAI:1b}}")
        server.console(f"summon piglin {x - 5}.5 {STAND} {z}.5 {{IsImmuneToZombification:1b,IsBaby:1b,NoAI:1b}}", wait=0.5)
        result, _ = server.run("barter", timeout=30)
        report.check("barter mit Brute daneben: brute_nearby, kein Gold weg",
                     result["status"] == "failed" and result.get("reason") == "brute_nearby"
                     and result["data"].get("gold_left") == 4, summary(result))
        server.console("kill @e[type=piglin_brute]", wait=0.5)
        result, _ = server.run("barter", timeout=30)
        report.check("barter nur mit Baby: no_piglin mit babies_nearby",
                     result["status"] == "failed" and result.get("reason") == "no_piglin"
                     and result["data"].get("babies_nearby") == 1, summary(result))
        server.console("kill @e[type=piglin]")

        # --- Drei Piglins, 16 Gold: alles getauscht, Ware eingesammelt, kein Gold zurück.
        x, z = 1100, 580
        arena(server, x, z)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} gold_ingot 16")
        server.console(f"item replace entity {BOT} armor.feet with golden_boots")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5")
        for dx, dz in ((5, 0), (-5, 2), (0, -6)):
            server.console(f"summon piglin {x + dx}.5 {STAND} {z + dz}.5 {PIGLIN}")
        server.drain(1.0)
        result, _ = server.run("barter", {"count": 16}, timeout=240)
        data = result.get("data", {})
        gained = {item["id"]: item["count"] for item in data.get("gained", [])}
        report.check("barter mit 3 Piglins: 16 Barren getauscht, keiner zurück im Inventar",
                     result["status"] == "ok" and data.get("gold_given") == 16 and data.get("gold_left") == 0
                     and "minecraft:gold_ingot" not in gained, summary(result))
        report.check("barter: Ware eingesammelt", sum(gained.values()) > 0, str(gained))
        server.console("kill @e[type=piglin]")
        server.console("kill @e[type=item]")

        # --- Perlen-Lauf 6: Enderman auf einem Pfeiler ist unerreichbar; ansehen lockt ihn herunter.
        x, z = 1100, 640
        arena(server, x, z)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} diamond_sword")
        server.console(f"item replace entity {BOT} armor.chest with diamond_chestplate")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5 facing {x + 8} {STAND + 5} {z}")
        server.console(f"fill {x + 8} {STAND} {z} {x + 8} {STAND + 3} {z} stone")
        server.console(f"summon enderman {x + 8}.5 {STAND + 4} {z}.5 {{PersistenceRequired:1b}}", wait=1)
        result, _ = server.run("attack", {"target": "enderman"}, timeout=120)
        report.check("attack auf Enderman auf einem Pfeiler: angelockt und getötet",
                     result["status"] == "ok" and result["data"].get("outcome") == "killed", summary(result))
        server.console("kill @e[type=enderman]")
        server.console(f"fill {x + 8} {STAND} {z} {x + 8} {STAND + 3} {z} air")

        # --- farm_endermen: vier Endermen, einer auf einem Pfeiler; alle tot, Perlen aufgehoben.
        x, z = 1160, 640
        arena(server, x, z)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} diamond_sword")
        for slot, item in (("head", "diamond_helmet"), ("chest", "diamond_chestplate"), ("legs", "diamond_leggings"),
                           ("feet", "diamond_boots")):
            server.console(f"item replace entity {BOT} armor.{slot} with {item}")
        server.console(f"effect give {BOT} instant_health 1 5")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5")
        server.console(f"fill {x + 8} {STAND} {z} {x + 8} {STAND + 3} {z} stone")
        server.console(f"summon enderman {x + 8}.5 {STAND + 4} {z}.5 {{PersistenceRequired:1b}}")
        for dx, dz in ((-6, 3), (2, -7), (-4, -5)):
            server.console(f"summon enderman {x + dx}.5 {STAND} {z + dz}.5 {{PersistenceRequired:1b}}")
        server.drain(1.0)
        result, _ = server.run("farm_endermen", {"count": 16, "min_health": 4}, timeout=300)
        data = result.get("data", {})
        report.check("farm_endermen: alle 4 Endermen getötet, Perlen im Inventar = pearls_gained",
                     data.get("killed") == 4 and data.get("ender_pearls") == data.get("pearls_gained")
                     and (result["status"] == "ok" or result.get("reason") == "no_enderman"), summary(result))
        server.console("kill @e[type=enderman]")
        server.console(f"fill {x + 8} {STAND} {z} {x + 8} {STAND + 3} {z} air")

        # --- Perlen-Lauf 9: Enderman 70 Blöcke weg, außerhalb der Nahsuche; farm_endermen zieht hin.
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5")
        server.console(f"effect give {BOT} instant_health 1 5")
        server.console(f"summon enderman {x + 70}.5 {STAND} {z}.5 {{PersistenceRequired:1b}}", wait=1)
        result, _ = server.run("farm_endermen", {"count": 16, "min_health": 4}, timeout=300)
        report.check("farm_endermen: Enderman 70 Blöcke entfernt angesteuert und getötet",
                     result.get("data", {}).get("killed") == 1, summary(result))
        server.console("kill @e[type=enderman]")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5")

        # --- Perlen-Lauf 12: Ein Witherskelett schlug ihn von hinten, farm_endermen wehrte sich nicht.
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5")
        server.console(f"effect give {BOT} instant_health 1 5")
        server.console(f"summon enderman {x + 20}.5 {STAND} {z}.5 {{PersistenceRequired:1b}}")
        server.console(f"summon zombie {x - 2}.5 {STAND} {z}.5 {{PersistenceRequired:1b}}", wait=0.5)
        result, _ = server.run("farm_endermen", {"count": 1, "min_health": 4}, timeout=240)
        mark = server.output_mark()
        server.console("execute if entity @e[type=zombie]", wait=0.6)
        zombie_alive = server.find_in_output("Test passed", mark) is not None
        report.check("farm_endermen schlägt zurück: Zombie tot, Enderman getötet",
                     not zombie_alive and result.get("data", {}).get("killed", 0) >= 1, summary(result))
        server.console("kill @e[type=zombie]")
        server.console("kill @e[type=enderman]")

        # --- goto_entity mit einer Wesen-ID (farm_endermen zieht vorher weit umher: erst zurück).
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=1)
        server.console(f"summon cow {x + 6}.5 {STAND} {z}.5 {{NoAI:1b}}", wait=0.5)
        first, _ = server.run("goto_entity", {"target": "cow"}, timeout=30)
        cow_id = first.get("data", {}).get("target", {}).get("id")
        server.console(f"tp {BOT} {x}.5 {STAND} {z - 6}.5", wait=0.5)
        result, _ = server.run("goto_entity", {"target": str(cow_id)}, timeout=30)
        report.check("goto_entity mit Wesen-ID", cow_id is not None and result["status"] == "ok", summary(result))
        server.console("kill @e[type=cow]")

        # --- Wasser im Nether verdampft: bucket lehnt ab.
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} water_bucket")
        server.console(f"execute in minecraft:the_nether run tp {BOT} 0 70 0", wait=3)
        look = server.look()
        px, py, pz = look["pos"]
        result, _ = server.run("bucket", {"action": "empty", "fluid": "water", "pos": [px + 1, py, pz], "anywhere": True}, timeout=20)
        report.check("bucket mit Wasser im Nether: evaporates",
                     result["status"] == "failed" and result.get("reason") == "evaporates", summary(result))

        # --- Perlen-Lauf 5: Piglins finden über das Biom.
        server.console("aiplayer locate on")
        server.console(f"execute in minecraft:the_nether run tp {BOT} 0 70 0", wait=3)
        result, _ = server.run("locate", {"structure": "crimson_forest"}, timeout=30)
        report.check("locate crimson_forest im Nether: ok mit approx_pos",
                     result["status"] == "ok" and "approx_pos" in result.get("data", {}), summary(result))
        server.console("aiplayer locate off")
    finally:
        # Die Testwelt teilen sich alle Servertests: Zeit wieder laufen lassen (sonst scheitern night_falling und sleep).
        server.console("gamerule advance_time true")
        server.console("weather clear 1")
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

"""Servertest: Festungen nicht angraben (KI-Lauf 15.10.2026: tunnel grub sich quer durch eine Netherfestung).

Aufruf:  python tests/server/test_fortress.py
Im Nether der Testwelt, ca. 2 Minuten. Geprüft wird:
- tunnel hält vor Netherziegeln an (fortress_reached) statt sie abzubauen
- goto mit allow_dig baut Netherziegel nicht ab
- locate fortress nur nach /smartcraft locate on; look meldet in_fortress in einer echten Festung
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT  # noqa: E402

NETHER = "execute in minecraft:the_nether run"
Y = 100


def block_at(server, x, y, z, block):
    """Ob an der Stelle der Block steht (über /execute if block)."""
    mark = server.output_mark()
    server.console(f"{NETHER} execute if block {x} {y} {z} {block}", wait=0.6)
    return server.find_in_output("Test passed", mark) is not None


def chamber(server, x, z):
    """Netherrack-Block 30 x 9 x 7 mit einer Kammer für den Bot am Westende, Bot darin."""
    server.send("stop")
    server.console(f"{NETHER} tp {BOT} {x}.5 {Y} {z}.5", wait=2)
    server.console(f"{NETHER} fill {x - 2} {Y - 2} {z - 4} {x + 28} {Y + 4} {z + 4} netherrack")
    server.console(f"{NETHER} fill {x} {Y} {z} {x} {Y + 1} {z} air")
    server.console(f"{NETHER} tp {BOT} {x}.5 {Y} {z}.5", wait=1)
    server.console(f"effect give {BOT} fire_resistance 120 1")
    server.console(f"effect give {BOT} instant_health 1 10", wait=0.5)


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-fortress.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console(f"smartcraft join {BOT}", wait=5)
        server.console(f"give {BOT} diamond_pickaxe 1")
        server.console(f"give {BOT} cobblestone 32", wait=0.5)

        # --- tunnel nach Osten, nach 6 Blöcken eine Ziegelwand: Halt davor, Wand heil.
        x, z = 3000, 3000
        chamber(server, x, z)
        server.console(f"{NETHER} fill {x + 7} {Y - 1} {z - 2} {x + 7} {Y + 2} {z + 2} nether_bricks", wait=0.5)
        result, _ = server.run("tunnel", {"direction": "east", "length": 20, "stop_at_ore": False}, timeout=90)
        data = result["data"]
        report.check("tunnel vor Netherziegeln: ok, fortress_reached, Wand heil",
                     result["status"] == "ok" and data.get("stopped_because") == "fortress_reached"
                     and data.get("length_done") == 6 and block_at(server, x + 7, Y, z, "nether_bricks")
                     and block_at(server, x + 7, Y + 1, z, "nether_bricks"), summary(result))

        # --- Nether-Lauf 13: tunnel verbrauchte die letzte Spitzhacke. Unter 50 Nutzen hält er an.
        x, z = 3000, 3100
        chamber(server, x, z)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} diamond_pickaxe[damage=1500]", wait=0.5)
        result, _ = server.run("tunnel", {"direction": "east", "length": 20, "stop_at_ore": False}, timeout=90)
        inv, _ = server.run("inventory")
        report.check("tunnel mit fast kaputter Spitzhacke: tool_worn, Spitzhacke noch da",
                     result["status"] == "failed" and result.get("reason") == "tool_worn"
                     and "diamond_pickaxe" in str(inv.get("data")), summary(result))

        # --- goto mit allow_dig durch eine geschlossene Ziegelwand: baut sie nicht ab.
        x, z = 3000, 3040
        chamber(server, x, z)
        server.console(f"{NETHER} fill {x + 4} {Y - 2} {z - 4} {x + 4} {Y + 4} {z + 4} nether_bricks", wait=0.5)
        result, _ = server.run("goto", {"pos": [x + 10, Y, z], "allow_dig": True, "tolerance": 0}, timeout=60)
        wall = all(block_at(server, x + 4, y, zz, "nether_bricks") for y in (Y, Y + 1) for zz in range(z - 4, z + 5))
        report.check("goto mit allow_dig hinter Ziegelwand: Wand heil (außen herum oder kein Weg)",
                     wall and block_at(server, x + 4, Y, z, "nether_bricks")
                     and block_at(server, x + 4, Y + 1, z, "nether_bricks"), summary(result))

        # --- locate: aus, dann an; in der Festung meldet look in_fortress.
        server.console("smartcraft locate off", wait=0.3)
        result, _ = server.run("locate", {"structure": "fortress"})
        report.check("locate ohne Freigabe: locate_disabled",
                     result["status"] == "failed" and result.get("reason") == "locate_disabled", summary(result))
        server.console("smartcraft locate on", wait=0.3)
        result, _ = server.run("locate", {"structure": "fortress"}, timeout=30)
        report.check("locate fortress nach Freigabe: ok mit approx_pos",
                     result["status"] == "ok" and "approx_pos" in result["data"], summary(result))
        if result["status"] == "ok":
            fx, fy, fz = result["data"]["approx_pos"]
            server.console(f"effect give {BOT} resistance 60 4", wait=0.3)
            server.console(f"{NETHER} tp {BOT} {fx}.5 {fy} {fz}.5", wait=3)
            result, _ = server.run("locate", {"structure": "fortress"}, timeout=30)
            print("   ", summary(result))
            px, py, pz = result["data"].get("approx_pos", [fx, fy, fz])
            server.console(f"{NETHER} tp {BOT} {px}.5 {py} {pz}.5", wait=3)
            look = server.look()
            report.check("look mitten in der Festung: in_fortress", look.get("in_fortress") is True,
                         f"pos {look.get('pos')}")
            result, _ = server.run("locate", {"structure": "blaze_spawner"}, timeout=30)
            report.check("locate blaze_spawner: ok mit approx_pos", result["status"] == "ok"
                         and "approx_pos" in result["data"], summary(result))
        server.console("smartcraft locate off")

        # --- Nether-Lauf 17: Spawner in einem geschlossenen Ziegelraum, Bot 11 Blöcke darüber: farm_spawner muss hinein.
        x, z = 500, 500
        server.console(f"{NETHER} tp {BOT} {x}.5 {Y + 30} {z}.5", wait=3)
        server.console(f"{NETHER} fill {x - 6} {Y - 1} {z - 6} {x + 6} {Y + 10} {z + 6} netherrack")
        server.console(f"{NETHER} fill {x - 4} {Y - 1} {z - 4} {x + 4} {Y + 4} {z + 4} nether_bricks hollow")
        server.console(f"{NETHER} setblock {x} {Y} {z} spawner")
        server.console(f"{NETHER} fill {x - 1} {Y + 11} {z - 1} {x + 1} {Y + 13} {z + 1} air")
        server.console(f"{NETHER} tp {BOT} {x}.5 {Y + 11} {z}.5", wait=1)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} diamond_pickaxe")
        server.console(f"give {BOT} netherrack 64", wait=0.5)
        # Durchs Dach sind es 5 Blöcke Fall: farm_spawner erlaubt 5 bei gesundem Leben (Nether-Lauf 25).
        result, _ = server.run("farm_spawner", {"item": "diamond", "count": 1, "seconds": 60}, timeout=120)
        look = server.look()
        inside = abs(look["pos"][1] - Y) <= 1
        honest = (result["status"] == "failed" and result.get("reason") == "out_of_reach" and "hint" in result["data"]
                  or result["data"].get("stand_off", 0) > 3 and result["data"].get("path_problem"))
        report.check("farm_spawner vom Festungsdach: 5 Blöcke hinunter in den Raum, steht am Spawner",
                     inside, f"pos {look['pos']}; honest={honest}; " + summary(result))
        server.console(f"{NETHER} fill {x - 6} {Y - 1} {z - 6} {x + 6} {Y + 13} {z + 6} air")

        # --- Nether-Lauf 19: Pfeil traf einen friedlichen Zombie-Piglin in der Schusslinie.
        x, z = 600, 600
        server.console(f"{NETHER} tp {BOT} {x}.5 {Y + 20} {z}.5", wait=3)
        server.console(f"{NETHER} fill {x - 3} {Y - 1} {z - 3} {x + 10} {Y - 1} {z + 3} netherrack")
        server.console(f"{NETHER} fill {x - 3} {Y} {z - 3} {x + 10} {Y + 4} {z + 3} air")
        server.console(f"{NETHER} tp {BOT} {x}.5 {Y} {z}.5", wait=1)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} bow")
        server.console(f"give {BOT} arrow 16", wait=0.3)
        server.console(f"{NETHER} kill @e[type=!player,distance=..20,x={x},y={Y},z={z}]")
        server.console(f"{NETHER} summon zombie {x + 8}.5 {Y} {z}.5 {{NoAI:1b,PersistenceRequired:1b}}")
        server.console(f"{NETHER} summon zombified_piglin {x + 4}.5 {Y} {z}.5 {{NoAI:1b,PersistenceRequired:1b}}", wait=0.5)
        result, _ = server.run("shoot", {"target": "zombie", "shots": 3}, timeout=40)
        mark = server.output_mark()
        server.console(f"{NETHER} execute if entity @e[type=zombified_piglin,x={x},y={Y},z={z},distance=..8,nbt={{Health:20.0f}}]", wait=0.6)
        unhurt = server.find_in_output("Test passed", mark) is not None
        report.check("shoot mit Zombie-Piglin in der Schusslinie: line_blocked, Piglin unverletzt",
                     result.get("reason") == "line_blocked" and unhurt
                     and result["data"].get("arrows_left") == 16, summary(result))
        server.console(f"{NETHER} kill @e[type=!player,distance=..20,x={x},y={Y},z={z}]")

        # --- Drachenlauf 13: Schwungschlag gegen einen Magmawürfel traf einen Zombie-Piglin daneben.
        x, z = 400, 400
        server.console(f"effect give {BOT} resistance 20 4")
        server.console(f"{NETHER} tp {BOT} {x}.5 {Y + 20} {z}.5", wait=3)  # erst Chunk laden, sonst greift fill nicht
        server.console(f"{NETHER} fill {x - 3} {Y - 1} {z - 3} {x + 6} {Y - 1} {z + 3} netherrack")
        server.console(f"{NETHER} fill {x - 3} {Y} {z - 3} {x + 6} {Y + 3} {z + 3} air")
        server.console(f"{NETHER} tp {BOT} {x}.5 {Y} {z}.5", wait=2)
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} diamond_sword", wait=0.3)
        server.console(f"{NETHER} kill @e[type=!player,distance=..20,x={x},y={Y},z={z}]")
        server.console(f"{NETHER} summon zombie {x + 2}.5 {Y} {z}.5 {{NoAI:1b,PersistenceRequired:1b}}")
        server.console(f"{NETHER} summon zombified_piglin {x + 2}.5 {Y} {z + 1}.5 {{NoAI:1b,PersistenceRequired:1b}}", wait=0.5)
        result, _ = server.run("attack", {"target": "zombie"}, timeout=40)
        mark = server.output_mark()
        server.console(f"{NETHER} execute if entity @e[type=zombified_piglin,x={x},y={Y},z={z},distance=..6,nbt={{Health:20.0f}}]", wait=0.6)
        unhurt = server.find_in_output("Test passed", mark) is not None
        report.check("attack neben einem Zombie-Piglin: Zombie tot, Piglin unverletzt (kein Schwungschlag)",
                     result["status"] == "ok" and unhurt, summary(result))
        server.console(f"{NETHER} kill @e[type=!player,distance=..20,x={x},y={Y},z={z}]")
    finally:
        server.console(f"execute in minecraft:overworld run tp {BOT} 0 -60 0", wait=2)
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

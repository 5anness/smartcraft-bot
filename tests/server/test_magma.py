"""Servertest: Bot steht auf einem Magmablock und muss herunter (KI-Lauf 15.10.2026: Tod durch hot_floor).

Aufruf:  python tests/server/test_magma.py
Flache Testwelt, ca. 1 Minute. Geprüft wird, dass goto und flee auf Magma nicht mit no_start scheitern und der Lava-Reflex ans Ufer führt.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, FLOOR, STAND, arena  # noqa: E402


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-magma.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console(f"smartcraft join {BOT}", wait=5)

        # --- Magmafeld 5 x 5, Bot in der Mitte: goto zum Rand.
        x, z = 700, 520
        arena(server, x, z)
        server.console(f"fill {x - 2} {FLOOR} {z - 2} {x + 2} {FLOOR} {z + 2} magma_block")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=0.8)
        result, _ = server.run("goto", {"pos": [x + 6, STAND, z]}, timeout=30)
        report.check("goto vom Magmablock herunter: ok", result["status"] == "ok", summary(result))

        # --- Ein einzelner Magmablock: flee vor einem Zombie.
        x, z = 700, 560
        arena(server, x, z)
        server.console(f"setblock {x} {FLOOR} {z} magma_block")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=0.8)
        server.console(f"summon zombie {x - 3}.5 {STAND} {z}.5 {{NoAI:1b,PersistenceRequired:1b}}", wait=0.5)
        result, _ = server.run("flee", {"from": "zombie", "distance": 8}, timeout=30)
        report.check("flee vom Magmablock: kein no_start, ok",
                     result["status"] == "ok" and "no_start" not in str(result["data"].get("hint", "")),
                     summary(result))
        server.console("kill @e[type=zombie]")

        # --- In einem Lavabecken 5 x 5 (1 tief): Reflex läuft ans Ufer, auch während goto läuft.
        x, z = 700, 600
        arena(server, x, z)
        server.console(f"fill {x - 2} {FLOOR} {z - 2} {x + 2} {FLOOR} {z + 2} lava")
        server.console(f"effect give {BOT} fire_resistance 60 1")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=4)
        look = server.look()
        px, _, pz = look["pos"]
        report.check("Lava-Reflex: steht nach 4 s außerhalb des Beckens",
                     abs(px - x) > 2 or abs(pz - z) > 2, f"pos {look['pos']}")
        server.console(f"fill {x - 2} {FLOOR} {z - 2} {x + 2} {FLOOR} {z + 2} grass_block")

        # --- In Lava ohne Feuerresistenz-Effekt, aber mit Trank: trinkt ihn sofort.
        x, z = 700, 640
        arena(server, x, z)
        server.console(f"effect clear {BOT}")
        server.console(f'give {BOT} potion[potion_contents={{potion:"minecraft:fire_resistance"}}] 1')
        server.console(f"effect give {BOT} resistance 10 2")
        server.console(f"fill {x - 2} {FLOOR} {z - 2} {x + 2} {FLOOR} {z + 2} lava")
        mark = len(server.all_events)
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=5)
        drank = [e for e in server.all_events[mark:] if e["name"] == "reflex_ate"
                 and e["data"].get("reason") == "fire_resistance" and e["data"].get("eaten")]
        report.check("In Lava: Trank der Feuerresistenz getrunken", bool(drank), str(drank[:1]))
        server.console(f"fill {x - 2} {FLOOR} {z - 2} {x + 2} {FLOOR} {z + 2} grass_block")

        # --- KI-Lauf 15.10.: Im Feuer stehend erst flee (brach wegen danger ab), dann shelter (baute das Feuer mit ein).
        x, z = 700, 680
        arena(server, x, z)
        server.console(f"effect clear {BOT}")
        server.console(f"effect give {BOT} resistance 30 4")
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} cobblestone 32")
        server.console(f"fill {x - 3} {FLOOR} {z - 3} {x + 3} {FLOOR} {z + 3} netherrack")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=0.8)
        server.console(f"setblock {x} {STAND} {z} fire", wait=0.3)
        server.console(f"summon zombie {x - 3}.5 {STAND} {z}.5 {{NoAI:1b,PersistenceRequired:1b}}", wait=0.5)
        result, _ = server.run("flee", {"from": "zombie", "distance": 8}, timeout=30)
        report.check("flee aus dem Feuer: kein Abbruch wegen danger, ok", result["status"] == "ok", summary(result))
        server.console("kill @e[type=zombie]")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=0.8)
        server.console(f"setblock {x} {STAND} {z} fire", wait=0.3)
        result, _ = server.run("shelter", timeout=30)
        report.check("shelter im Feuer: Hütte steht, Feuer innen gelöscht",
                     result["status"] == "ok" and not server.block_is(x, STAND, z, "fire"), summary(result))
        server.console(f"fill {x - 3} {STAND} {z - 3} {x + 3} {STAND + 3} {z + 3} air")
        server.console(f"fill {x - 3} {FLOOR} {z - 3} {x + 3} {FLOOR} {z + 3} grass_block")

        # --- Nether-Lauf 1: 20 s im Seelenfeuer gestanden. Reflex: ohne Befehl hinaus.
        x, z = 700, 720
        arena(server, x, z)
        server.console(f"effect clear {BOT}")
        server.console(f"effect give {BOT} resistance 30 4")
        server.console(f"clear {BOT}")
        server.console(f"fill {x - 1} {FLOOR} {z - 1} {x + 1} {FLOOR} {z + 1} soul_sand")
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=0.5)
        server.console(f"fill {x - 1} {STAND} {z - 1} {x + 1} {STAND} {z + 1} soul_fire", wait=3)
        look = server.look()
        px, _, pz = look["pos"]
        report.check("Feuer-Reflex: steht nach 3 s außerhalb des Seelenfeuers",
                     abs(px - x) > 1 or abs(pz - z) > 1, f"pos {look['pos']}")
        server.console(f"fill {x - 1} {STAND} {z - 1} {x + 1} {STAND} {z + 1} air")
        server.console(f"fill {x - 1} {FLOOR} {z - 1} {x + 1} {FLOOR} {z + 1} grass_block")

        # --- Nether-Lauf 2: Brücke von goto über einen Abgrund bekommt ein Geländer.
        x, z = 760, 760
        arena(server, x, z)
        hy = STAND + 20
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} cobblestone 64")
        server.console(f"give {BOT} cobblestone 64")
        server.console(f"give {BOT} obsidian 10")  # Nether-Lauf 4: Obsidian darf nicht in die Brücke
        server.console(f"fill {x - 2} {hy - 1} {z - 1} {x} {hy - 1} {z + 1} stone")
        server.console(f"fill {x + 5} {hy - 1} {z - 1} {x + 7} {hy - 1} {z + 1} stone")
        # Ohne Schützen in der Nähe: kein Geländer (Nether-Lauf 15: Geländer fraßen die Bausteine).
        server.console(f"tp {BOT} {x - 1}.5 {hy} {z}.5", wait=1)
        result, _ = server.run("goto", {"pos": [x + 6, hy, z], "tolerance": 0, "allow_place": True}, timeout=60)
        bare = sum(server.block_is(x + dx, hy, z + dz, "cobblestone") for dx in (1, 2, 3, 4) for dz in (-1, 1))
        report.check("goto über einen Abgrund ohne Schützen: kein Geländer", result["status"] == "ok" and bare == 0,
                     f"Geländer {bare}; " + summary(result))
        server.console(f"fill {x + 1} {hy - 1} {z - 2} {x + 4} {hy} {z + 2} air")
        # Mit einem Skelett in der Nähe: Geländer.
        server.console(f"summon skeleton {x - 1}.5 {hy} {z - 1}.5 {{NoAI:1b,PersistenceRequired:1b}}")
        server.console(f"tp {BOT} {x - 1}.5 {hy} {z}.5", wait=1)
        result, _ = server.run("goto", {"pos": [x + 6, hy, z], "tolerance": 0, "allow_place": True}, timeout=60)
        server.console("kill @e[type=skeleton]")
        rails = sum(server.block_is(x + dx, hy, z + dz, "cobblestone") for dx in (1, 2, 3, 4) for dz in (-1, 1))
        report.check("goto über einen Abgrund mit Skelett daneben: Brücke mit Geländer (mind. 4 Blöcke)",
                     result["status"] == "ok" and rails >= 4, f"Geländer {rails}; " + summary(result))
        obsidian = sum(server.block_is(x + dx, hy - 1 + dy, z + dz, "obsidian")
                       for dx in (1, 2, 3, 4) for dy in (0, 1) for dz in (-1, 0, 1))
        report.check("Brücke ohne Obsidian gebaut", obsidian == 0, f"{obsidian} Obsidian verbaut")
        server.console(f"fill {x - 2} {hy - 1} {z - 2} {x + 7} {hy} {z + 2} air")

        # --- Nether-Lauf 3: Bei wenig Leben mit Gegner daneben einen goldenen Apfel essen.
        x, z = 760, 800
        arena(server, x, z)
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=0.5)
        server.console(f"effect clear {BOT}")
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} golden_apple 2")
        server.console(f"summon zombie {x + 4}.5 {STAND} {z}.5 {{NoAI:1b,PersistenceRequired:1b}}")
        mark = len(server.all_events)
        server.console(f"damage {BOT} 12 minecraft:generic", wait=4)
        ate = [e for e in server.all_events[mark:] if e["name"] == "reflex_ate" and e["data"].get("reason") == "combat_apple"
               and e["data"].get("eaten")]
        report.check("Kampf-Reflex: goldener Apfel bei wenig Leben mit Gegner daneben", bool(ate), str(ate[:1]))
        server.console("kill @e[type=zombie]")

        # --- Nether-Lauf 26: mitten im Lavasee ohne nahes Ufer. Reflex schwimmt zum fernen Ufer oder meldet
        # reflex_lava_stuck und gibt die Aktion frei (vorher 7 Minuten gesperrt).
        x, z = 1000, 1000
        server.console(f"tp {BOT} {x}.5 {STAND + 5} {z}.5", wait=3)
        server.console(f"fill {x - 40} {FLOOR} {z - 40} {x + 40} {FLOOR} {z + 40} grass_block")
        server.console(f"fill {x - 40} {STAND} {z - 40} {x + 40} {STAND + 4} {z + 40} air")
        server.console(f"fill {x - 35} {FLOOR} {z - 35} {x + 35} {FLOOR} {z + 35} lava")
        server.console(f"effect clear {BOT}")
        server.console(f"effect give {BOT} fire_resistance 120 1")
        server.console(f"clear {BOT}")
        mark = len(server.all_events)
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=1)
        out = False
        for _ in range(30):
            server.drain(2)
            look = server.look()
            px, _, pz = look["pos"]
            if abs(px - x) > 35 or abs(pz - z) > 35:
                out = True
                break
        stuck = [e for e in server.all_events[mark:] if e["name"] == "reflex_lava_stuck"]
        answered, _ = server.run("inventory", timeout=10)
        report.check("Mitten im Lavasee: schwimmt hinaus oder meldet reflex_lava_stuck, Befehle gehen weiter",
                     (out or bool(stuck)) and answered.get("status") == "ok",
                     f"out={out}, stuck={stuck[:1]}, pos {look['pos']}")
        server.console(f"fill {x - 35} {FLOOR} {z - 35} {x + 35} {FLOOR} {z + 35} grass_block")

        # --- Nether-Lauf 34: Eine Enderperle setzte ihn in Basalt ab, er erstickte. Reflex gräbt ihn frei.
        x, z = 1040, 1000
        arena(server, x, z)
        server.console(f"effect clear {BOT}")
        server.console(f"clear {BOT}")
        server.console(f"give {BOT} diamond_pickaxe")
        server.console(f"fill {x - 1} {STAND} {z - 1} {x + 1} {STAND + 2} {z + 1} basalt")
        mark = len(server.all_events)
        server.console(f"tp {BOT} {x}.5 {STAND} {z}.5", wait=5)
        dug = [e for e in server.all_events[mark:] if e["name"] == "reflex_dug_out" and e["data"].get("ok")]
        died = [e for e in server.all_events[mark:] if e["name"] == "player_died"]
        look = server.look()
        report.check("In Basalt eingeschlossen: Reflex gräbt den Block am Kopf frei, kein Ersticken",
                     len(dug) >= 1 and not died, f"{len(dug)} Blöcke, Leben {look.get('health')}, tot {bool(died)}")
        server.console(f"fill {x - 1} {STAND} {z - 1} {x + 1} {STAND + 2} {z + 1} air")
    finally:
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

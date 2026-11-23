"""Servertest der Fehlerbehebungen nach dem ersten echten KI-Lauf (14.10.2026).

Aufruf:  python tests/server/test_fixes.py
Läuft auf der flachen Testwelt, ca. 4 Minuten. Geprüft wird:
- shelter am Hang (Stützblock unter der Wand) und Eingraben, wenn die Hütte nicht geht
- collect baut die ganze Erzader ab und gibt nach 8 unerreichbaren Blöcken auf
- attack nennt das nächste Tier außerhalb von 32 Blöcken; gibt auf, wenn das Ziel nah, aber nicht treffbar ist
- craft: Bett aus Wolle statt Umfärbe-Rezept; läuft nicht weit zur alten Werkbank, wenn eine neue billig ist
- night_falling meldet underground; look meldet world und seed
- descend gräbt bis zur Zielhöhe und hält vor Lava und Höhlen an
- shelter im Wasser wird abgelehnt; goto kommt aus tiefem Wasser heraus
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, FLOOR, STAND, arena  # noqa: E402


def failed(result, reason):
    return result["status"] == "failed" and result.get("reason") == reason


def fresh(server, x, z, time_of_day="noon"):
    arena(server, x, z)
    server.console(f"time set {time_of_day}")
    server.console(f"clear {BOT}")
    server.console(f"effect give {BOT} minecraft:instant_health 1 10")
    server.console(f"effect give {BOT} minecraft:saturation 1 20")
    server.console("kill @e[type=item]", wait=0.5)
    server.drain(0.3)


def daylight(server, on):
    # Der Name der Regel hat sich geändert; wie in test_course.py beide versuchen.
    for rule in ("advance_time", "doDaylightCycle"):
        server.console(f"gamerule {rule} {'true' if on else 'false'}", wait=0.1)


def wait_event(server, mark, name, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        server.drain(0.5)
        for event in server.all_events[mark:]:
            if event["name"] == name:
                return event
    return None


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-fixes.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        daylight(server, False)
        server.console("difficulty normal")
        server.console(f"smartcraft join {BOT}", wait=5)
        snapshot(server, report)
        shelter(server, report)
        collect(server, report)
        attack(server, report)
        craft(server, report)
        night(server, report)
        descend(server, report)
        water(server, report)
    finally:
        # Die Testwelt wie vorgefunden hinterlassen: Uhr läuft, Morgen (andere Tests erwarten das).
        daylight(server, True)
        server.console("time set 1000", wait=0.5)
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


def snapshot(server, report):
    look = server.look()
    report.check("look meldet world und seed (Notizen pro Welt)",
                 isinstance(look.get("world"), str) and isinstance(look.get("seed"), int), f"{look.get('world')} {look.get('seed')}")


def shelter(server, report):
    # --- Am Hang: südlich vom Bot geht es drei Blöcke hinab, die Südwand hätte keinen Halt.
    x, z = 600, 100
    fresh(server, x, z, "midnight")
    server.console(f"fill {x - 3} {FLOOR - 2} {z + 1} {x + 3} {FLOOR} {z + 3} air", wait=0.4)
    server.console(f"give {BOT} cobblestone 32", wait=0.4)
    result, _ = server.run("shelter", timeout=90)
    data = result["data"]
    report.check("shelter an der Kante: Hütte mit Stützblock unter der Wand, rundum zu",
                 result["status"] == "ok" and data.get("kind") == "hut" and data["enclosed"] is True
                 and data["blocks_placed"] == 11, summary(result))

    # --- Hütte geht nicht (eine Kuh steht dauerhaft in der Wand): stattdessen eingraben.
    x, z = 600, 140
    fresh(server, x, z, "midnight")
    server.console(f"give {BOT} cobblestone 32", wait=0.4)
    server.console(f"summon cow {x + 1}.5 {STAND} {z}.5 {{NoAI:1b,PersistenceRequired:1b}}", wait=0.4)
    result, _ = server.run("shelter", timeout=120)
    data = result["data"]
    report.check("shelter, Hütte versperrt: gräbt sich ein (hut_failed), rundum zu",
                 result["status"] == "ok" and data.get("kind") == "burrow" and data.get("hut_failed")
                 and data["enclosed"] is True, summary(result))
    server.console("kill @e[type=cow]")


def collect(server, report):
    # --- Eine Ader aus 5 Eisenerz im Boden; verlangt ist nur 1.
    x, z = 600, 200
    fresh(server, x, z)
    server.console(f"give {BOT} stone_pickaxe 1", wait=0.3)
    server.console(f"fill {x + 4} {FLOOR - 1} {z} {x + 5} {FLOOR - 1} {z + 1} iron_ore")
    server.console(f"setblock {x + 4} {FLOOR - 2} {z} iron_ore", wait=0.4)
    result, _ = server.run("collect", {"block": "iron_ore", "count": 1, "radius": 12, "allow_dig": True}, timeout=180)
    data = result["data"]
    raw = sum(entry["count"] for entry in data.get("gained", []) if entry["id"] == "minecraft:raw_iron")
    report.check("collect iron_ore 1 an einer Ader aus 5: baut alle 5 ab (vein_extra 4)",
                 result["status"] == "ok" and data["mined"] == 5 and data.get("vein_extra") == 4 and raw == 5,
                 summary(result))

    # --- Zehn Erze schweben unerreichbar in der Luft; ohne Blöcke kann er nicht hochbauen.
    x, z = 600, 240
    fresh(server, x, z)
    server.console(f"give {BOT} stone_pickaxe 1", wait=0.3)
    for i in range(10):
        server.console(f"setblock {x + 4 + i * 2} {STAND + 8} {z} coal_ore")
    server.drain(0.5)
    started = time.time()
    result, _ = server.run("collect", {"block": "coal_ore", "count": 5, "radius": 32}, timeout=300)
    took = time.time() - started
    report.check("collect, 10 Erze alle unerreichbar: gibt nach 8 auf (unreachable_streak)",
                 failed(result, "none_reachable") and result["data"].get("stopped_because") == "unreachable_streak"
                 and result["data"]["unreachable"] == 8, f"{took:.0f} s, {summary(result)}")


def attack(server, report):
    # --- Kuh 70 Blöcke weit weg: entity_not_found mit nearest.
    x, z = 600, 300
    fresh(server, x, z)
    server.console(f"forceload add {x + 60} {z} {x + 80} {z}")
    server.console(f"fill {x + 68} {FLOOR} {z - 2} {x + 72} {FLOOR} {z + 2} grass_block")
    server.console(f"summon cow {x + 70}.5 {STAND} {z}.5 {{NoAI:1b,PersistenceRequired:1b}}", wait=0.5)
    result, _ = server.run("attack", {"target": "cow"})
    data = result["data"]
    report.check("attack cow, Kuh 70 Blöcke weit: entity_not_found mit nearest und nearest_dist",
                 failed(result, "entity_not_found") and data.get("nearest") == [x + 70, STAND, z]
                 and 65 <= data.get("nearest_dist", 0) <= 75, summary(result))
    server.console("kill @e[type=cow]")
    server.console(f"forceload remove {x + 60} {z} {x + 80} {z}")

    # --- Zombie im Glaskasten direkt neben dem Bot: nah, aber nie treffbar.
    x, z = 600, 340
    fresh(server, x, z)
    server.console(f"give {BOT} iron_sword 1", wait=0.3)
    server.console(f"fill {x + 1} {STAND} {z - 1} {x + 3} {STAND + 3} {z + 1} glass")
    server.console(f"fill {x + 2} {STAND} {z} {x + 2} {STAND + 1} {z} air")
    server.console(f"summon zombie {x + 2}.5 {STAND} {z}.5 {{NoAI:1b,PersistenceRequired:1b}}", wait=0.5)
    started = time.time()
    result, _ = server.run("attack", {"target": "zombie"}, timeout=120)
    took = time.time() - started
    report.check("attack auf Zombie hinter Glas: gibt nach etwa 10 s auf (no_path), statt 90 s bis zum Zeitlimit",
                 failed(result, "no_path") and took < 30, f"{took:.0f} s, {summary(result)}")
    server.console("kill @e[type=zombie]")


def craft(server, report):
    # --- Bett: 2 weiße und 1 graue Wolle. Es fehlt eine weiße Wolle, kein Farbstoff.
    x, z = 600, 400
    fresh(server, x, z)
    server.console(f"give {BOT} white_wool 2")
    server.console(f"give {BOT} gray_wool 1")
    server.console(f"give {BOT} oak_planks 3", wait=0.4)
    result, _ = server.run("craft", {"item": "white_bed"})
    missing = [entry.get("item") for entry in result["data"].get("needs", [])
               if entry.get("count", 0) > entry.get("have", 0)]
    report.check("craft white_bed mit 2 weißer Wolle: missing_ingredients nennt weiße Wolle, keinen Farbstoff",
                 failed(result, "missing_ingredients") and "minecraft:white_wool" in str(missing)
                 and "dye" not in str(result["data"]), summary(result))

    # --- Alte Werkbank 40 Blöcke weit, Bretter für eine neue dabei: nicht hinlaufen.
    server.console(f"setblock {x + 2} {STAND} {z} crafting_table", wait=0.3)
    server.console(f"give {BOT} oak_planks 12")
    server.console(f"give {BOT} stick 4", wait=0.4)
    result, _ = server.run("craft", {"item": "wooden_pickaxe"}, timeout=60)
    report.check("craft wooden_pickaxe neben einer Werkbank: ok (Werkbank gemerkt)", result["status"] == "ok", summary(result))
    server.console(f"tp {BOT} {x + 28}.5 {STAND} {z}.5", wait=0.4)
    server.console(f"fill {x + 20} {STAND} {z - 14} {x + 30} {STAND + 9} {z + 14} air", wait=0.4)
    server.console(f"tp {BOT} {x + 28}.5 {STAND} {z + 10}.5", wait=0.6)
    started = time.time()
    result, _ = server.run("craft", {"item": "wooden_axe"}, timeout=60)
    took = time.time() - started
    report.check("craft wooden_axe 28 Blöcke von der alten Werkbank, Bretter dabei: no_crafting_table sofort",
                 failed(result, "no_crafting_table") and result["data"].get("can_craft_crafting_table") is True
                 and took < 10, f"{took:.0f} s, {summary(result)}")


def night(server, report):
    # --- Unter der Erde: Decke aus Stein über dem Bot.
    x, z = 600, 460
    fresh(server, x, z, "11300")
    # night_falling kommt einmal je Spieltag: auf einen neuen Tag springen (die Welt kann schon am Abend stehen).
    server.console("time add 24000")
    server.console(f"fill {x - 2} {STAND + 2} {z - 2} {x + 2} {STAND + 2} {z + 2} stone", wait=0.4)
    mark = len(server.all_events)
    daylight(server, True)
    falling = wait_event(server, mark, "night_falling", 25)
    daylight(server, False)
    report.check("night_falling unter einer Decke: underground true",
                 falling is not None and falling["data"].get("underground") is True, str(falling and falling["data"]))


def descend(server, report):
    # --- Zwei Blöcke hinab bis knapp über das Bedrock.
    x, z = 600, 520
    fresh(server, x, z)
    server.console(f"give {BOT} stone_pickaxe 1", wait=0.3)
    result, _ = server.run("descend", {"to_y": FLOOR - 1}, timeout=60)
    report.check("descend bis FLOOR-1: ok, 2 Blöcke gegraben, steht auf der Zielhöhe",
                 result["status"] == "ok" and result["data"]["blocks_dug"] == 2
                 and result["data"]["pos"][1] == FLOOR - 1, summary(result))

    # --- Lava zwei Blöcke unter den Füßen: hält vorher an.
    x, z = 600, 560
    fresh(server, x, z)
    server.console(f"give {BOT} stone_pickaxe 1", wait=0.3)
    server.console(f"setblock {x} {FLOOR - 2} {z} lava", wait=0.4)
    result, _ = server.run("descend", {"to_y": FLOOR - 2}, timeout=60)
    found = result["data"].get("found", {})
    report.check("descend über Lava: lava_below mit found, nichts gegraben, Bot unverletzt",
                 failed(result, "lava_below") and found.get("block") == "minecraft:lava"
                 and result["data"]["blocks_dug"] == 0 and server.look()["health"] >= 19, summary(result))
    server.console(f"setblock {x} {FLOOR - 2} {z} grass_block")

    # --- Auf einem hohlen Turm: darunter 5 Blöcke Luft.
    x, z = 600, 600
    fresh(server, x, z)
    server.console(f"give {BOT} stone_pickaxe 1", wait=0.3)
    server.console(f"fill {x - 1} {STAND} {z - 1} {x + 1} {STAND + 5} {z + 1} stone")
    server.console(f"fill {x} {STAND} {z} {x} {STAND + 4} {z} air")
    server.console(f"tp {BOT} {x}.5 {STAND + 6} {z}.5", wait=0.8)
    result, _ = server.run("descend", {"to_y": STAND}, timeout=60)
    report.check("descend über einem Hohlraum: cave_below, Bot bleibt oben",
                 failed(result, "cave_below") and result["data"]["pos"][1] == STAND + 6, summary(result))


    # --- Schwebender Kies über dem Kopf (wie aus der Weltgenerierung: ohne Block-Update gesetzt).
    x, z = 600, 630
    fresh(server, x, z)
    server.console(f"give {BOT} stone_pickaxe 1", wait=0.3)
    server.console(f"setblock {x} {STAND + 3} {z} gravel strict", wait=0.8)
    if server.block_is(x, STAND + 3, z, "gravel"):
        result, _ = server.run("descend", {"to_y": FLOOR - 1}, timeout=60)
        found = result["data"].get("found", {})
        report.check("descend unter schwebendem Kies: falling_block_above, nichts gegraben",
                     failed(result, "falling_block_above") and found.get("block") == "minecraft:gravel"
                     and result["data"]["blocks_dug"] == 0, summary(result))
    else:
        report.check("descend unter schwebendem Kies: Kies ließ sich nicht schwebend setzen (Test übersprungen)", False, "")


def water(server, report):
    # --- Becken 7x7, drei tief: shelter abgelehnt, goto schwimmt ans Ufer und läuft weiter.
    x, z = 600, 660
    fresh(server, x, z, "midnight")
    server.console(f"give {BOT} cobblestone 32", wait=0.3)
    server.console(f"fill {x - 3} {FLOOR - 2} {z - 3} {x + 3} {FLOOR} {z + 3} water", wait=0.5)
    server.console(f"tp {BOT} {x}.5 {FLOOR} {z}.5", wait=1.5)
    result, _ = server.run("shelter", timeout=60)
    report.check("shelter im Wasser: in_water, nichts gebaut", failed(result, "in_water")
                 and result["data"]["blocks_placed"] == 0, summary(result))
    result, _ = server.run("goto", {"pos": [x + 10, STAND, z]}, timeout=90)
    report.check("goto aus tiefem Wasser an Land 10 Blöcke weiter: ok (vorher no_start)",
                 result["status"] == "ok", summary(result))


if __name__ == "__main__":
    sys.exit(main())

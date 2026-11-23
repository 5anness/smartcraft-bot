"""Eimer, Obsidian, Rechtsklick (`use`), Behälter, Tunnel, Sprinten und die Sicherheitsregeln dazu.

Aufruf:  python tests/server/test_tools.py
Flache Testwelt wie test_movement.py (Boden y=-61, der Bot steht auf y=-60). Dauer etwa 4 Minuten.
Nicht geprüft: items_received (dafür bräuchte es einen zweiten echten Spieler, der wirft).
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, FLOOR, STAND, arena  # noqa: E402

Z = 600


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-tools.log")
    server = TestServer(log)
    report = Report()
    try:
        run(server, report)
    finally:
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


def lane(server, x0, *items):
    """Frische Bahn, leeres Inventar, dann die genannten Items."""
    arena(server, x0, Z)
    server.console(f"clear {BOT}")
    for item in items:
        server.console(f"give {BOT} {item}")
    server.drain(0.4)


def ok(result):
    return result["status"] == "ok"


def failed(result, reason):
    return result["status"] == "failed" and result.get("reason") == reason


def run(server, report):
    server.console("gamerule spawn_mobs false")
    server.console("gamerule fire_spread_radius_around_player 0")
    server.console("smartcraft events off")
    server.console(f"smartcraft join {BOT}", wait=5)

    # --- Obsidian: Wasser auf eine Lavaquelle, Wasser zurück, Obsidian abbauen
    x = 1000
    lane(server, x, "water_bucket", "diamond_pickaxe")
    lava = (x + 3, FLOOR, Z)
    server.console(f"setblock {lava[0]} {lava[1]} {lava[2]} lava", wait=0.4)
    result, _ = server.run("bucket", {"action": "empty", "fluid": "water", "pos": list(lava)})
    report.check("Wasser auf Lavaquelle: ok, lava_now obsidian",
                 ok(result) and result["data"].get("lava_now") == "minecraft:obsidian", summary(result))
    report.check("Obsidian steht im Spiel", server.block_is(*lava, "obsidian"))
    if ok(result):
        water = result["data"]["block_pos"]
        result, _ = server.run("bucket", {"action": "fill", "pos": water})
        report.check("Wasser wieder aufnehmen: ok, ein Wassereimer",
                     ok(result) and result["data"]["water_buckets"] == 1, summary(result))
        server.game_sleep(3)
        report.check("Kein Wasser mehr über dem Obsidian", server.block_is(*water, "air"))
    result, _ = server.run("mine", {"pos": list(lava)}, timeout=60)
    report.check("Obsidian abbauen: ok, im Inventar",
                 ok(result) and any(g["id"] == "minecraft:obsidian" for g in result["data"]["gained"]), summary(result))

    # --- Lava schöpfen und sicher ausgießen
    x = 1100
    lane(server, x, "bucket")
    server.console(f"setblock {x + 3} {FLOOR} {Z} lava", wait=0.4)
    result, _ = server.run("bucket", {"action": "fill", "fluid": "lava"})
    report.check("Lava schöpfen ohne pos: ok, ein Lavaeimer",
                 ok(result) and result["data"]["lava_buckets"] == 1, summary(result))
    hole = (x + 6, FLOOR, Z)
    server.console(f"setblock {hole[0]} {hole[1]} {hole[2]} air", wait=0.3)
    result, _ = server.run("bucket", {"action": "empty", "pos": [hole[0], hole[1] - 1, hole[2]]})
    report.check("Lava in ein Loch gießen (pos = Boden darunter): ok", ok(result), summary(result))
    report.check("Lava steht im Loch", server.block_is(*hole, "lava"))
    server.console(f"setblock {hole[0]} {hole[1]} {hole[2]} grass_block", wait=0.3)
    server.console(f"give {BOT} lava_bucket", wait=0.3)
    target = [x + 12, STAND, Z]
    result, _ = server.run("bucket", {"action": "empty", "pos": target})
    pos = result["data"].get("pos", [0, 0, 0])
    distance = max(abs(pos[0] - target[0]), abs(pos[2] - target[2]))
    report.check("Lava auf gleicher Höhe: ok, aus mindestens 4 Blöcken Abstand",
                 ok(result) and distance >= 4, f"Abstand {distance}; {summary(result)}")
    server.console(f"fill {x + 6} {STAND} {Z - 6} {x + 18} {STAND} {Z + 6} air", wait=0.3)

    # --- Feuerzeug: Netherportal anzünden
    x = 1200
    lane(server, x, "flint_and_steel")
    fx = x + 4
    server.console(f"fill {fx} {STAND} {Z} {fx + 3} {STAND + 4} {Z} obsidian")
    server.console(f"fill {fx + 1} {STAND + 1} {Z} {fx + 2} {STAND + 3} {Z} air", wait=0.4)
    result, _ = server.run("use", {"item": "flint_and_steel", "pos": [fx + 1, STAND, Z], "face": "up"})
    report.check("Feuerzeug auf den Rahmen: ok, beside_after nether_portal",
                 ok(result) and result["data"].get("beside_after") == "minecraft:nether_portal", summary(result))
    server.console(f"fill {fx} {STAND} {Z} {fx + 3} {STAND + 4} {Z} air", wait=0.3)
    feet = result["data"]["pos"]
    result, _ = server.run("use", {"item": "flint_and_steel", "pos": [feet[0], feet[1] - 1, feet[2]], "face": "up"})
    report.check("Feuerzeug auf das eigene Feld: unsafe", failed(result, "unsafe"), summary(result))

    # --- Tür, Kuh melken, Fass
    x = 1300
    lane(server, x, "bucket", "dirt 10")
    server.console(f"setblock {x + 3} {STAND} {Z} oak_door[half=lower,facing=west]")
    server.console(f"setblock {x + 3} {STAND + 1} {Z} oak_door[half=upper,facing=west]", wait=0.3)
    result, _ = server.run("use", {"pos": [x + 3, STAND, Z]})
    report.check("Tür mit leerer Hand öffnen: ok", ok(result), summary(result))
    report.check("Tür ist offen", server.block_is(x + 3, STAND, Z, "oak_door[open=true]"))
    server.console(f"summon cow {x + 2} {STAND} {Z + 4} {{NoAI:1b}}", wait=0.4)
    result, _ = server.run("use", {"item": "bucket", "entity": "cow"})
    report.check("Kuh melken: ok, Milcheimer bekommen",
                 ok(result) and any(g["id"] == "minecraft:milk_bucket" for g in result["data"]["gained"]), summary(result))
    barrel = [x + 2, STAND, Z - 3]
    server.console(f"setblock {barrel[0]} {barrel[1]} {barrel[2]} barrel", wait=0.3)
    result, _ = server.run("store", {"item": "dirt", "count": 4, "pos": barrel})
    report.check("store mit pos in ein Fass: 4 Erde", ok(result) and result["data"]["stored"] == 4, summary(result))
    result, _ = server.run("use", {"pos": barrel})
    report.check("use auf das Fass zeigt den Inhalt",
                 ok(result) and result["data"].get("contents", {}).get("minecraft:dirt") == 4, summary(result))
    result, _ = server.run("take", {"item": "dirt", "count": 2, "pos": barrel})
    report.check("take mit pos aus dem Fass: 2 Erde", ok(result) and result["data"]["taken"] == 2, summary(result))

    # --- Kein Holz neben Lava, Zombie-Piglin nicht angreifen
    x = 1400
    lane(server, x, "oak_planks 4", "iron_sword")
    server.console(f"setblock {x + 4} {FLOOR} {Z} lava", wait=1.0)
    server.drain(0.5)
    result, _ = server.run("place", {"block": "oak_planks", "pos": [x + 4, STAND, Z + 1]})
    if result["status"] == "cancelled" and result.get("reason") == "danger":
        # Die neue Lava meldet sich als Gefahr und bricht ab; die KI gibt den Befehl dann neu.
        result, _ = server.run("place", {"block": "oak_planks", "pos": [x + 4, STAND, Z + 1]})
    report.check("Bretter neben Lava: flammable_near_fire", failed(result, "flammable_near_fire"), summary(result))
    server.console(f"setblock {x + 4} {FLOOR} {Z} grass_block")
    server.console(f"summon zombified_piglin {x + 4} {STAND} {Z + 4} {{NoAI:1b}}", wait=0.4)
    result, _ = server.run("attack", {"target": "zombified_piglin"})
    report.check("Friedlicher Zombie-Piglin: would_anger_group", failed(result, "would_anger_group"), summary(result))

    # --- Tunnel: Gang durch Stein, Halt bei Erz und vor Lava
    x = 1500
    lane(server, x, "iron_pickaxe")
    server.console(f"fill {x + 1} {STAND} {Z - 3} {x + 30} {STAND + 3} {Z + 3} stone", wait=0.5)
    result, _ = server.run("tunnel", {"direction": "east", "length": 6, "stop_at_ore": False}, timeout=120)
    report.check("tunnel 6 nach Osten: ok, 6 Schritte", ok(result) and result["data"]["length_done"] == 6, summary(result))
    report.check("Gang ist 2 hoch frei",
                 server.block_is(x + 6, STAND, Z, "air") and server.block_is(x + 6, STAND + 1, Z, "air"))
    server.console(f"setblock {x + 9} {STAND + 1} {Z + 1} diamond_ore", wait=0.3)
    result, _ = server.run("tunnel", {"direction": "east", "length": 10}, timeout=120)
    report.check("tunnel hält bei neuem Erz: ore_found",
                 ok(result) and result["data"].get("stopped_because") == "ore_found"
                 and any(o["block"] == "minecraft:diamond_ore" for o in result["data"]["ores_seen"]), summary(result))
    here = result["data"].get("pos", [x, STAND, Z])
    server.console(f"setblock {here[0] + 3} {STAND} {Z + 1} lava", wait=0.3)
    result, _ = server.run("tunnel", {"direction": "east", "length": 10, "stop_at_ore": False}, timeout=120)
    report.check("tunnel hält vor Lava in der Wand: lava_ahead", failed(result, "lava_ahead"), summary(result))
    report.check("Bot steht nicht in Lava", server.block_is(*result["data"]["pos"], "air"))

    # --- Sprinten: 24 Blöcke geradeaus deutlich schneller als Gehen (Gehen ~111 Ticks, Sprinten ~86)
    x = 1600
    lane(server, x)
    server.console(f"effect give {BOT} saturation 5 10", wait=0.3)
    result, _ = server.run("goto", {"pos": [x + 24, STAND, Z], "tolerance": 0})
    report.check("goto 24 Blöcke gerade: unter 100 Ticks (sprintet)",
                 ok(result) and result["data"]["ticks"] < 100, summary(result))


    # --- Wasser von oben: Block über dem Kopf abbauen, darüber Wasser; der Reflex baut die Lücke zu
    x = 1700
    lane(server, x, "iron_pickaxe", "cobblestone 32")
    server.console(f"fill {x - 2} {STAND + 2} {Z - 2} {x + 2} {STAND + 2} {Z + 2} stone")
    server.console(f"fill {x - 2} {STAND + 3} {Z - 2} {x + 2} {STAND + 3} {Z + 2} glass")
    server.console(f"setblock {x} {STAND + 3} {Z} water", wait=0.5)
    server.console(f"setblock {x} {STAND + 3} {Z} glass")
    server.console(f"setblock {x} {STAND + 4} {Z} water", wait=0.4)
    server.console(f"setblock {x} {STAND + 3} {Z} air", wait=0.3)
    server.console(f"setblock {x} {STAND + 3} {Z} water", wait=0.3)
    server.drain(0.3)
    result, events = server.run("mine", {"pos": [x, STAND + 2, Z]})
    events += server.drain(2.0)
    sealed = [e for e in events if e["name"] == "reflex_sealed_water"]
    report.check("Wasser von oben nach dem Abbau: Reflex setzt einen Block in die Lücke",
                 ok(result) and sealed and sealed[0]["data"]["sealed"]
                 and not server.block_is(x, STAND + 2, Z, "water"), str([e["data"] for e in sealed]))

    # --- Wassertasche: Bot steht in einem gefluteten Loch, seal_water baut es zu
    x = 1800
    lane(server, x, "cobblestone 64", "iron_pickaxe")
    # Ein Loch mit Wasser auf Fußhöhe und Luft darüber, wie eine geflutete Höhlenmulde.
    server.console(f"fill {x - 3} {STAND} {Z - 3} {x + 3} {STAND + 3} {Z + 3} stone")
    server.console(f"fill {x - 2} {STAND + 1} {Z - 2} {x + 2} {STAND + 2} {Z + 2} air")
    server.console(f"fill {x - 2} {STAND} {Z - 2} {x + 2} {STAND} {Z + 2} water", wait=0.5)
    server.console(f"tp {BOT} {x}.5 {STAND} {Z}.5", wait=1.0)
    server.drain(0.3)
    result, _ = server.run("seal_water", {"radius": 2})
    # Ein flacher Rest Fließwasser im eigenen Feld kann bleiben; entscheidend ist, dass der Kopf frei ist.
    report.check("seal_water in einer Wassertasche: ok, höchstens ein Wasserfeld übrig",
                 ok(result) and result["data"]["blocks_placed"] > 0 and result["data"]["water_left"] <= 1, summary(result))
    result, _ = server.run("mine", {"pos": [x + 1, STAND, Z]})
    report.check("Danach abbauen in normalem Tempo (unter 40 Ticks für Bruchstein)",
                 ok(result) and result["data"]["ticks"] < 40, summary(result))

    # --- Lava zubauen: ein Lavagraben quer vor dem Bot
    x = 2400
    lane(server, x, "cobblestone 64")
    server.console(f"fill {x + 2} {FLOOR} {Z - 1} {x + 3} {FLOOR} {Z + 1} lava", wait=0.6)
    server.drain(1.0)
    result, _ = server.run("seal_lava", {"radius": 4})
    report.check("seal_lava: Graben zugebaut, keine Lava mehr", ok(result) and result["data"]["water_left"] == 0, summary(result))
    result, _ = server.run("goto", {"pos": [x + 6, STAND, Z], "tolerance": 0})
    report.check("Danach über den zugebauten Graben: ok", ok(result), summary(result))

    # --- Spawner einbauen: offener Spawner auf freier Fläche, danach geschlossen
    x = 2500
    lane(server, x, "cobblestone 64", "cobblestone 64")
    server.console(f"setblock {x + 5} {STAND} {Z} spawner", wait=0.4)
    result, _ = server.run("enclose_spawner", timeout=180)
    report.check("enclose_spawner: Raum geschlossen", ok(result) and result["data"]["gaps_left"] == 0, summary(result))
    pos = result["data"].get("pos", [0, 0, 0])
    report.check("Bot steht im Raum neben dem Spawner", abs(pos[0] - (x + 5)) <= 1 and abs(pos[2] - Z) <= 1, str(pos))

    # --- Rundum-Blick: Lava und Obsidian 15 Blöcke entfernt melden sich innerhalb von 5 Sekunden
    x = 1900
    lane(server, x)
    server.drain(6.0)
    # Ein einzelner Lavablock nah, ein See (4 x 4) weiter weg: gemeldet wird der See mit seiner Größe.
    server.console(f"setblock {x + 4} {FLOOR} {Z} lava")
    server.console(f"fill {x + 15} {FLOOR} {Z - 2} {x + 18} {FLOOR} {Z + 1} lava")
    server.console(f"setblock {x + 15} {STAND} {Z + 5} crying_obsidian", wait=0.3)
    events = server.drain(6.5)
    spotted = {e["data"]["kind"]: e["data"] for e in events if e["name"] == "fluid_spotted"}
    report.check("fluid_spotted meldet Lava und Obsidian in 15 Blöcken", {"lava", "obsidian"} <= set(spotted), str(spotted))
    server.console(f"setblock {x + 20} {STAND} {Z - 6} spawner")
    server.console(f"summon blaze {x - 15} {STAND} {Z} {{NoAI:1b}}", wait=0.3)
    more = server.drain(6.5)
    kinds = {e["data"]["kind"] for e in more if e["name"] == "fluid_spotted"}
    report.check("fluid_spotted meldet Spawner und Lohe", {"spawner", "blaze"} <= kinds, str(kinds))
    server.console("kill @e[type=blaze]")
    lava = spotted.get("lava", {})
    report.check("Bei Lava wird der See (16) gemeldet, nicht der nahe Einzelblock",
                 lava.get("size") == 16 and lava.get("pos", [0])[0] >= x + 15, str(lava))
    mark = server.output_mark()
    server.console("smartcraft status", wait=1.0)
    print("   ", (server.find_in_output("Wahrnehmung", mark) or server.find_in_output("µs", mark) or "").strip()[-120:])

    # --- Netherfestung in Sicht: Bot neben eine Festung stellen, look meldet sie
    mark = server.output_mark()
    server.console("execute in minecraft:the_nether run locate structure minecraft:fortress", wait=8)
    line = server.find_in_output("The nearest minecraft:fortress", mark) or ""
    match = re.search(r"\[(-?\d+), [~\d-]+, (-?\d+)\]", line)
    if not match:
        report.check("Festung im Nether gefunden (locate)", False, line)
        return
    fx, fz = int(match.group(1)), int(match.group(2))
    server.console(f"execute in minecraft:the_nether run tp {BOT} {fx + 40} 120 {fz}", wait=3)
    server.console(f"effect give {BOT} slow_falling 30 1")
    server.console(f"effect give {BOT} fire_resistance 60 1")
    server.console(f"effect give {BOT} resistance 60 4", wait=1)
    spotted = [e["data"] for e in server.drain(22.0) if e["name"] == "fluid_spotted" and e["data"].get("kind") == "fortress"]
    report.check("Rundum-Blick meldet die Festung von selbst (ohne look)", bool(spotted), str(spotted[:1]))
    snapshot = server.look()
    seen = snapshot.get("fortress_in_sight")
    report.check("look im Nether 40 Blöcke neben der Festung: fortress_in_sight",
                 seen is not None and seen["distance"] <= 100, str(seen))
    server.console(f"execute in minecraft:overworld run tp {BOT} 0 {STAND} 0", wait=2)


if __name__ == "__main__":
    sys.exit(main())

"""Etappe 4b: Abbauen, Sammeln, Platzieren, Inventar, dazu goto mit allow_dig und allow_place.

Aufruf:  python tests/server/test_blocks.py
Läuft auf der flachen Testwelt: Gras bei y=-61, darunter zwei Lagen Erde, dann Grundgestein.
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, FLOOR, STAND, arena  # noqa: E402


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-blocks.log")
    server = TestServer(log)
    report = Report()
    try:
        run(server, report)
    finally:
        server.close()
    print(f"Serverlog: {log}")
    print(f"Längste Wegsuche in einem Tick: {report.peak_search_ms} ms (Ziel: unter 3)")
    return report.finish()


def gained(result, item):
    return next((g["count"] for g in result["data"].get("gained", []) if g["id"] == f"minecraft:{item}"), 0)


def failed(result, reason):
    return result["status"] == "failed" and result.get("reason") == reason


def run(server, report):
    real_run = server.run

    def run_and_note(name, args=None, timeout=180):
        result, seen = real_run(name, args, timeout)
        report.note(result)
        return result, seen

    server.run = run_and_note
    server.console("gamerule spawn_mobs false")
    server.console("time set noon")
    server.console("smartcraft events off")
    server.console(f"smartcraft join {BOT}", wait=4)
    server.console(f"clear {BOT}")
    server.drain(0.5)

    # --- Inventar, equip, drop, pickup_items
    arena(server, 300, 0)
    result, _ = server.run("inventory")
    report.check("inventory: leer, 36 freie Plätze",
                 result["status"] == "ok" and result["data"]["free_slots"] == 36 and not result["data"]["items"])

    server.console(f"give {BOT} stone 10")
    server.console(f"give {BOT} wooden_pickaxe")
    server.console(f"give {BOT} leather_helmet", wait=0.4)
    result, _ = server.run("inventory")
    ids = {item["id"] for item in result["data"]["items"]}
    report.check("inventory: zeigt die drei Items",
                 ids == {"minecraft:stone", "minecraft:wooden_pickaxe", "minecraft:leather_helmet"}, str(sorted(ids)))

    result, _ = server.run("equip", {"item": "wooden_pickaxe"})
    held = result["data"].get("held_item") or {}
    report.check("equip Spitzhacke in die Hand, mit Haltbarkeit",
                 result["status"] == "ok" and held.get("id") == "minecraft:wooden_pickaxe"
                 and held.get("durability", {}).get("left") == 59, summary(result))
    result, _ = server.run("equip", {"item": "leather_helmet", "slot": "head"})
    worn = server.run("inventory")[0]["data"]["worn"]
    report.check("equip Helm auf den Kopf",
                 result["status"] == "ok" and worn.get("head", {}).get("id") == "minecraft:leather_helmet", str(worn))
    result, _ = server.run("equip", {"item": "stone", "slot": "head"})
    report.check("equip Stein auf den Kopf: wrong_slot", failed(result, "wrong_slot"), summary(result))
    result, _ = server.run("equip", {"item": "diamond"})
    report.check("equip ohne das Item: missing_item", failed(result, "missing_item"), summary(result))

    result, _ = server.run("drop", {"item": "stone", "count": 3})
    report.check("drop 3 Stein: 7 übrig",
                 result["status"] == "ok" and result["data"]["left_in_inventory"] == 7, summary(result))
    result, _ = server.run("pickup_items", {"radius": 8})
    report.check("pickup_items: die 3 Steine wieder aufgehoben",
                 result["status"] == "ok" and gained(result, "stone") == 3, summary(result))
    result, _ = server.run("drop", {"item": "diamond"})
    report.check("drop ohne das Item: missing_item", failed(result, "missing_item"), summary(result))

    # --- place
    arena(server, 300, 40)
    result, _ = server.run("place", {"block": "stone", "pos": [303, STAND, 40]})
    report.check("place Stein: steht nachweislich da",
                 result["status"] == "ok" and server.block_is(303, STAND, 40, "stone")
                 and result["data"]["left_in_inventory"] == 9
                 and result["data"]["held_item"]["id"] == "minecraft:stone", summary(result))
    result, _ = server.run("place", {"block": "stone", "pos": [303, STAND, 40]})
    report.check("place auf belegtes Feld: occupied", failed(result, "occupied"), summary(result))
    result, _ = server.run("place", {"block": "stone", "pos": [303, STAND + 5, 44]})
    report.check("place in die Luft: no_support", failed(result, "no_support"), summary(result))
    result, _ = server.run("place", {"block": "diamond_block", "pos": [304, STAND, 40]})
    report.check("place ohne den Block: missing_item", failed(result, "missing_item"), summary(result))
    result, _ = server.run("place", {"block": "stone", "pos": [318, STAND, 40]})
    report.check("place 18 Blöcke entfernt: läuft hin und setzt",
                 result["status"] == "ok" and server.block_is(318, STAND, 40, "stone"), summary(result))

    # --- mine
    result, _ = server.run("mine", {"pos": [318, STAND, 40]})
    held = result["data"].get("held_item") or {}
    report.check("mine Stein mit Spitzhacke: Bruchstein erhalten, Haltbarkeit sinkt",
                 result["status"] == "ok" and gained(result, "cobblestone") == 1
                 and held.get("id") == "minecraft:wooden_pickaxe" and held.get("durability", {}).get("left") == 58
                 and server.block_is(318, STAND, 40, "air"), summary(result))

    arena(server, 300, 80)
    server.console(f"clear {BOT}")
    server.console(f"setblock 303 {STAND} 80 stone", wait=0.3)
    result, _ = server.run("mine", {"pos": [303, STAND, 80]})
    report.check("mine Stein ohne Spitzhacke: wrong_tool mit Hinweis, Block bleibt",
                 failed(result, "wrong_tool") and "pickaxe" in result["data"].get("hint", "")
                 and server.block_is(303, STAND, 80, "stone"), summary(result))
    result, _ = server.run("mine", {"pos": [302, FLOOR, 82]})
    report.check("mine Grasblock von Hand: Erde erhalten",
                 result["status"] == "ok" and gained(result, "dirt") == 1, summary(result))
    result, _ = server.run("mine", {"pos": [300, FLOOR - 3, 80]})
    report.check("mine Grundgestein: unbreakable", failed(result, "unbreakable"), summary(result))
    result, _ = server.run("mine", {"pos": [300, STAND + 3, 80]})
    report.check("mine Luft: no_block", failed(result, "no_block"), summary(result))

    # Sicherheitsnetz: der Block unter den Füßen, darunter nichts
    server.console(f"setblock 310 {STAND + 5} 80 dirt", wait=0.3)
    server.console(f"tp {BOT} 310.5 {STAND + 6} 80.5", wait=0.8)
    result, _ = server.run("mine", {"pos": [310, STAND + 5, 80]})
    report.check("mine unter den Füßen über dem Abgrund: blocked_unsafe, Bot bleibt oben",
                 failed(result, "blocked_unsafe") and result["data"].get("hint") == "would_fall"
                 and result["data"]["pos"][1] == STAND + 6, summary(result))
    server.console(f"tp {BOT} 300.5 {STAND} 84.5", wait=0.8)
    result, _ = server.run("mine", {"pos": [300, FLOOR, 84]})
    report.check("mine unter den Füßen mit Boden darunter: erlaubt, Bot sinkt einen Block",
                 result["status"] == "ok" and result["data"]["pos"][1] == STAND - 1, summary(result))

    # --- Werkzeug fast kaputt
    arena(server, 300, 120)
    server.console(f"clear {BOT}")
    server.console(f"give {BOT} wooden_pickaxe[damage=55]", wait=0.4)
    server.drain(0.2)
    server.run("equip", {"item": "wooden_pickaxe"})
    events = server.drain(1.5)
    warning = next((e for e in events if e["name"] == "tool_low_durability"), None)
    report.check("tool_low_durability, sobald ein fast kaputtes Werkzeug in der Hand ist",
                 warning is not None and warning["data"]["left"] == 4, str(warning and warning["data"]))

    # --- collect
    server.console(f"clear {BOT}")
    server.console(f"give {BOT} wooden_pickaxe", wait=0.3)
    for x, z in ((305, 120), (309, 123), (312, 117), (316, 121)):
        server.console(f"setblock {x} {STAND} {z} stone")
    result, _ = server.run("collect", {"block": "stone", "count": 3, "radius": 20})
    report.check("collect stone 3: drei abgebaut, drei Bruchstein, einer bleibt",
                 result["status"] == "ok" and result["data"]["mined"] == 3 and gained(result, "cobblestone") == 3,
                 summary(result))
    result, _ = server.run("collect", {"block": "diamond_ore", "count": 1, "radius": 16})
    report.check("collect ohne Vorkommen: none_found", failed(result, "none_found"), summary(result))
    result, _ = server.run("collect", {"block": "blorb"})
    report.check("collect unbekannter Block: unknown_block", failed(result, "unknown_block"), summary(result))

    # Hoher Baum: neun Stämme mit Laubkrone. Oben kommt er nur durch Hochbauen hin.
    arena(server, 300, 160)
    server.console(f"clear {BOT}")
    server.console(f"fill 306 {STAND + 6} 158 310 {STAND + 9} 162 oak_leaves[persistent=true]")
    server.console(f"fill 308 {STAND} 160 308 {STAND + 8} 160 oak_log", wait=0.4)
    result, _ = server.run("collect", {"block": "#logs", "count": 9, "radius": 16}, timeout=400)
    report.check("collect #logs 9 am hohen Baum: alle neun, und wieder am Boden",
                 result["status"] == "ok" and result["data"]["mined"] == 9 and gained(result, "oak_log") == 9
                 and result["data"]["pos"][1] == STAND and "standing_on_own_pillar" not in result["data"],
                 summary(result))

    # --- goto mit allow_dig: Ziel in einem geschlossenen Steinraum
    arena(server, 300, 200)
    server.console(f"clear {BOT}")
    server.console(f"give {BOT} stone_pickaxe", wait=0.3)
    server.console(f"fill 310 {STAND} 198 314 {STAND + 3} 202 stone")
    server.console(f"fill 311 {STAND} 199 313 {STAND + 1} 201 air", wait=0.3)
    result, _ = server.run("goto", {"pos": [312, STAND, 200], "tolerance": 0})
    report.check("geschlossener Raum ohne allow_dig: no_path", failed(result, "no_path"), summary(result))
    result, _ = server.run("goto", {"pos": [312, STAND, 200], "tolerance": 0, "allow_dig": True})
    report.check("geschlossener Raum mit allow_dig: gräbt sich hinein",
                 result["status"] == "ok" and result["data"]["blocks_dug"] >= 2, summary(result))

    # --- goto mit allow_place: Insel hinter einem Graben, dann ein Podest in der Luft
    arena(server, 300, 240)
    server.console(f"clear {BOT}")
    server.console(f"give {BOT} dirt 32", wait=0.3)
    server.console(f"fill 309 {FLOOR - 2} 236 317 {FLOOR} 244 air")
    server.console(f"fill 311 {FLOOR - 2} 238 315 {FLOOR} 242 grass_block", wait=0.3)
    result, _ = server.run("goto", {"pos": [313, STAND, 240], "tolerance": 0})
    report.check("Insel hinter Graben ohne allow_place: no_path", failed(result, "no_path"), summary(result))
    result, _ = server.run("goto", {"pos": [313, STAND, 240], "tolerance": 0, "allow_place": True})
    report.check("Insel hinter Graben mit allow_place: baut eine Brücke",
                 result["status"] == "ok" and result["data"]["blocks_placed"] >= 2, summary(result))

    arena(server, 300, 280)
    server.console(f"fill 310 {STAND + 3} 279 312 {STAND + 3} 281 stone", wait=0.3)
    result, _ = server.run("goto", {"pos": [311, STAND + 4, 280], "tolerance": 0, "allow_place": True})
    report.check("Podest 4 Blöcke hoch mit allow_place: baut sich hoch",
                 result["status"] == "ok" and result["data"]["pos"][1] == STAND + 4, summary(result))
    server.console(f"clear {BOT}")
    time.sleep(0.3)
    arena(server, 300, 320)
    server.console(f"fill 310 {STAND + 3} 319 312 {STAND + 3} 321 stone", wait=0.3)
    result, _ = server.run("goto", {"pos": [311, STAND + 4, 320], "tolerance": 0, "allow_place": True})
    report.check("allow_place ohne Blöcke im Inventar: no_blocks", failed(result, "no_blocks"), summary(result))

    # --- collect mit allow_dig: Erz mitten in einem Steinwürfel, von außen nicht in Reichweite
    arena(server, 300, 360)
    server.console(f"give {BOT} stone_pickaxe", wait=0.3)
    server.console(f"fill 306 {STAND} 356 314 {STAND + 8} 364 stone")
    server.console(f"setblock 310 {STAND + 4} 360 iron_ore", wait=0.3)
    result, _ = server.run("collect", {"block": "iron_ore", "count": 1, "radius": 16})
    report.check("Erz im Steinwürfel ohne allow_dig: none_reachable", failed(result, "none_reachable"), summary(result))
    result, _ = server.run("collect", {"block": "iron_ore", "count": 1, "radius": 16, "allow_dig": True}, timeout=240)
    report.check("Erz im Steinwürfel mit allow_dig: gräbt sich hin und holt es",
                 result["status"] == "ok" and result["data"]["mined"] == 1 and result["data"].get("dug_on_the_way", 0) >= 1
                 and any(g["id"] == "minecraft:raw_iron" for g in result["data"]["gained"]), summary(result))


if __name__ == "__main__":
    sys.exit(main())

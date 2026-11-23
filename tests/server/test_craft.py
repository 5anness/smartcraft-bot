"""Etappe 4c: Crafting, Schmelzen, Truhe, Ortsgedächtnis, dazu der Parcours bis zur Eisenspitzhacke.

Aufruf:  python tests/server/test_craft.py [durchläufe]
Läuft auf der flachen Testwelt. Erst Einzelprüfungen, dann der Parcours mehrfach:
Holz → Bretter → Stöcke → Werkbank → Holzspitzhacke → Bruchstein → Steinspitzhacke → Ofen →
rohes Eisen schmelzen → Eisenspitzhacke.
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, STAND, arena  # noqa: E402


def main():
    runs = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-craft.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console("smartcraft events off")
        server.console(f"smartcraft join {BOT}", wait=5)
        details(server, report)
        passed = sum(course(server, report, number) for number in range(runs))
        print(f"Parcours bis zur Eisenspitzhacke: {passed} von {runs} Durchläufen")
    finally:
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


def failed(result, reason):
    return result["status"] == "failed" and result.get("reason") == reason


def count(server, item):
    inventory = server.run("inventory")[0]["data"]
    return sum(entry["count"] for entry in inventory["items"] if entry["id"] == f"minecraft:{item}")


def fresh(server, x, z):
    arena(server, x, z)
    server.console(f"clear {BOT}")
    server.console("kill @e[type=item]", wait=0.3)
    server.drain(0.3)


def details(server, report):
    fresh(server, 300, 300)
    # Reste früherer Testläufe in Reichweite des Ortsgedächtnisses entfernen (Öfen, Truhen, Werkbänke).
    server.console(f"fill 280 {STAND} 280 330 {STAND + 3} 350 air", wait=0.5)

    # --- recipe und craft ohne Zutaten
    result, _ = server.run("recipe", {"item": "iron_pickaxe"})
    first = result["data"]["craft"][0] if result["status"] == "ok" and result["data"]["craft"] else {}
    missing = {entry["item"]: entry["count"] for entry in first.get("missing", [])}
    report.check("recipe iron_pickaxe: Werkbank nötig, es fehlen 3 Eisen (Tag mit Beispiel iron_ingot) und 2 Stöcke",
                 first.get("needs_crafting_table") is True and missing.get("#minecraft:iron_tool_materials") == 3
                 and "minecraft:iron_ingot" in str(first.get("missing"))
                 and missing.get("minecraft:stick") == 2, summary(result))
    result, _ = server.run("recipe", {"item": "iron_ingot"})
    report.check("recipe iron_ingot: nennt den Ofen-Weg aus raw_iron",
                 result["status"] == "ok" and any(entry["from"] == "minecraft:raw_iron" for entry in result["data"]["smelt"]),
                 summary(result))
    result, _ = server.run("craft", {"item": "stick"})
    missing = result["data"].get("missing", [])
    report.check("craft stick ohne Bretter: missing_ingredients mit Tag und Anzahl",
                 failed(result, "missing_ingredients") and len(missing) == 1
                 and missing[0]["item"] == "#minecraft:planks" and missing[0]["count"] == 2, summary(result))
    result, _ = server.run("craft", {"item": "blorb"})
    report.check("craft unbekanntes Item: unknown_item", failed(result, "unknown_item"), summary(result))
    result, _ = server.run("craft", {"item": "diamond_ore"})
    report.check("craft ohne Rezept: no_recipe", failed(result, "no_recipe"), summary(result))

    # --- Teilerfolg und Holzarten
    server.console(f"give {BOT} birch_log 1", wait=0.4)
    result, _ = server.run("craft", {"item": "birch_planks", "count": 8})
    report.check("craft 8 Bretter mit einem Stamm: ok, crafted 4, stopped_because missing_ingredients",
                 result["status"] == "ok" and result["data"]["crafted"] == 4
                 and result["data"].get("stopped_because") == "missing_ingredients", summary(result))
    result, _ = server.run("craft", {"item": "stick", "count": 4})
    report.check("craft stick aus Birkenbrettern (Tag #planks): 4 Stöcke",
                 result["status"] == "ok" and result["data"]["crafted"] == 4, summary(result))
    result, _ = server.run("craft", {"item": "crafting_table"})
    report.check("craft crafting_table mit nur 2 Brettern: missing_ingredients",
                 failed(result, "missing_ingredients"), summary(result))

    # --- Werkbank fehlt
    server.console(f"clear {BOT}")
    server.console(f"give {BOT} oak_planks 16")
    server.console(f"give {BOT} stick 4", wait=0.4)
    result, _ = server.run("craft", {"item": "wooden_pickaxe"})
    report.check("craft wooden_pickaxe ohne Werkbank: no_crafting_table, könnte eine craften",
                 failed(result, "no_crafting_table") and result["data"].get("can_craft_crafting_table") is True
                 and result["data"].get("has_crafting_table_item") is False, summary(result))
    result, _ = server.run("place_utility", {"block": "crafting_table"})
    report.check("place_utility ohne Werkbank im Inventar: missing_item mit can_craft",
                 failed(result, "missing_item") and result["data"].get("can_craft") is True, summary(result))
    server.run("craft", {"item": "crafting_table"})
    result, _ = server.run("place_utility", {"block": "crafting_table"})
    table = result["data"].get("block_pos")
    report.check("place_utility crafting_table: steht, Position im Ergebnis",
                 result["status"] == "ok" and table and server.block_is(*table, "crafting_table"), summary(result))
    result, _ = server.run("craft", {"item": "wooden_pickaxe"})
    report.check("craft wooden_pickaxe an der Werkbank",
                 result["status"] == "ok" and result["data"]["crafted"] == 1, summary(result))

    # --- Ofen: Fehlerfälle
    result, _ = server.run("smelt", {"item": "raw_iron"})
    report.check("smelt ohne Rohstoff: missing_item", failed(result, "missing_item"), summary(result))
    server.console(f"clear {BOT}")
    server.console(f"give {BOT} dirt 2")
    server.console(f"give {BOT} raw_iron 4", wait=0.4)
    result, _ = server.run("smelt", {"item": "dirt"})
    report.check("smelt dirt: not_smeltable", failed(result, "not_smeltable"), summary(result))
    result, _ = server.run("smelt", {"item": "raw_iron", "count": 2})
    report.check("smelt ohne Brennstoff: no_fuel", failed(result, "no_fuel"), summary(result))
    server.console(f"give {BOT} coal 3", wait=0.4)
    result, _ = server.run("smelt", {"item": "raw_iron", "count": 2})
    report.check("smelt ohne Ofen: no_furnace", failed(result, "no_furnace"), summary(result))

    # --- Ofen: sparsam mit Brennstoff, Bot bleibt ansprechbar
    server.console(f"give {BOT} furnace", wait=0.4)
    server.run("place_utility", {"block": "furnace"})
    result, _ = server.run("smelt", {"item": "raw_iron", "count": 4})
    fuel = result["data"].get("fuel", {})
    report.check("smelt 4 raw_iron: 4 eingelegt, genau 1 Kohle (reicht für 8)",
                 result["status"] == "ok" and result["data"]["loaded"] == 4 and fuel.get("id") == "minecraft:coal"
                 and fuel.get("count") == 1 and count(server, "coal") == 2, summary(result))
    result, _ = server.run("smelt_collect")
    report.check("smelt_collect sofort danach: nothing_ready, nennt was noch drin ist",
                 failed(result, "nothing_ready") and (result["data"].get("still_cooking") or {}).get("count", 0) >= 3,
                 summary(result))
    done = wait_for(server, "smelting_done", 70)
    report.check("Event smelting_done nach rund 40 Sekunden, complete",
                 done is not None and done["data"]["complete"] is True and done["data"]["ready"]["count"] == 4,
                 str(done and done["data"]))
    result, _ = server.run("smelt_collect")
    report.check("smelt_collect: 4 Eisenbarren", result["status"] == "ok" and count(server, "iron_ingot") == 4, summary(result))

    # --- Truhe
    server.console(f"give {BOT} chest")
    server.console(f"give {BOT} cobblestone 20", wait=0.4)
    result, _ = server.run("take", {"item": "coal"})
    report.check("take ohne Truhe: no_chest", failed(result, "no_chest"), summary(result))
    result, _ = server.run("place_utility", {"block": "chest"})
    chest = result["data"].get("block_pos")
    report.check("place_utility chest", result["status"] == "ok" and chest is not None, summary(result))
    result, _ = server.run("store", {"item": "cobblestone", "count": 12})
    report.check("store cobblestone 12: 12 in der Truhe, 8 behalten",
                 result["status"] == "ok" and result["data"]["stored"] == 12
                 and result["data"]["chest_contents"].get("minecraft:cobblestone") == 12 and count(server, "cobblestone") == 8,
                 summary(result))
    result, _ = server.run("take", {"item": "cobblestone", "count": 5})
    report.check("take cobblestone 5", result["status"] == "ok" and result["data"]["taken"] == 5
                 and count(server, "cobblestone") == 13, summary(result))
    result, _ = server.run("take", {"item": "diamond"})
    report.check("take, was nicht drin ist: not_in_chest", failed(result, "not_in_chest"), summary(result))
    result, _ = server.run("take", {"item": "cobblestone", "count": 30})
    report.check("take mehr als da ist: ok, 7 genommen, stopped_because not_enough_in_chest",
                 result["status"] == "ok" and result["data"]["taken"] == 7
                 and result["data"].get("stopped_because") == "not_enough_in_chest", summary(result))
    result, _ = server.run("store", {"item": "all"})
    report.check("store all: Inventar leer", result["status"] == "ok" and server.run("inventory")[0]["data"]["items"] == [],
                 summary(result))

    # --- Ortsgedächtnis: Truhe außer Sicht, dann entfernt
    server.console(f"tp {BOT} {chest[0] - 2}.5 {STAND} {chest[2] + 40}.5", wait=1.0)
    result, _ = server.run("take", {"item": "coal", "count": 1})
    report.check("take aus 40 Blöcken Entfernung: läuft zur gemerkten Truhe",
                 result["status"] == "ok" and result["data"]["chest"] == chest, summary(result))
    server.console(f"setblock {chest[0]} {chest[1]} {chest[2]} air", wait=0.3)
    server.console("kill @e[type=item]")
    server.console(f"tp {BOT} {chest[0] - 2}.5 {STAND} {chest[2] + 40}.5", wait=1.0)
    result, _ = server.run("take", {"item": "coal", "count": 1})
    report.check("Truhe ist weg: no_chest, Ort ausgetragen", failed(result, "no_chest"), summary(result))


def wait_for(server, name, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        for event in server.drain(1.0):
            if event["name"] == name:
                return event
    return None


def course(server, report, number):
    """Ein Durchlauf vom leeren Inventar bis zur Eisenspitzhacke. Gibt 1 bei Erfolg zurück."""
    x, z = 300, 500 + number * 100
    fresh(server, x, z)
    server.console(f"fill {x + 5} {STAND} {z} {x + 5} {STAND + 4} {z} oak_log")
    server.console(f"fill {x + 10} {STAND} {z - 1} {x + 13} {STAND} {z + 1} stone")
    server.console(f"fill {x + 16} {STAND} {z + 4} {x + 18} {STAND} {z + 4} iron_ore", wait=0.4)

    steps = [
        ("collect", {"block": "#logs", "count": 5, "radius": 16}),
        ("craft", {"item": "oak_planks", "count": 16}),
        ("craft", {"item": "stick", "count": 8}),
        ("craft", {"item": "crafting_table"}),
        ("place_utility", {"block": "crafting_table"}),
        ("craft", {"item": "wooden_pickaxe"}),
        ("collect", {"block": "stone", "count": 11, "radius": 20}),
        ("craft", {"item": "stone_pickaxe"}),
        ("craft", {"item": "furnace"}),
        ("place_utility", {"block": "furnace"}),
        ("collect", {"block": "iron_ore", "count": 3, "radius": 24}),
        ("smelt", {"item": "raw_iron", "count": 3}),
    ]
    started = time.time()
    problem = None
    for name, args in steps:
        result, _ = server.run(name, args, timeout=300)
        if result["status"] != "ok" or result["data"].get("stopped_because"):
            problem = f"{name} {args}: {summary(result)}"
            break
    if problem is None and wait_for(server, "smelting_done", 60) is None:
        problem = "smelting_done kam nicht"
    if problem is None:
        for name, args in (("smelt_collect", {}), ("craft", {"item": "iron_pickaxe"})):
            result, _ = server.run(name, args, timeout=300)
            if result["status"] != "ok":
                problem = f"{name} {args}: {summary(result)}"
                break
    has_pickaxe = count(server, "iron_pickaxe") == 1
    report.check(f"Parcours {number + 1}: vom leeren Inventar zur Eisenspitzhacke",
                 problem is None and has_pickaxe, problem or f"{round(time.time() - started)} s")
    return 1 if problem is None and has_pickaxe else 0


if __name__ == "__main__":
    sys.exit(main())

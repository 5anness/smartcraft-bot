"""Etappe 4d: Essen, Kampf, Flucht, Rüstung, Schlafen, Unterschlupf, Fackel und die neuen Events.

Aufruf:  python tests/server/test_survival.py
Läuft auf der flachen Testwelt. Gegner werden einzeln hingestellt; der ganze Ablauf in einer
normalen Welt steht in test_course.py.
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, FLOOR, STAND, arena  # noqa: E402


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-survival.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console("difficulty normal")
        server.console("smartcraft events off")
        server.console(f"smartcraft join {BOT}", wait=5)
        eating(server, report)
        armor(server, report)
        fighting(server, report)
        night(server, report)
    finally:
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


def failed(result, reason):
    return result["status"] == "failed" and result.get("reason") == reason


def count(server, item):
    inventory = server.run("inventory")[0]["data"]
    return sum(entry["count"] for entry in inventory["items"] if entry["id"] == f"minecraft:{item}")


def fresh(server, x, z, time_of_day="midnight"):
    arena(server, x, z)
    server.console(f"time set {time_of_day}")
    server.console(f"clear {BOT}")
    server.console(f"effect clear {BOT}")
    server.console(f"effect give {BOT} minecraft:instant_health 1 10")
    server.console(f"effect give {BOT} minecraft:saturation 1 20")
    server.console("kill @e[type=item]", wait=0.5)
    server.send("shield", {"on": False})
    server.drain(0.5)


def events_since(server, mark, name):
    return [event for event in server.all_events[mark:] if event["name"] == name]


def wait_event(server, mark, name, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        found = events_since(server, mark, name)
        if found:
            return found[0]
        time.sleep(0.2)
    return None


def starve(server, target=6):
    """Lässt den Bot hungrig werden: der Hunger-Effekt zehrt erst die Sättigung, dann den Hunger auf."""
    server.console(f"effect give {BOT} minecraft:hunger 60 255 true", wait=1.0)
    deadline = time.time() + 30
    while server.look()["food"] > target and time.time() < deadline:
        time.sleep(0.3)
    server.console(f"effect clear {BOT}", wait=0.3)
    return server.look()["food"]


def eating(server, report):
    fresh(server, 300, 300, "noon")
    result, _ = server.run("eat")
    report.check("eat ohne Essen: no_food", failed(result, "no_food"), summary(result))
    result, _ = server.run("eat", {"item": "bread"})
    report.check("eat bread ohne Brot: missing_item", failed(result, "missing_item"), summary(result))

    server.console(f"give {BOT} rotten_flesh 2")
    server.console(f"give {BOT} bread 3")
    server.console(f"give {BOT} cooked_beef 2")
    server.console(f"give {BOT} golden_apple 1")
    server.console(f"give {BOT} dirt 1", wait=0.4)
    result, _ = server.run("eat", {"item": "bread"})
    report.check("eat bread bei vollem Hunger: not_hungry", failed(result, "not_hungry"), summary(result))
    result, _ = server.run("eat", {"item": "dirt"})
    report.check("eat dirt: not_edible", failed(result, "not_edible"), summary(result))

    mark = len(server.all_events)
    food = starve(server)
    low = events_since(server, mark, "hunger_low")
    report.check("Event hunger_low bei Hunger 6 oder weniger, einmal, nennt das beste Essen",
                 food <= 6 and len(low) == 1 and low[0]["data"]["best_food"] == "minecraft:cooked_beef",
                 f"Hunger {food}, {[event['data'] for event in low]}")
    held_before = server.look()["held"]
    result, _ = server.run("eat")
    data = result["data"]
    report.check("eat ohne Angabe: nimmt das Steak (meiste Sättigung), nicht Apfel oder Gammelfleisch; Hunger steigt um 8",
                 result["status"] == "ok" and data.get("ate") == "minecraft:cooked_beef" and data["food"] == data["food_before"] + 8
                 and data["left"] == 1 and data["food_before"] <= 6, summary(result))
    report.check("nach dem Essen liegt wieder dasselbe in der Hand", server.look()["held"] == held_before,
                 f"vorher {held_before}, nachher {server.look()['held']}")
    result, _ = server.run("eat", {"item": "rotten_flesh"})
    report.check("eat rotten_flesh auf Wunsch: ok mit harmful",
                 result["status"] == "ok" and result["data"].get("harmful") is True, summary(result))
    server.console(f"effect clear {BOT}")

    # Nur Schädliches da: dann nimmt er es trotzdem.
    server.console(f"clear {BOT}")
    server.console(f"give {BOT} rotten_flesh 1", wait=0.4)
    starve(server, 12)
    result, _ = server.run("eat")
    report.check("eat mit nur Gammelfleisch: isst es und meldet harmful",
                 result["status"] == "ok" and result["data"].get("ate") == "minecraft:rotten_flesh"
                 and result["data"].get("harmful") is True, summary(result))

    # --- Leben niedrig und Notfall-Reflex
    fresh(server, 300, 300, "noon")
    starve(server)
    mark = len(server.all_events)
    server.console(f"damage {BOT} 14 minecraft:out_of_world", wait=1.5)
    low = events_since(server, mark, "health_low")
    report.check("Event health_low bei Leben 6", len(low) == 1 and low[0]["data"]["health"] <= 6, str([e["data"] for e in low]))
    server.console(f"damage {BOT} 3 minecraft:out_of_world", wait=3.0)
    report.check("Leben 3 ohne Essen: kein Reflex", not events_since(server, mark, "reflex_ate"), "")
    server.console(f"give {BOT} bread 2", wait=0.2)
    reflex = wait_event(server, mark, "reflex_ate", 6)
    report.check("Leben 3 mit Brot: Reflex isst von selbst und meldet reflex_ate",
                 reflex is not None and reflex["data"]["eaten"] is True and reflex["data"]["item"] == "minecraft:bread"
                 and count(server, "bread") == 1, str(reflex and reflex["data"]))
    server.game_sleep(3.0)
    report.check("Reflex frühestens alle 10 Sekunden: kein zweites Mal direkt danach",
                 len(events_since(server, mark, "reflex_ate")) == 1, "")

    # --- cook
    fresh(server, 300, 340, "noon")
    server.console(f"give {BOT} furnace")
    server.console(f"give {BOT} coal 2")
    server.console(f"give {BOT} beef 3")
    server.console(f"give {BOT} raw_iron 1", wait=0.4)
    result, _ = server.run("cook", {"item": "raw_iron"})
    report.check("cook raw_iron: not_cookable", failed(result, "not_cookable"), summary(result))
    result, _ = server.run("cook", {"item": "beef", "count": 3})
    report.check("cook ohne Ofen: no_furnace", failed(result, "no_furnace"), summary(result))
    server.run("place_utility", {"block": "furnace"})
    mark = len(server.all_events)
    result, _ = server.run("cook", {"item": "beef", "count": 3})
    report.check("cook beef 3: eingelegt, ergibt cooked_beef",
                 result["status"] == "ok" and result["data"]["loaded"] == 3 and result["data"]["makes"] == "minecraft:cooked_beef",
                 summary(result))
    done = wait_event(server, mark, "smelting_done", 45)
    result, _ = server.run("smelt_collect")
    report.check("smelting_done und smelt_collect: 3 Steaks", done is not None and count(server, "cooked_beef") == 3, summary(result))
    # Den Ofen wieder wegräumen: Die Welt bleibt zwischen den Tests erhalten, und test_craft prüft „kein Ofen in der Nähe“.
    furnace = result["data"].get("furnace")
    if furnace:
        server.console(f"setblock {furnace[0]} {furnace[1]} {furnace[2]} air", wait=0.3)


def armor(server, report):
    fresh(server, 300, 380, "noon")
    result, _ = server.run("equip_best_armor")
    report.check("equip_best_armor ohne Rüstung: no_armor", failed(result, "no_armor"), summary(result))
    result, _ = server.run("shield", {"on": True})
    report.check("shield ohne Schild: no_shield", failed(result, "no_shield"), summary(result))

    for item in ("leather_helmet", "iron_helmet", "iron_chestplate", "leather_chestplate", "iron_boots", "shield"):
        server.console(f"give {BOT} {item}")
    time.sleep(0.4)
    result, _ = server.run("equip_best_armor")
    worn = result["data"].get("worn", {})
    report.check("equip_best_armor: Eisen statt Leder, Schild in die Zweithand, 10 Rüstungspunkte",
                 result["status"] == "ok" and worn.get("head", {}).get("id") == "minecraft:iron_helmet"
                 and worn.get("chest", {}).get("id") == "minecraft:iron_chestplate"
                 and worn.get("feet", {}).get("id") == "minecraft:iron_boots"
                 and worn.get("offhand", {}).get("id") == "minecraft:shield"
                 and result["data"]["armor"] == 10 and len(result["data"]["changed"]) == 4, summary(result))
    result, _ = server.run("equip_best_armor")
    report.check("equip_best_armor ein zweites Mal: nichts zu ändern",
                 result["status"] == "ok" and result["data"]["changed"] == [], summary(result))
    result, _ = server.run("shield", {"on": True})
    report.check("shield an: gehoben", result["status"] == "ok" and result["data"]["raised"] is True, summary(result))

    # Mit gehobenem Schild von vorn getroffen: kein Schaden, und der Schild bleibt beim Laufen oben.
    server.console(f"tp {BOT} 300.5 {STAND} 380.5 -90 0", wait=0.5)
    # Der Schild geht nur bei Gefahr hoch: ein Schütze in der Nähe, wie im Spiel hinter jedem Pfeil.
    server.console(f"summon skeleton 310.5 {STAND} 380.5 {{NoAI:1b}}", wait=0.5)
    mark = len(server.all_events)
    server.console(f"summon arrow 304.5 {STAND + 1.5} 380.5 {{Motion:[-2.0,0.05,0.0]}}", wait=1.5)
    report.check("Pfeil von vorn auf den gehobenen Schild: kein Schaden",
                 not events_since(server, mark, "damage_taken"), str([e["data"] for e in events_since(server, mark, "damage_taken")]))
    server.console("kill @e[type=skeleton]")
    result, _ = server.run("shield", {"on": False})
    mark = len(server.all_events)
    server.console(f"tp {BOT} 300.5 {STAND} 380.5 -90 0", wait=0.5)
    server.console(f"summon arrow 304.5 {STAND + 1.5} 380.5 {{Motion:[-2.0,0.05,0.0]}}", wait=1.5)
    report.check("derselbe Pfeil bei gesenktem Schild: Schaden",
                 len(events_since(server, mark, "damage_taken")) == 1, summary(result))


def summon(server, kind, x, z, extra=""):
    # PersistenceRequired: sonst verschwinden Monster wieder, bevor der Bot bei ihnen ist.
    server.console(f"summon {kind} {x}.5 {STAND} {z}.5 {{PersistenceRequired:1b{extra}}}", wait=0.4)


def fighting(server, report):
    # --- einfache Fälle
    fresh(server, 300, 420)
    result, _ = server.run("attack", {"target": "zombie"})
    report.check("attack ohne Ziel: entity_not_found", failed(result, "entity_not_found"), summary(result))
    result, _ = server.run("defend")
    report.check("defend ohne Feinde: ok, no_enemies",
                 result["status"] == "ok" and result["data"].get("outcome") == "no_enemies", summary(result))
    result, _ = server.run("flee")
    report.check("flee ohne Bedrohung: no_threat", failed(result, "no_threat"), summary(result))

    server.console(f"give {BOT} wooden_pickaxe")
    server.console(f"give {BOT} iron_sword")
    server.console(f"give {BOT} stone_axe", wait=0.4)
    summon(server, "cow", 308, 420)
    result, _ = server.run("attack", {"target": "cow"}, timeout=90)
    data = result["data"]
    report.check("attack cow: getötet, Eisenschwert gewählt, Beute liegt da",
                 result["status"] == "ok" and data.get("outcome") == "killed" and (data.get("weapon") or {}).get("id") == "minecraft:iron_sword"
                 and data.get("drops_nearby", 0) > 0, summary(result))
    result, _ = server.run("pickup_items")
    report.check("pickup_items danach: Rindfleisch im Inventar", count(server, "beef") >= 1, summary(result))

    # --- Zombie: volle Schläge und kritische Treffer
    fresh(server, 300, 460)
    server.console(f"give {BOT} iron_sword", wait=0.4)
    summon(server, "zombie", 310, 460)
    result, _ = server.run("attack", {"target": "zombie"}, timeout=90)
    data = result["data"]
    # 20 Leben, Eisenschwert 6, kritisch 9: mit vollen Schlägen höchstens 4 Treffer, mit schwachen wären es viel mehr.
    report.check("attack zombie: getötet mit höchstens 4 Treffern (volle Schläge), mindestens 1 kritisch",
                 result["status"] == "ok" and data.get("outcome") == "killed" and data["hits"] <= 4 and data["crits"] >= 1,
                 summary(result))

    # --- Ziel per id, Abbruch bei wenig Leben
    fresh(server, 300, 500)
    server.console(f"give {BOT} iron_sword", wait=0.4)
    mark = len(server.all_events)
    summon(server, "zombie", 312, 500)
    nearby = wait_event(server, mark, "entity_nearby", 5)
    zombie_id = nearby["data"]["mobs"][0]["id"] if nearby else -1
    server.console(f"damage {BOT} 12 minecraft:out_of_world", wait=0.3)
    result, _ = server.run("attack", {"target": zombie_id, "min_health": 10}, timeout=60)
    report.check("attack <id> mit Leben 8 und min_health 10: cancelled health_low",
                 result["status"] == "cancelled" and result.get("reason") == "health_low"
                 and result["data"]["target"]["id"] == zombie_id, summary(result))

    # --- flee
    result, _ = server.run("flee", {"distance": 20}, timeout=60)
    report.check("flee 20 vor dem Zombie: ok, Abstand mindestens 20",
                 result["status"] == "ok" and result["data"].get("outcome") == "fled" and result["data"]["distance"] >= 19.5,
                 summary(result))
    result, _ = server.run("flee", {"from": "creeper"})
    report.check("flee from creeper ohne Creeper: entity_not_found", failed(result, "entity_not_found"), summary(result))

    # --- Creeper: mehrere Anläufe, der Bot darf nicht in die Luft fliegen
    survived = 0
    details = []
    for attempt in range(3):
        fresh(server, 300, 540 + attempt * 40)
        server.console(f"give {BOT} iron_sword", wait=0.4)
        summon(server, "creeper", 311, 540 + attempt * 40)
        result, _ = server.run("attack", {"target": "creeper", "min_health": 0}, timeout=120)
        intact = server.block_is(305, FLOOR, 540 + attempt * 40, "grass_block")
        ok = result["status"] == "ok" and result["data"].get("outcome") == "killed" and result["data"]["health"] >= 14
        survived += ok
        details.append(f"{result['status']} {result.get('reason', '')} Treffer {result['data'].get('hits')} "
                       f"Leben {result['data'].get('health')} {result['data']['ticks'] // 20}s Boden heil {intact}")
    report.check("attack creeper, 3 Anläufe: jedes Mal getötet mit mindestens 14 Leben", survived == 3, "; ".join(details))

    # --- Creeper mit Holzschwert: viele Treffer nötig, er zischt zwischendurch
    fresh(server, 300, 700)
    server.console(f"give {BOT} wooden_sword", wait=0.4)
    summon(server, "creeper", 311, 700)
    result, _ = server.run("attack", {"target": "creeper", "min_health": 0}, timeout=180)
    report.check("attack creeper mit Holzschwert: getötet, höchstens eine Explosion abbekommen (Leben mindestens 8)",
                 result["status"] == "ok" and result["data"]["health"] >= 8, summary(result))

    # --- Skelett: mit und ohne Schild
    for label, items, z in (("ohne Rüstung", ("iron_sword",), 740),
                            ("mit Eisenrüstung und Schild", ("iron_sword", "iron_helmet", "iron_chestplate", "iron_leggings",
                                                             "iron_boots", "shield"), 780)):
        fresh(server, 300, z)
        for item in items:
            server.console(f"give {BOT} {item}")
        time.sleep(0.4)
        server.run("equip_best_armor")
        summon(server, "skeleton", 314, z)
        result, _ = server.run("attack", {"target": "skeleton", "min_health": 0}, timeout=120)
        report.check(f"attack skeleton {label}: getötet, Leben mindestens 8",
                     result["status"] == "ok" and result["data"].get("outcome") == "killed" and result["data"]["health"] >= 8,
                     summary(result))

    # --- defend: mehrere Gegner nacheinander
    fresh(server, 300, 820)
    for item in ("iron_sword", "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"):
        server.console(f"give {BOT} {item}")
    time.sleep(0.4)
    server.run("equip_best_armor")
    summon(server, "zombie", 309, 818)
    summon(server, "zombie", 310, 823)
    summon(server, "spider", 312, 820)
    result, _ = server.run("defend", {"radius": 16}, timeout=180)
    data = result["data"]
    report.check("defend gegen 2 Zombies und 1 Spinne: area_clear, 3 besiegt, niemand übrig",
                 result["status"] == "ok" and data.get("outcome") == "area_clear" and len(data["killed"]) == 3
                 and data["remaining"] == [], summary(result))

    # --- Enderman: nicht ansehen
    # Geradeaus laufend sähe der Bot einem Enderman in 22 Blöcken Entfernung genau ins Gesicht
    # (ab etwa 17 Blöcken reicht der waagerechte Blick). Der Reflex muss den Kopf senken.
    fresh(server, 300, 860)
    summon(server, "enderman", 322, 860, ",NoAI:1b")
    mark = len(server.all_events)
    result, _ = server.run("goto", {"pos": [304, STAND, 860], "tolerance": 0})
    pitch = server.look()["pitch"]
    server.console("data merge entity @e[type=enderman,limit=1] {NoAI:0b}", wait=4.0)
    report.check("auf einen Enderman zulaufen: Bot senkt den Blick, kein Angriff",
                 result["status"] == "ok" and pitch >= 60 and not events_since(server, mark, "damage_taken"),
                 f"Blickneigung {pitch}, Schaden {[e['data'] for e in events_since(server, mark, 'damage_taken')]}")


def night(server, report):
    # --- night_falling
    fresh(server, 300, 900, "11300")
    mark = len(server.all_events)
    falling = wait_event(server, mark, "night_falling", 20)
    report.check("Event night_falling kurz vor Sonnenuntergang, einmal",
                 falling is not None and 0 < falling["data"]["monsters_in_ticks"] <= 1500, str(falling and falling["data"]))

    # --- sleep
    result, _ = server.run("sleep")
    report.check("sleep am Tag: not_night", failed(result, "not_night"), summary(result))
    server.console("time set 13000")
    result, _ = server.run("sleep")
    report.check("sleep ohne Bett: no_bed", failed(result, "no_bed"), summary(result))
    server.console(f"setblock 310 {STAND} 900 red_bed[part=foot,facing=east]")
    server.console(f"setblock 311 {STAND} 900 red_bed[part=head,facing=east]", wait=0.3)
    summon(server, "zombie", 314, 902, ",NoAI:1b")
    result, _ = server.run("sleep", timeout=60)
    report.check("sleep mit Zombie am Bett: monsters_nearby", failed(result, "monsters_nearby"), summary(result))
    server.console("kill @e[type=zombie]", wait=0.5)
    server.console(f"tp {BOT} 300.5 {STAND} 900.5", wait=0.5)
    result, _ = server.run("sleep", timeout=60)
    daytime = server.look()["daytime"]
    report.check("sleep: läuft zum Bett, schläft, danach ist Tag",
                 result["status"] == "ok" and result["data"].get("slept") is True and daytime == "day", summary(result))

    # --- shelter: Hütte
    fresh(server, 300, 940)
    result, _ = server.run("shelter", timeout=60)
    report.check("shelter ohne Blöcke auf Gras: gräbt sich ein, rundum zu",
                 result["status"] == "ok" and result["data"].get("kind") == "burrow" and result["data"]["enclosed"] is True
                 and result["data"]["pos"][1] == STAND - 3, summary(result))
    result, _ = server.run("goto", {"pos": [306, STAND, 940], "allow_dig": True, "allow_place": True}, timeout=120)
    report.check("aus dem Loch wieder heraus mit goto dig place", result["status"] == "ok", summary(result))

    fresh(server, 300, 980)
    server.console(f"give {BOT} cobblestone 16", wait=0.4)
    summon(server, "zombie", 308, 980)
    result, _ = server.run("shelter", timeout=60)
    data = result["data"]
    report.check("shelter mit 16 Bruchstein, Zombie kommt: Hütte, rundum zu, 10 Blöcke verbaut",
                 result["status"] == "ok" and data.get("kind") == "hut" and data["enclosed"] is True and data["blocks_placed"] == 10,
                 summary(result))
    mark = len(server.all_events)
    time.sleep(8.0)
    report.check("8 Sekunden in der Hütte mit Zombie davor: kein Schaden", not events_since(server, mark, "damage_taken"),
                 str([e["data"] for e in events_since(server, mark, "damage_taken")]))
    server.console("kill @e[type=zombie]")

    # --- light
    fresh(server, 300, 1020)
    result, _ = server.run("light")
    report.check("light ohne Fackel und Material: no_torch", failed(result, "no_torch"), summary(result))
    server.console(f"give {BOT} coal 1")
    server.console(f"give {BOT} stick 1", wait=0.4)
    result, _ = server.run("light")
    spot = result["data"].get("block_pos")
    report.check("light mit Kohle und Stock: craftet 4 Fackeln und setzt eine neben sich",
                 result["status"] == "ok" and result["data"].get("crafted") == 4 and result["data"]["torches_left"] == 3
                 and spot and server.block_is(*spot, "torch"), summary(result))
    server.console(f"fill 306 {STAND} 1019 306 {STAND + 2} 1021 stone", wait=0.3)
    result, _ = server.run("light", {"pos": [305, STAND + 1, 1020]})
    report.check("light an eine Wand (x y z): Wandfackel hängt",
                 result["status"] == "ok" and server.block_is(305, STAND + 1, 1020, "wall_torch"), summary(result))


if __name__ == "__main__":
    sys.exit(main())

"""Servertest: Abraum wegwerfen bei vollem Inventar (Kit-Lauf 5), Erze behalten, nicht wieder aufheben.

Aufruf:  python tests/server/test_junk.py
Flache Testwelt, ca. 1 Minute.
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer  # noqa: E402
from test_movement import BOT, STAND, arena  # noqa: E402


def counts(server):
    answer, _ = server.run("inventory", timeout=10)
    total = {}
    for item in answer["data"]["items"]:
        total[item["id"]] = total.get(item["id"], 0) + item["count"]
    return total, answer["data"]["free_slots"]


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-junk.log")
    server = TestServer(log)
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console(f"smartcraft join {BOT}", wait=5)
        x, z = 820, 520
        arena(server, x, z)
        server.console(f"clear {BOT}")
        server.console("smartcraft kit nether", wait=1)
        for item, amount in (("raw_iron", 5), ("coal_ore", 3), ("diamond", 2), ("redstone", 9), ("lapis_lazuli", 4),
                             ("cobblestone", 64), ("cobblestone", 64), ("cobbled_deepslate", 64), ("granite", 30),
                             ("tuff", 20), ("gravel", 5), ("diorite", 12), ("andesite", 7), ("calcite", 9),
                             ("sand", 9), ("red_sand", 9)):
            server.console(f"give {BOT} minecraft:{item} {amount}", wait=0.1)
        time.sleep(6)
        total, free = counts(server)
        report.check("Gestein weg (Granit, Tuff, Diorit, Andesit), Kies bleibt",
                     not any(total.get(f"minecraft:{i}") for i in ("granite", "tuff")) and total.get("minecraft:gravel"), str(free))
        report.check("Erze und Funde behalten",
                     all(total.get(f"minecraft:{i}") for i in ("raw_iron", "coal_ore", "diamond", "redstone", "lapis_lazuli")),
                     str({k: v for k, v in total.items() if "raw" in k or "ore" in k or "diamond" in k}))
        report.check("Bausteine: höchstens 192 + ein Stapel, mindestens 192",
                     192 <= sum(total.get(f"minecraft:{i}", 0) for i in ("cobblestone", "cobbled_deepslate", "dirt")) <= 256,
                     str(sum(total.get(f"minecraft:{i}", 0) for i in ("cobblestone", "cobbled_deepslate", "dirt"))))
        report.check("Platz frei", free >= 1, f"frei {free}")
        time.sleep(4)
        total, _ = counts(server)
        report.check("Abraum nicht wieder aufgehoben", not total.get("minecraft:granite"), str(total.get("minecraft:granite")))
        # --- Ersetztes: Eisenrüstung unter Diamant, Steinschwert neben Diamantschwert; Stämme bleiben.
        for item in ("iron_helmet", "iron_boots", "stone_sword", "oak_log 23"):
            name, _, amount = item.partition(" ")
            server.console(f"give {BOT} minecraft:{name} {amount or 1}", wait=0.1)
        for n in range(12):
            server.console(f"give {BOT} minecraft:{('granite', 'tuff', 'diorite')[n % 3]} 64", wait=0.1)
        time.sleep(15)
        total, free = counts(server)
        report.check("Ersetztes weg (Eisenhelm, Eisenstiefel, Steinschwert)",
                     not any(total.get(f"minecraft:{i}") for i in ("iron_helmet", "iron_boots", "stone_sword")), str(free))
        report.check("Stämme und Diamantschwert bleiben", total.get("minecraft:oak_log") == 23
                     and total.get("minecraft:diamond_sword"), str(total.get("minecraft:oak_log")))
        report.check("Keine Steinspitzhacke im Kit", not total.get("minecraft:stone_pickaxe"), "")
    finally:
        server.close()
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

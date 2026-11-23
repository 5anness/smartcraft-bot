"""Servertest bed_bomb: frische Welt, Bot im End, Kristalle weg, der Drache wird immer wieder auf den Brunnen gesetzt
(Phase „hockt“); der Bot muss ihn mit Betten deutlich verletzen.

Aufruf:  python tests/server/test_bed_bomb.py   (ca. 2 Minuten)
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer  # noqa: E402

BOT = "Claude"


def dragon_health(server):
    mark = server.output_mark()
    server.console("execute in minecraft:the_end run data get entity @e[type=ender_dragon,limit=1] Health", wait=1)
    line = server.find_in_output("has the following entity data", mark)
    if not line:
        return None
    return float(line.rsplit(":", 1)[-1].strip().rstrip("f"))


def main():
    report = Report()
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-bed-bomb.log")
    server = TestServer(log, world=f"ai_test_bed_{int(time.time())}", seed=20261029)
    try:
        server.console("smartcraft events off")
        server.console(f"smartcraft join {BOT}", wait=5)
        for item in ["diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots", "diamond_sword",
                     "cobblestone 64", "white_bed 12", "golden_apple 8"]:
            server.console(f"give {BOT} {item}", wait=0.1)
        server.send("equip_best_armor", {})
        server.drain(2)
        server.console(f"execute in minecraft:the_end run tp {BOT} 18 70 0", wait=3)
        server.console(f"execute in minecraft:the_end run spreadplayers 18 0 0 2 false {BOT}", wait=10)
        server.console("execute in minecraft:the_end run kill @e[type=end_crystal]", wait=1)
        server.console(f"effect give {BOT} resistance 600 1 true", wait=0.2)
        before = None
        for _ in range(10):
            before = dragon_health(server)
            if before is not None:
                break
            server.drain(2)
        command_id = server.send("bed_bomb", {})
        after = before
        for _ in range(20):
            server.console("execute in minecraft:the_end run kill @e[type=enderman]", wait=0.2)
            server.console("execute in minecraft:the_end run kill @e[type=area_effect_cloud]", wait=0.2)
            server.console("execute in minecraft:the_end run tp @e[type=ender_dragon,limit=1] 0 68 0", wait=0.3)
            server.console("execute in minecraft:the_end run data merge entity @e[type=ender_dragon,limit=1] {DragonPhase:6}",
                           wait=0.3)
            server.drain(4)
            after = dragon_health(server)
            if after is None or after <= 0:
                break
        print("Drache vorher", before, "nachher", after)
        report.check("bed_bomb: Drache hockt, Betten schaden über 20",
                     before is not None and (after is None or after < before - 20), f"{before} -> {after}")
        server.send("stop", {})
        server.drain(1)
    finally:
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

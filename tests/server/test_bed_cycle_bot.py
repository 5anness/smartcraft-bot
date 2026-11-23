"""Servertest bed_cycle: frische Welt, Bot im End mit Diamantrüstung, 12 Betten, 64 Bruchstein, ohne Resistenz.
Alle Kristalle bleiben stehen. Sobald der Posten steht, wird der Drache auf den Brunnen gesetzt (Phase „hockt“), bis zu
3-mal. Erwartet: Drache tot (One-Cycle), Bot lebt.

Aufruf:  python tests/server/test_bed_cycle_bot.py   (ca. 2 Minuten)
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer  # noqa: E402
from test_bed_cycle import dragon_health, END  # noqa: E402

BOT = "Claude"


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "combo"
    run = sys.argv[2] if len(sys.argv) > 2 else "0"
    report = Report()
    log = os.path.join(os.environ.get("TEMP", "."), "aiplayer-test-bed-cycle-bot.log")
    server = TestServer(log, world=f"ai_test_bedcyclebot_{int(time.time())}", seed=20261029)
    deaths = 0
    try:
        server.console("aiplayer events off")
        server.console(f"aiplayer join {BOT}", wait=1)
        for _ in range(30):
            if server.find_in_output("bot_joined"):
                break
            server.drain(1)
        for item in ["diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots", "diamond_sword",
                     "cobblestone 64", "obsidian 16", "white_bed 12", "golden_apple 8"]:
            server.console(f"give {BOT} {item}", wait=0.1)
        server.send("equip_best_armor", {})
        server.drain(2)
        server.console(END + f"tp {BOT} 20 70 4", wait=3)
        server.console(END + f"spreadplayers 20 4 0 2 false {BOT}", wait=8)
        for _ in range(10):
            if dragon_health(server) is not None:
                break
            server.drain(2)
        before = dragon_health(server)
        command_id = server.send("bed_cycle", {"mode": mode})
        result = None
        perches = 0
        start = time.time()
        events = []

        def wait(seconds):
            try:
                found, seen = server.result(command_id, timeout=seconds)
                events.extend(seen)
                return found
            except TimeoutError:
                return None

        while time.time() - start < 150:
            result = wait(1)
            if result:
                break
            if server.find_in_output("bed_cycle on_post") and perches < 5 and (time.time() - start) > 15 * (perches + 1):
                perches += 1
                print("Drache hockt hin", perches)
                server.console(END + "tp @e[type=ender_dragon,limit=1] 0 68 0", wait=0.3)
                server.console(END + "data merge entity @e[type=ender_dragon,limit=1] {DragonPhase:6}", wait=0.3)
                result = wait(12)
                if result:
                    break
        after = dragon_health(server)
        deaths = sum(1 for e in events if e.get("name") == "player_died")
        deaths += sum(1 for line in server._output if "Claude died" in line or "was slain" in line or "was blown up" in line)
        for line in server._output:
            if "bed_cycle" in line or "damage_taken" in line:
                print("  ", line.strip()[:220])
        print("Drache vorher", before, "nachher", after, "Ergebnis", result, "Tode", deaths)
        booms = [line.split("bed_cycle boom", 1)[1].strip() for line in server._output if "bed_cycle boom" in line]
        hurt = sum(float(l.split('"amount":')[1].split(",")[0]) for l in server._output
                   if "damage_taken" in l and "bad_respawn_point" in l)
        with open(os.path.join(os.path.dirname(__file__), "..", "..", "run", "logs", "bed_cycle_results.log"), "a",
                  encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M')} lauf={run} mode={mode} dragon={before}->{after} "
                    f"perches={perches} beds={len(booms)} self_bed_damage={hurt:.1f} deaths={deaths} "
                    f"result={(result or {}).get('status')}/{(result or {}).get('reason')}\n")
        report.check("bed_cycle: Drache tot", after is None or after <= 1, f"{before} -> {after}")
        report.check("bed_cycle: Bot lebt", deaths == 0, str(deaths))
        server.send("stop", {})
        server.drain(1)
    finally:
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

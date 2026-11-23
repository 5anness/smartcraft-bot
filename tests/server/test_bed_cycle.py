"""Messung One-Cycle (30.10.2026): Wie viel Schaden machen Betten direkt unter dem Kopf des Drachen, wenn sie schnell
hintereinander explodieren, und reicht das gegen das Heilen der Kristalle? Die Testhilfe `/smartcraft debug bedprobe`
setzt die Betten ohne Weg und Reichweite (bester Fall). Alle Kristalle bleiben stehen.

Fälle: hockend (Phase erzwungen), im Flug. Je 8 Betten, Abstand und Höhe unter dem Kopf variiert.

Aufruf:  python tests/server/test_bed_cycle.py   (ca. 3 Minuten)
"""

import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer  # noqa: E402

BOT = "Claude"
END = "execute in minecraft:the_end run "


def dragon_health(server):
    mark = server.output_mark()
    server.console(END + "data get entity @e[type=ender_dragon,limit=1] Health", wait=1)
    line = server.find_in_output("has the following entity data", mark)
    if not line:
        return None
    return float(line.rsplit(":", 1)[-1].strip().rstrip("f"))


def booms(server, mark):
    lines = []
    for line in server._output:
        if "bedprobe boom" in line or "bedprobe end" in line:
            lines.append(line.split("bedprobe", 1)[1].strip())
    return lines[mark:]


def run_case(server, label, perch, count, interval, below, seen):
    server.console(END + "data merge entity @e[type=ender_dragon,limit=1] {Health:200f}", wait=0.5)
    if perch:
        server.console(END + "tp @e[type=ender_dragon,limit=1] 0 68 0", wait=0.3)
        server.console(END + "data merge entity @e[type=ender_dragon,limit=1] {DragonPhase:6}", wait=0.3)
    else:
        server.console(END + "data merge entity @e[type=ender_dragon,limit=1] {DragonPhase:0}", wait=0.3)
        server.drain(6)
    start = dragon_health(server)
    server.console(f"smartcraft debug bedprobe {count} {interval} {below}", wait=0.2)
    server.drain(count * interval / 20 + 2)
    end = dragon_health(server)
    server.drain(10)
    later = dragon_health(server)
    lines = booms(server, seen)
    print(f"\n== {label}: {start} -> {end} (10 s später {later})")
    for line in lines:
        print("  ", line)
    return start, end, later, len(lines) + seen


def main():
    report = Report()
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-bed-cycle.log")
    server = TestServer(log, world=f"ai_test_bedcycle_{int(time.time())}", seed=20261029)
    try:
        server.console("smartcraft events off")
        server.console(f"smartcraft join {BOT}", wait=1)
        for _ in range(30):
            if server.find_in_output("bot_joined"):
                break
            server.drain(1)
        server.console(f"execute in minecraft:the_end run tp {BOT} 40 70 0", wait=3)
        server.console(f"execute in minecraft:the_end run spreadplayers 40 0 0 2 false {BOT}", wait=10)
        server.console(f"effect give {BOT} resistance 900 4 true", wait=0.2)
        for _ in range(10):
            if dragon_health(server) is not None:
                break
            server.drain(2)
        seen = 0
        results = {}
        if len(sys.argv) > 1:
            # Kopfbahn: Bot steht nah am Brunnen (Abstand argv[1]) und schaut zu.
            r = int(sys.argv[1])
            server.console(f"execute in minecraft:the_end run tp {BOT} {r} 64 0", wait=1)
            server.console(f"execute in minecraft:the_end run spreadplayers {r} 0 0 1 false {BOT}", wait=3)
        cases = [
            ("im Flug, 2 unter dem Kopf, alle 10 Ticks", False, 2, 10, 2.0),
            ("im Flug, 0 unter dem Kopf, alle 10 Ticks", False, 2, 10, 0.0),
            ("im Flug, 4 unter dem Kopf, alle 10 Ticks", False, 2, 10, 4.0),
            ("hockend, 4 unter dem Kopf, alle 4 Ticks", True, 8, 4, 4.0),
            ("hockend, 2 unter dem Kopf, alle 10 Ticks", True, 8, 10, 2.0),
        ]
        for label, perch, count, interval, below in cases:
            server.console(END + "kill @e[type=enderman]", wait=0.2)
            server.console(END + "kill @e[type=area_effect_cloud]", wait=0.2)
            start, end, later, seen = run_case(server, label, perch, count, interval, below, seen)
            results[label] = (start, end, later)
            if end is None or end <= 0:
                print("   Drache tot, neuer Drache gibt es nicht – Abbruch der Serie")
                break
        any_kill = any(e is None or e <= 0 for _, e, _ in results.values())
        report.check("Messung lief (mindestens ein Fall mit Schaden)",
                     any(s is not None and e is not None and e < s for s, e, _ in results.values()) or any_kill,
                     str(results))
    finally:
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

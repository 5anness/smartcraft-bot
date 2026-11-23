"""Etappe 4b in echtem Gelände: Holz sammeln in einem Wald, mehrere Durchläufe an verschiedenen Stellen.

Aufruf:  python tests/server/test_forest.py [durchläufe]
Nutzt eine eigene normale Welt (run/world_forest) mit festem Seed, damit die Läufe vergleichbar sind.
Ein Durchlauf gilt als bestanden, wenn der Bot die verlangten Stämme hat und danach wieder auf
dem Boden steht, nicht auf Laub, einem Stamm oder seinem eigenen Turm.
"""

import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402

BOT = "Claude"
WORLD = "world_forest"
SEED = 20261013
LOGS_WANTED = 8


def main():
    runs = int(sys.argv[1]) if len(sys.argv) > 1 else 4
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-forest.log")
    server = TestServer(log, world=WORLD, seed=SEED)
    report = Report()
    try:
        run(server, report, runs)
    finally:
        server.close()
    print(f"Serverlog: {log}")
    print(f"Längste Wegsuche in einem Tick: {report.peak_search_ms} ms")
    return report.finish()


def locate_forest(server, near_x, near_z):
    """Sucht den nächsten Wald um einen Punkt und gibt dessen x und z zurück."""
    mark = server.output_mark()
    server.console(f"execute positioned {near_x} 64 {near_z} run locate biome minecraft:forest", wait=0)
    deadline = time.time() + 30
    while time.time() < deadline:
        line = server.find_in_output("minecraft:forest is at", mark)
        if line:
            x, _, z = re.search(r"\[(-?\d+), (-?\d+|~), (-?\d+)\]", line).groups()
            return int(x), int(z)
        time.sleep(0.2)
    raise RuntimeError("Kein Wald gefunden")


def run(server, report, runs):
    server.console("gamerule spawn_mobs false")
    server.console("time set noon")
    server.console("weather clear")
    server.console("smartcraft events off")
    server.console(f"smartcraft join {BOT}", wait=5)
    server.drain(0.5)

    for number in range(runs):
        # Jeder Durchlauf in einem anderen Waldstück, 300 Blöcke weiter.
        x, z = locate_forest(server, number * 300, number * 300)
        server.send("stop")
        server.console(f"clear {BOT}")
        server.console(f"effect give {BOT} minecraft:instant_health 1 10")
        server.console(f"spreadplayers {x} {z} 0 6 false {BOT}", wait=4)
        server.console("kill @e[type=item]")
        server.drain(0.5)
        start = server.look()

        result, _ = server.run("collect", {"block": "#logs", "count": LOGS_WANTED, "radius": 24}, timeout=600)
        report.note(result)
        logs = sum(g["count"] for g in result["data"].get("gained", []) if g["id"].endswith("_log"))
        end = server.look()
        under = end["ground"][1][1]
        on_ground = not any(word in under for word in ("leaves", "_log", "air"))
        passed = (result["status"] == "ok" and result["data"]["mined"] >= LOGS_WANTED and logs >= LOGS_WANTED
                  and on_ground and "standing_on_own_pillar" not in result["data"])
        report.check(f"Wald {number + 1} bei {x} {z}: {LOGS_WANTED} Stämme sammeln und am Boden enden", passed,
                     f"Start {start['pos']} auf {start['ground'][1][1]}, Ende {end['pos']} auf {under}, "
                     f"Stämme {logs}, {result['data']['ticks'] // 20} s, {summary(result)[:260]}")


if __name__ == "__main__":
    sys.exit(main())

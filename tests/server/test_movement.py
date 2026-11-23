"""Etappe 4a: Bewegung. Baut auf der flachen Testwelt Hindernisse und schickt den Bot hindurch.

Aufruf:  python tests/server/test_movement.py
Der Boden der flachen Welt ist Gras bei y=-61; der Bot steht also auf y=-60.
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402

FLOOR = -61
STAND = -60
BOT = "Claude"


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-movement.log")
    server = TestServer(log)
    report = Report()
    try:
        run(server, report)
    finally:
        server.close()
    print(f"Serverlog: {log}")
    print(f"Längste Wegsuche in einem Tick: {report.peak_search_ms} ms (Ziel: unter 3)")
    return report.finish()


def arena(server, x0, z):
    """Stellt den Bot an den Anfang einer frischen Bahn: Boden heil, alles darüber leer."""
    server.send("stop")
    # Erst hinstellen, damit die Chunks der Bahn geladen sind; /fill geht nur in geladenen Chunks.
    server.console(f"tp {BOT} {x0}.5 {STAND} {z}.5", wait=0.6)
    server.console(f"fill {x0 - 4} {FLOOR - 3} {z - 14} {x0 + 30} {FLOOR - 3} {z + 14} bedrock")
    server.console(f"fill {x0 - 4} {FLOOR - 2} {z - 14} {x0 + 30} {FLOOR} {z + 14} grass_block")
    server.console(f"fill {x0 - 4} {STAND} {z - 14} {x0 + 30} {STAND + 9} {z + 14} air")
    server.console("kill @e[type=!player]")
    server.console(f"effect give {BOT} minecraft:instant_health 1 10")
    server.console(f"tp {BOT} {x0}.5 {STAND} {z}.5", wait=0.8)
    server.drain(0.3)


def run(server, report):
    real_run = server.run

    def run_and_note(name, args=None, timeout=120):
        result, seen = real_run(name, args, timeout)
        report.note(result)
        return result, seen

    server.run = run_and_note
    server.console("gamerule spawn_mobs false")
    server.console("time set noon")
    server.console("smartcraft events off")
    server.console(f"smartcraft join {BOT}", wait=4)
    server.drain(0.5)

    # 1 Geradeaus, genau aufs Feld
    arena(server, 200, 0)
    result, _ = server.run("goto", {"pos": [215, STAND, 0], "tolerance": 0})
    report.check("geradeaus 15 Blöcke, genau aufs Feld",
                 result["status"] == "ok" and result["data"]["pos"] == [215, STAND, 0], summary(result))

    # 2 Mauer umgehen
    arena(server, 200, 40)
    server.console(f"fill 207 {STAND} 35 207 {STAND + 2} 45 stone")
    result, _ = server.run("goto", {"pos": [215, STAND, 40]})
    report.check("Mauer (11 breit, 3 hoch) umgehen", result["status"] == "ok", summary(result))

    # 3 Treppe aus ganzen Blöcken hinauf, dann 3 Blöcke hinunter
    arena(server, 200, 80)
    server.console(f"fill 205 {STAND} 79 215 {STAND} 81 stone")
    server.console(f"fill 208 {STAND + 1} 79 215 {STAND + 1} 81 stone")
    server.console(f"fill 211 {STAND + 2} 79 215 {STAND + 2} 81 stone")
    result, _ = server.run("goto", {"pos": [213, STAND + 3, 80], "tolerance": 0})
    report.check("drei Stufen hinaufspringen",
                 result["status"] == "ok" and result["data"]["pos"][1] == STAND + 3, summary(result))
    health_before = server.look()["health"]
    result, _ = server.run("goto", {"pos": [222, STAND, 80]})
    health_after = server.look()["health"]
    report.check("3 Blöcke hinunter ohne Schaden",
                 result["status"] == "ok" and health_after == health_before,
                 f"{summary(result)} Leben {health_before}->{health_after}")

    # 4 Vier Blöcke Fall: nicht springen, sondern ehrlich melden
    arena(server, 200, 120)
    server.console(f"fill 199 {STAND} 119 202 {STAND + 3} 122 stone")
    server.console(f"tp {BOT} 200.5 {STAND + 4} 120.5", wait=0.8)
    result, _ = server.run("goto", {"pos": [212, STAND, 120]})
    report.check("Turm (4 hoch): kein Sprung in die Tiefe, no_path",
                 result["status"] == "failed" and result["reason"] == "no_path"
                 and result["data"]["pos"][1] == STAND + 4, summary(result))

    # 5 Lavagraben: außen herum, ohne Schaden. Die neue Gefahr unterbricht einmal; danach weiter.
    arena(server, 200, 160)
    server.console(f"fill 207 {FLOOR} 153 207 {FLOOR} 167 lava")
    health_before = server.look()["health"]
    result, _ = server.run("goto", {"pos": [215, STAND, 160]})
    interrupted = result["status"] == "cancelled" and result["reason"] == "danger"
    if interrupted:
        result, _ = server.run("goto", {"pos": [215, STAND, 160]})
    health_after = server.look()["health"]
    report.check("Lavagraben: Gefahr meldet sich, danach außen herum ohne Schaden",
                 interrupted and result["status"] == "ok" and health_after == health_before,
                 f"unterbrochen={interrupted} {summary(result)} Leben {health_before}->{health_after}")
    server.console(f"fill 207 {FLOOR} 153 207 {FLOOR} 167 grass_block")

    # 6 Wassergraben, zu lang zum Umgehen: schwimmen
    arena(server, 200, 200)
    server.console(f"fill 205 {FLOOR - 1} 186 208 {FLOOR} 214 water")
    result, _ = server.run("goto", {"pos": [215, STAND, 200]})
    report.check("Wassergraben (4 breit, 2 tief) durchschwimmen", result["status"] == "ok", summary(result))
    server.console(f"fill 205 {FLOOR - 1} 186 208 {FLOOR} 214 grass_block")

    # 7 Leiter
    arena(server, 200, 240)
    server.console(f"fill 210 {STAND} 237 214 {STAND + 4} 243 stone")
    server.console(f"fill 209 {STAND} 240 209 {STAND + 4} 240 ladder[facing=west]")
    result, _ = server.run("goto", {"pos": [212, STAND + 5, 240], "tolerance": 0})
    report.check("Leiter 5 Blöcke hinauf",
                 result["status"] == "ok" and result["data"]["pos"][1] == STAND + 5, summary(result))

    # 8 Ziel mitten im Stein
    server.console(f"tp {BOT} 200.5 {STAND} 240.5", wait=0.8)
    result, _ = server.run("goto", {"pos": [212, STAND + 2, 240], "tolerance": 0})
    report.check("Ziel im Stein: sofort no_path mit Hinweis",
                 result["status"] == "failed" and result["reason"] == "no_path"
                 and result["data"].get("hint") == "goal_not_standable" and result["data"]["ticks"] <= 2,
                 summary(result))

    # 9 Ziel in einem zugemauerten Raum
    arena(server, 200, 280)
    server.console(f"fill 210 {STAND} 278 214 {STAND + 3} 282 stone")
    server.console(f"fill 211 {STAND} 279 213 {STAND + 1} 281 air")
    result, _ = server.run("goto", {"pos": [212, STAND, 280], "tolerance": 0})
    report.check("zugemauerter Raum: no_path in unter 3 s, mit nächstem erreichbaren Punkt",
                 result["status"] == "failed" and result["reason"] == "no_path"
                 and result["data"]["ticks"] <= 60 and "closest" in result["data"], summary(result))

    # 9b Platten quer über den Weg: darüber statt außen herum
    arena(server, 200, 400)
    server.console(f"fill 207 {STAND} 388 207 {STAND} 412 stone_slab")
    result, _ = server.run("goto", {"pos": [215, STAND, 400], "tolerance": 0})
    report.check("über eine Reihe Platten laufen",
                 result["status"] == "ok" and result["data"]["ticks"] < 110, summary(result))
    result, _ = server.run("goto", {"pos": [207, STAND, 400], "tolerance": 0})
    report.check("auf einer Platte stehen bleiben", result["status"] == "ok", summary(result))

    # 9c Echte Treppe: drei Treppenstufen hinauf auf ein Podest
    arena(server, 200, 440)
    for step in range(3):
        if step > 0:
            server.console(f"fill {205 + step} {STAND} 439 {205 + step} {STAND + step - 1} 441 stone")
        server.console(f"fill {205 + step} {STAND + step} 439 {205 + step} {STAND + step} 441 oak_stairs[facing=east]")
    server.console(f"fill 208 {STAND} 439 212 {STAND + 2} 441 stone")
    result, _ = server.run("goto", {"pos": [210, STAND + 3, 440], "tolerance": 0})
    report.check("Treppe hinauf", result["status"] == "ok" and result["data"]["pos"][1] == STAND + 3, summary(result))
    result, _ = server.run("goto", {"pos": [200, STAND, 440], "tolerance": 0})
    report.check("Treppe hinunter", result["status"] == "ok", summary(result))

    # 10 stop bricht ab
    arena(server, 200, 320)
    going = server.send("goto", {"pos": [228, STAND, 320]})
    time.sleep(1.0)
    stop_result, seen = server.run("stop")
    cancelled = next((e for e in seen if e.get("cmd_id") == going and e["name"] == "goto_result"), None)
    started = any(e.get("cmd_id") == going and e["name"] == "goto_started" for e in seen)
    report.check("stop: goto_started, dann goto cancelled (stopped), stop ok",
                 started and cancelled is not None and cancelled["status"] == "cancelled"
                 and cancelled["reason"] == "stopped" and stop_result["data"]["stopped"] == "goto",
                 summary(cancelled) if cancelled else "kein goto_result")
    time.sleep(0.5)
    first = server.look()["pos"]
    time.sleep(1.0)
    report.check("nach stop steht der Bot still", server.look()["pos"] == first, f"{first}")

    # 11 neuer Befehl verdrängt den alten
    going = server.send("goto", {"pos": [228, STAND, 320]})
    time.sleep(0.5)
    result, seen = server.run("goto", {"pos": [200, STAND, 320], "tolerance": 0})
    old = next((e for e in seen if e.get("cmd_id") == going and e["name"] == "goto_result"), None)
    report.check("neuer Befehl: alter cancelled (superseded), neuer ok",
                 old is not None and old["reason"] == "superseded" and result["status"] == "ok",
                 summary(result))

    # 12 Schaden unterbricht
    going = server.send("goto", {"pos": [228, STAND, 320]})
    time.sleep(0.7)
    server.console(f"damage {BOT} 2")
    result, _ = server.result(going)
    report.check("Schaden unterbricht: cancelled (damage) mit Auslöser",
                 result["status"] == "cancelled" and result["reason"] == "damage"
                 and result["data"].get("trigger", {}).get("event") == "damage_taken", summary(result))

    # 13 goto_entity
    arena(server, 200, 360)
    server.console(f"summon cow 218 {STAND} 364 {{NoAI:1b}}", wait=0.5)
    result, _ = server.run("goto_entity", {"target": "cow"})
    report.check("goto_entity cow: bis auf 2 Blöcke heran",
                 result["status"] == "ok" and result["data"]["distance"] <= 2.6, summary(result))
    result, _ = server.run("goto_entity", {"target": "dolphin"})
    report.check("goto_entity ohne Treffer: entity_not_found",
                 result["status"] == "failed" and result["reason"] == "entity_not_found", summary(result))

    # 14 follow: einer laufenden Kuh 6 Sekunden folgen
    server.console("kill @e[type=cow]")
    server.console(f"summon cow 206 {STAND} 360", wait=0.5)
    result, _ = server.run("follow", {"target": "cow", "distance": 2, "seconds": 6})
    report.check("follow cow 6 s: endet ok und ist in der Nähe",
                 result["status"] == "ok" and result["data"]["followed_seconds"] == 6
                 and result["data"]["distance"] <= 6, summary(result))

    # 15 falsche Argumente
    result, _ = server.run("goto", {})
    report.check("goto ohne pos: invalid_args",
                 result["status"] == "failed" and result["reason"] == "invalid_args", summary(result))


if __name__ == "__main__":
    sys.exit(main())

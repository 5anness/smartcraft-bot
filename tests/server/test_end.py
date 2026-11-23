"""Weg ins End: Türen, Bogen (`shoot`), Endportal füllen und betreten, Drache, Enderauge werfen.

Aufruf:  python tests/server/test_end.py
Teil 1 in der flachen Testwelt (Boden y=-61, der Bot steht auf y=-60), Teil 2 in einer eigenen normalen Welt
`ai_test_eye`, weil nur dort Festungen erzeugt werden. Dauer etwa 6 Minuten.
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, FLOOR, STAND, arena  # noqa: E402

Z = 900


def ok(result):
    return result["status"] == "ok"


def lane(server, x0, *items):
    arena(server, x0, Z)
    server.console(f"clear {BOT}")
    for item in items:
        server.console(f"give {BOT} {item}")
    server.drain(0.4)


def main():
    report = Report()
    log = os.path.join(os.environ.get("TEMP", "."), "aiplayer-test-end.log")
    server = TestServer(log)
    try:
        flat_part(server, report)
    finally:
        server.close()
    log_eye = os.path.join(os.environ.get("TEMP", "."), "aiplayer-test-end-eye.log")
    server = TestServer(log_eye, world="ai_test_eye", seed=20261014)
    try:
        eye_part(server, report)
    finally:
        server.close()
    print(f"Serverlogs: {log}, {log_eye}")
    return report.finish()


def flat_part(server, report):
    server.console("gamerule spawn_mobs false")
    server.console("aiplayer events off")
    server.console(f"aiplayer join {BOT}", wait=5)

    # --- Türen und Zauntore: eine Wand quer über die Bahn, nur durch die Tür passierbar
    x = 2000
    lane(server, x)
    server.console(f"fill {x + 4} {STAND} {Z - 10} {x + 4} {STAND + 3} {Z + 10} stone")
    server.console(f"setblock {x + 4} {STAND} {Z} oak_door[half=lower,facing=east]")
    server.console(f"setblock {x + 4} {STAND + 1} {Z} oak_door[half=upper,facing=east]", wait=0.4)
    result, _ = server.run("goto", {"pos": [x + 8, STAND, Z], "tolerance": 0})
    report.check("goto durch eine geschlossene Holztür: ok", ok(result), summary(result))
    report.check("Die Tür steht offen", server.block_is(x + 4, STAND, Z, "oak_door[open=true]"))
    server.console(f"fill {x + 10} {STAND} {Z - 10} {x + 10} {STAND + 3} {Z + 10} stone")
    server.console(f"setblock {x + 10} {STAND} {Z} oak_fence_gate[facing=east]", wait=0.4)
    result, _ = server.run("goto", {"pos": [x + 14, STAND, Z], "tolerance": 0})
    report.check("goto durch ein geschlossenes Zauntor: ok", ok(result), summary(result))

    # --- shoot: Endkristall, schwebende Lohe
    x = 2100
    lane(server, x, "bow", "arrow 64", "shield")
    server.run("shield", {"on": True})  # Wie im KI-Lauf: Schild oben, trotzdem muss der Bogen schießen
    server.console(f"summon end_crystal {x + 14} {STAND} {Z} {{ShowBottom:0b}}", wait=0.4)
    result, _ = server.run("shoot", {"target": "end_crystal", "shots": 4})
    report.check("shoot auf einen Endkristall: zerstört", ok(result) and result["data"].get("outcome") == "destroyed", summary(result))
    server.console(f"summon blaze {x + 12} {STAND + 4} {Z + 3} {{NoAI:1b}}", wait=0.4)
    result, _ = server.run("shoot", {"target": "blaze", "shots": 8})
    report.check("shoot auf eine schwebende Lohe: tot", ok(result) and result["data"].get("outcome") == "destroyed", summary(result))
    # Drei Fehlschüsse in Folge auf ein nahes Ziel: zum Schwert wechseln (Wunsch von Hannes). Resistenz 255 lässt die
    # Pfeile wirkungslos; ein Wüstenzombie brennt nicht in der Sonne (das zählte sonst als Treffer).
    x = 2150  # eigene Bahn: Der gesprengte Endkristall hinterlässt einen Krater
    lane(server, x, "bow", "arrow 64", "iron_sword")
    server.console(f"summon husk {x + 12} {STAND} {Z} {{NoAI:1b}}", wait=0.4)
    server.console(f"effect give @e[type=husk,limit=1,sort=nearest,x={x + 12},y={STAND},z={Z}] resistance 6 255 true", wait=0.2)
    result, _ = server.run("shoot", {"target": "husk", "shots": 8}, timeout=90)
    report.check("shoot: nach 3 Fehlschüssen auf 12 Blöcke Nahkampf, Ziel tot",
                 ok(result) and result["data"].get("switched_to_melee") is True
                 and result["data"].get("outcome") == "killed_melee", summary(result))

    # --- Drache: Pfeile treffen, Nahkampf auf den gelandeten Drachen macht Schaden
    x = 2200
    lane(server, x, "bow", "arrow 64", "diamond_sword")
    server.console(f"summon ender_dragon {x + 16} {STAND + 3} {Z} {{NoAI:1b}}", wait=1.0)
    server.console(f"tp {BOT} {x + 9} {STAND} {Z}", wait=0.5)
    attack_id = server.send("attack", {"target": "ender_dragon", "min_health": 2})
    server.game_sleep(8)
    server.send("stop")
    result, _ = server.result(attack_id, timeout=30)
    health = result["data"].get("target", {}).get("health", 200)
    report.check("attack auf den Drachen (Nahkampf): Schaden", result["data"].get("hits", 0) > 0 and health < 200, summary(result))
    server.console("kill @e[type=ender_dragon]", wait=0.5)

    # --- Festungsläufe 15 und 18: Rahmen stehen wie im Portalraum auf dem Boden (Portal auf Fußhöhe), der Bot steht in
    # der Ecke zwischen zwei Rahmen; enter_portal blieb dort mit not_teleported stehen.
    x = 2250
    lane(server, x)
    cx, cz = x + 6, Z
    for i in (-1, 0, 1):
        for fx, fz, facing in ((cx + i, cz - 2, "south"), (cx + i, cz + 2, "north"), (cx - 2, cz + i, "east"),
                               (cx + 2, cz + i, "west")):
            server.console(f"setblock {fx} {STAND} {fz} end_portal_frame[facing={facing},eye=true]")
    server.console(f"fill {cx - 1} {FLOOR} {cz - 1} {cx + 1} {FLOOR} {cz + 1} lava")
    server.console(f"fill {cx - 1} {STAND} {cz - 1} {cx + 1} {STAND} {cz + 1} end_portal", wait=0.5)
    server.console(f"tp {BOT} {cx + 2}.5 {STAND} {cz - 2}.5", wait=1)
    result, _ = server.run("enter_portal", timeout=90)
    report.check("enter_portal aus der Ecke zwischen zwei Rahmen: Bot ist im End",
                 ok(result) and result["data"].get("dim") == "minecraft:the_end", summary(result))
    server.console(f"execute in minecraft:overworld run tp {BOT} {x} {STAND} {Z}", wait=3)
    server.console(f"fill {cx - 2} {FLOOR} {cz - 2} {cx + 2} {STAND} {cz + 2} air")
    server.console(f"fill {cx - 2} {FLOOR} {cz - 2} {cx + 2} {FLOOR} {cz + 2} stone", wait=0.5)

    # --- Endportal: 12 Rahmen um eine 3 x 3-Fläche, füllen, Portal offen, hineingehen
    x = 2300
    lane(server, x, "ender_eye 12")
    cx, cz = x + 6, Z
    frames = []
    for i in (-1, 0, 1):
        frames += [(cx + i, cz - 2, "south"), (cx + i, cz + 2, "north"), (cx - 2, cz + i, "east"), (cx + 2, cz + i, "west")]
    for fx, fz, facing in frames:
        server.console(f"setblock {fx} {FLOOR} {fz} end_portal_frame[facing={facing}]")
    # Wie im Portalraum: innen Lava eine Ebene tiefer, das Portal entsteht auf Rahmenhöhe darüber.
    server.console(f"fill {cx - 1} {FLOOR - 1} {cz - 1} {cx + 1} {FLOOR - 1} {cz + 1} lava")
    server.console(f"fill {cx - 1} {FLOOR} {cz - 1} {cx + 1} {FLOOR} {cz + 1} air", wait=0.5)
    server.drain(5.5)
    result, events = server.run("fill_portal", timeout=180)
    report.check("fill_portal: 12 Augen gesetzt, Portal offen",
                 ok(result) and result["data"]["frames_with_eye"] == 12 and result["data"]["portal_open"], summary(result))
    result, _ = server.run("enter_portal", timeout=120)
    report.check("enter_portal: Bot ist im End", ok(result) and result["data"].get("dim") == "minecraft:the_end", summary(result))
    # Pfeile auf den echten Drachen: auf die Hauptinsel, nah an die Mitte, wo er kreist.
    server.console(f"execute in minecraft:the_end run spreadplayers 0 12 0 6 false {BOT}", wait=4)
    server.console(f"give {BOT} bow")
    server.console(f"give {BOT} arrow 64", wait=0.5)
    damage = 0
    for _ in range(8):
        result, _ = server.run("shoot", {"target": "ender_dragon", "shots": 6}, timeout=60)
        damage += result["data"].get("damage_dealt", 0)
        if damage > 0:
            break
        server.game_sleep(5)
    report.check("shoot auf den fliegenden Enderdrachen im End: Schaden", damage > 0, summary(result))
    server.console(f"execute in minecraft:overworld run tp {BOT} {x} {STAND} {Z}", wait=2)


def locate_from(server):
    """Nächste Festung vom Bot aus, wie /locate sie meldet: (x, z) oder None."""
    mark = server.output_mark()
    server.console(f"execute at {BOT} run locate structure minecraft:stronghold", wait=10)
    line = server.find_in_output("The nearest minecraft:stronghold", mark) or ""
    match = re.search(r"\[(-?\d+), [~\d-]+, (-?\d+)\]", line)
    return (int(match.group(1)), int(match.group(2))) if match else None


def bearing_error(result, stronghold):
    """Wie viel Grad die gemeldete Flugrichtung von der wahren Richtung zur Festung abweicht."""
    import math
    start, end = result["data"]["from"], result["data"]["ended_at"]
    flown = math.atan2(end[2] - start[2], end[0] - start[0])
    true = math.atan2(stronghold[1] - start[2], stronghold[0] - start[0])
    return abs((math.degrees(flown - true) + 180) % 360 - 180)


def eye_part(server, report):
    server.console("gamerule spawn_mobs false")
    server.console("aiplayer events off")
    server.console(f"aiplayer join {BOT}", wait=8)
    # Die Augenwelt bleibt bestehen: immer vom selben Startplatz an der Oberfläche werfen.
    server.console(f"effect give {BOT} slow_falling 60 1")
    server.console(f"effect give {BOT} resistance 60 4")
    server.console(f"tp {BOT} 1200 200 0", wait=1)
    server.game_sleep(25)
    for _ in range(20):
        mark = server.output_mark()
        server.console(f"give {BOT} ender_eye 4", wait=1.0)
        if server.find_in_output("Gave 4", mark):
            break
    target_one = locate_from(server)
    first, _ = server.run("throw_eye", timeout=30)
    error_one = bearing_error(first, target_one) if ok(first) and target_one else 999
    report.check("throw_eye: Richtung zeigt auf die Festung (höchstens 15 Grad daneben)", error_one <= 15,
                 f"{error_one:.0f} Grad; {summary(first)}")
    # Zweiter Wurf 400 Blöcke versetzt; aus der Höhe mit Sinkflug, landet sicher auf der Oberfläche.
    start = first["data"].get("from", [0, 0, 0])
    server.console(f"effect give {BOT} slow_falling 60 1")
    server.console(f"tp {BOT} {start[0] + 400} 200 {start[2]}", wait=1)
    server.game_sleep(25)
    target_two = locate_from(server)
    second, _ = server.run("throw_eye", timeout=30)
    error_two = bearing_error(second, target_two) if ok(second) and target_two else 999
    report.check("Zweiter Wurf: Richtung zeigt auf die nächste Festung", error_two <= 15, f"{error_two:.0f} Grad")
    estimate = second["data"].get("estimated_stronghold")
    if target_one == target_two and estimate:
        far = ((target_two[0] - start[0]) ** 2 + (target_two[1] - start[2]) ** 2) ** 0.5
        off = ((estimate["x"] - target_two[0]) ** 2 + (estimate["z"] - target_two[1]) ** 2) ** 0.5
        report.check("Zwei Würfe zur selben Festung: Schätzung höchstens 10 % der Entfernung daneben",
                     off <= max(100, far * 0.1), f"Schätzung {estimate}, Festung {target_two}, Abstand {off:.0f}")
    else:
        print(f"    (Schätzung nicht geprüft: Würfe zeigten auf verschiedene Festungen {target_one} / {target_two})")

    # --- Festungsläufe 7–12: locate end_portal nennt den Portalraum; throw_eye lehnt mit locate ab.
    server.console("aiplayer locate on", wait=0.3)
    result, _ = server.run("throw_eye", timeout=30)
    report.check("throw_eye mit locate: use_locate", result["status"] == "failed" and result.get("reason") == "use_locate",
                 summary(result))
    result, _ = server.run("locate", {"structure": "end_portal"}, timeout=60)
    found = result["status"] == "ok" and "approx_pos" in result.get("data", {})
    report.check("locate end_portal: ok mit approx_pos", found, summary(result))
    if found:
        # Der Portalraum hat Endportalrahmen: Bot hinbringen, der Rundum-Blick muss sie melden.
        px, py, pz = result["data"]["approx_pos"]
        server.console(f"effect give {BOT} resistance 60 4")
        server.console(f"tp {BOT} {px}.5 {py} {pz}.5", wait=4)
        # Rahmen im Umkreis zählen: zweimal fill (ohne Auge, dann mit), die Welt bleibt danach gleich; eine der
        # beiden Zeilen meldet die Anzahl.
        mark = server.output_mark()
        box = f"{px - 8} {py - 6} {pz - 8} {px + 8} {py + 6} {pz + 8}"
        server.console(f"fill {box} end_portal_frame[eye=false] replace end_portal_frame[eye=true]", wait=1)
        server.console(f"fill {box} end_portal_frame[eye=true] replace end_portal_frame[eye=false]", wait=1)
        found_frames = server.find_in_output("Successfully filled", mark) or ""
        report.check("locate end_portal: Endportalrahmen bis 8 Blöcke um approx_pos", bool(found_frames),
                     found_frames.strip())
    server.console("aiplayer locate off")


if __name__ == "__main__":
    sys.exit(main())

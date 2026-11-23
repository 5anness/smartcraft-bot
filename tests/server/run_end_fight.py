"""Testlauf mit echter API: Der Bot startet auf der Endinsel und soll den Enderdrachen töten.

Aufruf:  python tests/server/run_end_fight.py <Laufnummer> [Stunden, Standard 1.0]

- Neue Welt `ai_end_fight_<n>`, Start auf der Hauptinsel im End (etwa 40 Blöcke vom Brunnen), Ausrüstung wie nach
  einem Festungslauf: Diamantwerkzeug und -rüstung, Bogen, 128 Pfeile, Bausteine, Essen, Wassereimer.
- Erfolg: Fortschritt end/kill_dragon („Free the End“). Gescheitert: Tod, Zeit um oder Begleitprozess beendet.
- Ziel ist, echte Fehler im Drachenkampf zu sehen, bevor dafür Befehle gebaut werden. Keine Hilfen im Lauf.
- Ergebnis als eine Zeile in run/logs/end_fight_results.log, Verlauf in run/logs/end_fight_<n>.log und _agent.log.
"""

import datetime
import json
import math
import os
import random
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import PORT, PROJECT, TestServer  # noqa: E402
from run_end_game import BOT, where  # noqa: E402

GOAL = ("Du bist im End auf der Hauptinsel, mit voller Diamantausrüstung, Bogen, Pfeilen und 12 Betten. Töte den Enderdrachen. "
        "Überleben geht vor, "
        "fall nicht in die Leere.")
ITEMS = ["diamond_pickaxe", "diamond_sword", "diamond_axe", "diamond_helmet", "diamond_chestplate",
         "diamond_leggings", "diamond_boots", "shield", "bow", "arrow 64", "arrow 64", "cooked_beef 32", "bread 16",
         "cobblestone 64", "cobblestone 64", "cobblestone 64", "water_bucket", "bucket", "golden_apple 2", "white_bed 12",
         "crafting_table", "torch 32"]


def entity_count(server, selector):
    """Anzahl passender Wesen im End (über `execute if entity`, das die Treffer zählt)."""
    mark = server.output_mark()
    server.console(f"execute in minecraft:the_end if entity {selector}", wait=0.6)
    line = server.find_in_output("Test passed", mark)
    match = re.search(r"[Cc]ount: (\d+)", line or "")
    return int(match.group(1)) if match else 0


def usage(world):
    """Tokens und Kosten des Laufs aus dem JSON-Log des Begleitprozesses (Zeilen kind=model)."""
    folder = os.path.join(PROJECT, "companion", "logs")
    total = {"calls": 0, "input": 0, "cached": 0, "output": 0, "cost": 0.0}
    for name in sorted(os.listdir(folder)):
        path = os.path.join(folder, name)
        if not name.endswith(".jsonl") or os.path.getmtime(path) < time.time() - 3 * 3600:
            continue
        with open(path, encoding="utf-8") as file:
            lines = file.readlines()
        if not any(f'\\"world\\":\\"{world}\\"' in line or f'"world": "{world}"' in line for line in lines):
            continue
        # `cost` ist die laufende Summe des Ziels (beginnt bei jedem neuen Ziel bei 0): je Ziel den letzten Stand nehmen.
        last = 0.0
        for line in lines:
            try:
                row = json.loads(line)
            except ValueError:
                continue
            if row.get("kind") == "model":
                total["calls"] += 1
                for key in ("input", "cached", "output"):
                    total[key] += row.get(key, 0)
                cost = row.get("cost", 0.0)
                if cost < last:
                    total["cost"] += last
                last = cost
        total["cost"] += last
    return total


def usage_text(world):
    u = usage(world)
    return (f"{u['calls']} Aufrufe, Eingabe {u['input'] / 1000:.0f} Tsd. Tokens (davon {u['cached'] / 1000:.0f} Tsd. aus dem "
            f"Cache), Ausgabe {u['output'] / 1000:.1f} Tsd., {u['cost']:.3f} $")


_dragon_seen = [False]


def dragon_killed(server):
    """Fortschritt end/kill_dragon oder: Der Drache war da und ist weg. Betten-Explosionen rechnet das Spiel keinem Spieler zu,
    dann fehlt der Fortschritt (Lauf 94: Drache mit 11 Betten getötet, Skript meldete „Zeitlimit“)."""
    mark = server.output_mark()
    server.console(f"execute if entity @a[name={BOT},advancements={{minecraft:end/kill_dragon=true}}]", wait=0.6)
    if server.find_in_output("Test passed", mark):
        return True
    # Nur zählen, wenn der Bot im End ist: Nach einem Tod liegt er in der Oberwelt, das End entlädt, und „kein Drache“
    # sah wie ein Sieg aus (Lauf 95).
    if where(server) != "the_end":
        return False
    if entity_count(server, "@e[type=ender_dragon]") > 0:
        _dragon_seen[0] = True
        return False
    if not _dragon_seen[0]:
        return False
    # Und der Mod hat es bestätigt (bed_cycle/fight_dragon melden „dragon_dead“).
    return bool(server.find_in_output('"outcome":"dragon_dead"'))


def main():
    run = int(sys.argv[1])
    hours = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
    world = f"ai_end_fight_{run}"
    seed = random.randint(1, 2**31)
    log_path = os.path.join(PROJECT, "run", "logs", f"end_fight_{run}.log")
    started = time.time()

    def log(line):
        text = f"[{datetime.datetime.now():%H:%M:%S}] {line}"
        print(text, flush=True)
        with open(log_path, "a", encoding="utf-8") as file:
            file.write(text + "\n")

    def result(outcome):
        minutes = (time.time() - started) / 60
        line = f"Lauf {run} (Seed {seed}): {outcome} nach {minutes:.0f} min; {usage_text(world)}"
        log(f"ERGEBNIS: {line}")
        with open(os.path.join(PROJECT, "run", "logs", "end_fight_results.log"), "a", encoding="utf-8") as file:
            file.write(f"{datetime.datetime.now():%Y-%m-%d %H:%M} {line}\n")

    def status(server):
        return (f"{where(server)}, Kristalle {entity_count(server, '@e[type=end_crystal]')}, "
                f"Drache {entity_count(server, '@e[type=ender_dragon]')}")

    # Alte Welt gleichen Namens löschen: Lauf 101 lief in der Welt eines abgebrochenen Versuchs ohne Drachen.
    import shutil
    shutil.rmtree(os.path.join(PROJECT, os.environ.get("SMARTCRAFT_TEST_RUNDIR", "run"), world), ignore_errors=True)
    server = TestServer(os.path.join(PROJECT, "run", "logs", f"end_fight_{run}_server.log"), world=world, seed=seed)
    agent = None
    try:
        server.console("difficulty normal")
        server.console(f"smartcraft join {BOT}", wait=6)
        for _ in range(30):
            answer, _ = server.run("inventory", timeout=10)
            if answer and answer.get("status") == "ok":
                break
            time.sleep(2)
        server.console("smartcraft locate off")
        server.console(f"clear {BOT}")
        for item in ITEMS:
            server.console(f"give {BOT} {item}", wait=0.1)
        server.drain(1.0)
        answer, _ = server.run("inventory", timeout=10)
        have = str(answer.get("data", {}))
        missing = [i for i in ("diamond_sword", "diamond_chestplate", "bow", "arrow") if i not in have]
        if missing:
            log(f"Ausrüstung fehlt nach dem Verteilen: {missing}")
            result("ungültig (Ausrüstung fehlt)")
            return 1
        server.run("equip_best_armor", timeout=20)
        server.run("equip", {"item": "diamond_sword"}, timeout=20)
        # Auf die Hauptinsel, nicht auf die Obsidianplattform bei 100 0: Die entsteht nur beim Weg durchs Portal.
        # spreadplayers verteilt im ganzen Kreis bis 45 (End-Lauf 5 startete am Brunnen): fester Ring bei 40 Blöcken.
        angle = random.uniform(0, 6.283)
        # 28 statt 40: Auf 40 Blöcken stehen die Obsidiantürme, spreadplayers stellte ihn auf eine Turmspitze (End-Lauf 30).
        x, z = round(28 * math.cos(angle)), round(28 * math.sin(angle))
        server.console(f"execute in minecraft:the_end run spreadplayers {x} {z} 0 3 false {BOT}", wait=6)
        server.drain(5.0)  # Der Drachenkampf startet, sobald ein Spieler im End ist
        log(f"Lauf {run}: Welt {world} (Seed {seed}), Start: {status(server)}")
        # Mehrfach prüfen: Beim Laden der Endinsel antwortet der Server manchmal zu spät (Lauf 9: als ungültig abgebrochen).
        for _ in range(3):
            if where(server) == "the_end":
                break
            server.drain(2.0)
        else:
            result("ungültig (nicht im End)")
            return 1

        env = {**os.environ, "SMARTCRAFT_PORT": str(PORT), "SMARTCRAFT_TTS": "0", "SMARTCRAFT_MAX_STEPS": "3000",
               "PYTHONIOENCODING": "utf-8", "PYTHONUNBUFFERED": "1"}
        agent_out = open(os.path.join(PROJECT, "run", "logs", f"end_fight_{run}_agent.log"), "w", encoding="utf-8")
        agent = subprocess.Popen([sys.executable, os.path.join(PROJECT, "companion", "agent.py"), GOAL],
                                 cwd=PROJECT, env=env, stdout=agent_out, stderr=subprocess.STDOUT)
        watch_from = server.output_mark()

        deadline = time.time() + hours * 3600
        last_report = 0
        while time.time() < deadline:
            time.sleep(15)
            died = server.find_in_output("player_died", watch_from)
            if not died and dragon_killed(server):
                result("ERFOLG (Drache tot)")
                break
            if died:
                cause = re.search(r'"message":"([^"]*)"', died)
                result(f"GESTORBEN ({cause.group(1) if cause else '?'}; {status(server)})")
                break
            if agent.poll() is not None:
                result(f"Begleitprozess beendet ({status(server)})")
                break
            if time.time() - last_report >= 60:
                last_report = time.time()
                log(f"{status(server)}; {usage_text(world)}")
        else:
            result(f"Zeitlimit {hours} h ({status(server)})")
    finally:
        if agent is not None and agent.poll() is None:
            agent.terminate()
            try:
                agent.wait(10)
            except subprocess.TimeoutExpired:
                agent.kill()
        server.close()
        log("Server gestoppt")
    return 0


if __name__ == "__main__":
    sys.exit(main())

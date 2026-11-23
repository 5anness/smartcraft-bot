"""Testlauf mit echter API: Der Bot startet ausgerüstet im Nether, holt Lohenruten und kommt lebend in die Oberwelt zurück.

Aufruf:  python tests/server/run_nether.py <Laufnummer> [Stunden, Standard 1]

- Ausrüstung und Start wie in run_dragon.py (neue Welt `ai_nether_<n>`, `/smartcraft locate on`).
- Erfolg: Bot in der Oberwelt, nie gestorben, mindestens 7 Lohenruten im Inventar.
- Gescheitert: Tod (Lauf endet sofort), Zeit um oder Begleitprozess beendet.
- Ergebnis als eine Zeile in run/logs/nether_results.log, Verlauf in run/logs/nether_<n>.log und nether_<n>_agent.log.
"""

import datetime
import os
import random
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import PORT, PROJECT, TestServer  # noqa: E402
from run_dragon import EXTRA  # noqa: E402
from run_end_game import BOT, ITEMS, where  # noqa: E402

RODS = 10
GOAL = (f"Du startest im Nether mit voller Ausrüstung. Hol mindestens {RODS} Lohenruten aus einer Netherfestung und "
        "komm lebend in die Oberwelt zurück (zum Beispiel mit build_portal und enter_portal). Überleben geht vor.")


def rods(server):
    """Lohenruten im Inventar des Bots (über `clear` mit Menge 0, das nur zählt)."""
    mark = server.output_mark()
    server.console(f"clear {BOT} blaze_rod 0", wait=0.6)
    line = server.find_in_output("matching item", mark)
    match = re.search(r"Found (\d+) matching item", line or "")
    return int(match.group(1)) if match else 0


def main():
    run = int(sys.argv[1])
    hours = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
    world = f"ai_nether_{run}"
    seed = random.randint(1, 2**31)
    log_path = os.path.join(PROJECT, "run", "logs", f"nether_{run}.log")
    started = time.time()

    def log(line):
        text = f"[{datetime.datetime.now():%H:%M:%S}] {line}"
        print(text, flush=True)
        with open(log_path, "a", encoding="utf-8") as file:
            file.write(text + "\n")

    def result(outcome):
        minutes = (time.time() - started) / 60
        line = f"Lauf {run} (Seed {seed}): {outcome} nach {minutes:.0f} min"
        log(f"ERGEBNIS: {line}")
        with open(os.path.join(PROJECT, "run", "logs", "nether_results.log"), "a", encoding="utf-8") as file:
            file.write(f"{datetime.datetime.now():%Y-%m-%d %H:%M} {line}\n")

    server = TestServer(os.path.join(PROJECT, "run", "logs", f"nether_{run}_server.log"), world=world, seed=seed)
    agent = None
    try:
        server.console("difficulty normal")
        server.console(f"smartcraft join {BOT}", wait=6)
        for _ in range(30):
            answer, _ = server.run("inventory", timeout=10)
            if answer and answer.get("status") == "ok":
                break
            time.sleep(2)
        server.console("smartcraft locate on")
        server.console(f"clear {BOT}")
        skip = {"dirt 64", "oak_planks 32", "stick 16", "iron_ingot 16", "coal 32"}
        for item in [i for i in ITEMS if i not in skip] + EXTRA:
            server.console(f"give {BOT} {item}", wait=0.1)
        server.drain(1.0)
        answer, _ = server.run("inventory", timeout=10)
        have = str(answer.get("data", {}))
        missing = [i for i in ("diamond_sword", "diamond_chestplate", "bow", "cooked_beef", "shield", "obsidian") if i not in have]
        if missing:
            log(f"Ausrüstung fehlt nach dem Verteilen: {missing}")
            result("ungültig (Ausrüstung fehlt)")
            return 1
        server.run("equip_best_armor", timeout=20)
        server.run("equip", {"item": "diamond_sword"}, timeout=20)
        server.console(f"execute in minecraft:the_nether run spreadplayers 0 0 0 16 under 100 false {BOT}", wait=4)
        log(f"Lauf {run}: Welt {world} (Seed {seed}), Start: {where(server)}")

        env = {**os.environ, "SMARTCRAFT_PORT": str(PORT), "SMARTCRAFT_TTS": "0", "SMARTCRAFT_MAX_STEPS": "3000",
               "PYTHONIOENCODING": "utf-8", "PYTHONUNBUFFERED": "1"}
        agent_out = open(os.path.join(PROJECT, "run", "logs", f"nether_{run}_agent.log"), "w", encoding="utf-8")
        agent = subprocess.Popen([sys.executable, os.path.join(PROJECT, "companion", "agent.py"), GOAL],
                                 cwd=PROJECT, env=env, stdout=agent_out, stderr=subprocess.STDOUT)
        watch_from = server.output_mark()

        deadline = time.time() + hours * 3600
        last_report = 0
        while time.time() < deadline:
            time.sleep(15)
            died = server.find_in_output("player_died", watch_from)
            if died:
                cause = re.search(r'"message":"([^"]*)"', died)
                result(f"GESTORBEN ({cause.group(1) if cause else '?'})")
                break
            if agent.poll() is not None:
                count = rods(server)
                dim = where(server)
                result(f"{'ERFOLG' if dim == 'overworld' and count >= RODS else 'Begleitprozess beendet'}"
                       f" ({dim}, {count} Lohenruten)")
                break
            dim = where(server)
            count = rods(server)
            if dim == "overworld" and count >= RODS:
                result(f"ERFOLG (Oberwelt, {count} Lohenruten)")
                break
            if time.time() - last_report >= 60:
                last_report = time.time()
                log(f"{dim}, {count} Lohenruten")
        else:
            result(f"Zeitlimit {hours} h ({where(server)}, {rods(server)} Lohenruten)")
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

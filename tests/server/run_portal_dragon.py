"""Testlauf mit echter API: Start an der Oberfläche der Oberwelt mit Endausrüstung, Ziel: Enderdrache besiegen.

Aufruf:  python tests/server/run_portal_dragon.py <Laufnummer> [Stunden, Standard 2] [--mit-locate]

- Neue normale Welt `ai_portal_<n>`, Start an der Oberfläche bei 0 0, ohne `locate` (nur mit --mit-locate).
- Ausrüstung: `/smartcraft kit end` – Diamant (Goldstiefel), Schild, Bogen, 128 Pfeile, 18 Enderaugen (Wunsch von Hannes: mindestens 18,
  ein Portal braucht bis zu 12, Würfe gehen verloren), 12 Betten, 192 Bausteine, Essen, Goldäpfel – also `end_ready`.
- Er sucht die Festung, setzt die Augen ein, geht ins End und tötet den Drachen.
- Erfolg: Drache tot (Fortschritt end/kill_dragon oder `dragon_dead` aus bed_cycle/fight_dragon). Tode beenden den
  Lauf nicht, stehen aber im Ergebnis. Ergebnis in run/logs/portal_results.log.
"""

import datetime
import os
import random
import re
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import PORT, PROJECT, TestServer  # noqa: E402
from run_end_game import BOT, where  # noqa: E402

GOAL = ("Du startest an der Oberfläche der Oberwelt mit voller Endausrüstung (18 Enderaugen, 16 Betten). "
        "Finde die Festung, setz die Augen in den Endportalrahmen, geh ins End und töte den Enderdrachen.")


def count(server, item):
    mark = server.output_mark()
    server.console(f"clear {BOT} {item} 0", wait=0.6)
    match = re.search(r"Found (\d+) matching item", server.find_in_output("matching item", mark) or "")
    return int(match.group(1)) if match else 0


def main():
    run = int(sys.argv[1])
    hours = float(sys.argv[2]) if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else 2.0
    # Seit 04.11.2026 Standard ohne locate (Wunsch von Hannes): Festung über throw_eye finden.
    locate = "--mit-locate" in sys.argv
    world = f"ai_portal_{run}"
    seed = random.randint(1, 2**31)
    logs = os.path.join(PROJECT, "run", "logs")
    log_path = os.path.join(logs, f"portal_{run}.log")
    agent_log = os.path.join(logs, f"portal_{run}_agent.log")

    def log(line):
        text = f"[{datetime.datetime.now():%H:%M:%S}] {line}"
        print(text, flush=True)
        with open(log_path, "a", encoding="utf-8") as file:
            file.write(text + "\n")

    shutil.rmtree(os.path.join(PROJECT, os.environ.get("SMARTCRAFT_TEST_RUNDIR", "run"), world), ignore_errors=True)
    outcome = "abgebrochen"
    start = time.time()
    deaths = 0
    server = TestServer(os.path.join(logs, f"portal_{run}_server.log"), world=world, seed=seed)
    agent = None
    try:
        server.console("difficulty normal")
        server.console(f"smartcraft join {BOT}", wait=6)
        for _ in range(30):
            answer, _ = server.run("inventory", timeout=10)
            if answer and answer.get("status") == "ok":
                break
            time.sleep(2)
        server.console("smartcraft locate on" if locate else "smartcraft locate off")
        server.console(f"clear {BOT}")
        server.console("smartcraft kit end", wait=1.0)
        server.drain(1.0)
        answer, _ = server.run("inventory", timeout=10)
        have = str(answer.get("data", {}))
        missing = [i for i in ("diamond_sword", "ender_eye", "white_bed", "bow") if i not in have]
        if missing:
            log(f"Ausrüstung fehlt nach dem Verteilen: {missing}, Lauf abgebrochen")
            return 1
        if "end_missing" in have:
            log(f"Hinweis: Mod meldet noch end_missing: {answer.get('data', {}).get('end_missing')}")
        server.run("equip_best_armor", timeout=20)
        server.run("equip", {"item": "diamond_sword"}, timeout=20)
        server.console(f"execute in minecraft:overworld run spreadplayers 0 0 0 16 false {BOT}", wait=4)
        log(f"Lauf {run}: Welt {world} (Seed {seed}), Start: {where(server)}")

        env = {**os.environ, "SMARTCRAFT_PORT": str(PORT), "SMARTCRAFT_TTS": "0", "SMARTCRAFT_MAX_STEPS": "3000",
               "PYTHONIOENCODING": "utf-8", "PYTHONUNBUFFERED": "1"}
        agent_out = open(agent_log, "w", encoding="utf-8")
        agent = subprocess.Popen([sys.executable, os.path.join(PROJECT, "companion", "agent.py"), GOAL],
                                 cwd=PROJECT, env=env, stdout=agent_out, stderr=subprocess.STDOUT)
        watch_from = server.output_mark()

        goal_ends = 0
        deadline = time.time() + hours * 3600
        last_report = 0
        while time.time() < deadline:
            time.sleep(15)
            deaths = sum(1 for line in server._output[watch_from:] if "Event:" in line and "player_died" in line)
            with open(agent_log, encoding="utf-8", errors="replace") as file:
                ends = file.read().count("Ziel vom Modell beendet")
            if ends > goal_ends:
                goal_ends = ends
                if goal_ends > 5:
                    outcome = f"Modell gab 6-mal auf ({where(server)})"
                    break
                log(f"Modell beendete das Ziel ({goal_ends}. Mal), Ziel neu gesetzt")
                server.console(f"smartcraft goal {GOAL} Du hattest aufgehört, das Ziel ist aber nicht erreicht: mach weiter.")
            if agent.poll() is not None:
                outcome = f"Begleitprozess beendet (Code {agent.returncode})"
                break
            mark = server.output_mark()
            server.console(f"execute if entity @a[name={BOT},advancements={{minecraft:end/kill_dragon=true}}]", wait=0.6)
            dragon_dead = any('"outcome":"dragon_dead"' in line for line in server._output)
            if server.find_in_output("Test passed", mark) or dragon_dead:
                outcome = "ERFOLG (Drache tot)"
                break
            if time.time() - last_report >= 60:
                last_report = time.time()
                log(f"{where(server)}, {count(server, 'ender_eye')} Enderaugen, {deaths} Tode")
        else:
            outcome = f"Zeitlimit {hours} h ({where(server)})"
        line = f"Lauf {run} (Seed {seed}, {'mit' if locate else 'ohne'} locate): {outcome}, {deaths} Tode, nach {(time.time() - start) / 60:.0f} min"
        log(f"ERGEBNIS: {line}")
        with open(os.path.join(logs, "portal_results.log"), "a", encoding="utf-8") as file:
            file.write(f"{datetime.datetime.now():%Y-%m-%d %H:%M} {line}\n")
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

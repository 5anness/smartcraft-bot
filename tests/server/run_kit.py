"""Testlauf mit echter API: das ganze Spiel. Neue normale Welt, leeres Inventar, Ziel: Enderdrache besiegt.

Aufruf:  python tests/server/run_full.py <Laufnummer> [Stunden, Standard 8] [--ohne-locate] [--mit-nacht]

- Neue Welt `ai_kit_<n>`, Start mit `/aiplayer kit nether` ohne Obsidian, Ziel Enderdrache.
- Standard: dauerhaft Mittag (Zeit angehalten), damit keine Nachtpausen in Hütten anfallen (Gesamtlauf 3: grob ein Viertel
  der Zeit); `--mit-nacht` lässt den Tag-Nacht-Wechsel an.
- Ein Tod beendet den Lauf nicht (der Begleitprozess schickt die KI bis zu 3-mal zurück ans Ziel); Tode werden gezählt.
- Erfolg: Fortschritt „Free the End“ (Drache getötet). Gescheitert: Zeit um oder Begleitprozess beendet.
- Ergebnis als eine Zeile in run/logs/full_results.log, Verlauf in run/logs/full_<n>.log und _agent.log.
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
from run_end_game import BOT, where  # noqa: E402
from run_stronghold import count  # noqa: E402

GOAL = ("Spiel Minecraft komplett durch: Ziel ist, den Enderdrachen zu besiegen. Du startest in einer neuen Welt mit "
        "voller Nether-Ausrüstung (Diamantrüstung mit Goldstiefeln, Diamantschwert, Diamantspitzhacke, Bogen, Essen, "
        "Feuerzeug, Wassereimer), aber ohne Obsidian. Besorge zuerst 10 Obsidian (Lava suchen, make_obsidian), baue "
        "das Netherportal und spiel im Nether weiter. Überleben geht vor.")
# Fortschritte als grober Stand im Log (Name wie im Chat des Servers).
MILESTONES = ("We Need to Go Deeper", "A Terrible Fortress",
              "Into Fire", "Eye Spy", "The End?", "Free the End")


def main():
    run = int(sys.argv[1])
    hours = float(sys.argv[2]) if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else 8.0
    locate = "--ohne-locate" not in sys.argv
    always_day = "--mit-nacht" not in sys.argv
    world = f"ai_kit_{run}"
    seed = random.randint(1, 2**31)
    log_path = os.path.join(PROJECT, "run", "logs", f"kit_{run}.log")
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
        with open(os.path.join(PROJECT, "run", "logs", "kit_results.log"), "a", encoding="utf-8") as file:
            file.write(f"{datetime.datetime.now():%Y-%m-%d %H:%M} {line}\n")

    server = TestServer(os.path.join(PROJECT, "run", "logs", f"kit_{run}_server.log"), world=world, seed=seed)
    agent = None
    try:
        server.console("difficulty normal")
        if always_day:
            server.console("time set noon")
            server.console("gamerule advance_time false")
        server.console(f"aiplayer join {BOT}", wait=6)
        for _ in range(30):
            answer, _ = server.run("inventory", timeout=10)
            if answer and answer.get("status") == "ok":
                break
            time.sleep(2)
        server.console("aiplayer locate on" if locate else "aiplayer locate off")
        server.console(f"clear {BOT}", wait=0.5)
        server.console("aiplayer kit nether", wait=1)
        server.console(f"clear {BOT} minecraft:obsidian", wait=0.5)
        log(f"Lauf {run}: Welt {world} (Seed {seed}), Start: {where(server)}, locate {'an' if locate else 'aus'}, "
            f"{'immer Tag' if always_day else 'mit Nacht'}")

        env = {**os.environ, "AIPLAYER_PORT": str(PORT), "AIPLAYER_TTS": "0", "AIPLAYER_MAX_STEPS": "20000",
               "PYTHONIOENCODING": "utf-8", "PYTHONUNBUFFERED": "1"}
        agent_out = open(os.path.join(PROJECT, "run", "logs", f"kit_{run}_agent.log"), "w", encoding="utf-8")
        agent = subprocess.Popen([sys.executable, os.path.join(PROJECT, "companion", "agent.py"), GOAL],
                                 cwd=PROJECT, env=env, stdout=agent_out, stderr=subprocess.STDOUT)
        watch_from = server.output_mark()

        deadline = time.time() + hours * 3600
        last_report = 0
        deaths_seen = 0
        reached = []
        agent_log = os.path.join(PROJECT, "run", "logs", f"kit_{run}_agent.log")
        goal_ends = 0
        while time.time() < deadline:
            time.sleep(15)
            for name in MILESTONES:
                if name not in reached and server.find_in_output(f"[{name}]", watch_from):
                    reached.append(name)
                    log(f"Fortschritt: {name}")
            deaths = [line for line in server._output[watch_from:] if "player_died" in line and "(aiplayer) Event" in line]
            for line in deaths[deaths_seen:]:
                cause = re.search(r'"message":"([^"]*)"', line)
                log(f"TOD: {cause.group(1) if cause else '?'}")
            deaths_seen = len(deaths)
            # Beendet das Modell das Ziel vorzeitig (Gesamtlauf 1: Aufgabe in einer Lavazone), Ziel erneut setzen.
            with open(agent_log, encoding="utf-8", errors="replace") as file:
                ends = file.read().count("Ziel vom Modell beendet")
            if ends > goal_ends and "Free the End" not in reached:
                goal_ends = ends
                if goal_ends > 5:
                    result(f"Modell gab 6-mal auf ({where(server)}, {deaths_seen} Tode, zuletzt {reached[-1:] or '-'})")
                    break
                log(f"Modell beendete das Ziel ({goal_ends}. Mal), Ziel neu gesetzt")
                server.console(f"aiplayer goal {GOAL} Du hattest aufgehört, das Ziel ist aber nicht erreicht: mach weiter.")
            if "Free the End" in reached:
                result(f"ERFOLG (Drache besiegt, {deaths_seen} Tode)")
                break
            if agent.poll() is not None:
                result(f"Begleitprozess beendet ({where(server)}, {deaths_seen} Tode, zuletzt {reached[-1:] or '-'})")
                break
            if time.time() - last_report >= 60:
                last_report = time.time()
                log(f"{where(server)}, Tode {deaths_seen}, Diamanten {count(server, 'diamond')}, "
                    f"Obsidian {count(server, 'obsidian')}, Lohenruten {count(server, 'blaze_rod')}, Perlen {count(server, 'ender_pearl')}, "
                    f"Augen {count(server, 'ender_eye')}")
        else:
            result(f"Zeitlimit {hours} h ({where(server)}, {deaths_seen} Tode, zuletzt {reached[-1:] or '-'})")
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

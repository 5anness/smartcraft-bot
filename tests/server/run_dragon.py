"""Testlauf mit echter API: Der Bot startet ausgerüstet im Nether und soll den Enderdrachen töten.

Aufruf:  python tests/server/run_dragon.py <Laufnummer> [Stunden, Standard 2]

- Jeder Lauf eine neue normale Welt `ai_dragon_<n>` mit eigenem Seed, Testserver auf Port 25598.
- Ausrüstung: `/smartcraft kit nether` (volle Netherausrüstung mit 10 Obsidian), keine Tränke; Start im Nether, `/smartcraft locate on`.
- Ergebnis als eine Zeile in run/logs/dragon_results.log.
- Endet, wenn der Bot den Drachen getötet hat (Fortschritt end/kill_dragon) oder die Zeit um ist.
- Fortschritt: run/logs/dragon_<n>.log (alle 60 s Dimension), Begleitprozess: run/logs/dragon_<n>_agent.log
"""

import datetime
import os
import random
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import PORT, PROJECT, TestServer  # noqa: E402
from run_end_game import BOT, ITEMS, where  # noqa: E402

WATCHER = os.environ.get("SMARTCRAFT_WATCHER", "ichbinderhanness")
GOAL = "Töte den Enderdrachen und spiel das Spiel durch. Du startest im Nether mit voller Ausrüstung."
EXTRA = ["cobblestone 64", "cobblestone 64", "cobblestone 64", "obsidian 10",
         "golden_apple 8", "ender_pearl 12"]


def main():
    run = int(sys.argv[1])
    hours = float(sys.argv[2]) if len(sys.argv) > 2 else 2.0
    world = f"ai_dragon_{run}"
    seed = random.randint(1, 2**31)
    log_path = os.path.join(PROJECT, "run", "logs", f"dragon_{run}.log")

    def log(line):
        text = f"[{datetime.datetime.now():%H:%M:%S}] {line}"
        print(text, flush=True)
        with open(log_path, "a", encoding="utf-8") as file:
            file.write(text + "\n")

    import shutil
    shutil.rmtree(os.path.join(PROJECT, os.environ.get("SMARTCRAFT_TEST_RUNDIR", "run"), world), ignore_errors=True)
    outcome = "abgebrochen"
    server = TestServer(os.path.join(PROJECT, "run", "logs", f"dragon_{run}_server.log"), world=world, seed=seed)
    agent = None
    try:
        server.console("difficulty normal")
        server.console(f"smartcraft join {BOT}", wait=6)
        # Lauf 12: In einer neuen Welt dauerte der Join länger, die ersten give gingen ins Leere („No player was found“).
        for _ in range(30):
            result, _ = server.run("inventory", timeout=10)
            if result and result.get("status") == "ok":
                break
            time.sleep(2)
        server.console("smartcraft locate on")
        server.console(f"clear {BOT}")
        server.console("smartcraft kit nether", wait=1.0)
        server.drain(1.0)
        result, _ = server.run("inventory", timeout=10)
        have = str(result.get("data", {}))
        missing = [i for i in ("diamond_sword", "diamond_chestplate", "bow", "cooked_beef", "shield") if i not in have]
        if missing:
            log(f"Ausrüstung fehlt nach dem Verteilen: {missing}, Lauf abgebrochen")
            return 1
        server.run("equip_best_armor", timeout=20)
        start = time.time()
        server.run("equip", {"item": "diamond_sword"}, timeout=20)
        # Teleport prüfen und wiederholen: Lauf 36 blieb in der Oberwelt (Start „unknown“), der Bot hielt sich für im Nether.
        for attempt in range(5):
            server.console(f"execute in minecraft:the_nether run spreadplayers {attempt * 64} 0 0 16 under 100 false {BOT}", wait=4)
            if where(server) == "the_nether":
                break
        else:
            log("Teleport in den Nether gescheitert, Lauf abgebrochen")
            return 1
        log(f"Lauf {run}: Welt {world} (Seed {seed}), Start im Nether: {where(server)}")

        env = {**os.environ, "SMARTCRAFT_PORT": str(PORT), "SMARTCRAFT_TTS": "0", "SMARTCRAFT_MAX_STEPS": "3000",
               "PYTHONIOENCODING": "utf-8", "PYTHONUNBUFFERED": "1"}
        agent_out = open(os.path.join(PROJECT, "run", "logs", f"dragon_{run}_agent.log"), "w", encoding="utf-8")
        agent = subprocess.Popen([sys.executable, os.path.join(PROJECT, "companion", "agent.py"), GOAL],
                                 cwd=PROJECT, env=env, stdout=agent_out, stderr=subprocess.STDOUT)

        watcher_placed = False
        goal_ends = 0
        deadline = time.time() + hours * 3600
        last_report = 0
        while time.time() < deadline:
            time.sleep(15)
            # Beendet das Modell das Ziel vorzeitig (Lauf 17: gab auf einer Brücke ohne Bausteine auf), Ziel erneut setzen.
            agent_log = os.path.join(PROJECT, "run", "logs", f"dragon_{run}_agent.log")
            with open(agent_log, encoding="utf-8", errors="replace") as file:
                ends = file.read().count("Ziel vom Modell beendet")
            if ends > goal_ends:
                goal_ends = ends
                if goal_ends > 5:
                    log("Modell gab 6-mal auf")
                    outcome = f"Modell gab 6-mal auf ({where(server)})"
                    break
                log(f"Modell beendete das Ziel ({goal_ends}. Mal), Ziel neu gesetzt")
                server.console(f"smartcraft goal {GOAL} Du hattest aufgehört, das Ziel ist aber nicht erreicht: mach weiter.")
            if agent.poll() is not None:
                log(f"Begleitprozess beendet (Code {agent.returncode})")
                outcome = f"Begleitprozess beendet (Code {agent.returncode})"
                break
            # Zuschauer (Wunsch von Hannes): beim ersten Beitritt Zuschauermodus und zu Claude.
            if not watcher_placed:
                mark = server.output_mark()
                server.console(f"execute if entity @a[name={WATCHER}]", wait=0.6)
                if server.find_in_output("Test passed", mark):
                    server.console(f"gamemode spectator {WATCHER}", wait=0.3)
                    server.console(f"tp {WATCHER} {BOT}", wait=0.3)
                    watcher_placed = True
            mark = server.output_mark()
            server.console(f"execute if entity @a[name={BOT},advancements={{minecraft:end/kill_dragon=true}}]", wait=0.6)
            # Bettexplosionen zählen im Spiel nicht als Kill des Spielers (kein Fortschritt „Free the End“): Lauf 20 tötete den
            # Drachen mit bed_cycle, das Skript merkte es nicht. Darum auch das Ergebnis von bed_cycle/fight_dragon prüfen.
            dragon_dead = any('"outcome":"dragon_dead"' in line for line in server._output)
            if server.find_in_output("Test passed", mark) or dragon_dead:
                log("ENDERDRACHE GETÖTET!")
                outcome = "ERFOLG (Drache tot)"
                break
            if time.time() - last_report >= 60:
                last_report = time.time()
                log(f"Dimension: {where(server)}")
        else:
            log(f"Zeitlimit von {hours} h erreicht")
            outcome = f"Zeitlimit {hours} h ({where(server)})"
        with open(os.path.join(PROJECT, "run", "logs", "dragon_results.log"), "a", encoding="utf-8") as file:
            file.write(f"{datetime.datetime.now():%Y-%m-%d %H:%M} Lauf {run} (Seed {seed}): {outcome} nach "
                       f"{(time.time() - start) / 60:.0f} min\n")
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

"""Ein KI-Spiel mit echter API: Der Bot startet mit Nether-Ausrüstung und soll ins End kommen.

Aufruf (Schlüssel in ANTHROPIC_API_KEY oder in companion/api_key.txt):
    python tests/server/run_end_game.py [Stunden, Standard 3] [--weiter] [--zuschauer NAME]

--weiter: Inventar behalten (Neustart in derselben Welt), keine Startausrüstung.
--zuschauer NAME: Dieser Spieler wird Operator und beim Beitritt Zuschauer neben dem Bot.

- Neue normale Welt `ai_end_run` (fester Seed), eigener Testserver auf Port 25598, keine Sprachausgabe.
- Startausrüstung: Diamantspitzhacke, Diamantschwert, Diamantrüstung mit Goldstiefeln (Piglins), Schild,
  Bogen, Essen, Bausteine, Feuerzeug, Eimer. Obsidian bekommt er nicht: Das Portal baut er selbst.
- Alle 15 Sekunden wird geprüft, ob der Bot im End ist. Dann: Begleitprozess beenden, Welt speichern, Server stoppen.
- Fortschritt steht in run/logs/end_game.log (alle 60 s: Dimension, Position, Leben) und im Log des
  Begleitprozesses unter companion/logs/.
"""

import datetime
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import PORT, PROJECT, TestServer  # noqa: E402

BOT = "Claude"
WORLD = "ai_end_run"
SEED = 20261014
GOAL = ("Komm ins End. Du hast schon Diamantwerkzeug, Rüstung und Nether-Ausrüstung. Bau ein Netherportal, "
        "hol im Nether Lohenruten, besorg Enderperlen, craft Enderaugen, finde die Festung und geh durch das Endportal.")
ITEMS = ["diamond_pickaxe", "diamond_sword", "diamond_axe", "diamond_shovel", "diamond_helmet", "diamond_chestplate",
         "diamond_leggings", "golden_boots", "shield", "bow", "arrow 64", "cooked_beef 64", "bread 32",
         "cobblestone 64", "cobblestone 64", "dirt 64", "flint_and_steel", "water_bucket", "bucket", "bucket",
         "crafting_table", "furnace", "torch 64", "oak_planks 32", "stick 16", "iron_ingot 16", "coal 32"]


def log(line):
    stamp = datetime.datetime.now().strftime("%H:%M:%S")
    text = f"[{stamp}] {line}"
    print(text, flush=True)
    with open(os.path.join(PROJECT, "run", "logs", "end_game.log"), "a", encoding="utf-8") as file:
        file.write(text + "\n")


def where(server):
    """Dimension des Bots über einen Befehl, der nur in der richtigen Dimension trifft."""
    # Ohne „run say“: Das stünde im Chat, und der Bot hielte bei jeder Prüfung seine Aktion an.
    for dim in ("the_end", "the_nether", "overworld"):
        mark = server.output_mark()
        server.console(f"execute in minecraft:{dim} if entity @a[name={BOT},distance=0..]", wait=0.6)
        if server.find_in_output("Test passed", mark):
            return dim
    return "unknown"


def main():
    args = sys.argv[1:]
    keep = "--weiter" in args
    viewer = args[args.index("--zuschauer") + 1] if "--zuschauer" in args else None
    numbers = [arg for arg in args if arg.replace(".", "", 1).isdigit()]
    hours = float(numbers[0]) if numbers else 3.0
    key_file = os.path.join(PROJECT, "companion", "api_key.txt")
    if not os.environ.get("ANTHROPIC_API_KEY") and not os.path.exists(key_file):
        print("Kein Schlüssel: ANTHROPIC_API_KEY setzen oder companion/api_key.txt anlegen.")
        return 1
    server = TestServer(os.path.join(PROJECT, "run", "logs", "end_game_server.log"), world=WORLD, seed=SEED)
    agent = None
    try:
        server.console("difficulty normal")
        server.console(f"smartcraft join {BOT}", wait=6)
        if viewer:
            server.console(f"op {viewer}")
        if not keep:
            server.console(f"clear {BOT}")
            for item in ITEMS:
                server.console(f"give {BOT} {item}", wait=0.1)
            server.drain(1.0)
            server.run("equip_best_armor", timeout=20)
            server.run("equip", {"item": "diamond_sword"}, timeout=20)
        log(f"Welt {WORLD} (Seed {SEED}), {'Inventar behalten' if keep else 'Ausrüstung gegeben'}, starte den Begleitprozess")

        env = {**os.environ, "SMARTCRAFT_PORT": str(PORT), "SMARTCRAFT_TTS": "0", "SMARTCRAFT_MAX_STEPS": "3000",
               "PYTHONIOENCODING": "utf-8", "PYTHONUNBUFFERED": "1"}
        agent_out = open(os.path.join(PROJECT, "run", "logs", "end_game_agent.log"), "w", encoding="utf-8")
        agent = subprocess.Popen([sys.executable, os.path.join(PROJECT, "companion", "agent.py"), GOAL],
                                 cwd=PROJECT, env=env, stdout=agent_out, stderr=subprocess.STDOUT)

        deadline = time.time() + hours * 3600
        last_report = 0
        viewer_placed = False
        while time.time() < deadline:
            time.sleep(15)
            if agent.poll() is not None:
                log(f"Begleitprozess beendet (Code {agent.returncode})")
                break
            if viewer and not viewer_placed:
                mark = server.output_mark()
                server.console(f"execute if entity @a[name={viewer}]", wait=0.6)
                if server.find_in_output("Test passed", mark):
                    server.console(f"gamemode spectator {viewer}")
                    server.console(f"tp {viewer} {BOT}")
                    viewer_placed = True
                    log(f"{viewer} ist Zuschauer beim Bot")
            dim = where(server)
            if dim == "the_end":
                log("Der Bot ist im End! Spiel wird beendet und gespeichert.")
                break
            if time.time() - last_report >= 60:
                last_report = time.time()
                log(f"Dimension: {dim}")
        else:
            log(f"Zeitlimit von {hours} h erreicht")
    finally:
        if agent is not None and agent.poll() is None:
            agent.terminate()
            try:
                agent.wait(10)
            except subprocess.TimeoutExpired:
                agent.kill()
        server.console("save-all flush", wait=5)
        log("Welt gespeichert")
        server.close()
        log("Server gestoppt")
    return 0


if __name__ == "__main__":
    sys.exit(main())

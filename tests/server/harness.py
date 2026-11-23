"""Testgerüst: startet den Testserver ohne Fenster und steuert ihn von zwei Seiten.

- Serverkonsole (Minecraft-Befehle wie /fill, /tp, /summon) über die Standardeingabe
- Brücke des Mods (Befehle an den Bot, Events zurück) über den lokalen Socket

Damit lassen sich Abläufe wie „Hindernis bauen, Bot hinschicken, Ergebnis prüfen“ automatisch
und wiederholbar durchspielen. Aufruf über die Testskripte in diesem Ordner.
"""

import itertools
import json
import os
import queue
import socket
import subprocess
import sys
import threading
import time

PROJECT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
# Eigener Port für Tests: Auf dem Standardport 25599 kann gleichzeitig ein echtes Spiel laufen,
# und dessen Bot darf keine Testbefehle bekommen.
PORT = int(os.environ.get("SMARTCRAFT_TEST_PORT", "25598"))
# Ticks pro Sekunde für die Tests (Spiel: 20). Mehr Ticks lassen die Aktionen schneller ablaufen; die
# Zeitgrenzen der Tests in echten Sekunden werden dadurch nur großzügiger.
TICK_RATE = int(os.environ.get("SMARTCRAFT_TEST_TICK_RATE", "20"))
# Mehrere Testserver gleichzeitig: eigener Spielordner (relativ zum Projekt) und eigener Spielport je Instanz.
RUN_DIR = os.environ.get("SMARTCRAFT_TEST_RUNDIR", "run")
TEST_GAME_PORT = os.environ.get("SMARTCRAFT_TEST_GAMEPORT")


class TestServer:
    def __init__(self, log_path, world=None, seed=None, camera=False):
        """world=None nimmt die flache Standardwelt; sonst eine normale Welt dieses Namens mit festem Seed.

        camera=True: Der Server braucht dafür online-mode=false, damit der Zuschauer-Client ohne Konto beitreten kann.
        """
        run_dir = os.path.join(PROJECT, RUN_DIR)
        if RUN_DIR != "run":
            # Eigener Ordner je Instanz: eula und server.properties aus run/ übernehmen.
            os.makedirs(run_dir, exist_ok=True)
            for name in ("eula.txt", "server.properties"):
                target = os.path.join(run_dir, name)
                if not os.path.exists(target):
                    import shutil
                    # Läuft in run/ gerade ein Test, steht dort dessen Welt; das Original liegt dann in .vor-test.
                    source = os.path.join(PROJECT, "run", name)
                    if os.path.exists(source + ".vor-test"):
                        source += ".vor-test"
                    shutil.copyfile(source, target)
        self._properties_path = os.path.join(run_dir, "server.properties")
        self._backup_path = self._properties_path + ".vor-test"
        self._original_properties = None
        # Brach ein früherer Lauf ab, ohne aufzuräumen, liegt hier noch das Original: erst zurückholen.
        if os.path.exists(self._backup_path):
            os.replace(self._backup_path, self._properties_path)
        # Spawnschutz aus: Sobald ops.json einen Op hat, könnte der Bot nahe am Weltspawn nichts abbauen (Kit-Lauf 1).
        wanted = {"spawn-protection": "0"}
        if world is not None:
            wanted.update({"level-name": world, "level-seed": str(seed), "level-type": "minecraft\\:normal"})
        if camera:
            wanted["online-mode"] = "false"
        if TEST_GAME_PORT:
            wanted["server-port"] = TEST_GAME_PORT
        if wanted:
            self._patch_properties(wanted)
        self._log = open(log_path, "w", encoding="utf-8")
        self._events = queue.Queue()
        self._ids = itertools.count(1)
        self._ready = threading.Event()
        self._output = []
        self._tokens = itertools.count(1)
        self.all_events = []
        self.camera = None

        gradlew = "gradlew.bat" if os.name == "nt" else "./gradlew"
        self._process = subprocess.Popen(
            [os.path.join(PROJECT, gradlew), "runServer", "--console=plain", f"-PaiRunDir={RUN_DIR}"],
            cwd=PROJECT, env={**os.environ, "SMARTCRAFT_PORT": str(PORT)}, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", shell=(os.name == "nt"))
        threading.Thread(target=self._read_server_output, daemon=True).start()

        if not self._ready.wait(240):
            self.close()
            raise RuntimeError(f"Server nicht gestartet, siehe {log_path}")
        self._socket = self._connect()
        threading.Thread(target=self._read_bridge, daemon=True).start()
        if TICK_RATE != 20:
            mark = self.output_mark()
            self.console(f"tick rate {TICK_RATE}", wait=0.5)
            if self.find_in_output("Unknown or incomplete command", mark) or self.find_in_output("Incorrect argument", mark):
                print(f"Warnung: tick rate {TICK_RATE} nicht angenommen, Tests laufen mit 20 Ticks", flush=True)
            else:
                print(f"Testserver läuft mit {TICK_RATE} Ticks pro Sekunde", flush=True)

    def game_sleep(self, seconds):
        """Wartet so lange, dass im Spiel etwa so viele Sekunden vergehen (bei schnellerem Tick kürzer)."""
        time.sleep(seconds * 20 / TICK_RATE)

    # --- Serverkonsole

    def console(self, command, wait=0.15):
        """Führt einen Minecraft-Befehl auf der Serverkonsole aus."""
        self._log.write(f">>> {command}\n")
        self._process.stdin.write(command + "\n")
        self._process.stdin.flush()
        time.sleep(wait)

    def block_is(self, x, y, z, block):
        """Fragt den Server, ob an der Position dieser Block steht."""
        token = f"CHECK{next(self._tokens)}"
        start = len(self._output)
        self.console(f"execute if block {x} {y} {z} {block} run say {token}", wait=0)
        self.console(f"say END{token}", wait=0)
        deadline = time.time() + 5
        while time.time() < deadline:
            seen = self._output[start:]
            if any(f"END{token}" in line for line in seen):
                return any(f"] {token}" in line for line in seen)
            time.sleep(0.05)
        raise TimeoutError("Server hat die Blockabfrage nicht beantwortet")

    def find_in_output(self, text, since=0):
        """Erste Zeile der Serverausgabe ab Index since, die den Text enthält, oder None."""
        return next((line for line in self._output[since:] if text in line), None)

    def output_mark(self):
        return len(self._output)

    # --- Brücke

    def send(self, name, args=None):
        """Schickt einen Befehl an den Bot und gibt die id zurück."""
        command_id = f"t-{next(self._ids)}"
        message = {"v": 1, "type": "command", "id": command_id, "name": name, "args": args or {}}
        self._socket.sendall((json.dumps(message) + "\n").encode("utf-8"))
        return command_id

    def result(self, command_id, timeout=120):
        """Wartet auf das Ergebnis eines Befehls. Gibt (Ergebnis, Events bis dahin) zurück."""
        seen = []
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                event = self._events.get(timeout=0.5)
            except queue.Empty:
                continue
            seen.append(event)
            if event.get("cmd_id") == command_id and event["name"].endswith("_result"):
                return event, seen
        raise TimeoutError(f"Kein Ergebnis für {command_id} nach {timeout} s")

    def run(self, name, args=None, timeout=120):
        """Befehl schicken und auf das Ergebnis warten."""
        return self.result(self.send(name, args), timeout)

    def drain(self, seconds=0.0):
        """Sammelt Events ein, die in dieser Zeit ankommen."""
        seen = []
        deadline = time.time() + seconds
        while True:
            try:
                seen.append(self._events.get(timeout=max(0.0, deadline - time.time())))
            except queue.Empty:
                return seen

    def look(self):
        return self.run("look", timeout=10)[0]["data"]

    def close(self):
        if self.camera is not None:
            self.camera.close()
        try:
            self.console("stop", wait=0)
            self._process.wait(60)
        except Exception:
            self._process.kill()
        self._log.close()
        if self._original_properties is not None:
            with open(self._properties_path, "w", encoding="utf-8", newline="\n") as file:
                file.write(self._original_properties)
            if os.path.exists(self._backup_path):
                os.remove(self._backup_path)

    def _patch_properties(self, wanted):
        with open(self._properties_path, encoding="utf-8") as file:
            self._original_properties = file.read()
        with open(self._backup_path, "w", encoding="utf-8", newline="\n") as file:
            file.write(self._original_properties)
        lines = []
        for line in self._original_properties.splitlines():
            key = line.split("=", 1)[0]
            lines.append(f"{key}={wanted[key]}" if key in wanted else line)
        with open(self._properties_path, "w", encoding="utf-8", newline="\n") as file:
            file.write("\n".join(lines) + "\n")

    # --- intern

    def _read_server_output(self):
        for line in self._process.stdout:
            self._log.write(line)
            self._log.flush()
            self._output.append(line)
            if "Done (" in line:
                self._ready.set()

    def _connect(self):
        deadline = time.time() + 30
        while True:
            try:
                return socket.create_connection(("127.0.0.1", PORT), timeout=3)
            except OSError:
                if time.time() > deadline:
                    raise
                time.sleep(0.5)

    def _read_bridge(self):
        self._socket.settimeout(None)
        try:
            for line in self._socket.makefile("r", encoding="utf-8", newline="\n"):
                event = json.loads(line)
                self.all_events.append(event)
                self._events.put(event)
                if self.camera is not None:
                    self.camera.on_event(event)
        except OSError:
            pass


SHOT_PORT = int(os.environ.get("SMARTCRAFT_SHOT_PORT", "25597"))
GAME_PORT = 25565  # server.properties: server-port


class Camera:
    """Zuschauer mit eigenem Spielfenster: tritt dem Testserver als „Kamera“ bei, sieht dem Bot zu
    und speichert bei auffälligen Events ein Bild (run/screenshots/<lauf>_<nr>_<anlass>.png).

    Die Bilder entstehen in einem Hilfsthread, damit das Lesen der Events nicht stockt.
    Im Schnelllauf (/tick sprint) kommt der Client nicht mit; dort wird nicht fotografiert.
    """

    MIN_GAP = 8.0       # Sekunden zwischen zwei gewöhnlichen Bildern
    MAX_PER_RUN = 60

    def __init__(self, server, bot, spectator="Kamera"):
        self.server = server
        self.bot = bot
        self.spectator = spectator
        self.run_name = "lauf1"
        self.shots = []          # (Dateiname, Anlass)
        self.paused = False
        self._count = 0
        self._last = 0.0
        self._queue = queue.Queue()
        self._socket = None
        self._reader = None
        self._replies = queue.Queue()
        self._process = None
        self._closing = False
        self.screens_dir = os.path.join(PROJECT, "run", "screenshots")

    def start(self, timeout=300):
        """Startet den Client und wartet, bis der Zuschauer im Spiel ist."""
        mark = self.server.output_mark()
        gradlew = os.path.join(PROJECT, "gradlew.bat" if os.name == "nt" else "gradlew")
        args = f"--username {self.spectator} --quickPlayMultiplayer 127.0.0.1:{GAME_PORT}"
        self._process = subprocess.Popen(
            [gradlew, "runClient", "--console=plain", f"--args={args}"], cwd=PROJECT,
            env={**os.environ, "SMARTCRAFT_SHOT_PORT": str(SHOT_PORT)},
            stdout=open(os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-client.log"), "w", encoding="utf-8"),
            stderr=subprocess.STDOUT, shell=(os.name == "nt"))
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.server.find_in_output(f"{self.spectator} joined the game", mark):
                break
            if self._process.poll() is not None:
                raise RuntimeError("Kamera-Client ist beendet, siehe smartcraft-test-client.log")
            time.sleep(1.0)
        else:
            raise RuntimeError("Kamera ist dem Server nicht beigetreten")
        self.server.console(f"gamemode spectator {self.spectator}", wait=0.5)
        self._socket = socket.create_connection(("127.0.0.1", SHOT_PORT), timeout=30)
        self._socket.settimeout(None)
        self._reader = threading.Thread(target=self._read_replies, daemon=True)
        self._reader.start()
        threading.Thread(target=self._work, daemon=True).start()

    def follow(self):
        """Hängt die Kamera an den Bot (nach Tod und Neustart nötig)."""
        self.server.console(f"spectate {self.bot} {self.spectator}", wait=0.4)

    def new_run(self, number):
        self.run_name = f"lauf{number + 1}"
        self._count = 0
        self._last = 0.0

    # --- Anlässe

    def on_event(self, event):
        """Vom Brückenthread aufgerufen: entscheidet nur, ob ein Bild fällig ist."""
        label = self._label(event)
        if label:
            self.shoot(label, urgent=label in ("tod", "leben_niedrig"))

    @staticmethod
    def _label(event):
        name, data = event["name"], event.get("data", {})
        if name == "player_died":
            return "tod"
        if name == "health_low":
            return "leben_niedrig"
        if name == "damage_taken":
            attacker = (data.get("attacker") or "").split(":")[-1]
            if attacker == "creeper" or data.get("amount", 0) >= 4:
                return f"schaden_{attacker or data.get('source', '').split(':')[-1]}"
        elif name == "entity_nearby":
            for mob in data.get("mobs", []):
                if mob["type"].endswith("creeper") and mob["dist"] <= 5:
                    return "creeper_nah"
        elif name == "danger" and data.get("dangers"):
            return f"gefahr_{data['dangers'][0]['kind']}"
        elif name in ("reflex_ate", "reflex_surfaced"):
            return name.replace("_", "")
        elif name == "defend_result" and event.get("data", {}).get("killed"):
            return "sieg"
        elif name == "flee_result":
            return "flucht"
        elif name == "shelter_result" and event.get("status") == "ok":
            return "huette"
        return None

    def shoot(self, label, urgent=False):
        """Meldet ein Bild an; ohne Wirkung bei Pause, Abstand oder Obergrenze."""
        now = time.time()
        if self.paused or self._socket is None or self._count >= self.MAX_PER_RUN:
            return
        if not urgent and now - self._last < self.MIN_GAP:
            return
        self._last = now
        self._count += 1
        self._queue.put(f"{self.run_name}_{self._count:03d}_{label}")

    # --- intern

    def _work(self):
        while not self._closing:
            try:
                name = self._queue.get(timeout=0.5)
            except queue.Empty:
                continue
            try:
                self.follow()
                time.sleep(0.4)  # Kamera anhängen und ein Bild rendern lassen
                self._socket.sendall((name + "\n").encode("utf-8"))
                reply = self._replies.get(timeout=10)
                if reply.startswith("ok"):
                    self.shots.append(name)
                else:
                    print(f"  Kamera: {reply}", flush=True)
            except (queue.Empty, OSError) as error:
                print(f"  Kamera: Bild {name} nicht entstanden ({error!r})", flush=True)

    def _read_replies(self):
        try:
            for line in self._socket.makefile("r", encoding="utf-8", newline="\n"):
                self._replies.put(line.strip())
        except OSError:
            pass

    def close(self):
        self._closing = True
        try:
            self.server.console(f"kick {self.spectator}", wait=0.2)
        except Exception:
            pass
        if self._process is not None:
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(self._process.pid)],
                           capture_output=True) if os.name == "nt" else self._process.kill()


class Report:
    """Sammelt Prüfungen und gibt am Ende eine Übersicht aus."""

    def __init__(self):
        self.rows = []
        self.peak_search_ms = 0.0

    def note(self, result):
        """Merkt sich die längste Wegsuche pro Tick über alle Ergebnisse."""
        self.peak_search_ms = max(self.peak_search_ms, result["data"].get("search_ms_peak", 0.0))
        return result

    def check(self, name, passed, detail=""):
        self.rows.append((name, passed, detail))
        print(f"{'OK  ' if passed else 'FAIL'} {name}  {detail}", flush=True)

    def finish(self):
        failed = [row for row in self.rows if not row[1]]
        print(f"\n{len(self.rows) - len(failed)} von {len(self.rows)} Prüfungen bestanden")
        return 1 if failed else 0


def summary(result):
    """Kurzform eines Ergebnisses für die Ausgabe."""
    data = {key: value for key, value in result["data"].items() if key not in ("trigger", "target")}
    reason = f" ({result['reason']})" if "reason" in result else ""
    return f"{result['status']}{reason} {json.dumps(data, separators=(',', ':'))}"

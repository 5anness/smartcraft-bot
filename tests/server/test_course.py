"""Etappe 4d, Abschluss: der ganze Weg von Hand, ohne KI, in einer normalen Welt mit festem Seed.

Aufruf:  python tests/server/test_course.py [durchläufe] [--kamera]

Mit --kamera öffnet sich ein Spielfenster: Ein Zuschauer („Kamera“) sieht dem Bot von hinten zu und
speichert bei auffälligen Events (Schaden, Creeper nah, Flucht, Sieg, Tod, ...) und nach jeder Etappe
ein Bild nach run/screenshots/. Das Fenster nicht schließen und den Server nicht von Hand bedienen.

Holz → Werkzeuge → Stein → Ofen → Fleisch jagen und garen → Eisen finden und schmelzen →
Eisenschwert, Eisenrüstung, Schild → eine Nacht im Freien überleben und dabei mindestens einen
Zombie, ein Skelett und einen Creeper besiegen.

Das Skript spielt so, wie man es in der Konsole täte: ein Befehl, Ergebnis lesen, nächster Befehl.
Wo es der Welt nachhilft, steht das im Bericht (helped):
- Die Tageszeit steht während der Vorbereitung still; allein 27 Barren brauchen im Ofen 4,5 Minuten.
  Die Nacht läuft in Echtzeit.
- Beim Warten auf den Ofen läuft der Server im Schnelllauf (/tick sprint).
- Findet der Bot keine Tiere oder nicht genug Eisen, stellt das Skript welche hin.
- Kommt einer der drei Gegner nachts nicht von selbst, stellt das Skript ihn 14 Blöcke entfernt hin.
"""

import collections
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import Camera, Report, TestServer, summary  # noqa: E402
from test_forest import locate_forest  # noqa: E402

PROJECT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BOT = "Claude"
WORLD = "world_course"
SEED = 20261013
IRON_NEEDED = 27
MEAT = {"minecraft:beef", "minecraft:porkchop", "minecraft:mutton", "minecraft:chicken", "minecraft:rabbit"}
ANIMALS = ("cow", "pig", "sheep", "chicken")
NIGHT_TARGETS = ("zombie", "skeleton", "creeper")
NIGHT_START = 12600
NIGHT_END = 23500


class StageFailed(Exception):
    pass


class Run:
    def __init__(self, server, number):
        self.server = server
        self.number = number
        self.helped = []
        self.deaths = []
        self.stage = "start"
        self.mark = len(server.all_events)
        self.table = None

    # --- Handgriffe

    def pause_camera(self, paused):
        if self.server.camera is not None:
            self.server.camera.paused = paused

    def snap(self, label):
        if self.server.camera is not None:
            self.server.camera.shoot(label, urgent=True)

    def say(self, text):
        print(f"  [{self.number + 1}] {text}", flush=True)

    def cmd(self, name, args=None, timeout=300, quiet=False, retries=4):
        result, _ = self.server.run(name, args or {}, timeout=timeout)
        if not quiet:
            self.say(f"{name} {args or ''} -> {summary(result)[:230]}")
        self.check_alive()
        if result["status"] == "cancelled" and result.get("reason") in ("damage", "danger") and retries > 0:
            # Wie ein Spieler: erst die Störung beseitigen, dann denselben Befehl noch einmal.
            trigger = result["data"].get("trigger", {}).get("data", {})
            self.say(f"unterbrochen durch {trigger}")
            if trigger.get("attacker"):
                self.cmd("defend", {"radius": 12}, timeout=120, retries=0)
            self.eat_if_hungry()
            return self.cmd(name, args, timeout, quiet, retries - 1)
        return result

    def need(self, name, args=None, timeout=300):
        """Ein Befehl, der klappen muss; sonst ist die Etappe gescheitert."""
        result = self.cmd(name, args, timeout)
        if result["status"] != "ok":
            raise StageFailed(f"{name} {args}: {summary(result)[:300]}")
        return result

    def check_alive(self):
        died = [event for event in self.server.all_events[self.mark:] if event["name"] == "player_died"]
        if died:
            self.mark = len(self.server.all_events)
            for event in died:
                cause = event["data"].get("attacker") or event["data"].get("cause")
                self.deaths.append(f"{cause} ({self.stage})")
            raise StageFailed(f"gestorben: {died[0]['data'].get('message')}")

    def items(self):
        data = self.server.run("inventory")[0]["data"]
        counts = collections.Counter()
        for entry in data["items"]:
            counts[entry["id"]] += entry["count"]
        return counts

    def have(self, item):
        return self.items()[f"minecraft:{item}"]

    def look(self):
        return self.server.look()

    def go(self, pos, tolerance=3):
        """Zurück zu einem Ort, notfalls mit Graben und Bauen (aus dem Stollen heraus)."""
        here = self.look()["pos"]
        if sum((a - b) ** 2 for a, b in zip(here, pos)) <= (tolerance + 1) ** 2:
            return
        result = self.cmd("goto", {"pos": pos, "tolerance": tolerance, "allow_dig": True, "allow_place": True}, timeout=400)
        if result["status"] != "ok":
            # Ein zweiter Anlauf von dort, wo er jetzt steht, hilft oft.
            self.need("goto", {"pos": pos, "tolerance": tolerance, "allow_dig": True, "allow_place": True}, timeout=400)

    def craft(self, item, count=1):
        self.go(self.table)
        return self.need("craft", {"item": item, "count": count})

    def eat_if_hungry(self, below=15):
        state = self.look()
        if state["food"] < below:
            self.cmd("eat")

    # --- Etappen

    def wood(self):
        self.stage = "Holz und Werkzeug"
        result = self.cmd("collect", {"block": "#logs", "count": 16, "radius": 40}, timeout=900)
        logs = {item: count for item, count in self.items().items() if item.endswith("_log")}
        if sum(logs.values()) < 10:
            raise StageFailed(f"nur {sum(logs.values())} Stämme: {summary(result)[:300]}")
        for log, count in logs.items():
            self.need("craft", {"item": log.replace("_log", "_planks"), "count": count * 4})
        self.need("craft", {"item": "crafting_table"})
        self.table = self.need("place_utility", {"block": "crafting_table"})["data"]["block_pos"]
        self.craft("stick", 16)
        self.craft("wooden_pickaxe")
        self.craft("wooden_sword")

    def stone(self):
        self.stage = "Stein und Ofen"
        dig = {"block": "stone", "radius": 32, "allow_dig": True, "allow_place": True}
        self.cmd("collect", {**dig, "count": 3}, timeout=600)
        if self.have("cobblestone") < 3:
            raise StageFailed("keine 3 Bruchsteine für die Steinspitzhacke")
        self.craft("stone_pickaxe")
        self.cmd("collect", {**dig, "count": 30}, timeout=900)
        if self.have("cobblestone") < 16:
            raise StageFailed(f"nur {self.have('cobblestone')} Bruchstein")
        self.craft("stone_pickaxe", 2)
        self.craft("stone_sword")
        self.craft("furnace")
        self.need("place_utility", {"block": "furnace"})

    def meat(self):
        self.stage = "Fleisch"
        self.go(self.table)
        kills = 0
        for attempt in range(8):
            if kills >= 3:
                break
            result = None
            for animal in ANIMALS:
                result = self.cmd("attack", {"target": animal}, timeout=120, quiet=True)
                if result.get("reason") != "entity_not_found":
                    break
            if result.get("reason") == "entity_not_found":
                if "Tiere hingestellt" in self.helped:
                    break
                self.helped.append("Tiere hingestellt")
                x, y, z = self.look()["pos"]
                for offset in (4, 5, 6):
                    self.server.console(f"execute at {BOT} run summon cow ~{offset} ~1 ~2")
                time.sleep(1.0)
                continue
            self.say(f"attack -> {summary(result)[:200]}")
            if result["status"] == "ok":
                kills += 1
                self.cmd("pickup_items", {"radius": 10}, quiet=True)
        raw = {item: count for item, count in self.items().items() if item in MEAT}
        if not raw:
            raise StageFailed("kein Fleisch erbeutet")
        for item, count in raw.items():
            self.smelt_all("cook", item.split(":")[1], count)
        self.say(f"Essen: { {i: c for i, c in self.items().items() if i.startswith('minecraft:cooked_')} }")
        self.eat_if_hungry(18)

    def smelt_all(self, command, item, count):
        """Legt ein, wartet im Schnelllauf auf den Ofen und holt ab, bis alles durch ist."""
        self.go(self.table)
        for _ in range(6):
            left = self.have(item)
            if left == 0:
                return
            mark = len(self.server.all_events)
            result = self.need(command, {"item": item, "count": min(left, 64)})
            self.pause_camera(True)
            self.server.console(f"tick sprint {result['data'].get('ready_in_ticks', 200) + 100}", wait=0)
            deadline = time.time() + 120
            while time.time() < deadline and not any(
                    event["name"] == "smelting_done" for event in self.server.all_events[mark:]):
                time.sleep(0.5)
            self.server.console("tick sprint stop", wait=0.5)
            self.pause_camera(False)
            self.need("smelt_collect")
        raise StageFailed(f"{item} nach 6 Ofengängen nicht durch")

    def iron(self):
        self.stage = "Eisen"
        self.eat_if_hungry()
        ores = {"block": "#iron_ores", "radius": 48, "allow_dig": True, "allow_place": True}
        for _ in range(2):
            missing = IRON_NEEDED - self.have("raw_iron")
            if missing <= 0:
                break
            result = self.cmd("collect", {**ores, "count": missing}, timeout=900)
            if self.have("stone_pickaxe") == 0:
                self.craft("stone_pickaxe")
            if result["data"].get("mined", 0) == 0:
                break
        found = self.have("raw_iron")
        self.say(f"selbst gefundenes Roheisen: {found}")
        self.natural_iron = found
        if found < IRON_NEEDED:
            missing = IRON_NEEDED - found
            self.helped.append(f"{missing} von {IRON_NEEDED} Eisenerz hingestellt")
            self.go(self.table)
            x, y, z = self.table
            self.server.console(f"fill {x + 2} {y + 1} {z + 3} {x + 1 + missing} {y + 1} {z + 3} iron_ore", wait=0.5)
            self.cmd("collect", {"block": "iron_ore", "count": missing, "radius": 40, "allow_dig": True, "allow_place": True}, timeout=600)
            if self.have("raw_iron") < IRON_NEEDED:
                raise StageFailed(f"nur {self.have('raw_iron')} Roheisen trotz hingestelltem Erz")
        self.smelt_all("smelt", "raw_iron", IRON_NEEDED)

    def gear(self):
        self.stage = "Ausrüstung"
        for item in ("iron_sword", "iron_chestplate", "iron_leggings", "iron_helmet", "iron_boots", "shield"):
            self.craft(item)
        result = self.need("equip_best_armor")
        if result["data"]["armor"] != 15 or "offhand" not in result["data"]["worn"]:
            raise StageFailed(f"Rüstung unvollständig: {summary(result)[:300]}")
        self.eat_if_hungry(18)

    def night(self):
        self.stage = "Nacht"
        self.go(self.table)
        server = self.server
        server.console(f"time set {NIGHT_START}")
        for rule in ("advance_time", "doDaylightCycle"):
            server.console(f"gamerule {rule} true", wait=0.1)
        killed = collections.Counter()
        started = time.time()
        summoned = 0
        while True:
            state = self.look()
            if state["daytime"] == "day" and not NIGHT_START - 700 <= state["time"] < NIGHT_END:
                break
            if state["health"] <= 8:
                # Zu wenig Leben zum Kämpfen: weg, essen, notfalls einschließen und warten.
                self.cmd("flee", {"distance": 20}, timeout=60)
                self.cmd("eat")
                if self.look()["health"] <= 8:
                    self.cmd("shelter", timeout=60)
                    for _ in range(40):
                        time.sleep(2.0)
                        self.check_alive()
                        if self.look()["health"] >= 16:
                            break
                    self.eat_if_hungry(18)
                    here = self.look()["pos"]
                    self.cmd("goto", {"pos": [here[0] + 4, here[1] + 3, here[2]], "tolerance": 3,
                                      "allow_dig": True, "allow_place": True}, timeout=120)
                continue
            self.eat_if_hungry(14)
            result = self.cmd("defend", {"radius": 16, "min_health": 8}, timeout=240, quiet=True)
            for kind in result["data"].get("killed", []):
                killed[kind.split(":")[1]] += 1
            if result["data"].get("killed") or result["status"] != "ok":
                self.say(f"defend -> {summary(result)[:260]}")
            if result["data"].get("outcome") == "no_enemies":
                # Ruhe: Wer nach einer Minute noch fehlt, wird hingestellt, einer nach dem anderen.
                missing = [kind for kind in NIGHT_TARGETS if killed[kind] == 0]
                if missing and time.time() - started > 60 + summoned * 40:
                    summoned += 1
                    self.helped.append(f"{missing[0]} hingestellt")
                    server.console(f"execute at {BOT} run summon {missing[0]} ~14 ~1 ~ {{PersistenceRequired:1b}}")
                time.sleep(2.0)
        self.say(f"Morgen. Besiegt: {dict(killed)}")
        missing = [kind for kind in NIGHT_TARGETS if killed[kind] == 0]
        if missing:
            raise StageFailed(f"nicht besiegt: {missing}")
        self.killed = dict(killed)


def play(server, number):
    """Ein Durchlauf. Gibt (Erfolg, Bericht) zurück."""
    run = Run(server, number)
    if server.camera is not None:
        server.camera.new_run(number)
    x, z = locate_forest(server, number * 400, number * 400)
    server.send("stop")
    server.console(f"clear {BOT}")
    server.console("time set 1000")
    server.console("weather clear")
    for rule in ("advance_time", "doDaylightCycle"):
        server.console(f"gamerule {rule} false", wait=0.1)
    server.console(f"effect give {BOT} minecraft:instant_health 1 10")
    server.console(f"effect give {BOT} minecraft:saturation 1 20")
    server.console(f"spreadplayers {x} {z} 0 6 false {BOT}", wait=4)
    server.console("kill @e[type=item]")
    server.console("kill @e[type=!player,type=!item,distance=..1]")
    server.drain(0.5)
    run.mark = len(server.all_events)
    run.say(f"Start bei {server.look()['pos']}")

    started = time.time()
    problem = None
    done = []
    try:
        for stage in (run.wood, run.stone, run.meat, run.iron, run.gear, run.night):
            stage()
            done.append(run.stage)
            run.snap("etappe_" + run.stage.lower().replace(" ", "_").replace("ü", "ue"))
    except StageFailed as error:
        problem = f"{run.stage}: {error}"
    except TimeoutError as error:
        problem = f"{run.stage}: {error}"
        server.send("stop")
    minutes = round((time.time() - started) / 60)
    detail = (f"{minutes} min, geschafft: {', '.join(done) or 'nichts'}; "
              f"eigenes Eisen: {getattr(run, 'natural_iron', '-')}; helped: {run.helped or 'nichts'}; "
              f"Tode: {run.deaths or 'keine'}; " + (problem or f"besiegt {run.killed}"))
    return problem is None, detail, run


def main():
    args = [arg for arg in sys.argv[1:] if not arg.startswith("--")]
    runs = int(args[0]) if args else 3
    with_camera = "--kamera" in sys.argv
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-course.log")
    server = TestServer(log, world=WORLD, seed=SEED, camera=with_camera)
    report = Report()
    deaths = collections.Counter()
    failed_stages = collections.Counter()
    try:
        if with_camera:
            server.camera = Camera(server, BOT)
            server.camera.start()
        server.console("gamerule spawn_mobs true")
        server.console("difficulty normal")
        server.console("smartcraft events off")
        server.console(f"smartcraft join {BOT}", wait=5)
        server.drain(0.5)
        for number in range(runs):
            passed, detail, run = play(server, number)
            report.check(f"Parcours {number + 1}: vom leeren Inventar durch die Nacht", passed, detail)
            for death in run.deaths:
                deaths[death] += 1
            if not passed:
                failed_stages[run.stage] += 1
    finally:
        server.close()
    passed = sum(1 for row in report.rows if row[1])
    print(f"\nErfolgsquote: {passed} von {runs}")
    print(f"Gescheitert in: {dict(failed_stages) or 'nirgends'}")
    print(f"Todesursachen: {dict(deaths) or 'keine'}")
    print(f"Serverlog: {log}")
    if server.camera is not None:
        print(f"Bilder ({len(server.camera.shots)}): {os.path.join(PROJECT_DIR, 'run', 'screenshots')}")
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

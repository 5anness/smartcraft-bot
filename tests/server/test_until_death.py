"""Dauerlauf in einer frischen, normalen Welt, bis der Bot stirbt (oder die Zeit um ist).

Aufruf:  python tests/server/test_until_death.py [minuten] [seed]

Anders als der Parcours hilft dieses Skript der Welt nicht nach: keine hingestellten Tiere, Erze
oder Gegner, die Tageszeit läuft von Anfang an. Scheitert eine Etappe, spielt der Bot mit dem
weiter, was er hat. Danach lebt er vor sich hin: tags erkunden, Holz und Erz sammeln, jagen,
nachts kämpfen. Kein Schnelllauf: Am Ofen wartet er in Echtzeit und wehrt sich dabei.

Am Ende stehen die letzten Events vor dem Tod in einer JSON-Datei, zum Nachlesen.
"""

import json
import os
import random
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from harness import TestServer, summary  # noqa: E402
from test_course import BOT, MEAT, Run, StageFailed  # noqa: E402
from test_forest import locate_forest  # noqa: E402


class Life(Run):
    def cooked(self):
        return sum(count for item, count in self.items().items() if item.startswith("minecraft:cooked_"))

    def is_night(self, state):
        return state["daytime"] != "day"

    def low_health(self):
        """Zu wenig Leben zum Kämpfen: weg, essen, notfalls einschließen und warten."""
        self.cmd("flee", {"distance": 20}, timeout=60)
        self.cmd("eat")
        if self.look()["health"] > 8:
            return
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

    def hunt(self):
        for animal in ("cow", "pig", "sheep", "chicken"):
            result = self.cmd("attack", {"target": animal}, timeout=120, quiet=True)
            if result["status"] == "ok":
                self.say(f"attack {animal} -> {summary(result)[:160]}")
                self.cmd("pickup_items", {"radius": 10}, quiet=True)
                return True
        return False

    def cook_raw(self):
        raw = {item: count for item, count in self.items().items() if item in MEAT}
        if raw and self.table:
            try:
                for item, count in raw.items():
                    self.smelt_all("cook", item.split(":")[1], count)
            except StageFailed as error:
                self.say(f"Garen gescheitert: {error}")

    def smelt_all(self, command, item, count):
        """Wie im Parcours, aber ohne Schnelllauf: Der Bot wartet in Echtzeit am Ofen und wehrt sich.

        Im ersten Dauerlauf starb er hier: Der Schnelllauf ließ die Nacht anbrechen, ein Zombie kam,
        und sieben Treffer vergingen in unter einer Sekunde echter Zeit, ohne dass das Skript reagieren konnte.
        """
        self.go(self.table)
        for _ in range(6):
            left = self.have(item)
            if left == 0:
                return
            mark = len(self.server.all_events)
            result = self.need(command, {"item": item, "count": min(left, 64)})
            deadline = time.time() + result["data"].get("ready_in_ticks", 200) / 20 + 60
            while time.time() < deadline and not any(
                    event["name"] == "smelting_done" for event in self.server.all_events[mark:]):
                state = self.look()
                if state["health"] <= 8:
                    self.low_health()
                    self.go(self.table)
                    continue
                guard = self.cmd("defend", {"radius": 12, "min_health": 8}, timeout=120, quiet=True)
                if guard["data"].get("outcome") == "no_enemies":
                    time.sleep(1.0)
                else:
                    self.say(f"am Ofen verteidigt -> {summary(guard)[:200]}")
            self.go(self.table)
            self.need("smelt_collect")
        raise StageFailed(f"{item} nach 6 Ofengängen nicht durch")

    def day(self, step):
        """Ein Handgriff am Tag, reihum."""
        choice = step % 4
        if choice == 0:
            here = self.look()["pos"]
            target = [here[0] + random.randint(-70, 70), here[1], here[2] + random.randint(-70, 70)]
            self.cmd("goto", {"pos": target, "tolerance": 6}, timeout=240)
        elif choice == 1:
            self.cmd("collect", {"block": "#logs", "count": 6, "radius": 32}, timeout=400)
        elif choice == 2:
            if self.cooked() < 4:
                if self.hunt():
                    self.cook_raw()
        else:
            self.cmd("collect", {"block": "#iron_ores", "count": 4, "radius": 32,
                                 "allow_dig": True, "allow_place": True}, timeout=400)

    def live(self, deadline):
        self.stage = "Leben"
        step = 0
        while time.time() < deadline:
            state = self.look()
            if state["health"] <= 8:
                self.low_health()
                continue
            self.eat_if_hungry(14)
            if self.is_night(state):
                result = self.cmd("defend", {"radius": 16, "min_health": 8}, timeout=240, quiet=True)
                if result["data"].get("killed") or result["status"] != "ok":
                    self.say(f"defend -> {summary(result)[:260]}")
                if result["data"].get("outcome") == "no_enemies":
                    time.sleep(2.0)
            else:
                self.day(step)
                step += 1


def main():
    args = sys.argv[1:]
    minutes = int(args[0]) if args else 100
    seed = int(args[1]) if len(args) > 1 else random.randint(1, 2**31)
    world = f"world_life_{seed}"
    temp = os.environ.get("TEMP", ".")
    log = os.path.join(temp, "smartcraft-test-life.log")
    print(f"Welt {world}, Seed {seed}, höchstens {minutes} Minuten", flush=True)
    server = TestServer(log, world=world, seed=seed)
    life = Life(server, 0)
    started = time.time()
    outcome = "Zeit um, Bot lebt noch"
    try:
        server.console("gamerule spawn_mobs true")
        server.console("difficulty normal")
        server.console("smartcraft events off")
        server.console(f"smartcraft join {BOT}", wait=5)
        x, z = locate_forest(server, 0, 0)
        server.console(f"spreadplayers {x} {z} 0 6 false {BOT}", wait=4)
        server.console("time set 1000")
        server.drain(0.5)
        life.mark = len(server.all_events)
        life.say(f"Start bei {server.look()['pos']}")
        try:
            for stage in (life.wood, life.stone, life.meat_plain, life.iron_plain, life.gear_plain):
                try:
                    stage()
                    life.say(f"Etappe geschafft: {life.stage}")
                except StageFailed as error:
                    if str(error).startswith("gestorben"):
                        raise
                    life.say(f"Etappe gescheitert, weiter ohne: {life.stage}: {error}")
                except TimeoutError as error:
                    life.say(f"Etappe hängt, weiter ohne: {life.stage}: {error}")
                    server.send("stop")
            life.live(started + minutes * 60)
        except StageFailed as error:
            outcome = f"{error} (in: {life.stage}; {life.deaths})"
        except TimeoutError as error:
            outcome = f"Befehl ohne Antwort: {error}"
    finally:
        dump = os.path.join(temp, "smartcraft-test-life-events.json")
        with open(dump, "w", encoding="utf-8") as file:
            json.dump(server.all_events[-400:], file, ensure_ascii=False, indent=1)
        server.close()
    print(f"\nEnde nach {round((time.time() - started) / 60)} min: {outcome}")
    print(f"Letzte Events: {dump}")
    print(f"Serverlog: {log}")


# Etappen ohne Nachhilfe (der Parcours stellt Tiere und Erz hin; hier nicht).

def meat_plain(self):
    self.stage = "Fleisch"
    for _ in range(3):
        if not self.hunt():
            break
    self.cook_raw()
    self.eat_if_hungry(18)


def iron_plain(self):
    self.stage = "Eisen"
    self.eat_if_hungry()
    self.cmd("collect", {"block": "#iron_ores", "radius": 48, "allow_dig": True, "allow_place": True,
                         "count": 27}, timeout=900)
    found = self.have("raw_iron")
    self.say(f"selbst gefundenes Roheisen: {found}")
    if found and self.table:
        self.smelt_all("smelt", "raw_iron", found)


def gear_plain(self):
    self.stage = "Ausrüstung"
    if not self.table:
        raise StageFailed("keine Werkbank")
    # Was das Eisen hergibt, das Wichtigste zuerst.
    for item, cost in (("iron_sword", 2), ("shield", 1), ("iron_chestplate", 8), ("iron_leggings", 7),
                       ("iron_helmet", 5), ("iron_boots", 4)):
        if self.have("iron_ingot") >= cost:
            self.go(self.table)
            self.cmd("craft", {"item": item})
    self.cmd("equip_best_armor")


Life.meat_plain = meat_plain
Life.iron_plain = iron_plain
Life.gear_plain = gear_plain

if __name__ == "__main__":
    main()

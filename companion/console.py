"""Konsole für den AI-Player-Mod: zeigt Events an und schickt Befehle.

Start:   python companion/console.py
Befehl:  <name> [argumente]
         Kurzform:   goto 10 64 -3        goto 10 64 -3 0 dig place
                     goto_entity cow      goto_entity cow 3
                     follow Hannes        follow Hannes 3 60
                     mine 10 64 -3        place cobblestone 10 64 -3
                     collect #logs 8      collect stone 4 20   collect iron_ore 3 32 dig place
                     pickup_items         pickup_items 12
                     equip stone_pickaxe  equip iron_helmet kopf
                     drop dirt            drop dirt 5
                     craft stick 4        recipe iron_pickaxe   locate fortress (nur nach /smartcraft locate on)
                     smelt raw_iron 3     smelt raw_iron 3 coal     smelt_collect
                     place_utility werkbank    place_utility ofen 10 64 -3
                     store cobblestone 32      store all      take coal 8
                     eat   eat bread      cook beef 3
                     attack zombie        attack 123 (id aus entity_nearby)     attack zombie 10 (min. Leben)
                     defend   defend 16   flee   flee creeper 20     flee 24
                     shield an            shield aus           equip_best_armor
                     sleep   shelter      light   light 10 64 -3      descend -40 (bis Höhe y)
                     inventory   stop   look   ping
         Allgemein:  <name> {"argument": wert}   z. B.  goto {"pos": [10, 64, -3], "tolerance": 0}
Beenden: quit oder Strg+C

Die Konsole verbindet sich mit dem Mod (127.0.0.1, Port aus SMARTCRAFT_PORT, sonst 25599)
und versucht es alle zwei Sekunden erneut, solange das Spiel nicht läuft.
"""

import itertools
import json
import os
import socket
import sys
import threading
import time

HOST = "127.0.0.1"
PORT = int(os.environ.get("SMARTCRAFT_PORT", "25599"))
PROTOCOL_VERSION = 1
RETRY_SECONDS = 2


class Bridge:
    """Hält die Verbindung zum Mod und verbindet sich bei Bedarf neu."""

    def __init__(self):
        self._socket = None
        self._lock = threading.Lock()
        self._ids = itertools.count(1)

    def run_forever(self):
        """Liest Nachrichten vom Mod, bis das Programm endet. Läuft in einem eigenen Thread."""
        announced_waiting = False
        while True:
            try:
                connection = socket.create_connection((HOST, PORT), timeout=3)
            except OSError:
                if not announced_waiting:
                    print(f"Warte auf den Mod ({HOST}:{PORT}) … Läuft das Spiel mit einer geöffneten Welt?")
                    announced_waiting = True
                time.sleep(RETRY_SECONDS)
                continue

            announced_waiting = False
            connection.settimeout(None)
            with self._lock:
                self._socket = connection
            print(f"Verbunden mit {HOST}:{PORT}.")
            try:
                for line in connection.makefile("r", encoding="utf-8", newline="\n"):
                    show(line.rstrip("\n"))
            except OSError:
                pass
            with self._lock:
                self._socket = None
            print("Verbindung zum Mod getrennt.")

    def send_command(self, name, args):
        """Schickt einen Befehl und gibt seine id zurück, oder None ohne Verbindung."""
        command_id = f"c-{next(self._ids)}"
        message = {"v": PROTOCOL_VERSION, "type": "command", "id": command_id, "name": name, "args": args}
        return command_id if self.send_raw(json.dumps(message, ensure_ascii=False)) else None

    def send_raw(self, line):
        with self._lock:
            if self._socket is None:
                return False
            try:
                self._socket.sendall((line + "\n").encode("utf-8"))
                return True
            except OSError:
                return False


def show(line):
    """Gibt eine Nachricht vom Mod als eine lesbare Zeile aus."""
    try:
        message = json.loads(line)
    except json.JSONDecodeError:
        print(f"?? {line}")
        return

    name = message.get("name", message.get("type", "?"))
    parts = [f"t{message.get('tick', '?')}", name]
    if "cmd_id" in message:
        parts.append(f"[{message['cmd_id']}]")
    if "status" in message:
        parts.append(message["status"].upper())
    if "reason" in message:
        parts.append(f"reason={message['reason']}")
    data = message.get("data")
    if data and "api_key" in data:
        data = {**data, "api_key": "***"}
    if data:
        parts.append(json.dumps(data, ensure_ascii=False, separators=(",", ":")))
    marker = "!!" if message.get("prio") == "high" else "<-"
    print(f"{marker} {' '.join(parts)}")


def _goto(words):
    # Die Wörter dig und place dürfen irgendwo hinter den Koordinaten stehen.
    flags = {word for word in words if word in ("dig", "place")}
    words = [word for word in words if word not in flags]
    if len(words) not in (3, 4):
        raise ValueError("goto braucht x y z, optional die Toleranz und optional dig und/oder place")
    args = {"pos": [int(word) for word in words[:3]]}
    if len(words) == 4:
        args["tolerance"] = float(words[3])
    if "dig" in flags:
        args["allow_dig"] = True
    if "place" in flags:
        args["allow_place"] = True
    return args


def _mine(words):
    if len(words) != 3:
        raise ValueError("mine braucht x y z")
    return {"pos": [int(word) for word in words]}


def _place(words):
    if len(words) != 4:
        raise ValueError("place braucht den Block und x y z")
    return {"block": words[0], "pos": [int(word) for word in words[1:]]}


def _collect(words):
    flags = {word for word in words if word in ("dig", "place")}
    words = [word for word in words if word not in flags]
    if not 1 <= len(words) <= 3:
        raise ValueError("collect braucht den Block, optional Anzahl und Umkreis, optional dig und/oder place")
    args = {"block": words[0]}
    if "dig" in flags:
        args["allow_dig"] = True
    if "place" in flags:
        args["allow_place"] = True
    if len(words) >= 2:
        args["count"] = int(words[1])
    if len(words) == 3:
        args["radius"] = int(words[2])
    return args


def _pickup(words):
    if len(words) > 1:
        raise ValueError("pickup_items nimmt höchstens den Umkreis")
    return {"radius": float(words[0])} if words else {}


# Deutsche Namen der Plätze, damit man in der Konsole nicht umdenken muss.
EQUIP_SLOTS = {"hand": "hand", "offhand": "offhand", "zweithand": "offhand", "kopf": "head", "head": "head",
               "brust": "chest", "chest": "chest", "beine": "legs", "legs": "legs",
               "füße": "feet", "fuesse": "feet", "feet": "feet"}


def _equip(words):
    if not 1 <= len(words) <= 2:
        raise ValueError("equip braucht das Item und optional den Platz")
    args = {"item": words[0]}
    if len(words) == 2:
        if words[1].lower() not in EQUIP_SLOTS:
            raise ValueError("Platz muss hand, offhand, kopf, brust, beine oder füße sein")
        args["slot"] = EQUIP_SLOTS[words[1].lower()]
    return args


def _drop(words):
    if not 1 <= len(words) <= 2:
        raise ValueError("drop braucht das Item und optional die Anzahl")
    args = {"item": words[0]}
    if len(words) == 2:
        args["count"] = int(words[1])
    return args


def _pursue(words, with_seconds):
    if not 1 <= len(words) <= (3 if with_seconds else 2):
        raise ValueError("erwartet: Ziel, optional Abstand" + (", optional Sekunden" if with_seconds else ""))
    args = {"target": words[0]}
    if len(words) >= 2:
        args["distance"] = float(words[1])
    if len(words) == 3:
        args["seconds"] = int(words[2])
    return args


def _item_count(words, name):
    if not 1 <= len(words) <= 2:
        raise ValueError(f"{name} braucht das Item und optional die Anzahl")
    args = {"item": words[0]}
    if len(words) == 2:
        args["count"] = int(words[1])
    return args


def _recipe(words):
    if len(words) != 1:
        raise ValueError("recipe braucht genau ein Item")
    return {"item": words[0]}


def _smelt(words):
    if not 1 <= len(words) <= 3:
        raise ValueError("smelt braucht das Item, optional Anzahl und Brennstoff")
    args = {"item": words[0]}
    if len(words) >= 2:
        args["count"] = int(words[1])
    if len(words) == 3:
        args["fuel"] = words[2]
    return args


UTILITIES = {"werkbank": "crafting_table", "crafting_table": "crafting_table", "ofen": "furnace", "furnace": "furnace",
             "truhe": "chest", "chest": "chest"}


def _place_utility(words):
    if len(words) not in (1, 4) or words[0].lower() not in UTILITIES:
        raise ValueError("place_utility braucht werkbank, ofen oder truhe und optional x y z")
    args = {"block": UTILITIES[words[0].lower()]}
    if len(words) == 4:
        args["near"] = [int(word) for word in words[1:]]
    return args


def _eat(words):
    if len(words) > 1:
        raise ValueError("eat nimmt höchstens ein Item")
    return {"item": words[0]} if words else {}


def _target(word):
    """Eine Zahl ist eine Entity-ID, alles andere ein Typ."""
    return int(word) if word.lstrip("-").isdigit() else word


def _attack(words):
    if not 1 <= len(words) <= 2:
        raise ValueError("attack braucht das Ziel (Typ oder id) und optional das Mindestleben")
    args = {"target": _target(words[0])}
    if len(words) == 2:
        args["min_health"] = float(words[1])
    return args


def _defend(words):
    if len(words) > 2:
        raise ValueError("defend nimmt optional den Umkreis und das Mindestleben")
    args = {}
    if len(words) >= 1:
        args["radius"] = float(words[0])
    if len(words) == 2:
        args["min_health"] = float(words[1])
    return args


def _flee(words):
    # flee 20 = Abstand; flee creeper = wovor; flee creeper 20 = beides.
    if len(words) > 2:
        raise ValueError("flee nimmt optional das Wesen und den Abstand")
    args = {}
    if len(words) == 2:
        args = {"from": _target(words[0]), "distance": float(words[1])}
    elif len(words) == 1:
        if words[0].replace(".", "", 1).isdigit():
            args["distance"] = float(words[0])
        else:
            args["from"] = words[0]
    return args


ON_OFF = {"an": True, "on": True, "ein": True, "aus": False, "off": False}


def _shield(words):
    if len(words) != 1 or words[0].lower() not in ON_OFF:
        raise ValueError("shield braucht an oder aus")
    return {"on": ON_OFF[words[0].lower()]}


def _light(words):
    if len(words) != 3:
        raise ValueError("light nimmt keine Argumente oder x y z")
    return {"pos": [int(word) for word in words]}


# Kurzformen: Befehl -> Funktion, die die Wörter nach dem Namen in Argumente übersetzt.
SHORTHAND = {
    "goto": _goto,
    "goto_entity": lambda words: _pursue(words, with_seconds=False),
    "follow": lambda words: _pursue(words, with_seconds=True),
    "mine": _mine,
    "place": _place,
    "collect": _collect,
    "pickup_items": _pickup,
    "equip": _equip,
    "drop": _drop,
    "craft": lambda words: _item_count(words, "craft"),
    "recipe": _recipe,
    "locate": lambda words: {"structure": words[0] if words else "fortress"},
    "smelt": _smelt,
    "place_utility": _place_utility,
    "store": lambda words: _item_count(words, "store"),
    "take": lambda words: _item_count(words, "take"),
    "eat": _eat,
    "cook": lambda words: _item_count(words, "cook"),
    "attack": _attack,
    "defend": _defend,
    "flee": _flee,
    "shield": _shield,
    "light": _light,
    "descend": lambda words: {"to_y": int(words[0])},
}


def parse_input(text):
    """Zerlegt '<name> [argumente]' in Name und Argumente (JSON-Objekt oder Kurzform)."""
    name, _, rest = text.partition(" ")
    rest = rest.strip()
    if not rest:
        return name, {}
    if rest.startswith("{"):
        args = json.loads(rest)
        if not isinstance(args, dict):
            raise ValueError("Die Argumente müssen ein JSON-Objekt sein")
        return name, args
    if name in SHORTHAND:
        return name, SHORTHAND[name](rest.split())
    raise ValueError(f"Für '{name}' gibt es keine Kurzform; bitte ein JSON-Objekt angeben")


def main():
    # Die Windows-Konsole nimmt sonst eine Codepage, an der Sonderzeichen in Events scheitern können.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    bridge = Bridge()
    threading.Thread(target=bridge.run_forever, daemon=True).start()
    print(__doc__.split("\n\n")[1])

    while True:
        try:
            text = input().strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not text:
            continue
        if text in ("quit", "exit"):
            break
        try:
            name, args = parse_input(text)
        except ValueError as error:
            print(f"Eingabe nicht verstanden: {error}")
            continue
        command_id = bridge.send_command(name, args)
        if command_id is None:
            print("Nicht verbunden, Befehl verworfen.")
        else:
            print(f"-> {command_id} {name} {json.dumps(args, ensure_ascii=False) if args else ''}".rstrip())


if __name__ == "__main__":
    sys.exit(main())

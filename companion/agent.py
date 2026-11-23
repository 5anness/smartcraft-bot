"""KI-Anbindung: Claude spielt über die Befehle des Mods (Etappe 5).

Aufruf:  python companion/agent.py ["Ziel in Worten"]

Der Prozess verbindet sich mit dem Mod (127.0.0.1, Port aus AIPLAYER_PORT, sonst 25599) und wartet
auf ein Ziel: entweder als Argument beim Start oder im Spiel mit `/aiplayer goal <text>`.
Für jedes Ziel läuft eine Schleife: Das Modell wählt ein Tool, der Mod führt es aus, das Ergebnis
geht zurück an das Modell, bis das Modell ohne Tool antwortet (Ziel beendet).

- Jeder Befehl aus `schemas/commands/` ist ein Tool; dazu `remember` (Notizblock) und `wait`.
- Der Systemprompt steht in `companion/prompts/system.md`.
- Die Kosten werden aus den gemeldeten Tokens berechnet und angezeigt. Ein Kostenlimit pro Ziel
  gibt es nur, wenn AIPLAYER_COST_LIMIT gesetzt ist.
- Schreibt jemand im Spielchat, liest die KI mit: Im Leerlauf beginnt damit ein Gespräch, während
  eines Ziels kommt die Nachricht mit dem nächsten Ergebnis beim Modell an.
- Was der Bot im Chat schreibt, liest der Prozess auch laut vor (companion/speech.py).
- `/aiplayer tts on | off` schaltet die Sprachausgabe um.
- `/aiplayer pause | resume | stop` im Spiel halten die Schleife an oder beenden das Ziel.
- Nähert sich während einer längeren Aktion ein Gegner oder wird das Leben knapp, hält der
  Prozess die Aktion an und gibt dem Modell die Entscheidung.

Der API-Schlüssel kommt aus der Umgebung (ANTHROPIC_API_KEY, sonst companion/api_key.txt, oder ein `ant auth login`-Profil) und
wird nie ausgegeben oder protokolliert. Einstellungen: siehe companion/README.md.
"""

import datetime
import glob
import json
import itertools
import os
import queue
import socket
import sys
import threading
import time
from collections import deque

import anthropic

from speech import Speaker

# Schlüssel aus einer Datei, wenn die Umgebungsvariable fehlt. Die Datei steht in .gitignore; ihr Inhalt
# wird nie ausgegeben oder protokolliert.
KEY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "api_key.txt")


def load_key_file():
    if os.environ.get("ANTHROPIC_API_KEY") or not os.path.exists(KEY_FILE):
        return
    with open(KEY_FILE, encoding="utf-8") as file:
        key = next((line.strip() for line in file if line.strip() and not line.startswith("#")), "")
    if key:
        os.environ["ANTHROPIC_API_KEY"] = key


load_key_file()


def save_key(key):
    """Neuer Schlüssel aus /aiplayer apikey: in api_key.txt speichern (steht in .gitignore) und sofort benutzen."""
    with open(KEY_FILE, "w", encoding="utf-8") as file:
        file.write("# Anthropic-API-Schlüssel, gesetzt mit /aiplayer apikey. Nie committen.
" + key.strip() + "
")
    os.environ["ANTHROPIC_API_KEY"] = key.strip()

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
HOST = "127.0.0.1"
PORT = int(os.environ.get("AIPLAYER_PORT", "25599"))

MODEL = os.environ.get("AIPLAYER_MODEL", "claude-haiku-5-5")
EFFORT = os.environ.get("AIPLAYER_EFFORT", "medium")
# Kostenlimit pro Ziel in Dollar; 0 heißt kein Limit.
COST_LIMIT = float(os.environ.get("AIPLAYER_COST_LIMIT", "0"))
# 0 = unbegrenzt (Standard, Wunsch von Hannes: bis zum Ziel arbeiten); eine Zahl setzt eine Obergrenze pro Ziel.
MAX_STEPS = int(os.environ.get("AIPLAYER_MAX_STEPS", "0"))
USE_FALLBACKS = os.environ.get("AIPLAYER_FALLBACKS", "1") != "0"
MAX_TOKENS = 8000

# Dollar pro Million Tokens: (Eingabe, Ausgabe). Cache-Lesen 10 %, Cache-Schreiben 125 % der Eingabe.
# Unbekannte Modelle rechnen mit dem teuersten Satz, damit das Limit eher zu früh als zu spät greift.
PRICES = {
    "claude-sonnet-5-5": (2.0, 10.0),
    "claude-sonnet-5": (2.0, 10.0),
    "claude-opus-5-5": (4.0, 20.0),
    "claude-opus-5": (5.0, 25.0),
    "claude-haiku-5-5": (0.1, 0.5),
    "claude-haiku-4-5": (1.0, 5.0),
    "claude-fable-5-1": (10.0, 50.0),
}
DEFAULT_PRICE = (10.0, 50.0)
# Cache-Lesen als Anteil des Eingabepreises (Stand der API-Referenz 10/2026): sonst 0,1.
CACHE_READ_FACTOR = {"claude-opus-5-5": 0.05, "claude-fable-5-1": 0.025}
# Haiku 5.5: Prompts über 100.000 Tokens kosten das Fünffache ($0,50 / $2,50).
LONG_PROMPT = {"claude-haiku-5-5": (100_000, (0.5, 2.5))}

# Notizen pro Welt: memory/<welt>_<seed>/notes.json. Die Kennung kommt aus `look` (world, seed).
NOTES_DIR = os.path.join(HERE, "memory")
MAX_NOTES = 30
LOG_DIR = os.path.join(HERE, "logs")

# Events, die das Modell zusammen mit einem Ergebnis sehen soll. Alles andere (z. B. jeder
# abgebaute Block) steckt schon im Ergebnis oder wäre nur Rauschen.
NOTEWORTHY = {"chat_received", "items_received", "damage_taken", "danger", "entity_nearby", "health_low", "hunger_low", "night_falling",
              "player_died", "player_respawned", "reflex_ate", "reflex_surfaced", "reflex_left_lava", "reflex_left_dragon_breath", "dimension_followed", "reflex_struck_back", "reflex_lava_stuck", "reflex_dug_out", "reflex_sealed_water", "fluid_spotted", "smelting_done",
              "tool_low_durability", "no_pickaxe"}
MAX_EVENTS_PER_RESULT = 10
# So viele Chatzeilen (fremde und eigene) stehen am Anfang eines Ziels oder Gesprächs.
CHAT_HISTORY = 12
MAX_CHAT = 256
# Felder in Ergebnissen, die nur fürs Debug-Log zählen; das Modell bekommt sie nicht (Tokens sparen).
DEBUG_KEYS = {"ticks", "search_nodes", "search_ms_peak", "replans", "distance_left"}
# Ältere Tool-Ergebnisse werden auf Status und Grund gekürzt. Gekürzt wird nur alle TRIM_EVERY Schritte,
# damit der Prompt-Cache dazwischen hält; die letzten KEEP_RECENT Schritte bleiben vollständig.
# Jedes Kürzen schreibt den Cache des Verlaufs neu (Lauf 18.10.: alle 10 Schritte 0,0065 $ statt 0,0007 $), daher selten.
KEEP_RECENT = 8
TRIM_EVERY = 30
# Höchstens so viele Schritte bleiben im Verlauf; ältere fallen ganz weg, die Eröffnung bekommt dann Ziel und
# aktuelle Notizen neu. Gesamtläufe: nach 400 Schritten trug jeder Aufruf alle 400 Befehle mit, pro Schritt 40 % teurer.
MAX_KEPT_STEPS = 60
# Wird MAX_KEPT_STEPS überschritten, bleiben nur so viele: ein großer Schnitt statt alle 10 Schritte ein kleiner (Cache).
CUT_TO_STEPS = 30

# Bei diesen Befehlen kümmert sich der Bot gerade um die Gefahr oder sie dauern nur einen Augenblick:
# Sie werden nicht angehalten.
# use/shoot/bucket/build_portal/enter_portal sind kurz und oft selbst die Antwort auf Gefahr. Nether-Lauf 24: Jeder
# Fluchtwurf mit der Enderperle (use) wurde wegen der Witherskelette daneben abgebrochen, der Bot starb.
# barter braucht Piglins in der Nähe; Perlen-Lauf 7: jeder Tausch brach nach dem ersten Barren an entity_nearby ab.
NEVER_INTERRUPT = {"attack", "defend", "farm_spawner", "flee", "shelter", "eat", "shield", "stop", "look", "inventory",
                   "recipe", "locate", "places", "equip", "equip_best_armor", "chat", "drop", "use", "shoot", "bucket",
                   "build_portal", "enter_portal", "mine", "place", "barter", "farm_endermen", "make_obsidian", "destroy_crystals", "fight_dragon", "bed_bomb", "bed_cycle"}
# Nicht die Drachenbefehle: Hannes' Lauf (xyz, 03.11.) brach bed_cycle fünfmal durch Chatzeilen („aha“, Beleidigungen) ab, der
# Drache heilte zwischen den Versuchen von 127 auf 200 zurück.
CHAT_INTERRUPTIBLE = {"farm_spawner", "farm_endermen", "make_obsidian", "barter"}
COMMAND_TIMEOUT = 1900  # bed_bomb wartet bis 30 min auf eine Landung des Drachen
# So lange unterbricht ein schon gemeldeter Gegner keine Aktion mehr (z. B. Zombies vor der Hütte).
ACK_SECONDS = 45
# Nach so vielen Befehlen in Folge, die ein naher Gegner angehalten hat, läuft der nächste durch (nur health_low hält
# ihn noch an). Nether-Lauf 33: Am Spawner kamen ständig neue Lohen, jeder Befehl aus der Hütte heraus brach sofort ab.
MAX_THREAT_STOPS = 2
# So oft schickt der Prozess das Modell nach einem Tod zurück ans Ziel, statt es aufhören zu lassen.
MAX_REVIVES = 3
# So viele Orte erfolgloser Suche bekommt das Modell mit.
MAX_SEARCHED = 12
# Herzschlag: Alle so viele Schritte erinnert der Prozess das Modell an das Ziel.
HEARTBEAT_STEPS = int(os.environ.get("AIPLAYER_HEARTBEAT_STEPS", "15"))
# So viele Schritte nur warten oder schauen in Folge gelten als Leerlauf; dann kommt ein Anstoß.
IDLE_STEPS = 4
IDLE_TOOLS = {"wait", "look", "inventory"}
# Zählen weder als Leerlauf noch als Arbeit: Wer chattet und wartet, wartet trotzdem.
NEUTRAL_TOOLS = {"chat", "remember"}
# So oft fragt der Herzschlag pro Ziel nach, wenn das Modell aufhören will.
MAX_END_CHECKS = 6
# Nach so vielen Schritten seit einem Tod gilt der Tod als verarbeitet.
STEPS_AFTER_DEATH = 3


REVIVE_TEXT = ("[Herzschlag] Du bist gestorben, das Ziel gilt weiter. Liegt dein Inventar sicher erreichbar, hol es "
               "(5 Minuten Zeit) und zieh die Rüstung wieder an; sonst fang neu an mit Holz, Werkzeug und Essen.")


HEARTBEAT_TEXT = ("[Herzschlag, Schritt {step}] Ziel: {goal}. Was ist der nächste Teilschritt? Kurz prüfen: Leben, "
                  "Hunger, Werkzeug, Tageszeit. Dann weiter.")
IDLE_TEXT = ("[Herzschlag] {steps} Schritte nur gewartet oder geschaut. Ziel: {goal}. Warten heilt nur ab Hunger 18; "
             "sonst erst essen. Tu den nächsten echten Schritt.")
# Plaudern: Nach so vielen Schritten ohne `chat` erinnert der Prozess ans Reden (0 schaltet ab).
# Chat des Bots (Tokens sparen): "plans" (Standard) nur kurze, witzige Ansagen der Pläne, "full" gesprächig mit Humor
# und [Publikum]-Anstößen, "off" gar kein Chat.
# Talkmode (/aiplayer talkmode): wie oft der Bot von sich aus chattet. Zentral einstellbar:
# nudge_steps = stille Modellschritte bis zum [Publikum]-Anstoß (0 = nie), min_gap = Sekunden zwischen zwei eigenen
# Chatnachrichten (Spam-Schutz; Antworten auf eine Nachricht an den Bot gehen immer), highlights = Funde kommentieren.
TALK_SETTINGS = {
    "light": {"nudge_steps": 0, "min_gap": 60, "highlights": False},
    "medium": {"nudge_steps": 6, "min_gap": 25, "highlights": True},
    "hard": {"nudge_steps": 2, "min_gap": 8, "highlights": True},
}
# Humor (/aiplayer humor): Stil der Chat- und Sprachausgabe, unabhängig vom Talkmode.
HUMOR_NOTES = {
    "light": "dezenter Humor: sachlich mit gelegentlich einer lockeren Bemerkung.",
    "medium": "regelmäßig Witze und Wortspiele, trocken und selbstironisch wie ein Let's-Player.",
    "hard": "häufig frech und pointiert, gern mit Seitenhieben auf Creeper, Lava und das eigene Pech.",
    "crazy": "maximal übertrieben, chaotisch und verrückt: dramatische Ausrufe, absurde Vergleiche, spontane Kommentare.",
}
TALK_NOTES = {
    "light": "selten: nur wenn ein neuer Abschnitt beginnt (ein kurzer Satz, was du vorhast) und als Antwort auf Nachrichten.",
    "medium": "regelmäßig: Pläne, Funde und Gefahren kurz kommentieren, etwa alle paar Schritte.",
    "hard": "häufig und aktiv: fast jeden Abschnitt kommentieren, Zuschauer unterhalten.",
}
# So lange nach einer Nachricht an den Bot gelten seine Chats als Antwort (kein Spam-Schutz).
REPLY_WINDOW = 60
SETTINGS_TEXT = ("[Einstellung] Humor {humor}: {humor_note} Talkmode {talk}: {talk_note} Das gilt ab jetzt für jedes `chat`, "
                 "bis ein neuer Hinweis kommt.")
CHAT_MODE = {"1": "full", "0": "off"}.get(os.environ.get("AIPLAYER_CHAT", "plans"), os.environ.get("AIPLAYER_CHAT", "plans"))
# Startwert des Talkmode aus AIPLAYER_CHAT (plans = light, full = hard); danach gilt /aiplayer talkmode.
START_TALKMODE = {"full": "hard", "plans": "light"}.get(CHAT_MODE, "light")
CHAT_NOTES = {
    "plans": ("\n\n## Chat: Humor und Talkmode\n\nWie oft du von dir aus im Chat schreibst (Talkmode) und wie witzig "
              "(Humor), steht in `[Einstellung]`-Hinweisen; der neueste gilt. Ohne Hinweis: Talkmode light, Humor medium. "
              "Kurze Sätze (ein bis zwei), nie Spam. Auf Nachrichten des Menschen antwortest du immer kurz. Meldet `chat` "
              "`too_soon`, warte mit dem nächsten Kommentar.\n"),
    "off": ("\n\n## Chat ist ausgeschaltet\n\nDu hast im Moment kein `chat`-Tool. Spiel still und konzentriert; "
            "alles über Chat, Humor und `[Publikum]` oben gilt gerade nicht.\n"),
}
TALK_TEXT = ("[Publikum] Seit {steps} Schritten kein Wort. Gib mit dem nächsten Befehl ein `chat` mit: ein witziger "
             "Satz zu dem, was gerade passiert, mit Pointe.")
# Funde, die einen begeisterten Kommentar verdienen (Teil des Item- oder Blocknamens).
HIGHLIGHTS = {"diamond": "Diamanten", "emerald": "Smaragde", "ancient_debris": "Antiken Schutt",
              "netherite": "Netherit", "ender_pearl": "Enderperlen", "blaze_rod": "Lohenruten",
              "iron_ingot": "Eisenbarren", "gold_ingot": "Gold", "obsidian": "Obsidian", "lava": "Lava",
              "_chestplate": "eine Brustplatte", "golden_apple": "einen goldenen Apfel"}
GIFT_TEXT = ("[Publikum] Jemand hat dir etwas zugeworfen (`items_received`). Bedank dich im Chat mit Witz, sag, was "
             "du damit machst, und nutz es, wenn es passt.")
HIGHLIGHT_TEXT = ("[Publikum] Neu: {what}. Ein großer Moment: sofort mit `chat` kommentieren, gern völlig überdreht.")
END_CHECK_TEXT = ("[Herzschlag] Ist „{goal}“ wirklich erreicht? Wenn nicht: weiter mit dem nächsten Schritt. Niemand "
                  "antwortet dir auf Fragen, du entscheidest selbst. Ist ein Weg versperrt, nimm einen anderen (anderer "
                  "Ort, `descend`, `tunnel` in eine andere Richtung oder schräg nach oben, erkunden). Fehlen Bausteine: "
                  "`collect` Netherrack oder Stein aus Wänden, Decke und Boden (`allow_dig`). Steckst du fest (Insel, "
                  "Abgrund): Enderperle ans Ufer werfen, durch die Decke nach oben graben, oder mit `build_portal` in die "
                  "andere Welt und zurück, das bringt dich an einen anderen Ort. Nur wenn es erreicht ist, antworte noch "
                  "einmal ohne Tool.")


class GoalEnded(Exception):
    """Das Ziel endet von außen: gestoppt, ersetzt oder Verbindung weg."""


class Bridge:
    """Verbindung zum Mod: Befehle hinaus, Events herein (eine JSON-Nachricht pro Zeile)."""

    def __init__(self):
        self.events = queue.Queue()
        self._ids = 0
        self._socket = None
        self.connected = threading.Event()

    def connect(self):
        announced = False
        while True:
            try:
                self._socket = socket.create_connection((HOST, PORT), timeout=3)
                break
            except OSError:
                if not announced:
                    print(f"Warte auf den Mod ({HOST}:{PORT}) … Läuft das Spiel mit einer geöffneten Welt?", flush=True)
                    announced = True
                time.sleep(2.0)
        self._socket.settimeout(None)
        self.connected.set()
        threading.Thread(target=self._read, daemon=True).start()
        print(f"Verbunden mit dem Mod ({HOST}:{PORT}).", flush=True)

    def _read(self):
        try:
            for line in self._socket.makefile("r", encoding="utf-8", newline="\n"):
                try:
                    self.events.put(json.loads(line))
                except json.JSONDecodeError:
                    pass
        except OSError:
            pass
        self.connected.clear()
        self.events.put({"name": "_disconnected", "data": {}})

    def send(self, name, args):
        self._ids += 1
        command_id = f"ai-{self._ids}"
        message = {"v": 1, "type": "command", "id": command_id, "name": name, "args": args or {}}
        self._socket.sendall((json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8"))
        return command_id


# Liest alles vor, was der Bot im Chat schreibt (companion/speech.py).
SPEAKER = Speaker()


COMMON_REASONS = {"no_bot", "bot_died", "invalid_args", "timeout"}


def load_tools():
    """Baut die Tool-Liste aus den Befehls-Schemas. Sortiert, damit der Prompt-Cache stabil bleibt."""
    tools = []
    for path in sorted(glob.glob(os.path.join(PROJECT, "schemas", "commands", "*.schema.json"))):
        with open(path, encoding="utf-8") as file:
            schema = json.load(file)
        name = os.path.basename(path).split(".")[0]
        # usage meldet nur der Begleitprozess selbst (XP-Leiste), nie das Modell.
        # bed_bomb ersetzt durch bed_cycle (31.10.2026): End-Lauf 99 nahm bed_bomb, wartete am Brunnen und starb im Atem.
        if name in ("ping", "usage", "settings", "bed_bomb") or name == "chat" and CHAT_MODE == "off":
            continue
        description = schema["title"]
        if description.startswith(name + ":"):
            description = description[len(name) + 1:].strip()
        # Die Gründe, die jeder Befehl haben kann, stehen nicht bei jedem Tool (Tokens sparen).
        reasons = [reason for reason in schema.get("reasons", {}).get("failed", []) if reason not in COMMON_REASONS]
        if reasons:
            description += " Fehlergründe: " + ", ".join(reasons) + "."
        tools.append({"name": name, "description": description,
                      "input_schema": schema.get("args") or {"type": "object", "properties": {}}})
    tools.append({
        "name": "remember",
        "description": "Schreibt eine Notiz in deinen Notizblock. Die Notizen bleiben über Ziele und Neustarts "
                       "hinweg erhalten und stehen am Anfang jedes neuen Ziels. Für Dauerhaftes: wo die Basis "
                       "steht, wo es Erz gab, was nicht funktioniert hat. Höchstens 30 Notizen, die älteste fällt weg.",
        "input_schema": {"type": "object", "required": ["note"], "additionalProperties": False, "properties": {
            "note": {"type": "string", "description": "Ein kurzer Satz, mit Koordinaten wenn es um einen Ort geht"}}},
    })
    tools.append({
        "name": "search_for",
        "description": "Sagt deiner Figur, wonach du gerade suchst (Wasser, Lava, Obsidian etwa von einem zerfallenen Portal, Spawner, Lohe). Kommt es in Sicht (Prüfung alle 5 s, Umkreis 24, im Nether 32), hält "
                       "die laufende Aktion an und du bekommst `interrupted_by` mit `fluid_spotted` und dem Ort. Danach ist "
                       "die Suche nach dieser Art erledigt. Leere Liste beendet die Suche.",
        "input_schema": {"type": "object", "required": ["what"], "additionalProperties": False, "properties": {
            "what": {"type": "array", "items": {"enum": ["water", "lava", "obsidian", "spawner", "blaze", "end_portal_frame", "fortress"]}}}},
    })
    tools.append({
        "name": "wait",
        "description": "Wartet, ohne etwas zu tun, zum Beispiel auf den Ofen oder den Morgen. Endet früher, "
                       "sobald etwas Wichtiges passiert (Ofen fertig, Gegner, Schaden, Nachricht im Chat). Kostet nichts.",
        "input_schema": {"type": "object", "required": ["seconds"], "additionalProperties": False, "properties": {
            "seconds": {"type": "integer", "minimum": 1, "maximum": 120, "description": "Höchste Wartezeit in Sekunden"}}},
    })
    return tools


def world_key(snapshot):
    """Ordnername für die Notizen einer Welt; ohne Angaben vom Mod ein gemeinsamer Ordner."""
    name = "".join(c if c.isalnum() or c in "-_" else "_" for c in str(snapshot.get("world") or "unbekannt"))
    seed = snapshot.get("seed")
    return f"{name}_{seed}" if seed is not None else name


def notes_file(world):
    return os.path.join(NOTES_DIR, world, "notes.json")


def load_notes(world):
    try:
        with open(notes_file(world), encoding="utf-8") as file:
            return json.load(file)
    except (OSError, json.JSONDecodeError):
        return []


def save_note(world, note):
    notes = load_notes(world)
    notes.append(note.strip()[:300])
    notes = notes[-MAX_NOTES:]
    os.makedirs(os.path.dirname(notes_file(world)), exist_ok=True)
    with open(notes_file(world), "w", encoding="utf-8") as file:
        json.dump(notes, file, ensure_ascii=False, indent=1)
    return len(notes)


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def summarize_events(events):
    """Kürzt die Events eines Befehls: nur Wichtiges, von `entity_nearby` nur der letzte Stand."""
    kept = [event for event in events if event.get("name") in NOTEWORTHY]
    last_nearby = max((i for i, event in enumerate(kept) if event["name"] == "entity_nearby"), default=None)
    kept = [event for i, event in enumerate(kept) if event["name"] != "entity_nearby" or i == last_nearby]
    return [{"name": event["name"], **event.get("data", {})} for event in kept[-MAX_EVENTS_PER_RESULT:]]


def shorten_result(text):
    """Kurzfassung eines alten Tool-Ergebnisses: Status, Grund und Standort reichen als Erinnerung."""
    try:
        answer = json.loads(text)
    except (TypeError, ValueError):
        return text
    short = {key: answer[key] for key in ("status", "reason") if key in answer}
    if isinstance(answer.get("data"), dict) and "pos" in answer["data"]:
        short["pos"] = answer["data"]["pos"]
    short["old"] = True
    return compact(short)


def trim_history(messages):
    """Kürzt Tool-Ergebnisse und Hinweise vor den letzten KEEP_RECENT Schritten (das Gespräch wächst sonst ohne Ende)."""
    user_turns = [i for i, message in enumerate(messages) if message["role"] == "user" and isinstance(message["content"], list)]
    for index in user_turns[:-KEEP_RECENT]:
        kept = []
        for block in messages[index]["content"]:
            if block.get("type") == "tool_result" and isinstance(block.get("content"), str):
                block = {**block, "content": shorten_result(block["content"])}
            elif block.get("type") == "text":
                continue  # alte [Herzschlag]- und [Publikum]-Hinweise
            kept.append(block)
        messages[index]["content"] = kept


def is_threat(event):
    """Anlass, eine längere Aktion anzuhalten: Gegner dicht dran oder Leben knapp."""
    if event.get("name") == "health_low":
        return True
    return event.get("name") == "entity_nearby" and event.get("prio") == "high" and bool(event.get("data", {}).get("mobs"))


def is_direct_chat(event):
    """Eine Chatnachricht, die dem Bot gilt: Auch sie hält eine längere Aktion an."""
    return event.get("name") == "chat_received" and bool(event.get("data", {}).get("direct"))


class Agent:
    def __init__(self, bridge):
        self.bridge = bridge
        # Kurzes Timeout mit Wiederholung: Festungslauf 16 und Perlen-Lauf 21 hingen nach einem Ergebnis minutenlang
        # ohne Modellschritt (Standard des SDK: 10 Minuten pro Aufruf), der Wächter brach beide ab.
        self.client = anthropic.Anthropic(timeout=90.0, max_retries=4)
        self.tools = load_tools()
        with open(os.path.join(HERE, "prompts", "system.md"), encoding="utf-8") as file:
            self.system = file.read() + CHAT_NOTES.get(CHAT_MODE, "")
        self.use_fallbacks = USE_FALLBACKS
        self.use_effort = bool(EFFORT)
        self.paused = False
        self.pending_goal = None
        # Wer das wartende Ziel in den Chat geschrieben hat; None bei /aiplayer goal.
        self.pending_from = None
        self.busy = False
        self.chat_log = deque(maxlen=CHAT_HISTORY)
        self.tool_warned = {}
        self.cost = 0.0
        # Gesamtverbrauch seit dem Start des Begleitprozesses (self.cost gilt nur für das laufende Ziel).
        self.total_cost = 0.0
        self.total_tokens = 0
        self.log = None
        self.reset_goal_state()

    def reset_goal_state(self):
        # Mob-ID -> Zeitpunkt, zu dem das Modell sie per interrupted_by gesehen hat.
        self.acknowledged = {}
        self.creeper_close = set()  # Creeper-IDs, die schon auf unter 4 Blöcke gemeldet wurden
        self.threat_stops = 0
        self.deaths = 0
        self.revived = 0
        # Wie oft das Modell ein Ziel beenden wollte und gefragt wurde, ob es wirklich fertig ist.
        self.end_checks = 0
        self.idle_steps = 0
        self.silent_steps = 0
        if not hasattr(self, "humor"):
            self.humor, self.talkmode = "medium", START_TALKMODE
            self.settings_note = None
            self.last_chat_time = 0.0
            # Bis wann Chats als Antwort gelten und der Spam-Schutz nicht greift (Zeit statt Merker: Hannes' Lauf 28.10.:
            # eine Planansage im selben Schritt verbrauchte den Merker, die Antwort auf seine Frage wurde blockiert).
            self.reply_until = 0.0
        # Bis wann der zuletzt befüllte Ofen läuft; solange lehnt `wait` einmal ab.
        self.furnace_until = 0.0
        self.wait_refused = False
        self.last_mobs = []
        self.last_mobs_time = 0.0
        self.wait_refused_enemies = False
        self.wait_refused_hunger = False
        # Gegartes Essen im Ofen ({"item", "furnace"}), bis smelt_collect es holt. Lauf 18.10.: 9 Rindfleisch lagen
        # gegart im Ofen, der Bot hielt sich für essenlos und sank in der Hütte auf 2,4 Leben.
        if not hasattr(self, "food_in_furnace"):
            self.food_in_furnace = None
        # Das letzte Ziel aus /aiplayer goal; nach einem Gespräch macht der Bot damit weiter, bis /aiplayer stop.
        if not hasattr(self, "main_goal"):
            self.main_goal = None
        # Wonach das Modell mit search_for sucht (water, lava); ein Fund hält die laufende Aktion an.
        self.searching = set()
        self.highlights_seen = set()
        self.steps_since_death = 0
        # Orte, an denen attack nichts fand, damit das Modell nicht im Kreis sucht.
        self.searched = []
        self.last_pos = None
        self.world = world_key({})

    # --- Steuerung aus dem Spiel

    def handle_control(self, event):
        """Verarbeitet goal/pause/resume/stop. Gibt True zurück, wenn das laufende Ziel enden muss."""
        name, data = event.get("name"), event.get("data", {})
        if name == "_disconnected":
            raise ConnectionError("Verbindung zum Mod verloren")
        if name == "player_died":
            self.deaths += 1
            self.steps_since_death = 0
            # Neue Umgebung nach dem Respawn: alte Gegner zählen nicht mehr als bekannt.
            self.acknowledged.clear()
            self.creeper_close.clear()
        if name == "goal_requested":
            self.pending_goal = data.get("text", "").strip()
            self.pending_from = None
            self.paused = False
            return True
        if name == "smelting_done":
            self.furnace_until = 0.0
        if name == "entity_nearby":
            self.last_mobs = data.get("mobs", [])
            self.last_mobs_time = time.time()
            if not self.last_mobs:
                self.wait_refused_enemies = False
        if name == "items_received":
            gift = ", ".join(f"{item.get('count')} {str(item.get('id', '')).removeprefix('minecraft:')}"
                             for item in data.get("items", []))
            self.chat_log.append(f"{data.get('from')} wirft dir zu: {gift}")
            # Im Leerlauf beginnt ein Geschenk ein Gespräch wie eine Chatnachricht; im Ziel steht es bei den Events.
            if not self.busy:
                self.pending_goal = f"*wirft dir {gift} zu*"
                self.pending_from = data.get("from")
            return False
        if name == "chat_received":
            self.chat_log.append(f"{data.get('from')}: {data.get('text')}")
            if data.get("direct"):
                self.reply_until = time.time() + REPLY_WINDOW
            # Im Leerlauf beginnt eine Nachricht an den Bot ein Gespräch; im Ziel sieht sie das Modell beim Ergebnis.
            if not self.busy and data.get("direct"):
                self.pending_goal = data.get("text", "").strip()
                self.pending_from = data.get("from")
            return False
        if name == "control_requested":
            action = data.get("action")
            if action == "pause":
                self.paused = True
                print("  [pausiert, weiter mit /aiplayer resume]", flush=True)
            elif action == "resume":
                self.paused = False
                print("  [weiter]", flush=True)
            elif action == "settings":
                self.apply_settings(data)
            elif action == "apikey" and data.get("api_key"):
                self.set_api_key(data["api_key"])
            elif action in ("tts_on", "tts_off"):
                SPEAKER.set_enabled(action == "tts_on")
                print(f"  [Sprachausgabe {'an' if action == 'tts_on' else 'aus'}]", flush=True)
            elif action == "stop":
                self.paused = False
                self.main_goal = None
                return True
        return False

    def set_api_key(self, key):
        save_key(key)
        self.client = anthropic.Anthropic(api_key=key.strip(), timeout=90.0, max_retries=4)
        print("  [API-Schlüssel übernommen und in companion/api_key.txt gespeichert]", flush=True)

    def apply_settings(self, data):
        """Einstellungen aus dem Mod übernehmen (/aiplayer tts|humor|talkmode|streamer); sofort wirksam."""
        if "tts" in data:
            SPEAKER.set_enabled(bool(data["tts"]))
        humor = data.get("humor", self.humor)
        talk = data.get("talkmode", self.talkmode)
        if humor in HUMOR_NOTES and talk in TALK_SETTINGS and (humor, talk) != (self.humor, self.talkmode):
            self.humor, self.talkmode = humor, talk
            # Als Hinweis im Verlauf statt im Systemprompt: der Prompt-Cache bleibt erhalten.
            self.settings_note = self.settings_text()
        print(f"  [Einstellungen: TTS {'an' if SPEAKER.enabled else 'aus'}, Humor {self.humor}, Talkmode {self.talkmode}]",
              flush=True)

    def settings_text(self):
        return SETTINGS_TEXT.format(humor=self.humor.upper(), humor_note=HUMOR_NOTES[self.humor],
                                    talk=self.talkmode.upper(), talk_note=TALK_NOTES[self.talkmode])

    def sync_settings(self):
        """Beim Verbinden: Startwerte aus der Umgebung an den Mod, dann seine Einstellungen übernehmen."""
        args = {}
        if "AIPLAYER_TTS" in os.environ:
            args["tts"] = os.environ["AIPLAYER_TTS"] == "1"
        if "AIPLAYER_CHAT" in os.environ:
            args["talkmode"] = START_TALKMODE
        try:
            result, _ = self.command("settings", args, [], timeout=10, may_interrupt=False)
        except (TimeoutError, OSError):
            return
        if result.get("status") == "ok":
            if result.get("data", {}).get("api_key"):
                self.set_api_key(result["data"]["api_key"])
            self.apply_settings(result.get("data", {}))
            self.settings_note = None  # die Eröffnung jedes Ziels nennt sie ohnehin

    def drain(self, seen):
        """Liest alle wartenden Events. Löst GoalEnded aus, wenn das Ziel enden muss."""
        ended = False
        while True:
            try:
                event = self.bridge.events.get_nowait()
            except queue.Empty:
                break
            ended = self.handle_control(event) or ended
            seen.append(event)
        if ended:
            raise GoalEnded("gestoppt" if self.pending_goal is None else "neues Ziel")

    def wait_while_paused(self, seen):
        while self.paused:
            self.drain(seen)
            time.sleep(0.3)

    # --- Befehle an den Mod

    def command(self, name, args, seen, timeout=COMMAND_TIMEOUT, may_interrupt=True):
        """Schickt einen Befehl und wartet auf sein Ergebnis. Events bis dahin landen in seen."""
        command_id = self.bridge.send(name, args)
        # Lebenszeichen für lange Befehle: Der Wächter der Testläufe sieht sonst minutenlang nichts.
        print(f"  [{time.strftime('%H:%M:%S')} {name} läuft]", flush=True)
        deadline = time.time() + timeout
        interrupted_by = None
        ended = False
        last_damage = ""
        while time.time() < deadline:
            try:
                event = self.bridge.events.get(timeout=0.5)
            except queue.Empty:
                continue
            if event.get("cmd_id") == command_id:
                if event["name"].endswith("_result"):
                    if ended:
                        raise GoalEnded("gestoppt" if self.pending_goal is None else "neues Ziel")
                    return event, interrupted_by
                continue  # <name>_started
            ended = self.handle_control(event) or ended
            seen.append(event)
            # Ertrinken, Brennen, Lava: Die laufende Aktion ist meist die Rettung (goto an die Luft). Drachenlauf 56: health_low
            # hielt das goto zur Oberfläche nach 0,5 s an, der Bot ertrank.
            if event.get("name") == "damage_taken":
                last_damage = event.get("data", {}).get("source", "")
            environmental = last_damage in ("minecraft:drown", "minecraft:lava", "minecraft:on_fire", "minecraft:in_fire")
            threat = self.is_new_threat(event) and (self.threat_stops < MAX_THREAT_STOPS
                                                     or event.get("name") == "health_low")
            if threat and event.get("name") == "health_low" and environmental and name in ("goto", "seal_water", "flee"):
                threat = False
            # Lange Befehle ohne Gefahrenabbruch hält eine direkte Chatnachricht trotzdem an, sonst antwortet der Bot erst
            # nach Minuten (Hannes' Lauf 03.11.: „Was machst du?“ blieb während fight_dragon unbeantwortet).
            chat_stop = is_direct_chat(event) and name in CHAT_INTERRUPTIBLE
            if (may_interrupt and interrupted_by is None and (name not in NEVER_INTERRUPT or chat_stop)
                    and (threat or is_direct_chat(event) or self.is_searched(event)
                         or self.is_new_tool_warning(event) or event.get("name") == "no_pickaxe")):
                interrupted_by = {"name": event["name"], **event.get("data", {})}
                self.acknowledge(event)
                self.bridge.send("stop", {})
        raise TimeoutError(f"Kein Ergebnis für {name} nach {timeout} s")

    def is_new_tool_warning(self, event):
        """tool_low_durability hält nur einmal je Werkzeug an. Hannes' Lauf 17.10.: Dieselbe Steinspitzhacke (50 übrig)
        hielt sechsmal ein collect an, weil der Mod die Warnung bei jedem Werkzeugwechsel neu schickt."""
        if event.get("name") != "tool_low_durability":
            return False
        data = event.get("data", {})
        item, left = data.get("item"), data.get("left", 0)
        last = self.tool_warned.get(item)
        self.tool_warned[item] = left
        # Neu ist die Warnung beim ersten Mal und bei einem frischen Werkzeug derselben Art (mehr übrig als zuletzt).
        return last is None or left > last

    def is_new_threat(self, event):
        """Wie is_threat, aber ein schon gemeldeter Gegner hält ACK_SECONDS lang nichts mehr an."""
        if not is_threat(event):
            return False
        if event.get("name") != "entity_nearby":
            return True
        now = time.time()
        mobs = event.get("data", {}).get("mobs", [])
        # Ein schon gemeldeter Creeper hält noch einmal an, wenn er auf 4 Blöcke heran ist (Drachenlauf 15: tot durch
        # einen Creeper, der während der 45 s Ruhe herankam).
        if any(mob.get("type") == "minecraft:creeper" and mob.get("dist", 99) < 4 and mob.get("id") not in self.creeper_close
               for mob in mobs):
            return True
        return any(now - self.acknowledged.get(mob.get("id"), 0) > ACK_SECONDS for mob in mobs)

    def is_searched(self, event):
        """Ein gesuchtes Wasser oder Lava ist in Sicht: Einmal anhalten, dann ist die Suche nach dieser Art erledigt."""
        kind = event.get("data", {}).get("kind")
        if event.get("name") != "fluid_spotted" or kind not in self.searching:
            return False
        self.searching.discard(kind)
        return True

    def acknowledge(self, event):
        if event.get("name") == "entity_nearby":
            now = time.time()
            for mob in event.get("data", {}).get("mobs", []):
                self.acknowledged[mob.get("id")] = now
                if mob.get("type") == "minecraft:creeper" and mob.get("dist", 99) < 4:
                    self.creeper_close.add(mob.get("id"))

    def heartbeat(self, step, goal, tools):
        """Erinnert das Modell regelmäßig an das Ziel und stößt es an, wenn es nur wartet."""
        working = [name for name in tools if name not in NEUTRAL_TOOLS]
        if all(name in IDLE_TOOLS for name in working):
            self.idle_steps += 1
        else:
            self.idle_steps = 0
        # Hat das Modell nach einem Tod schon weitergemacht, braucht es später keinen Hinweis darauf mehr.
        self.steps_since_death += 1
        if self.deaths > self.revived and self.steps_since_death >= STEPS_AFTER_DEATH:
            self.revived = self.deaths
        if self.idle_steps >= IDLE_STEPS:
            self.idle_steps = 0
            self.write_log("heartbeat", {"step": step, "reason": "idle"})
            return IDLE_TEXT.format(goal=goal, steps=IDLE_STEPS)
        if HEARTBEAT_STEPS > 0 and step % HEARTBEAT_STEPS == 0:
            self.write_log("heartbeat", {"step": step, "reason": "regular"})
            return HEARTBEAT_TEXT.format(goal=goal, step=step)
        return None

    def talk_nudge(self, tools, texts):
        """Stößt das Modell zum Reden an: bei einem neuen besonderen Fund oder nach längerem Schweigen."""
        joined = " ".join(texts).lower()
        new = [label for key, label in HIGHLIGHTS.items() if key in joined and key not in self.highlights_seen]
        self.highlights_seen.update(key for key in HIGHLIGHTS if key in joined)
        if '"items_received"' in joined:
            self.silent_steps = 0
            self.write_log("talk", {"reason": "gift"})
            return GIFT_TEXT
        if "chat" in tools:
            self.silent_steps = 0
            return None
        self.silent_steps += 1
        if new and TALK_SETTINGS[self.talkmode]["highlights"]:
            self.silent_steps = 0
            self.write_log("talk", {"reason": "highlight", "what": new})
            return HIGHLIGHT_TEXT.format(what=" und ".join(new))
        steps = TALK_SETTINGS[self.talkmode]["nudge_steps"]
        if steps > 0 and self.silent_steps >= steps:
            self.silent_steps = 0
            self.write_log("talk", {"reason": "silent"})
            return TALK_TEXT.format(steps=steps)
        return None

    @staticmethod
    def fit_chat(args):
        """Zu lange Chatnachrichten kürzen statt scheitern lassen: am letzten Satzende vor 256 Zeichen."""
        text = str(args.get("text", ""))
        if len(text) <= MAX_CHAT:
            return args
        cut = text[:MAX_CHAT]
        end = max(cut.rfind(". "), cut.rfind("! "), cut.rfind("? "))
        return {**args, "text": cut[:end + 1] if end > MAX_CHAT // 2 else cut[:MAX_CHAT - 1] + "…"}

    def run_tool(self, name, args, pending_events):
        """Führt ein Tool aus und gibt (Text für das Modell, Fehler?) zurück."""
        seen = list(pending_events)
        pending_events.clear()
        if name == "chat":
            args = self.fit_chat(args)
        gap = TALK_SETTINGS[self.talkmode]["min_gap"]
        since = time.time() - self.last_chat_time
        if name == "chat" and time.time() > self.reply_until and since < gap:
            # Talkmode-Spam-Schutz: eigene Kommentare nur mit Abstand; Antworten auf Nachrichten an den Bot gehen immer.
            answer = {"status": "failed", "reason": "too_soon", "wait_seconds": round(gap - since),
                      "hint": "Zu kurz nach deiner letzten Nachricht; nichts gesendet. Spiel weiter, kommentier später."}
        elif name == "remember":
            count = save_note(self.world, str(args.get("note", "")))
            answer = {"status": "ok", "notes": count}
        elif name == "search_for":
            self.searching = set(args.get("what", []))
            answer = {"status": "ok", "searching": sorted(self.searching)}
        elif name == "wait" and self.enemies_near() and not self.wait_refused_enemies:
            # Mit Gegnern in Sicht heilt Warten nicht, sie schießen weiter (bei den Lohen stand der Bot 70 s still).
            self.wait_refused_enemies = True
            answer = {"status": "failed", "reason": "enemies_near",
                      "hint": "Gegner in der Nähe: Warten heilt hier nicht. Geh in Deckung (shelter, goto hinter eine Wand "
                              "oder um eine Ecke), iss (eat) und schieß zurück (shoot) oder flieh (flee). Danach weiter."}
        elif name == "wait" and self.furnace_running() and not self.wait_refused:
            # Der Ofen läuft allein: Warten verschenkt die Zeit. Einmal ablehnen; besteht das Modell darauf, darf es.
            self.wait_refused = True
            answer = {"status": "failed", "reason": "furnace_running",
                      "hint": "Der Ofen arbeitet allein weiter, smelting_done meldet das Ende. Nutz die Zeit: Holz, "
                              "Kohle oder Erz sammeln, Essen besorgen, Werkzeug craften, erkunden. Danach smelt_collect."}
        elif name == "wait" and not self.wait_refused_hunger and (vitals := self.vitals(seen))                 and vitals[0] < 20 and vitals[1] < 18:
            # Unter Hunger 18 heilt Warten nicht (Lauf 18.10.: mit Hunger 16 in der Hütte auf Heilung gewartet).
            self.wait_refused_hunger = True
            answer = {"status": "failed", "reason": "no_regen_hunger", "health": vitals[0], "food": vitals[1],
                      "hint": "Leben heilt nur bei Hunger 18 oder mehr, Warten bringt hier nichts. Erst essen (eat), "
                              + (self.furnace_food_hint() if self.food_in_furnace else
                                 "ohne Essen Tiere jagen (attack cow/pig/sheep/chicken) und garen (cook).")}
        elif name == "wait":
            answer = self.wait_tool(int(args.get("seconds", 10)), seen)
        else:
            try:
                result, interrupted_by = self.command(name, args, seen)
            except TimeoutError as error:
                self.bridge.send("stop", {})
                answer = {"status": "failed", "reason": "timeout", "hint": str(error)}
            else:
                answer = {"status": result.get("status")}
                if "reason" in result:
                    answer["reason"] = result["reason"]
                if interrupted_by:
                    answer["interrupted_by"] = interrupted_by
                if interrupted_by and interrupted_by.get("name") == "entity_nearby":
                    self.threat_stops += 1
                elif name not in NEVER_INTERRUPT:
                    self.threat_stops = 0
                answer["data"] = {key: value for key, value in result.get("data", {}).items() if key not in DEBUG_KEYS}
                if name == "chat":
                    # Den eigenen Text kennt das Modell schon.
                    answer["data"].pop("text", None)
                if "pos" in answer["data"]:
                    self.last_pos = answer["data"]["pos"]
                if name == "attack" and result.get("reason") == "entity_not_found":
                    if self.last_pos and self.last_pos not in self.searched:
                        self.searched.append(self.last_pos)
                    answer["already_searched"] = self.searched[-MAX_SEARCHED:]
                if name in ("smelt", "cook") and result.get("status") == "ok":
                    ready = answer["data"].get("ready_in_ticks", 0)
                    self.furnace_until = time.time() + ready / 20
                    self.wait_refused = False
                    makes = str(answer["data"].get("makes", ""))
                    if name == "cook" or "cooked_" in makes:
                        self.food_in_furnace = {"item": makes, "furnace": answer["data"].get("furnace")}
                if name == "smelt_collect" and result.get("status") == "ok":
                    self.food_in_furnace = None
                if name == "eat" and result.get("status") == "ok":
                    self.wait_refused_hunger = False
                if name == "chat" and result.get("status") == "ok":
                    self.last_chat_time = time.time()
                    said = result.get("data", {}).get("text", args.get("text"))
                    self.chat_log.append(f"Du: {said}")
                    SPEAKER.say(said)
        events = summarize_events(seen)
        if events:
            answer["events"] = events
        if self.food_in_furnace and any(event["name"] in ("hunger_low", "health_low") for event in events)                 or (name == "eat" and answer.get("reason") == "no_food" and self.food_in_furnace)                 or (name == "look" and self.food_in_furnace and answer.get("data", {}).get("food", 20) < 18):
            answer["food_in_furnace"] = self.food_in_furnace
        if any(event["name"] == "chat_received" and event.get("direct") for event in events):
            # Lauf 18.10.: Hinweise wie „Bau dich durch die Wand“ beantwortete das Modell nur und wartete weiter.
            answer["chat_hint"] = ("Ein Spieler schreibt dir. Ist es ein Rat oder eine Bitte, prüf sie und setz sie um, "
                                   "statt nur zu antworten und weiter zu warten.")
        return compact(answer), answer.get("status") == "failed"

    def enemies_near(self):
        """Ob das letzte entity_nearby (bis 20 s alt) feindliche Wesen nannte."""
        return bool(self.last_mobs) and time.time() - self.last_mobs_time < 20

    def vitals(self, seen):
        """(Leben, Hunger) über look; None, wenn look scheitert."""
        try:
            look, _ = self.command("look", {}, seen, timeout=15, may_interrupt=False)
        except TimeoutError:
            return None
        data = look.get("data", {})
        if look.get("status") != "ok" or "health" not in data or "food" not in data:
            return None
        return data["health"], data["food"]

    def furnace_food_hint(self):
        food = self.food_in_furnace
        return (f"im Ofen bei {food['furnace']} liegt gegartes Essen ({food['item']}): hol es mit smelt_collect "
                "(läuft selbst hin) und iss.")

    def furnace_running(self):
        return time.time() < self.furnace_until

    def wait_tool(self, seconds, seen):
        deadline = time.time() + max(1, min(seconds, 120))
        woke = None
        while time.time() < deadline and woke is None:
            try:
                event = self.bridge.events.get(timeout=0.5)
            except queue.Empty:
                continue
            if self.handle_control(event):
                raise GoalEnded("gestoppt" if self.pending_goal is None else "neues Ziel")
            seen.append(event)
            if (event.get("name") in ("smelting_done", "damage_taken", "player_died") or self.is_new_threat(event)
                    or is_direct_chat(event)):
                woke = event["name"]
                self.acknowledge(event)
        return {"status": "ok", "waited_seconds": round(seconds - max(0.0, deadline - time.time())),
                **({"woke_because": woke} if woke else {})}

    # --- Modell

    def call_model(self, messages):
        # Dauer jedes Modellaufrufs protokollieren: Gesamtläufe 2 und 3 standen minutenlang ohne Schritt (Verdacht: API).
        started = time.time()
        try:
            response = self.send_request(messages)
            took = time.time() - started
            if took > 20:
                print(f"  [Modellaufruf dauerte {took:.0f} s]", flush=True)
            return response
        except anthropic.APIError as error:
            print(f"  [Modellaufruf scheiterte nach {time.time() - started:.0f} s: {type(error).__name__}]", flush=True)
            # Manche Modelle (z. B. Haiku) kennen den Parameter `effort` nicht: einmal ohne wiederholen.
            if not isinstance(error, anthropic.BadRequestError) or not is_effort_unsupported(error) or not self.use_effort:
                raise
            print(f"  [Modell {MODEL} kennt `effort` nicht, weiter ohne]", flush=True)
            self.use_effort = False
            return self.send_request(messages)

    def send_request(self, messages):
        # Eigener Cache-Haltepunkt hinter Tools und Systemprompt: Ändert sich der Verlauf (Kürzen), bleiben diese
        # rund 33 Tsd. Tokens im Cache, nur der Verlauf wird neu geschrieben.
        system = [{"type": "text", "text": self.system, "cache_control": {"type": "ephemeral"}}]
        request = dict(model=MODEL, max_tokens=MAX_TOKENS, system=system, tools=self.tools,
                       messages=messages, cache_control={"type": "ephemeral"})
        if self.use_effort:
            request["output_config"] = {"effort": EFFORT}
        if self.use_fallbacks:
            # Lehnt das Modell eine Anfrage aus Sicherheitsgründen ab, übernimmt serverseitig ein anderes.
            try:
                return self.client.beta.messages.create(
                    betas=["server-side-fallback-2026-07-01"], fallbacks="default", **request)
            except (TypeError, anthropic.BadRequestError) as error:
                if is_missing_key(error) or (self.use_effort and is_effort_unsupported(error)):
                    raise
                print(f"  [Fallback-Modelle nicht verfügbar ({type(error).__name__}), weiter ohne]", flush=True)
                self.use_fallbacks = False
        return self.client.messages.create(**request)

    def add_cost(self, usage, model=None):
        """Kosten genau nach der Preisliste: Modell der Antwort (Fallback zählt mit seinem Preis), Cache-Schreiben nach
        Laufzeit (5 min 1,25×, 1 h 2×), Cache-Lesen je Modell, Haiku-Staffel über 100.000 Tokens."""
        model = model or MODEL
        fresh = usage.input_tokens or 0
        written = getattr(usage, "cache_creation_input_tokens", 0) or 0
        read = getattr(usage, "cache_read_input_tokens", 0) or 0
        out = usage.output_tokens or 0
        price_in, price_out = PRICES.get(model, DEFAULT_PRICE)
        if model in LONG_PROMPT and fresh + written + read > LONG_PROMPT[model][0]:
            price_in, price_out = LONG_PROMPT[model][1]
        detail = getattr(usage, "cache_creation", None)
        written_1h = (getattr(detail, "ephemeral_1h_input_tokens", 0) or 0) if detail is not None else 0
        written_5m = written - written_1h
        cost = (fresh * price_in + written_5m * price_in * 1.25 + written_1h * price_in * 2.0
                + read * price_in * CACHE_READ_FACTOR.get(model, 0.1) + out * price_out) / 1_000_000
        self.cost += cost
        self.total_cost += cost
        self.total_tokens += fresh + written + read + out
        # Für die XP-Leiste im Spiel (Wunsch von Hannes); die Antwort des Mods braucht hier niemand.
        try:
            self.bridge.send("usage", {"tokens": self.total_tokens, "cost": round(self.total_cost, 4)})
        except OSError:
            pass
        return fresh + written + read, read, out

    # --- Ein Ziel

    def write_log(self, kind, payload):
        if self.log:
            self.log.write(json.dumps({"t": round(time.time(), 2), "kind": kind, **payload}, ensure_ascii=False) + "\n")
            self.log.flush()

    def say_in_game(self, text):
        try:
            self.command("chat", {"text": text[:256]}, [], timeout=10, may_interrupt=False)
            SPEAKER.say(text[:256])
        except (TimeoutError, GoalEnded, OSError):
            pass

    def run_goal(self, goal, chat_from=None):
        """Ein Ziel oder, mit chat_from, ein Gespräch, das mit einer Chatnachricht beginnt."""
        self.busy = True
        try:
            self.run_logged(goal, chat_from)
        finally:
            self.busy = False

    def run_logged(self, goal, chat_from):
        os.makedirs(LOG_DIR, exist_ok=True)
        stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        self.cost = 0.0
        self.paused = False
        self.reset_goal_state()
        with open(os.path.join(LOG_DIR, f"{stamp}.jsonl"), "w", encoding="utf-8") as self.log:
            self.write_log("goal", {"text": goal, "model": MODEL, "effort": EFFORT, "cost_limit": COST_LIMIT,
                                    **({"chat_from": chat_from} if chat_from else {})})
            limit = f"Limit {COST_LIMIT:.2f} $" if COST_LIMIT > 0 else "kein Kostenlimit"
            title = f"Chat von {chat_from}" if chat_from else "Ziel"
            print(f"\n=== {title}: {goal}  (Modell {MODEL}, {limit}) ===", flush=True)
            try:
                outcome = self.goal_loop(goal, chat_from)
            except GoalEnded as ended:
                outcome = f"beendet: {ended}"
            except anthropic.AuthenticationError:
                outcome = "API-Schlüssel fehlt oder ist ungültig (/aiplayer apikey <key> im Spiel, ANTHROPIC_API_KEY setzen oder `ant auth login`)"
            except TypeError as error:
                # Ohne Schlüssel wirft das SDK schon vor dem Senden einen TypeError.
                if not is_missing_key(error):
                    raise
                outcome = "Kein API-Schlüssel gefunden (/aiplayer apikey <key> im Spiel, ANTHROPIC_API_KEY setzen oder `ant auth login`)"
            except anthropic.RateLimitError:
                outcome = "Ratenlimit der API erreicht, später noch einmal versuchen"
            except anthropic.APIStatusError as error:
                outcome = f"API-Fehler {error.status_code}: {error.message}"
            except anthropic.APIConnectionError:
                outcome = "API nicht erreichbar (Netzwerk?)"
            self.write_log("end", {"outcome": outcome, "cost": round(self.cost, 4)})
            print(f"=== Ende: {outcome}  (Kosten {self.cost:.2f} $) ===\n", flush=True)
        self.log = None

    def goal_loop(self, goal, chat_from=None):
        pending = []
        self.drain(pending)
        pending.clear()
        look, _ = self.command("look", {}, pending, timeout=15, may_interrupt=False)
        if look.get("status") != "ok":
            return "kein KI-Spieler im Spiel: erst /aiplayer join"
        self.last_pos = look.get("data", {}).get("pos")
        self.world = world_key(look.get("data", {}))
        notes = load_notes(self.world)
        # Die auslösende Nachricht steht schon in der ersten Zeile, nicht noch einmal im Verlauf.
        earlier = list(self.chat_log)[:-1] if chat_from else list(self.chat_log)
        opening = ((f"{chat_from} schreibt dir im Chat: {goal}\n\n" if chat_from else f"Dein Ziel: {goal}\n\n")
                   + (f"Dein eigentliches Ziel ist weiterhin: {self.main_goal}. Antworte und hilf kurz; danach machst du "
                      "damit weiter.\n\n" if chat_from and self.main_goal else "")
                   + ("Zuletzt im Chat:\n" + "\n".join(f"- {line}" for line in earlier) + "\n\n" if earlier else "")
                   + ("Deine Notizen:\n" + "\n".join(f"- {note}" for note in notes) + "\n\n" if notes else "")
                   + (self.settings_text() + "\n\n" if CHAT_MODE != "off" else "")
                   + "Aktueller Stand (look):\n" + compact(look.get("data", {})))
        messages = [{"role": "user", "content": opening}]

        for step in (itertools.count(1) if MAX_STEPS <= 0 else range(1, MAX_STEPS + 1)):
            self.wait_while_paused(pending)
            self.drain(pending)
            if COST_LIMIT > 0 and self.cost >= COST_LIMIT:
                self.bridge.send("stop", {})
                self.say_in_game(f"Kostenlimit von {COST_LIMIT:.2f} $ erreicht, ich höre hier auf.")
                return "Kostenlimit erreicht"

            response = self.call_model(messages)
            total_in, cached, out = self.add_cost(response.usage, getattr(response, "model", None))
            self.write_log("model", {"step": step, "stop_reason": response.stop_reason, "input": total_in,
                                     "cached": cached, "output": out, "cost": round(self.cost, 4)})
            for block in response.content:
                if block.type == "text" and block.text.strip():
                    print(f"  Claude: {block.text.strip()}", flush=True)
                    self.write_log("text", {"text": block.text})

            if response.stop_reason == "refusal":
                return "das Modell hat die Anfrage abgelehnt"
            if response.stop_reason == "max_tokens":
                return "Antwort des Modells abgeschnitten (max_tokens)"
            tool_uses = [block for block in response.content if block.type == "tool_use"]
            if not tool_uses and not chat_from and self.deaths <= self.revived and self.end_checks < MAX_END_CHECKS:
                # Herzschlag am Ende: einmal nachfragen, bevor ein Ziel als erledigt gilt.
                self.end_checks += 1
                self.write_log("heartbeat", {"step": step, "reason": "end_check"})
                print("  [Herzschlag: wirklich fertig?]", flush=True)
                messages.append({"role": "assistant", "content": response.content})
                messages.append({"role": "user", "content": END_CHECK_TEXT.format(goal=goal)})
                continue
            if not tool_uses:
                # Ein Tod beendet kein Ziel: Das Modell bekommt das Ziel zurück (Gespräche ausgenommen).
                if not chat_from and self.deaths > self.revived and self.revived < MAX_REVIVES:
                    self.revived = self.deaths
                    self.write_log("revive", {"step": step, "deaths": self.deaths})
                    print("  [nach dem Tod: Ziel gilt weiter]", flush=True)
                    messages.append({"role": "assistant", "content": response.content})
                    messages.append({"role": "user", "content": REVIVE_TEXT})
                    continue
                return "Ziel vom Modell beendet"

            messages.append({"role": "assistant", "content": response.content})
            results = []
            for use in tool_uses:
                text, failed = self.run_tool(use.name, use.input, pending)
                print(f"  [{step}] {use.name} {compact(use.input)} -> {text[:220]}  ({self.cost:.2f} $)", flush=True)
                self.write_log("tool", {"step": step, "name": use.name, "input": use.input, "result": text})
                results.append({"type": "tool_result", "tool_use_id": use.id, "content": text,
                                **({"is_error": True} if failed else {})})
            nudge = self.heartbeat(step, goal, [use.name for use in tool_uses])
            if nudge:
                results.append({"type": "text", "text": nudge})
            if self.settings_note:
                results.append({"type": "text", "text": self.settings_note})
                self.settings_note = None
            talk = CHAT_MODE != "off" and self.talk_nudge([use.name for use in tool_uses], [r["content"] for r in results if r["type"] == "tool_result"])
            if talk:
                results.append({"type": "text", "text": talk})
            messages.append({"role": "user", "content": results})
            if step % TRIM_EVERY == 0:
                trim_history(messages)
                self.cap_history(messages, goal)

        self.bridge.send("stop", {})
        self.say_in_game("Ich habe die Höchstzahl an Schritten erreicht und höre hier auf.")
        return f"Höchstzahl von {MAX_STEPS} Schritten erreicht"

    def cap_history(self, messages, goal):
        """Behält die Eröffnung und die letzten MAX_KEPT_STEPS Schritte (je Antwort des Modells plus Ergebnisse)."""
        if len(messages) <= 1 + 2 * MAX_KEPT_STEPS:
            return
        keep = 2 * CUT_TO_STEPS
        # Nach der Eröffnung (user) muss eine Antwort des Modells folgen: Paare aus assistant und user abschneiden.
        cut = len(messages) - keep
        if messages[cut]["role"] != "assistant":
            cut += 1
        del messages[1:cut]
        notes = load_notes(self.world)
        messages[0] = {"role": "user", "content": (
            f"Dein Ziel: {goal}\n\n[Der ältere Verlauf wurde gekürzt; was du dir merken wolltest, steht in den Notizen.]\n\n"
            + ("Deine Notizen:\n" + "\n".join(f"- {note}" for note in notes) if notes else "Noch keine Notizen."))}
        print(f"  [Verlauf auf {MAX_KEPT_STEPS} Schritte gekürzt]", flush=True)

    # --- Leerlauf

    def run_forever(self, first_goal):
        self.sync_settings()
        self.pending_goal = first_goal
        while True:
            if self.pending_goal:
                goal, sender = self.pending_goal, self.pending_from
                self.pending_goal = self.pending_from = None
                if not sender:
                    self.main_goal = goal
                self.run_goal(goal, sender)
                # Nach einem Gespräch das eigentliche Ziel wieder aufnehmen: Im Lauf vom 15.10. fragte der Bot nach
                # jeder Hilfe des Menschen „Was soll ich als Nächstes tun?“, statt weiterzuspielen.
                if sender and self.main_goal and not self.pending_goal:
                    print(f"  [zurück zum Ziel: {self.main_goal}]", flush=True)
                    self.pending_goal = self.main_goal
                continue
            print("Warte auf ein Ziel: im Spiel /aiplayer goal <text> oder einfach in den Chat schreiben", flush=True)
            while not self.pending_goal:
                self.handle_control(self.bridge.events.get())


def is_missing_key(error):
    return isinstance(error, TypeError) and "authentication method" in str(error)


def is_effort_unsupported(error):
    return isinstance(error, anthropic.BadRequestError) and "effort" in str(error)


def main():
    first_goal = " ".join(sys.argv[1:]).strip() or None
    bridge = Bridge()
    while True:
        bridge.connect()
        agent = Agent(bridge)
        try:
            agent.run_forever(first_goal)
        except (ConnectionError, OSError) as error:
            print(f"{error}. Verbinde neu …", flush=True)
            first_goal = None
            bridge = Bridge()
            time.sleep(2.0)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nBeendet.")

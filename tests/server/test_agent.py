"""Servertest der KI-Schleife ohne API: Ein Platzhalter spielt das Modell nach Drehbuch.

Aufruf:  python tests/server/test_agent.py

Geprüft wird alles um den Modellaufruf herum: Tools aus den Schemas, Befehle an den Mod und
Ergebnisse zurück, `chat` im Spielchat, Notizblock, `wait`, Kostenrechnung und Kostenlimit sowie
`/aiplayer goal` und `/aiplayer stop` aus dem Spiel. Der echte Aufruf der API ist nicht Teil des Tests.
"""

import os

# Der Test prüft auch den Chat des Bots, der sonst vorübergehend aus ist.
os.environ.setdefault("AIPLAYER_CHAT", "full")
import sys
import tempfile
import threading
import time
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(__file__))
from harness import PORT, PROJECT, Report, TestServer  # noqa: E402

os.environ["AIPLAYER_PORT"] = str(PORT)
os.environ["AIPLAYER_COST_LIMIT"] = "2.0"  # Standard ist kein Limit; der Test prüft das Limit
os.environ.setdefault("ANTHROPIC_API_KEY", "test-kein-echter-schluessel")
sys.path.insert(0, os.path.join(PROJECT, "companion"))
import agent as agent_module  # noqa: E402

BOT = "Claude"


def text(value):
    return SimpleNamespace(type="text", text=value)


def tool(name, **args):
    tool.count += 1
    return SimpleNamespace(type="tool_use", id=f"tu-{tool.count}", name=name, input=args)


tool.count = 0


class ScriptedModel:
    """Antwortet der Reihe nach mit den vorgegebenen Inhalten und merkt sich, was es gefragt wurde."""

    def __init__(self, turns, input_tokens=1000):
        self.turns = list(turns)
        self.requests = []
        self.input_tokens = input_tokens

    def create(self, **request):
        # Die Nachrichtenliste wächst weiter: den Stand von jetzt festhalten.
        self.requests.append({**request, "messages": list(request["messages"])})
        content = self.turns.pop(0)
        calls_tool = any(block.type == "tool_use" for block in content)
        return SimpleNamespace(content=content, stop_reason="tool_use" if calls_tool else "end_turn",
                               usage=SimpleNamespace(input_tokens=self.input_tokens, output_tokens=200,
                                                     cache_creation_input_tokens=0, cache_read_input_tokens=0))


def results_of(request):
    """Die Tool-Ergebnisse der letzten Nachricht eines Requests als Texte."""
    last = request["messages"][-1]["content"]
    return [block["content"] for block in last if isinstance(block, dict) and block.get("type") == "tool_result"]


def main():
    report = Report()
    agent_module.NOTES_DIR = tempfile.mkdtemp()
    # Das Drehbuch beendet jedes Ziel zweimal: einmal fragt der Herzschlag nach, dann ist Schluss.
    agent_module.MAX_END_CHECKS = 1
    agent_module.LOG_DIR = tempfile.mkdtemp()
    server = TestServer(os.path.join(os.environ.get("TEMP", "."), "aiplayer-test-agent.log"))
    try:
        server.console("gamerule spawn_mobs false")
        server.console("time set noon")
        server.console("aiplayer events off")
        server.console(f"aiplayer join {BOT}", wait=5)
        server.console(f"tp {BOT} 300 -60 300", wait=1)

        tools = agent_module.load_tools()
        names = {entry["name"] for entry in tools}
        report.check("Tools aus den Schemas: alle Befehle, dazu chat, remember, wait; kein ping",
                     {"goto", "collect", "craft", "chat", "remember", "wait"} <= names and "ping" not in names,
                     f"{len(tools)} Tools")

        # Ohne Begleitprozess meldet der Mod das, statt das Ziel zu verschlucken. (Der Test selbst ist verbunden;
        # die eigene Verbindung des Agenten ersetzt sie gleich.)
        bridge = agent_module.Bridge()
        bridge.connect()
        time.sleep(0.5)
        bot = agent_module.Agent(bridge)

        # Satt und gesund, sonst lehnt wait mit no_regen_hunger ab (der Bot behält Schaden aus früheren Läufen).
        server.console(f"effect give {BOT} minecraft:instant_health 1 10", wait=0.5)
        server.console(f"effect give {BOT} minecraft:saturation 1 20", wait=1)

        # --- Ziel 1: chat, look, remember, wait, dann fertig
        model = ScriptedModel([
            [text("Ich sage kurz Hallo."), tool("chat", text="Hallo, ich lege los."), tool("look")],
            [tool("remember", note="Testbasis bei 300 -60 300"), tool("wait", seconds=2)],
            [tool("craft", item="blorb")],
            [text("Fertig.")],
            # Der Herzschlag fragt einmal nach, ob das Ziel wirklich erreicht ist.
            [text("Ja, wirklich fertig.")],
        ])
        bot.client = SimpleNamespace(messages=model, beta=SimpleNamespace(messages=model))
        mark = server.output_mark()
        bot.run_goal("Sag Hallo")
        time.sleep(0.5)
        chat_line = server.find_in_output("Hallo, ich lege los.", mark)
        report.check("chat: Nachricht steht als Spielernachricht im Chat",
                     chat_line is not None and f"<{BOT}>" in chat_line, (chat_line or "nichts").strip()[-80:])
        first = model.requests[0]
        report.check("Erste Nachricht: Ziel und Stand aus look",
                     "Dein Ziel: Sag Hallo" in first["messages"][0]["content"] and '"health"' in first["messages"][0]["content"])
        report.check("Request: Modell, Systemprompt, Cache, Effort",
                     first["model"] == agent_module.MODEL and "Minecraft" in first["system"][0]["text"]
                     and first["system"][0].get("cache_control") == {"type": "ephemeral"}
                     and first["cache_control"] == {"type": "ephemeral"} and first["output_config"] == {"effort": agent_module.EFFORT})
        second = results_of(model.requests[1])
        report.check("Zwei Tools in einer Antwort: zwei Ergebnisse in einer Nachricht, look mit Stand",
                     len(second) == 2 and '"status":"ok"' in second[0] and '"pos"' in second[1], second[0][:80])
        third = results_of(model.requests[2])
        report.check("remember und wait: Notiz gespeichert, gewartet",
                     agent_module.load_notes(bot.world) == ["Testbasis bei 300 -60 300"] and '"waited_seconds"' in third[1], third[1][:80])
        fourth = model.requests[3]["messages"][-1]["content"][0]
        report.check("Fehlgeschlagener Befehl: is_error und Grund kommen beim Modell an",
                     fourth.get("is_error") is True and "unknown_item" in fourth["content"], fourth["content"][:80])
        check = model.requests[4]["messages"][-1]["content"] if len(model.requests) > 4 else ""
        report.check("Herzschlag am Ende: einmal nachgefragt, dann endet das Ziel",
                     len(model.requests) == 5 and "[Herzschlag]" in check and "Sag Hallo" in check, str(check)[:80])
        report.check("Kosten: 5 Aufrufe zu je 1000 ein und 200 aus",
                     abs(bot.cost - 5 * (1000 * agent_module.PRICES[agent_module.MODEL][0]
                                         + 200 * agent_module.PRICES[agent_module.MODEL][1]) / 1e6) < 1e-9, f"{bot.cost:.4f} $")

        # --- Hungrig und verletzt: wait lehnt einmal ab (unter Hunger 18 heilt nichts)
        server.console(f"effect give {BOT} minecraft:hunger 10 255", wait=4)
        server.console(f"damage {BOT} 4", wait=1)
        hungry = ScriptedModel([[tool("wait", seconds=1)], [text("Ich esse.")], [text("Fertig.")]])
        bot.client = SimpleNamespace(messages=hungry, beta=SimpleNamespace(messages=hungry))
        bot.run_goal("Warte kurz")
        refused = results_of(hungry.requests[1])
        report.check("wait bei Hunger unter 18 und fehlendem Leben: no_regen_hunger",
                     len(refused) == 1 and "no_regen_hunger" in refused[0], refused[0][:80] if refused else "nichts")
        server.console(f"effect clear {BOT}", wait=0.5)
        server.console(f"effect give {BOT} minecraft:instant_health 1 10", wait=0.5)
        server.console(f"effect give {BOT} minecraft:saturation 1 20", wait=1)


        # --- Herzschlag im Leerlauf: nach vier Schritten nur warten kommt ein Anstoß
        model = ScriptedModel([[tool("wait", seconds=1)]] * 4 + [[text("Ende.")], [text("Ende.")]])
        bot.client = SimpleNamespace(messages=model, beta=SimpleNamespace(messages=model))
        bot.run_goal("Nichts tun")
        nudge = model.requests[4]["messages"][-1]["content"] if len(model.requests) > 4 else []
        texts = [block.get("text", "") for block in nudge if isinstance(block, dict) and block.get("type") == "text"]
        report.check("Herzschlag im Leerlauf: nach 4 Schritten nur wait ein Anstoß mit Ziel und Hunger-Regel",
                     any("Nichts tun" in t and "18" in t for t in texts), (texts or ["nichts"])[0][:80])
        bot.cost = 0.0

        # --- Ziel 2: Notizen stehen am Anfang; Kostenlimit beendet das Ziel
        # Jeder Aufruf kostet gut ein Viertel des Limits, unabhängig vom Preis des eingestellten Modells.
        price_in = agent_module.PRICES.get(agent_module.MODEL, agent_module.DEFAULT_PRICE)[0]
        model = ScriptedModel([[tool("look")]] * 50, input_tokens=int(agent_module.COST_LIMIT / 4 * 1e6 / price_in))
        bot.client = SimpleNamespace(messages=model, beta=SimpleNamespace(messages=model))
        mark = server.output_mark()
        bot.run_goal("Teuer")
        time.sleep(0.5)
        report.check("Notizen stehen am Anfang des nächsten Ziels",
                     "Testbasis bei 300 -60 300" in model.requests[0]["messages"][0]["content"])
        report.check("Kostenlimit: Ziel endet, Bot sagt es im Chat",
                     bot.cost >= agent_module.COST_LIMIT and len(model.requests) < 10
                     and server.find_in_output("Kostenlimit", mark) is not None,
                     f"{len(model.requests)} Aufrufe, {bot.cost:.2f} $")

        # --- Ziel 3 aus dem Spiel, mit /aiplayer stop während einer langen Aktion
        model = ScriptedModel([[tool("goto", pos=[380, -60, 300])], [text("Sollte nie gefragt werden.")]])
        bot.client = SimpleNamespace(messages=model, beta=SimpleNamespace(messages=model))
        runner = threading.Thread(target=bot.run_forever, args=(None,), daemon=True)
        runner.start()
        time.sleep(1.0)
        mark = server.output_mark()
        server.console("aiplayer goal Lauf nach Osten", wait=3)
        report.check("/aiplayer goal: Ziel kommt beim Begleitprozess an, Modell wird gefragt",
                     len(model.requests) == 1 and "Lauf nach Osten" in model.requests[0]["messages"][0]["content"],
                     (server.find_in_output("Ziel an die KI", mark) or "").strip()[-60:])
        server.console("aiplayer stop", wait=3)
        report.check("/aiplayer stop: Ziel endet, das Modell wird nicht mehr gefragt",
                     len(model.requests) == 1 and server.find_in_output("KI gestoppt", mark) is not None)

        # --- Chat im Leerlauf beginnt ein Gespräch; eine Nachricht während einer Aktion hält sie an
        model = ScriptedModel([[tool("chat", text="Mir geht es blendend."), tool("goto", pos=[380, -60, 300])],
                               [text("Erledigt.")], [text("Erledigt.")]])
        bot.client = SimpleNamespace(messages=model, beta=SimpleNamespace(messages=model))
        mark = server.output_mark()
        server.console("say Wie geht es dir?", wait=3)
        first = model.requests[0]["messages"][0]["content"] if model.requests else ""
        report.check("Chat im Leerlauf: Nachricht kommt mit Absender beim Modell an, Bot antwortet im Chat",
                     "schreibt dir im Chat: Wie geht es dir?" in first
                     and server.find_in_output("Mir geht es blendend.", mark) is not None, first[:60])
        server.console("say Komm bitte zurück", wait=3)
        answer = results_of(model.requests[1])[1] if len(model.requests) > 1 else ""
        report.check("Chat während einer Aktion: goto hält an, die Nachricht steht im Ergebnis",
                     '"interrupted_by"' in answer and "Komm bitte zurück" in answer, answer[:120])
    finally:
        server.close()
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

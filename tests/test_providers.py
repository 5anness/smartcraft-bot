"""Test der Anbieter OpenAI und Gemini ohne API (companion/providers.py).

Spielt ein Gespräch wie in agent.py nach (Modell ruft Tools auf, Ergebnisse gehen zurück) und prüft, was beim
Anbieter ankäme: Format der Werkzeuge, Zuordnung von Aufruf und Ergebnis, Gemini-Signaturen, Tokens und Kosten.
Der Transport ist ersetzt; die Typen der SDKs (openai, google-genai) sind echt.

Aufruf:  python tests/test_providers.py     (braucht pip install openai google-genai anthropic)
Wenige Sekunden.
"""

import os
import sys
from types import SimpleNamespace

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(PROJECT, "companion"))
os.environ.setdefault("ANTHROPIC_API_KEY", "test-kein-echter-schluessel")

import providers  # noqa: E402

failures = []


def check(name, passed, detail=""):
    print(("OK   " if passed else "FAIL ") + name + (f"  {detail}" if detail and not passed else ""))
    if not passed:
        failures.append(name)


def tool_result(call_id, text):
    return {"type": "tool_result", "tool_use_id": call_id, "content": text}


def load_tools():
    import agent
    return agent.load_tools()


def test_detect():
    check("Anbieter am Präfix: sk-ant- / sk- / AIza / unbekannt",
          [providers.detect_provider(k) for k in ("sk-ant-api03-x", "sk-proj-x", "AIzaSyX", "xyz")]
          == ["anthropic", "openai", "gemini", None])


def test_openai(tools):
    p = providers.OpenAIProvider(api_key="sk-test")
    sent = []

    def answer(message, finish="tool_calls", prompt=1200, cached=0):
        return SimpleNamespace(choices=[SimpleNamespace(message=message, finish_reason=finish)], model="gpt-6-luna",
                               usage=SimpleNamespace(prompt_tokens=prompt, completion_tokens=80,
                                                     prompt_tokens_details=SimpleNamespace(cached_tokens=cached)))

    script = [
        answer(SimpleNamespace(content="Ich schaue mich um.", tool_calls=[SimpleNamespace(
            id="call_1", function=SimpleNamespace(name="look", arguments="{}"))])),
        answer(SimpleNamespace(content=None, tool_calls=[
            SimpleNamespace(id="call_2", function=SimpleNamespace(name="goto", arguments='{"pos": [1, 64, 2]}')),
            SimpleNamespace(id="call_3", function=SimpleNamespace(name="remember", arguments='{"note": "Basis"}'))]),
            cached=1000),
        answer(SimpleNamespace(content="Fertig.", tool_calls=None), finish="stop"),
    ]

    def create(**request):
        sent.append(request)
        return script.pop(0)

    p.client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
    messages = [{"role": "user", "content": "Dein Ziel: Baue ein Haus"}]
    r1 = p.create(system="SYSTEM", tools=tools, messages=messages, max_tokens=8000, model="gpt-6-luna")
    check("OpenAI: Text und Tool-Aufruf als Blöcke, stop_reason tool_use",
          [b.type for b in r1.content] == ["text", "tool_use"] and r1.stop_reason == "tool_use"
          and r1.content[1].name == "look" and r1.content[1].input == {})
    check("OpenAI: Werkzeuge als function mit parameters",
          sent[0]["tools"][0]["type"] == "function" and "parameters" in sent[0]["tools"][0]["function"]
          and len(sent[0]["tools"]) == len(tools))
    check("OpenAI: Systemprompt als erste Nachricht", sent[0]["messages"][0] == {"role": "system", "content": "SYSTEM"})

    messages.append({"role": "assistant", "content": r1.content})
    messages.append({"role": "user", "content": [tool_result("call_1", '{"status":"ok"}'),
                                                 {"type": "text", "text": "[Herzschlag]"}]})
    r2 = p.create(system="SYSTEM", tools=tools, messages=messages, max_tokens=8000, model="gpt-6-luna")
    m = sent[1]["messages"]
    check("OpenAI: Verlauf system, user, assistant(tool_calls), tool, user",
          [x["role"] for x in m] == ["system", "user", "assistant", "tool", "user"], [x["role"] for x in m])
    check("OpenAI: Ergebnis gehört zum Aufruf (tool_call_id)",
          m[2]["tool_calls"][0]["id"] == "call_1" and m[3]["tool_call_id"] == "call_1"
          and m[3]["content"] == '{"status":"ok"}' and m[4]["content"] == "[Herzschlag]")
    check("OpenAI: zwei Aufrufe mit Argumenten", [(b.name, b.input) for b in r2.content]
          == [("goto", {"pos": [1, 64, 2]}), ("remember", {"note": "Basis"})])
    check("OpenAI: Tokens (gecacht abgezogen)", (r2.usage.input_tokens, r2.usage.cache_read_input_tokens,
                                                 r2.usage.output_tokens) == (200, 1000, 80))

    messages.append({"role": "assistant", "content": r2.content})
    messages.append({"role": "user", "content": [tool_result("call_2", "ok"), tool_result("call_3", "gemerkt")]})
    r3 = p.create(system="SYSTEM", tools=tools, messages=messages, max_tokens=8000, model="gpt-6-luna")
    roles = [x["role"] for x in sent[2]["messages"]]
    check("OpenAI: zwei Ergebnisse folgen direkt auf die zwei Aufrufe",
          roles[-3:] == ["assistant", "tool", "tool"] and r3.stop_reason == "end_turn", roles)


def test_gemini(tools):
    from google.genai import types
    p = providers.GeminiProvider(api_key="AIza-test")
    sent = []

    def answer(parts, finish="STOP", prompt=1500, cached=0):
        return types.GenerateContentResponse(
            candidates=[types.Candidate(content=types.Content(role="model", parts=parts), finish_reason=finish)],
            usage_metadata=types.GenerateContentResponseUsageMetadata(
                prompt_token_count=prompt, cached_content_token_count=cached, candidates_token_count=60,
                thoughts_token_count=40),
            model_version="gemini-3.8-flash")

    script = [
        answer([types.Part(text="Ich schaue.", thought_signature=b"sig-text"),
                types.Part(function_call=types.FunctionCall(name="look", args={}), thought_signature=b"sig-1")]),
        answer([types.Part(function_call=types.FunctionCall(id="g-2", name="goto", args={"pos": [1, 64, 2]}),
                           thought_signature=b"sig-2")], cached=1000),
        answer([types.Part(text="Fertig.")]),
    ]

    def generate_content(model, contents, config):
        sent.append({"model": model, "contents": list(contents), "config": config})
        return script.pop(0)

    p.client = SimpleNamespace(models=SimpleNamespace(generate_content=generate_content))
    try:
        declared = p.convert_tools(tools)
        ok = len(declared[0].function_declarations) == len(tools)
    except Exception as error:  # pydantic prüft jedes Schema
        ok, declared = False, error
    check("Gemini: alle Tool-Schemas als FunctionDeclaration gültig", ok, str(declared)[:300])

    messages = [{"role": "user", "content": "Dein Ziel: Baue ein Haus"}]
    r1 = p.create(system="SYSTEM", tools=tools, messages=messages, max_tokens=8000, model="gemini-3.8-flash")
    call = r1.content[1]
    check("Gemini: Text und Aufruf, fehlende ID wird vergeben", [b.type for b in r1.content] == ["text", "tool_use"]
          and call.name == "look" and call.id and r1.stop_reason == "tool_use")
    config = sent[0]["config"]
    check("Gemini: Systemprompt, Werkzeuge, kein automatisches Function Calling",
          config.system_instruction == "SYSTEM" and config.tools and config.automatic_function_calling.disable)

    messages.append({"role": "assistant", "content": r1.content})
    messages.append({"role": "user", "content": [tool_result(call.id, '{"status":"ok"}'),
                                                 {"type": "text", "text": "[Herzschlag]"}]})
    r2 = p.create(system="SYSTEM", tools=tools, messages=messages, max_tokens=8000, model="gemini-3.8-flash")
    contents = sent[1]["contents"]
    model_turn = contents[1]
    signatures = [part.thought_signature for part in model_turn.parts]
    check("Gemini: Antwort samt Gedanken-Signaturen unverändert zurück", signatures == [b"sig-text", b"sig-1"]
          and model_turn.parts[1].function_call.id == call.id, signatures)
    reply = contents[2].parts
    check("Gemini: Ergebnis als function_response mit ID und Name, dann Text",
          reply[0].function_response.id == call.id and reply[0].function_response.name == "look"
          and reply[0].function_response.response == {"result": '{"status":"ok"}'} and reply[1].text == "[Herzschlag]")
    check("Gemini: Tokens (gecacht abgezogen, Denken zählt zur Ausgabe)",
          (r2.usage.input_tokens, r2.usage.cache_read_input_tokens, r2.usage.output_tokens) == (500, 1000, 100))
    check("Gemini: vorhandene ID bleibt", r2.content[0].id == "g-2")

    messages.append({"role": "assistant", "content": r2.content})
    messages.append({"role": "user", "content": [tool_result("g-2", "ok")]})
    r3 = p.create(system="SYSTEM", tools=tools, messages=messages, max_tokens=8000, model="gemini-3.8-flash")
    check("Gemini: Ende ohne Aufruf", r3.stop_reason == "end_turn" and r3.content[0].text == "Fertig.")
    # Gekürzter Verlauf: assistant-Inhalt ohne Originalantwort (z. B. aus einer Liste) wird nachgebaut.
    rebuilt = p.convert_messages([{"role": "assistant", "content": [providers.Block("tool_use", id="x", name="look",
                                                                                     input={})]},
                                  {"role": "user", "content": [tool_result("x", "ok")]}])
    check("Gemini: Verlauf ohne Originalantwort wird nachgebaut",
          rebuilt[0].parts[0].function_call.name == "look" and rebuilt[1].parts[0].function_response.name == "look")


def test_agent_switch():
    """Anbieterwechsel und Kostenrechnung in agent.py, ohne Verbindung zum Mod."""
    import agent
    bot = agent.Agent.__new__(agent.Agent)
    bot.use_fallbacks = bot.use_effort = True
    bot.cost = bot.total_cost = 0.0
    bot.total_tokens = 0
    bot.bridge = SimpleNamespace(send=lambda *a: None)
    bot.set_provider("openai", None)
    check("agent: Wechsel auf OpenAI, Standardmodell, ohne Anthropic-Extras",
          (agent.PROVIDER, agent.MODEL, bot.use_fallbacks, bot.use_effort) == ("openai", "gpt-6-luna", False, False)
          and isinstance(bot.client, providers.OpenAIProvider))
    bot.add_cost(providers.Usage(1_000_000, 1_000_000, 0), "gpt-6-luna")
    check("agent: Kosten gpt-6-luna 0,10 + 0,50 $", abs(bot.cost - 0.6) < 1e-9, bot.cost)
    bot.set_provider("gemini", "gemini-3.8-flash")
    check("agent: Wechsel auf Gemini", isinstance(bot.client, providers.GeminiProvider))
    bot.set_provider("anthropic", None)
    check("agent: zurück zu Claude Haiku 5.5", (agent.PROVIDER, agent.MODEL) == ("anthropic", "claude-haiku-5-5"))


def main():
    tools = load_tools()
    test_detect()
    test_openai(tools)
    test_gemini(tools)
    test_agent_switch()
    print(f"\n{'Alle Prüfungen bestanden' if not failures else str(len(failures)) + ' Prüfung(en) gescheitert'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

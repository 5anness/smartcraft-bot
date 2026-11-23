"""Anbieter für die KI: Anthropic (Claude), OpenAI (ChatGPT) und Google (Gemini).

Intern spricht agent.py weiter das Format der Anthropic-API: Systemprompt, Tools mit `input_schema`, Nachrichten mit
Blöcken `text`, `tool_use` und `tool_result`. Für OpenAI und Gemini übersetzt diese Datei hin und zurück; die Antwort
sieht für agent.py aus wie eine von Anthropic (`content`, `stop_reason`, `usage`, `model`).

Die Pakete `openai` und `google-genai` werden erst geladen, wenn der Anbieter gewählt ist.
"""

import itertools
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# Standardmodell, Umgebungsvariable und Schlüsseldatei je Anbieter. Modelle: die günstigen mit guten Werkzeug-
# Fähigkeiten, wie Haiku bei Claude (Stand 23.11.2026, Preise in agent.py).
PROVIDERS = {
    "anthropic": {"model": "claude-haiku-5-5", "env": "ANTHROPIC_API_KEY", "file": "api_key.txt",
                  "package": "anthropic", "module": "anthropic"},
    "openai": {"model": "gpt-6-luna", "env": "OPENAI_API_KEY", "file": "openai_key.txt",
               "package": "openai", "module": "openai"},
    "gemini": {"model": "gemini-3.8-flash", "env": "GEMINI_API_KEY", "file": "gemini_key.txt",
               "package": "google-genai", "module": "google.genai"},
}


def detect_provider(key):
    """Anbieter am Präfix des Schlüssels: sk-ant- (Anthropic), AIza (Google), sk- (OpenAI); sonst None."""
    key = key.strip()
    if key.startswith("sk-ant-"):
        return "anthropic"
    if key.startswith("AIza"):
        return "gemini"
    if key.startswith("sk-"):
        return "openai"
    return None


def key_file(provider):
    return os.path.join(HERE, PROVIDERS[provider]["file"])


def load_key_files():
    """Schlüssel aus den Dateien in die Umgebung, wo die Variable fehlt. Inhalt wird nie ausgegeben."""
    for provider, info in PROVIDERS.items():
        path = key_file(provider)
        if os.environ.get(info["env"]) or not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as file:
            key = next((line.strip() for line in file if line.strip() and not line.startswith("#")), "")
        if key:
            os.environ[info["env"]] = key


def save_key(provider, key):
    """Neuer Schlüssel aus /smartcraft apikey: in die Datei des Anbieters (steht in .gitignore) und in die Umgebung."""
    with open(key_file(provider), "w", encoding="utf-8") as file:
        file.write(f"# {provider}-API-Schlüssel, gesetzt mit /smartcraft apikey. Nie committen.\n{key.strip()}\n")
    os.environ[PROVIDERS[provider]["env"]] = key.strip()


# --- Fehler, die agent.py ohne Kenntnis des Anbieters behandelt

class ProviderError(Exception):
    """Fehler eines Anbieters außer Anthropic (die Anthropic-Fehler behandelt agent.py direkt)."""


class AuthError(ProviderError):
    pass


class RateLimitError(ProviderError):
    pass


class ConnectionError_(ProviderError):
    pass


class MissingPackage(ProviderError):
    pass


# --- Antwort im Format von Anthropic

class Block:
    """Ein Inhaltsblock wie bei Anthropic: type text (text) oder tool_use (id, name, input)."""

    def __init__(self, type, text=None, id=None, name=None, input=None):
        self.type = type
        self.text = text
        self.id = id
        self.name = name
        self.input = input

    def __repr__(self):
        return f"Block({self.type}, {self.name or self.text!r})"


class Content(list):
    """Liste der Blöcke; `raw` hält die Originalantwort des Anbieters (Gemini braucht sie unverändert zurück)."""

    def __init__(self, blocks, raw=None):
        super().__init__(blocks)
        self.raw = raw


class Usage:
    def __init__(self, input_tokens, output_tokens, cache_read_input_tokens=0):
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens
        self.cache_read_input_tokens = cache_read_input_tokens
        self.cache_creation_input_tokens = 0
        self.cache_creation = None


class Response:
    def __init__(self, content, stop_reason, usage, model):
        self.content = content
        self.stop_reason = stop_reason
        self.usage = usage
        self.model = model


def _get(block, field, default=None):
    """Feld eines Blocks, egal ob dict (eigene Nachrichten) oder Objekt (Antworten)."""
    if isinstance(block, dict):
        return block.get(field, default)
    return getattr(block, field, default)


def _result_text(block):
    content = _get(block, "content", "")
    if isinstance(content, list):
        return "\n".join(_get(part, "text", "") or "" for part in content)
    return content if isinstance(content, str) else json.dumps(content, ensure_ascii=False)


def _blocks(content):
    return [{"type": "text", "text": content}] if isinstance(content, str) else list(content)


# --- OpenAI (Chat Completions mit Function Calling)

class OpenAIProvider:
    name = "openai"

    def __init__(self, api_key=None, timeout=90.0, max_retries=4):
        try:
            import openai
        except ImportError as error:
            raise MissingPackage("Paket openai fehlt (pip install openai)") from error
        self.openai = openai
        self.client = openai.OpenAI(api_key=api_key or os.environ.get("OPENAI_API_KEY") or "missing",
                                    timeout=timeout, max_retries=max_retries)

    @staticmethod
    def convert_tools(tools):
        return [{"type": "function", "function": {"name": tool["name"], "description": tool["description"],
                                                   "parameters": tool["input_schema"]}} for tool in tools]

    @staticmethod
    def convert_messages(system, messages):
        out = [{"role": "system", "content": system}]
        for message in messages:
            blocks = _blocks(message["content"])
            if message["role"] == "assistant":
                text = "\n".join(_get(b, "text") or "" for b in blocks if _get(b, "type") == "text").strip()
                calls = [{"id": _get(b, "id"), "type": "function",
                          "function": {"name": _get(b, "name"), "arguments": json.dumps(_get(b, "input") or {},
                                                                                         ensure_ascii=False)}}
                         for b in blocks if _get(b, "type") == "tool_use"]
                entry = {"role": "assistant", "content": text or None}
                if calls:
                    entry["tool_calls"] = calls
                out.append(entry)
                continue
            # Nutzer: erst alle Tool-Ergebnisse (müssen direkt auf die Aufrufe folgen), dann der Text.
            texts = []
            for block in blocks:
                if _get(block, "type") == "tool_result":
                    out.append({"role": "tool", "tool_call_id": _get(block, "tool_use_id"),
                                "content": _result_text(block)})
                elif _get(block, "type") == "text":
                    texts.append(_get(block, "text") or "")
            if texts:
                out.append({"role": "user", "content": "\n\n".join(texts)})
        return out

    @staticmethod
    def convert_response(raw, model):
        choice = raw.choices[0]
        message = choice.message
        blocks = []
        if message.content and message.content.strip():
            blocks.append(Block("text", text=message.content))
        for call in message.tool_calls or []:
            try:
                args = json.loads(call.function.arguments or "{}")
            except json.JSONDecodeError:
                args = {}
            blocks.append(Block("tool_use", id=call.id, name=call.function.name, input=args))
        finish = choice.finish_reason
        stop = ("tool_use" if any(b.type == "tool_use" for b in blocks) else "max_tokens" if finish == "length"
                else "refusal" if finish == "content_filter" else "end_turn")
        usage = raw.usage
        cached = getattr(getattr(usage, "prompt_tokens_details", None), "cached_tokens", 0) or 0
        prompt = getattr(usage, "prompt_tokens", 0) or 0
        return Response(Content(blocks, raw=message), stop,
                        Usage(prompt - cached, getattr(usage, "completion_tokens", 0) or 0, cached),
                        getattr(raw, "model", None) or model)

    def create(self, system, tools, messages, max_tokens, model):
        openai = self.openai
        try:
            raw = self.client.chat.completions.create(
                model=model, messages=self.convert_messages(system, messages), tools=self.convert_tools(tools),
                max_completion_tokens=max_tokens)
        except openai.AuthenticationError as error:
            raise AuthError(str(error)) from error
        except openai.RateLimitError as error:
            raise RateLimitError(str(error)) from error
        except openai.APIConnectionError as error:
            raise ConnectionError_(str(error)) from error
        except openai.APIError as error:
            raise ProviderError(str(error)) from error
        return self.convert_response(raw, model)


# --- Gemini (google-genai, Function Calling)

class GeminiProvider:
    name = "gemini"

    def __init__(self, api_key=None, timeout=90.0, max_retries=4):
        try:
            from google import genai
            from google.genai import errors, types
        except ImportError as error:
            raise MissingPackage("Paket google-genai fehlt (pip install google-genai)") from error
        self.types = types
        self.errors = errors
        self.client = genai.Client(
            api_key=api_key or os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY") or "missing",
            http_options=types.HttpOptions(timeout=int(timeout * 1000)))
        self.ids = itertools.count(1)

    def convert_tools(self, tools):
        types = self.types
        return [types.Tool(function_declarations=[
            types.FunctionDeclaration(name=tool["name"], description=tool["description"],
                                      parameters_json_schema=tool["input_schema"]) for tool in tools])]

    def convert_messages(self, messages):
        types = self.types
        contents = []
        names = {}  # tool_use_id -> Name; Gemini will den Namen auch in der Antwort
        for message in messages:
            content = message["content"]
            if message["role"] == "assistant":
                for block in _blocks(content):
                    if _get(block, "type") == "tool_use":
                        names[_get(block, "id")] = _get(block, "name")
                raw = getattr(content, "raw", None)
                if raw is not None:
                    # Originalantwort samt Gedanken-Signaturen zurückgeben (Pflicht bei Gemini 3 mit Function Calling).
                    contents.append(raw)
                    continue
                parts = []
                for block in _blocks(content):
                    if _get(block, "type") == "text" and _get(block, "text"):
                        parts.append(types.Part(text=_get(block, "text")))
                    elif _get(block, "type") == "tool_use":
                        parts.append(types.Part(function_call=types.FunctionCall(
                            id=_get(block, "id"), name=_get(block, "name"), args=_get(block, "input") or {})))
                contents.append(types.Content(role="model", parts=parts or [types.Part(text="…")]))
                continue
            parts = []
            for block in _blocks(content):
                if _get(block, "type") == "tool_result":
                    call_id = _get(block, "tool_use_id")
                    parts.append(types.Part(function_response=types.FunctionResponse(
                        id=call_id, name=names.get(call_id, "unknown"), response={"result": _result_text(block)})))
                elif _get(block, "type") == "text":
                    parts.append(types.Part(text=_get(block, "text") or ""))
            contents.append(types.Content(role="user", parts=parts or [types.Part(text="…")]))
        return contents

    def convert_response(self, raw, model):
        candidate = (raw.candidates or [None])[0]
        content = getattr(candidate, "content", None)
        blocks = []
        for part in (getattr(content, "parts", None) or []):
            if getattr(part, "thought", False):
                continue
            call = getattr(part, "function_call", None)
            if call is not None:
                call_id = call.id or f"gemini-{next(self.ids)}"
                blocks.append(Block("tool_use", id=call_id, name=call.name, input=dict(call.args or {})))
                if not call.id:
                    call.id = call_id  # damit die Antwort im nächsten Aufruf dieselbe ID trägt
            elif getattr(part, "text", None) and part.text.strip():
                blocks.append(Block("text", text=part.text))
        finish = str(getattr(candidate, "finish_reason", "") or "")
        stop = ("tool_use" if any(b.type == "tool_use" for b in blocks) else "max_tokens" if "MAX_TOKENS" in finish
                else "refusal" if any(word in finish for word in ("SAFETY", "PROHIBITED", "BLOCKLIST", "SPII"))
                else "end_turn")
        meta = raw.usage_metadata
        cached = getattr(meta, "cached_content_token_count", 0) or 0
        prompt = getattr(meta, "prompt_token_count", 0) or 0
        out = (getattr(meta, "candidates_token_count", 0) or 0) + (getattr(meta, "thoughts_token_count", 0) or 0)
        return Response(Content(blocks, raw=content), stop, Usage(prompt - cached, out, cached),
                        getattr(raw, "model_version", None) or model)

    def create(self, system, tools, messages, max_tokens, model):
        types = self.types
        config = types.GenerateContentConfig(
            system_instruction=system, tools=self.convert_tools(tools), max_output_tokens=max_tokens,
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True))
        try:
            raw = self.client.models.generate_content(model=model, contents=self.convert_messages(messages),
                                                      config=config)
        except self.errors.ClientError as error:
            code = getattr(error, "code", 0)
            text = str(error)
            if code in (401, 403) or "API key" in text or "API_KEY" in text:
                raise AuthError(text) from error
            if code == 429:
                raise RateLimitError(text) from error
            raise ProviderError(text) from error
        except self.errors.APIError as error:
            raise ProviderError(str(error)) from error
        except OSError as error:
            raise ConnectionError_(str(error)) from error
        return self.convert_response(raw, model)


def make_client(provider, api_key=None):
    """Client für OpenAI oder Gemini (Anthropic legt agent.py selbst an)."""
    if provider == "openai":
        return OpenAIProvider(api_key)
    if provider == "gemini":
        return GeminiProvider(api_key)
    raise ValueError(provider)

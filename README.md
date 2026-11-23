# SmartCraft

A Fabric mod that lets an AI (Claude, ChatGPT or Gemini) play Minecraft as a real second player, from punching
trees all the way to the Ender Dragon.

SmartCraft is a server-side mod for Minecraft Java Edition 26.2. An LLM (Claude by default, or ChatGPT / Gemini)
controls its own player in your world. The AI joins like a real LAN player, with a join message, a tab list entry, a skin and its
own inventory and health. You can watch it, chat with it, or give it goals like
`/smartcraft goal Defeat the Ender Dragon`.

Unlike Mineflayer-based bots, SmartCraft runs inside the game itself. The mod provides perception, events, A*
pathfinding and a library of high-level actions (mining, crafting, smelting, combat, building Nether portals,
bartering, fighting the dragon …). A small Python companion process sends these to the model as tools over a local
socket. Every action reports back `ok`/`failed` with a reason, and safety reflexes handle what can't wait (lava,
drowning, dragon breath).

```
Minecraft ── Fabric mod (Java) ──socket/JSON── companion (Python) ──API── Claude / ChatGPT / Gemini
```

- Only the host needs the mod; other LAN players join with vanilla clients
- The mod starts the companion process by itself; you only need Python installed
- The mod never calls the API and never stores your key; only the companion does
- Optional voice output, live health/hunger above the bot, token cost shown in the XP bar
- Meant for singleplayer, LAN and local test worlds only, not for public servers

> **Work in progress:** SmartCraft is under active development. Expect rough edges, breaking changes and an AI
> that still dies more often than it should. The bot has defeated the Ender Dragon in test runs, but a full
> playthrough from a fresh world is not reliable yet. Feedback and bug reports are welcome.

> **Tested with Claude Haiku 5.5 only.** All test runs, the system prompt and the tuning of the actions were done
> with Claude Haiku 5.5. ChatGPT and Gemini are supported, but have not been tried in a real game yet; expect them
> to play worse until they are tested and tuned.

> **Note:** The in-game messages, the AI's system prompt and most of the developer docs are in German.

## Requirements

- Minecraft Java Edition 26.2 with Fabric Loader 0.19.5+ and Fabric API
- Python 3.11 or newer (from [python.org](https://www.python.org/downloads/); on Windows tick "Add python.exe to PATH")
- An API key from one of the providers:
  [Anthropic](https://console.anthropic.com/) (recommended, tested),
  [OpenAI](https://platform.openai.com/api-keys) or
  [Google AI Studio](https://aistudio.google.com/apikey)
- Java 25 only if you want to build the mod yourself

## Getting started

1. Put `smartcraft-<version>.jar` and Fabric API into your `mods` folder and start the game.
2. Open a world (singleplayer works; "Open to LAN" lets friends join too).
3. In chat:

   ```
   /smartcraft join                 # the AI joins and the mod starts the companion process
   /smartcraft apikey sk-ant-...    # set your API key once; it is remembered
   /smartcraft goal Build a stone pickaxe
   ```

The first start takes about a minute: the mod unpacks the companion to `config/smartcraft/`, creates its own Python
environment there (`config/smartcraft/venv`) and installs the `anthropic` package into it (plus `openai` or
`google-genai` when you switch to those providers). Your system Python is not
changed. Output goes to `logs/smartcraft/companion.log`.

You can also just write to the AI in chat. It answers there and does what you ask.

## Commands

| Command | What it does |
|---|---|
| `/smartcraft join [name]` / `leave` | Let the AI join or leave (leaving also stops the companion) |
| `/smartcraft goal <text>` | Give the AI a goal |
| `/smartcraft pause` / `resume` / `stop` | Pause, resume or end the current goal |
| `/smartcraft apikey <key>` | Set an API key; the provider is detected from the key (`sk-ant-`, `sk-`, `AIza`) |
| `/smartcraft apikey anthropic\|openai\|gemini <key>` | Set the key for a specific provider |
| `/smartcraft provider anthropic\|openai\|gemini` | Choose who plays (default: anthropic) |
| `/smartcraft model [<name>\|default]` | Show or change the model; `default` goes back to the provider's default |
| `/smartcraft companion start\|stop\|status` | Control the companion process by hand |
| `/smartcraft companion autostart on\|off` | Start the companion on `join` (default: on) |
| `/smartcraft info` | Show all settings |
| `/smartcraft stats on\|off` | Hearts and hunger above the bot |
| `/smartcraft xpbar on\|off` | Token usage and cost in your XP bar |
| `/smartcraft inventar` | Open the bot's inventory |
| `/smartcraft tts on\|off` | Voice output on the host |
| `/smartcraft humor light\|medium\|hard\|crazy` | How funny the AI tries to be |
| `/smartcraft talkmode light\|medium\|hard` | How often the AI talks in chat |
| `/smartcraft locate on\|off` | Allow the AI to look up structures (a cheat, off by default) |
| `/smartcraft dimensionfollow on\|off` | Bot follows you through portals |
| `/smartcraft streamer [off]` | Preset for streaming |

The full list (including test helpers) is in [CLAUDE.md](CLAUDE.md).

## Providers and models

| Provider | Default model | Price per 1M tokens (in / out) | Key |
|---|---|---|---|
| Anthropic (Claude) | `claude-haiku-5-5` | $0.10 / $0.50 | `sk-ant-…` |
| OpenAI (ChatGPT) | `gpt-6-luna` | $0.10 / $0.50 | `sk-…` |
| Google (Gemini) | `gemini-3.8-flash` | $0.75 / $3.75 (intro price until Dec 31, 2026) | `AIza…` |

Prices as listed by the providers on Nov 23, 2026. Switch with `/smartcraft provider openai`; any other model of
that provider works with `/smartcraft model <name>`. Only Claude uses prompt caching tuned for this project, so the
other providers may cost more per step.

## Costs

Every step of the AI is an API call and costs money. By default there is no limit; set one with the environment
variable `SMARTCRAFT_COST_LIMIT` (in dollars per goal). `/smartcraft xpbar on` shows tokens and cost live in your
XP bar. Provider and model can also be preset with `SMARTCRAFT_PROVIDER` and `SMARTCRAFT_MODEL`.

## Your API key

`/smartcraft apikey` passes the key to the companion, which stores it in `config/smartcraft/companion/`
(`api_key.txt`, `openai_key.txt`, `gemini_key.txt`; in a development checkout in `companion/`, git-ignored). Logs
and chat only ever show `***`. Alternatively set `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` or `GEMINI_API_KEY`. Never
commit or share a key.

## Building and developing

```bash
./gradlew build          # build the mod (jar in build/libs/)
./gradlew runClient      # start the game with the mod
./gradlew test           # unit tests
python tests/test_providers.py   # OpenAI/Gemini translation without API calls (needs openai, google-genai)
```

In a development checkout the mod uses the `companion/` folder directly and the Python in which `anthropic` is
already installed (`pip install -r companion/requirements.txt`). You can still run the companion by hand with
`python companion/agent.py`; automated server tests are in [tests/server/](tests/server/) and start their own
companion (`SMARTCRAFT_COMPANION=off` keeps the mod from starting one).

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) – structure, events, commands, reflexes (German)
- [companion/README.md](companion/README.md) – companion process, console, voice output (German)
- [schemas/](schemas/) – JSON schemas of all events and commands

## License

MIT, see [LICENSE](LICENSE).

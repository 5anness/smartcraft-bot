"""Test der Sprachausgabe ohne Abspielen (companion/speech.py).

Prüft Systemsprache, Wahl der Piper-Stimme (vorhandene zuerst, beste Qualität, sonst Standard der Sprache) und dass
das Vorbereiten nur einmal startet. Mit `--download` zusätzlich echt: piper-tts nachinstallieren (nur in einer venv),
Stimme der Sprache herunterladen und einen Satz erzeugen (ohne Abspielen).

Aufruf:  python tests/test_speech.py [--download]
"""

import os
import shutil
import sys
import tempfile
import time
import wave
from io import BytesIO

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(PROJECT, "companion"))

import speech  # noqa: E402

failures = []


def check(name, passed, detail=""):
    print(("OK   " if passed else "FAIL ") + name + (f"  {detail}" if detail and not passed else ""))
    if not passed:
        failures.append(name)


def touch(path):
    open(path, "w").close()


def main():
    language = speech.system_language()
    check("Systemsprache erkannt", len(language) >= 2 and language[:2].isalpha(), language)
    print(f"     Systemsprache: {language}")
    os.environ["SMARTCRAFT_TTS_LANG"] = "fr-FR"
    check("SMARTCRAFT_TTS_LANG hat Vorrang", speech.system_language() == "fr_FR", speech.system_language())
    del os.environ["SMARTCRAFT_TTS_LANG"]

    voices = tempfile.mkdtemp()
    speech.VOICES_DIR = voices
    check("Deutsch ohne Stimme: Standard zum Laden", speech.piper_voice_for("de_DE") == ("de_DE-thorsten-medium", False))
    check("Sprache ohne Piper-Stimme: Systemstimme", speech.piper_voice_for("ja_JP") == (None, False))
    touch(os.path.join(voices, "de_DE-thorsten-medium.onnx"))
    touch(os.path.join(voices, "de_DE-thorsten-high.onnx"))
    check("vorhandene Stimme, beste Qualität zuerst", speech.piper_voice_for("de_DE") == ("de_DE-thorsten-high", True))
    check("vorhandene Stimme passt auch zu de_AT", speech.piper_voice_for("de_AT")[0] == "de_DE-thorsten-high")
    os.environ["SMARTCRAFT_TTS_PIPER_VOICE"] = "en_GB-alan-low"
    check("SMARTCRAFT_TTS_PIPER_VOICE hat Vorrang", speech.piper_voice_for("de_DE") == ("en_GB-alan-low", False))
    del os.environ["SMARTCRAFT_TTS_PIPER_VOICE"]

    started = []
    speaker = speech.Speaker.__new__(speech.Speaker)
    speaker.queue, speaker.prepared, speaker.enabled = speech.queue.Queue(), False, False
    speaker.lock = speech.threading.Lock()
    speaker._prepare_piper = lambda: started.append(1)
    speaker.set_enabled(True)
    speaker.set_enabled(False)
    speaker.set_enabled(True)
    time.sleep(0.3)
    check("Vorbereiten startet nur beim ersten Einschalten", len(started) == 1, str(len(started)))
    speaker.say("eins")
    speaker.say("zwei")
    check("nur die neueste Nachricht wartet", speaker.queue.qsize() == 1 and speaker.queue.get() == "zwei")
    shutil.rmtree(voices, ignore_errors=True)

    # Chat in der Systemsprache: Hinweis an das Modell in der Eröffnung jedes Ziels.
    os.environ.setdefault("ANTHROPIC_API_KEY", "test-kein-echter-schluessel")
    import agent
    check("Chat: Deutsch ohne Sprachhinweis", agent.language_text("de_DE") == "" and agent.language_text("de_AT") == "")
    check("Chat: Englisch mit Hinweis", "auf Englisch" in agent.language_text("en_US")
          and "en_US" in agent.language_text("en_US"))
    check("Chat: unbekannte Sprache mit Code", "Code ja_JP" in agent.language_text("ja_JP") or
          "Japanisch" in agent.language_text("ja_JP"))
    bot = agent.Agent.__new__(agent.Agent)
    bot.humor, bot.talkmode = "medium", "light"
    agent.SPEAKER.language = "fr_FR"
    check("Chat: Einstellungs-Hinweis nennt die Sprache", "auf Französisch" in bot.settings_text()
          and bot.settings_text().startswith("[Einstellung] Humor MEDIUM"))
    agent.SPEAKER.language = "de_DE"
    check("Chat: auf Deutsch bleibt der Hinweis wie bisher", "Sprache:" not in bot.settings_text())

    if "--download" in sys.argv:
        voices = tempfile.mkdtemp()
        speech.VOICES_DIR = voices
        speaker = speech.Speaker.__new__(speech.Speaker)
        speaker.piper, speaker.language = None, language
        began = time.time()
        speaker._prepare_piper()
        name = speech.piper_voice_for(language)[0]
        check(f"piper-tts da und Stimme {name} geladen ({time.time() - began:.0f} s)",
              speaker.piper is not None and os.path.exists(os.path.join(voices, name + ".onnx")))
        if speaker.piper is not None:
            buffer = BytesIO()
            with wave.open(buffer, "wb") as wav:
                speaker.piper.synthesize_wav("Hallo, ich bin dein Minecraft-Begleiter.", wav)
            with wave.open(BytesIO(buffer.getvalue())) as wav:
                seconds = wav.getnframes() / wav.getframerate()
            check("Satz erzeugt (WAV, nicht abgespielt)", seconds > 0.5, f"{seconds:.1f} s")
        shutil.rmtree(voices, ignore_errors=True)

    print(f"\n{'Alle Prüfungen bestanden' if not failures else str(len(failures)) + ' Prüfung(en) gescheitert'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

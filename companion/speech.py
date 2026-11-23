"""Sprachausgabe (TTS): Was der Bot im Chat schreibt, liest der Begleitprozess auch vor.

Die Sprache folgt der Systemsprache (Windows: Anzeigesprache, macOS: AppleLocale, Linux: LANG), überschreibbar mit
SMARTCRAFT_TTS_LANG (z. B. `en_US`).

Stimme, in dieser Reihenfolge:
1. Piper (github.com/OHF-Voice/piper1-gpl): neuronale Stimme, lokal auf der CPU. Modelle liegen in `voices/` neben
   dieser Datei. Gibt es dort schon eine Stimme der Sprache (z. B. de_DE-thorsten-high), nimmt er die. Sonst lädt er
   beim ersten Einschalten die mittlere Stimme der Sprache (ca. 60 MB, einmalig) und installiert `piper-tts`, falls es
   fehlt – aber nur in einer eigenen Umgebung (venv), nie ins System-Python.
2. Bis dahin oder ohne Internet: die Systemstimme (Windows: System.Speech per PowerShell, macOS: `say`,
   Linux: `espeak-ng`/`espeak`), jeweils in der Systemsprache, soweit installiert.

Einstellungen: SMARTCRAFT_TTS (`1` schaltet beim Start ein), SMARTCRAFT_TTS_LANG, SMARTCRAFT_TTS_PIPER_VOICE (Name
eines Piper-Modells, z. B. en_US-lessac-medium), SMARTCRAFT_TTS_RATE (-10 bis 10) und SMARTCRAFT_TTS_VOICE (Teil des
Stimmennamens) für Windows.
"""

import glob
import locale
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import wave
from io import BytesIO

HERE = os.path.dirname(os.path.abspath(__file__))
VOICES_DIR = os.path.join(HERE, "voices")
RATE = os.environ.get("SMARTCRAFT_TTS_RATE", "3")
VOICE = os.environ.get("SMARTCRAFT_TTS_VOICE", "")
# Nur die neueste Nachricht wartet: Kommt eine neue, fällt die wartende weg. Die Stimme bleibt nah am
# Geschehen, statt alten Sätzen hinterherzureden; ein angefangener Satz läuft zu Ende.
MAX_QUEUED = 1

# Piper-Stimme je Sprache (mittlere Qualität, ca. 60 MB), aus rhasspy/piper-voices. Andere Sprachen: Systemstimme.
PIPER_VOICES = {
    "de": "de_DE-thorsten-medium",
    "en": "en_US-lessac-medium",
    "fr": "fr_FR-siwis-medium",
    "es": "es_ES-davefx-medium",
    "it": "it_IT-paola-medium",
    "pl": "pl_PL-darkman-medium",
    "pt": "pt_BR-faber-medium",
    "ru": "ru_RU-denis-medium",
}

# Windows: Der Text kommt über stdin, nie über die Befehlszeile – so kann er nichts ausführen.
WINDOWS_SCRIPT = r"""
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$want = $env:SMARTCRAFT_TTS_VOICE
$lang = $env:SMARTCRAFT_TTS_CULTURE
$voices = $s.GetInstalledVoices() | Where-Object { $_.Enabled } | ForEach-Object { $_.VoiceInfo }
$v = $null
if ($want) { $v = $voices | Where-Object { $_.Name -like "*$want*" } | Select-Object -First 1 }
if (-not $v) { $v = $voices | Where-Object { $_.Culture.Name -like "$lang*" } | Select-Object -First 1 }
if ($v) { $s.SelectVoice($v.Name) }
$s.Rate = [int]$env:SMARTCRAFT_TTS_RATE
$in = [Console]::In
while ($null -ne ($line = $in.ReadLine())) { if ($line.Trim()) { $s.Speak($line) } }
"""


def system_language():
    """Systemsprache als `de_DE`, `en_US` …; SMARTCRAFT_TTS_LANG hat Vorrang."""
    wanted = os.environ.get("SMARTCRAFT_TTS_LANG", "").strip()
    if wanted:
        return wanted.replace("-", "_")
    found = None
    try:
        if sys.platform == "win32":
            import ctypes
            found = locale.windows_locale.get(ctypes.windll.kernel32.GetUserDefaultUILanguage())
        elif sys.platform == "darwin":
            found = subprocess.run(["defaults", "read", "-g", "AppleLocale"], capture_output=True, text=True,
                                   timeout=5).stdout.strip()
    except (OSError, AttributeError, subprocess.SubprocessError):
        found = None
    if not found:
        for name in ("LC_ALL", "LC_MESSAGES", "LANG"):
            value = os.environ.get(name, "")
            if value and value not in ("C", "POSIX") and not value.startswith("C."):
                found = value
                break
    if not found:
        found = (locale.getlocale()[0] or "en_US")
    return found.split(".")[0].split("@")[0].replace("-", "_") or "en_US"


def piper_voice_for(language):
    """(Name, schon vorhanden) der Piper-Stimme: ausdrücklich gesetzt, sonst vorhandene der Sprache (beste Qualität
    zuerst), sonst die Standardstimme der Sprache zum Herunterladen; None, wenn es für die Sprache keine gibt."""
    explicit = os.environ.get("SMARTCRAFT_TTS_PIPER_VOICE", "").strip()
    if explicit:
        return explicit, os.path.exists(os.path.join(VOICES_DIR, explicit + ".onnx"))
    family = language.split("_")[0].lower()
    present = [os.path.basename(path)[:-5] for path in glob.glob(os.path.join(VOICES_DIR, f"{family}_*.onnx"))]
    if present:
        order = {"high": 0, "medium": 1, "low": 2, "x_low": 3}
        exact = [name for name in present if name.startswith(language)]
        present = sorted(exact or present, key=lambda name: order.get(name.rsplit("-", 1)[-1], 9))
        return present[0], True
    if family in PIPER_VOICES:
        return PIPER_VOICES[family], False
    return None, False


def in_own_environment():
    """Läuft in einer venv (der Mod legt eine an): nur dann installiert die Sprachausgabe Pakete nach."""
    return sys.prefix != getattr(sys, "base_prefix", sys.prefix)


class Speaker:
    def __init__(self):
        self.queue = queue.Queue()
        self.process = None
        self.piper = None
        self.language = system_language()
        self.prepared = False
        self.lock = threading.Lock()
        # Standardmäßig aus; SMARTCRAFT_TTS=1 schaltet sie beim Start ein, im Spiel /smartcraft tts on|off.
        self.enabled = False
        threading.Thread(target=self._run, daemon=True).start()
        self.set_enabled(os.environ.get("SMARTCRAFT_TTS", "0") == "1")

    def say(self, text):
        if not self.enabled or not text:
            return
        while self.queue.qsize() >= MAX_QUEUED:
            try:
                self.queue.get_nowait()
            except queue.Empty:
                break
        self.queue.put(" ".join(str(text).split()))

    def set_enabled(self, enabled):
        self.enabled = enabled
        if enabled:
            # Beim ersten Einschalten Piper und die Stimme vorbereiten (im Hintergrund; bis dahin die Systemstimme).
            with self.lock:
                start = not self.prepared
                self.prepared = True
            if start:
                threading.Thread(target=self._prepare_piper, daemon=True).start()
            return
        # Was noch wartet, verwerfen; ein angefangener Satz läuft zu Ende.
        while not self.queue.empty():
            try:
                self.queue.get_nowait()
            except queue.Empty:
                break

    # --- Piper

    def _prepare_piper(self):
        name, present = piper_voice_for(self.language)
        if name is None:
            print(f"  [Sprachausgabe: keine Piper-Stimme für {self.language}, nehme die Systemstimme]", flush=True)
            return
        try:
            import piper  # noqa: F401
        except ImportError:
            if not in_own_environment():
                print("  [Sprachausgabe: piper-tts fehlt (pip install piper-tts), nehme die Systemstimme]", flush=True)
                return
            print("  [Sprachausgabe: installiere piper-tts …]", flush=True)
            done = subprocess.run([sys.executable, "-m", "pip", "install", "-q", "piper-tts"],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if done.returncode != 0:
                print("  [Sprachausgabe: piper-tts ließ sich nicht installieren, nehme die Systemstimme]", flush=True)
                return
        model = os.path.join(VOICES_DIR, name + ".onnx")
        if not present:
            print(f"  [Sprachausgabe: lade die Stimme {name} (einmalig, ca. 60 MB) …]", flush=True)
            try:
                from pathlib import Path
                from piper.download_voices import download_voice
                os.makedirs(VOICES_DIR, exist_ok=True)
                download_voice(name, Path(VOICES_DIR))
            except Exception as error:  # kein Netz, unbekannte Stimme: Systemstimme bleibt
                print(f"  [Sprachausgabe: Stimme nicht geladen ({type(error).__name__}), nehme die Systemstimme]",
                      flush=True)
                return
        try:
            from piper import PiperVoice
            self.piper = PiperVoice.load(model)
            print(f"  [Sprachausgabe: Piper-Stimme {name}]", flush=True)
        except Exception as error:  # Modell kaputt: Systemstimme statt Abbruch
            print(f"  [Sprachausgabe: Piper nicht verfügbar ({type(error).__name__}), nehme die Systemstimme]",
                  flush=True)

    def _speak_piper(self, text):
        buffer = BytesIO()
        with wave.open(buffer, "wb") as wav:
            self.piper.synthesize_wav(text, wav)
        data = buffer.getvalue()
        if sys.platform == "win32":
            import winsound
            winsound.PlaySound(data, winsound.SND_MEMORY)
            return
        player = (["afplay"] if sys.platform == "darwin" else
                  next(([tool] for tool in ("paplay", "aplay", "pw-play") if shutil.which(tool)), None))
        if not player:
            raise OSError("kein Abspielprogramm (paplay/aplay)")
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as file:
            file.write(data)
        try:
            subprocess.run(player + [file.name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        finally:
            os.unlink(file.name)

    # --- Systemstimme

    def _speak_system(self, text):
        if sys.platform == "win32":
            if self.process is None or self.process.poll() is not None:
                env = dict(os.environ, SMARTCRAFT_TTS_RATE=RATE, SMARTCRAFT_TTS_VOICE=VOICE,
                           SMARTCRAFT_TTS_CULTURE=self.language.replace("_", "-").split("-")[0])
                self.process = subprocess.Popen(
                    ["powershell", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_SCRIPT],
                    stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    env=env, encoding="utf-8", creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            self.process.stdin.write(text + "\n")
            self.process.stdin.flush()
            return
        if sys.platform == "darwin":
            # `say` ohne Text liest stdin; die Standardstimme folgt der Systemsprache.
            subprocess.run(["say"], input=text, text=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
        tool = next((name for name in ("espeak-ng", "espeak") if shutil.which(name)), None)
        if tool is None:
            raise OSError("keine Systemstimme (espeak-ng installieren)")
        subprocess.run([tool, "-v", self.language.split("_")[0].lower(), "--stdin"], input=text, text=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def _run(self):
        warned = False
        while True:
            text = self.queue.get()
            if self.piper:
                try:
                    self._speak_piper(text)
                    continue
                except Exception as error:
                    print(f"  [Piper-Fehler ({type(error).__name__}), nehme die Systemstimme]", flush=True)
                    self.piper = None
            try:
                self._speak_system(text)
            except OSError as error:
                self.process = None
                if not warned:
                    print(f"  [Sprachausgabe nicht möglich: {error}]", flush=True)
                    warned = True

"""Sprachausgabe (TTS): Was der Bot im Chat schreibt, liest der Begleitprozess auch vor.

Bevorzugt Piper (github.com/OHF-Voice/piper1-gpl, `pip install piper-tts`): neuronale Stimme, läuft
lokal auf der CPU. Das Stimmmodell liegt in `companion/voices/` (Standard `de_DE-thorsten-high`,
laden mit `python -m piper.download_voices <stimme>` in diesem Ordner). Abgespielt wird mit winsound.
Fehlen Piper oder das Modell, nimmt der Prozess die eingebaute Windows-Sprachausgabe (System.Speech
per PowerShell). Auf anderen Systemen bleibt der Bot stumm.
Einstellungen: SMARTCRAFT_TTS (`0` schaltet ab), SMARTCRAFT_TTS_PIPER_VOICE (Name des Piper-Modells),
SMARTCRAFT_TTS_RATE (-10 bis 10) und SMARTCRAFT_TTS_VOICE (Teil des Stimmennamens) für Windows.
"""

import os
import queue
import subprocess
import sys
import threading
import wave
from io import BytesIO

RATE = os.environ.get("SMARTCRAFT_TTS_RATE", "3")
VOICE = os.environ.get("SMARTCRAFT_TTS_VOICE", "")
PIPER_VOICE = os.environ.get("SMARTCRAFT_TTS_PIPER_VOICE", "de_DE-thorsten-high")
PIPER_MODEL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "voices", PIPER_VOICE + ".onnx")
# Nur die neueste Nachricht wartet: Kommt eine neue, fällt die wartende weg. Die Stimme bleibt nah am
# Geschehen, statt alten Sätzen hinterherzureden; ein angefangener Satz läuft zu Ende.
MAX_QUEUED = 1

# Der Text kommt über stdin, nie über die Befehlszeile – so kann er nichts ausführen.
SCRIPT = r"""
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$want = $env:SMARTCRAFT_TTS_VOICE
$voices = $s.GetInstalledVoices() | Where-Object { $_.Enabled } | ForEach-Object { $_.VoiceInfo }
$v = $null
if ($want) { $v = $voices | Where-Object { $_.Name -like "*$want*" } | Select-Object -First 1 }
if (-not $v) { $v = $voices | Where-Object { $_.Culture.Name -like 'de*' } | Select-Object -First 1 }
if ($v) { $s.SelectVoice($v.Name) }
$s.Rate = [int]$env:SMARTCRAFT_TTS_RATE
$in = [Console]::In
while ($null -ne ($line = $in.ReadLine())) { if ($line.Trim()) { $s.Speak($line) } }
"""


class Speaker:
    def __init__(self):
        self.queue = queue.Queue()
        self.process = None
        self.piper = None
        # Standardmäßig aus; SMARTCRAFT_TTS=1 schaltet sie beim Start ein, im Spiel /smartcraft tts on|off.
        self.enabled = os.environ.get("SMARTCRAFT_TTS", "0") == "1"
        if sys.platform == "win32":
            threading.Thread(target=self._run, daemon=True).start()

    def say(self, text):
        if not self.enabled or sys.platform != "win32" or not text:
            return
        while self.queue.qsize() >= MAX_QUEUED:
            try:
                self.queue.get_nowait()
            except queue.Empty:
                break
        self.queue.put(" ".join(str(text).split()))

    def set_enabled(self, enabled):
        self.enabled = enabled
        if not enabled:
            # Was noch wartet, verwerfen; ein angefangener Satz läuft zu Ende.
            while not self.queue.empty():
                try:
                    self.queue.get_nowait()
                except queue.Empty:
                    break

    def _start(self):
        env = dict(os.environ, SMARTCRAFT_TTS_RATE=RATE, SMARTCRAFT_TTS_VOICE=VOICE)
        self.process = subprocess.Popen(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", SCRIPT],
            stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            env=env, encoding="utf-8", creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

    def _load_piper(self):
        if not os.path.exists(PIPER_MODEL):
            return
        try:
            from piper import PiperVoice
            self.piper = PiperVoice.load(PIPER_MODEL)
        except Exception as error:  # Paket fehlt oder Modell kaputt: Windows-Stimme statt Abbruch
            print(f"Piper nicht verfügbar ({error}), nehme die Windows-Stimme.", flush=True)

    def _speak_piper(self, text):
        import winsound
        buffer = BytesIO()
        with wave.open(buffer, "wb") as wav:
            self.piper.synthesize_wav(text, wav)
        winsound.PlaySound(buffer.getvalue(), winsound.SND_MEMORY)

    def _run(self):
        self._load_piper()
        while True:
            text = self.queue.get()
            if self.piper:
                try:
                    self._speak_piper(text)
                    continue
                except Exception as error:
                    print(f"Piper-Fehler ({error}), nehme die Windows-Stimme.", flush=True)
                    self.piper = None
            try:
                if self.process is None or self.process.poll() is not None:
                    self._start()
                self.process.stdin.write(text + "\n")
                self.process.stdin.flush()
            except OSError as error:
                print(f"Sprachausgabe nicht möglich: {error}", flush=True)
                self.process = None

"""Servertest: /smartcraft streamermode on|off mit einem echten zweiten Spieler (Zuschauer-Fenster "Kamera").

Prüft: on stellt TTS, Humor, Talkmode, Statusanzeige, XP-Leiste, Events, Locate, Dimension Follow und Zuschauermodus um;
off stellt alles genau so wieder her, wie es vorher war (auch den Spielmodus). Öffnet ein Minecraft-Fenster.

Aufruf:  python tests/server/test_streamer.py
Ca. 3 Minuten.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Camera, Report, TestServer  # noqa: E402
from test_movement import BOT  # noqa: E402

PLAYER = "Kamera"
DISPLAY = "@e[type=text_display,tag=smartcraft_stats]"


def main():
    server = TestServer(os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-streamer.log"), camera=True)
    report = Report()
    camera = None
    try:
        server.console(f"smartcraft join {BOT}", wait=5)
        camera = Camera(server, BOT, spectator=PLAYER)
        camera.start()
        # Ausgangszustand: Kreativ, alles Mögliche anders als im Streamer-Modus.
        server.console(f"gamemode creative {PLAYER}", wait=0.5)
        server.console("smartcraft humor light", wait=0.2)
        server.console("smartcraft talkmode medium", wait=0.2)
        server.console("smartcraft tts off", wait=0.2)
        server.console("smartcraft stats off", wait=0.2)
        server.console("smartcraft events on", wait=0.2)
        server.console("smartcraft locate off", wait=0.5)
        before, _ = server.run("settings", {})

        mark = server.output_mark()
        server.console(f"execute as {PLAYER} run smartcraft streamermode on", wait=2)
        on, _ = server.run("settings", {})
        data = on["data"]
        report.check("on: TTS an, Humor CRAZY, Talkmode HARD, Streamer, Dimension Follow",
                     data.get("tts") is True and data.get("humor") == "crazy" and data.get("talkmode") == "hard"
                     and data.get("streamer") is True and data.get("dimension_follow") is True, str(data))
        report.check("on: Meldung nennt Statusanzeige und Zuschauer",
                     server.find_in_output("Statusanzeige und XP-Leiste an", mark) is not None
                     and server.find_in_output("du bist Zuschauer", mark) is not None, "")
        mark = server.output_mark()
        server.console(f"execute if entity {DISPLAY}", wait=1)
        report.check("on: Statusanzeige über dem Bot", server.find_in_output("Test passed", mark) is not None, "")
        mark = server.output_mark()
        server.console(f"execute if entity @a[name={PLAYER},gamemode=spectator]", wait=1)
        report.check("on: Spieler ist Zuschauer", server.find_in_output("Test passed", mark) is not None, "")
        mark = server.output_mark()
        server.console("smartcraft info", wait=1)
        report.check("on: Events aus, Locate an (info)",
                     server.find_in_output("Events (Chat-Anzeige): OFF", mark) is not None
                     and server.find_in_output("Locate: ON", mark) is not None, "")

        # Zweites "on" darf den Ausgangszustand nicht überschreiben.
        server.console(f"execute as {PLAYER} run smartcraft streamermode on", wait=1)

        mark = server.output_mark()
        server.console(f"execute as {PLAYER} run smartcraft streamermode off", wait=2)
        off, _ = server.run("settings", {})
        report.check("off: TTS, Humor, Talkmode, Dimension Follow wie vorher",
                     all(off["data"].get(key) == before["data"].get(key)
                         for key in ("tts", "humor", "talkmode", "dimension_follow", "streamer")),
                     f"vorher {before['data']}, nachher {off['data']}")
        mark2 = server.output_mark()
        server.console(f"execute if entity {DISPLAY}", wait=1)
        report.check("off: Statusanzeige wieder aus", server.find_in_output("Test passed", mark2) is None, "")
        mark2 = server.output_mark()
        server.console(f"execute if entity @a[name={PLAYER},gamemode=creative]", wait=1)
        report.check("off: Spieler wieder im Kreativmodus", server.find_in_output("Test passed", mark2) is not None, "")
        mark2 = server.output_mark()
        server.console("smartcraft info", wait=1)
        report.check("off: Events an, Locate aus (info)",
                     server.find_in_output("Events (Chat-Anzeige): ON", mark2) is not None
                     and server.find_in_output("Locate: OFF", mark2) is not None, "")
        mark2 = server.output_mark()
        server.console(f"execute as {PLAYER} run smartcraft streamermode off", wait=1)
        report.check("off zweimal: freundliche Meldung", server.find_in_output("schon aus", mark2) is not None, "")
        mark2 = server.output_mark()
        server.console("smartcraft streamermode on", wait=1)
        report.check("on aus der Konsole: Absage (braucht Spieler)",
                     server.find_in_output("braucht einen Spieler", mark2) is not None, "")
    finally:
        if camera is not None:
            camera.close()
        server.close()
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

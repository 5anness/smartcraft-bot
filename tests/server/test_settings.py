"""Servertest Einstellungen: /aiplayer info|humor|talkmode|tts|streamer, Befehl settings, shelter nachts bei voller Ausrüstung.

Aufruf:  python tests/server/test_settings.py   (ca. 1 Minute)
Dimension Follow und gamemode brauchen einen echten Spieler und sind hier nicht abgedeckt (nur Konsolen-Absage).
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer, summary  # noqa: E402

BOT = "Claude"


def main():
    server = TestServer(os.path.join(os.environ.get("TEMP", "."), "aiplayer-test-settings.log"))
    report = Report()
    try:
        server.console("gamerule spawn_mobs false")
        server.console(f"aiplayer join {BOT}", wait=5)

        # --- Befehl settings (Begleitprozess): Startwerte setzen, alle zurück.
        result, _ = server.run("settings", {"tts": True, "talkmode": "hard"})
        data = result.get("data", {})
        report.check("settings setzt tts und talkmode, liefert alle Werte",
                     result["status"] == "ok" and data.get("tts") is True and data.get("talkmode") == "hard"
                     and data.get("humor") == "medium" and "dimension_follow" in data, summary(result))
        result, _ = server.run("settings", {"humor": "gigantisch"})
        report.check("settings mit unbekannter Stufe: invalid_args",
                     result["status"] == "failed" and result.get("reason") == "invalid_args", summary(result))

        # --- Konsolenbefehle: humor/talkmode wirken auf den Zustand.
        server.console("aiplayer humor crazy")
        server.console("aiplayer talkmode light")
        server.console("aiplayer tts off", wait=0.5)
        result, _ = server.run("settings", {})
        data = result.get("data", {})
        report.check("/aiplayer humor crazy, talkmode light, tts off",
                     data.get("humor") == "crazy" and data.get("talkmode") == "light" and data.get("tts") is False,
                     summary(result))
        mark = server.output_mark()
        server.console("aiplayer info", wait=1)
        report.check("/aiplayer info zeigt Humor und Talkmode",
                     server.find_in_output("Humor: CRAZY", mark) is not None
                     and server.find_in_output("Talkmode: LIGHT", mark) is not None, "")
        mark = server.output_mark()
        server.console("aiplayer streamer", wait=1)
        report.check("/aiplayer streamer aus der Konsole: verständliche Absage",
                     server.find_in_output("braucht einen Spieler", mark) is not None, "")

        # --- shelter nachts: voll ausgerüstet lehnt ab, mit force nicht, ohne Rüstung nicht.
        server.console("time set midnight")
        server.console(f"clear {BOT}")
        for item in ("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "iron_sword", "cobblestone 32"):
            server.console(f"give {BOT} {item}", wait=0.1)
        server.drain(0.5)
        server.run("equip_best_armor", timeout=20)
        result, _ = server.run("shelter", timeout=60)
        report.check("shelter nachts mit Eisenrüstung und Schwert: well_equipped",
                     result["status"] == "failed" and result.get("reason") == "well_equipped", summary(result))
        result, _ = server.run("shelter", {"force": True}, timeout=60)
        report.check("shelter mit force: baut trotzdem",
                     result["status"] == "ok", summary(result))
    finally:
        server.close()
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

"""Servertest: /smartcraft stats on|off zeigt Leben und Hunger über dem Bot (Textanzeige als Mitfahrer).

Prüft: Anzeige sitzt auf dem Bot, ist durch Blöcke sichtbar und voll hell, sitzt nach Tod/Respawn und Teleport
wieder auf, verschwindet mit stats off.

Aufruf:  python tests/server/test_stats.py
Ca. 45 Sekunden.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer  # noqa: E402
from test_movement import BOT  # noqa: E402

DISPLAY = "@e[type=text_display,tag=smartcraft_stats]"
ONE = "@e[type=text_display,tag=smartcraft_stats,limit=1]"


def count(server):
    mark = server.output_mark()
    server.console(f"execute if entity {DISPLAY}", wait=1)
    line = server.find_in_output("Test passed", mark)
    if line is None:
        return 0
    return int(line.split("Count:")[-1].strip()) if "Count:" in line else 1


def data(server, target, path):
    mark = server.output_mark()
    server.console(f"data get entity {target} {path}", wait=1)
    line = server.find_in_output("has the following entity data:", mark)
    return line.split("entity data:", 1)[1].strip() if line else ""


def position(server, target):
    raw = data(server, target, "Pos").strip("[]")
    return [float(v.strip().rstrip("d")) for v in raw.split(",")] if raw else None


def riding(server):
    """Mitfahrer sitzen am Kopfpunkt: gleiche x/z wie der Bot, 1,8 höher."""
    bot, shown = position(server, BOT), position(server, ONE)
    return bool(bot and shown) and abs(bot[0] - shown[0]) < 0.01 and abs(bot[2] - shown[2]) < 0.01 \
        and abs(shown[1] - bot[1] - 1.8) < 0.01


def main():
    server = TestServer(os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-stats.log"))
    report = Report()
    try:
        server.console(f"smartcraft join {BOT}", wait=5)
        server.console("smartcraft stats on", wait=1.5)
        report.check("stats on: genau eine Anzeige, sitzt auf dem Bot", count(server) == 1 and riding(server),
                     f"{count(server)} Anzeigen")
        report.check("Anzeige durch Blöcke sichtbar (see_through)",
                     data(server, ONE, "see_through") == "1b", "")
        report.check("Anzeige voll hell (brightness 15/15)",
                     data(server, ONE, "brightness") == "{sky: 15, block: 15}", "")

        server.console(f"kill {BOT}", wait=4)
        report.check("nach Tod und Respawn: wieder eine Anzeige auf dem Bot",
                     count(server) == 1 and riding(server), f"{count(server)} Anzeigen")

        server.console(f"tp {BOT} ~40 ~ ~", wait=2)
        report.check("nach Teleport: Anzeige sitzt auf", count(server) == 1 and riding(server),
                     f"{count(server)} Anzeigen")

        server.console("smartcraft stats off", wait=1)
        report.check("stats off: Anzeige entfernt", count(server) == 0, "")
    finally:
        server.close()
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

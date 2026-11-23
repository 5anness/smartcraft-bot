"""Servertest: /aiplayer stats on|off zeigt Leben und Hunger unter dem Namen (Anzeigetafel below_name).

Aufruf:  python tests/server/test_stats.py
Ca. 30 Sekunden.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from harness import Report, TestServer  # noqa: E402
from test_movement import BOT  # noqa: E402


def main():
    server = TestServer(os.path.join(os.environ.get("TEMP", "."), "aiplayer-test-stats.log"))
    report = Report()
    try:
        server.console(f"aiplayer join {BOT}", wait=5)
        server.console("aiplayer stats on", wait=1)
        server.console(f"damage {BOT} 5", wait=1.5)
        mark = server.output_mark()
        health = server.look()["health"]
        server.console(f"execute if score {BOT} aiplayer_stats matches {round(health)}", wait=1)
        report.check("stats on: Eintrag des Bots = Leben", server.find_in_output("Test passed", mark) is not None,
                     f"Leben {health}")
        server.console("aiplayer stats off", wait=1)
        mark = server.output_mark()
        server.console(f"execute if score {BOT} aiplayer_stats matches 0..", wait=1)
        report.check("stats off: Ziel entfernt", server.find_in_output("Test passed", mark) is None, "")
    finally:
        server.close()
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())

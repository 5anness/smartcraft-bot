"""Ausrüsten und Werkzeug-Warnung, so wie man es in der Konsole eintippt.

Aufruf:  python tests/server/test_equip.py
Die Befehle laufen durch dieselbe Kurzform-Zerlegung wie in companion/console.py.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "companion"))
from console import parse_input  # noqa: E402
from harness import Report, TestServer, summary  # noqa: E402
from test_movement import BOT, STAND, arena  # noqa: E402


def main():
    log = os.path.join(os.environ.get("TEMP", "."), "smartcraft-test-equip.log")
    server = TestServer(log)
    report = Report()
    try:
        run(server, report)
    finally:
        server.close()
    print(f"Serverlog: {log}")
    return report.finish()


def typed(server, text, timeout=120):
    """Führt eine Konsolenzeile aus und gibt Ergebnis und Events zurück."""
    name, args = parse_input(text)
    return server.run(name, args, timeout=timeout)


def slot_item(result, slot):
    return str(result["data"].get("worn", {}).get(slot))


def run(server, report):
    server.console("gamerule spawn_mobs false")
    server.console("smartcraft events off")
    server.console(f"smartcraft join {BOT}", wait=5)
    arena(server, 300, 200)
    server.console(f"tp {BOT} 300.5 {STAND} 200.5", wait=0.8)
    server.console(f"clear {BOT}")
    server.console(f"give {BOT} stone_pickaxe")
    server.console(f"give {BOT} iron_helmet")
    server.console(f"give {BOT} dirt 5", wait=0.4)
    server.drain(0.3)

    result, _ = typed(server, "equip stone_pickaxe")
    report.check("equip stone_pickaxe: in der Hand",
                 result["status"] == "ok" and result["data"]["held_item"]["id"] == "minecraft:stone_pickaxe",
                 summary(result))
    result, _ = typed(server, "equip iron_helmet kopf")
    report.check("equip iron_helmet kopf: ok", result["status"] == "ok", summary(result))
    result, _ = typed(server, "inventory")
    report.check("Helm sitzt danach auf dem Kopf", "iron_helmet" in slot_item(result, "head"), summary(result))
    result, _ = typed(server, "equip minecraft:dirt")
    report.check("equip mit minecraft:-Vorsatz: ok", result["status"] == "ok", summary(result))
    result, _ = typed(server, "equip diamond_sword")
    report.check("equip ohne das Item: missing_item",
                 result["status"] == "failed" and result.get("reason") == "missing_item", summary(result))

    # Fast kaputte Spitzhacke: 131 Haltbarkeit, Warnung ab 13. Start bei 15, also beim zweiten Stein.
    server.console(f"clear {BOT}")
    server.console(f"give {BOT} stone_pickaxe[damage=116]", wait=0.4)
    for i in range(4):
        server.console(f"setblock {302 + i} {STAND} 200 stone")
    server.drain(0.5)
    warnings = []
    for i in range(4):
        result, events = typed(server, f"mine {302 + i} {STAND} 200")
        warnings += [e for e in events if e["name"] == "tool_low_durability"]
        if result["status"] != "ok":
            report.check(f"mine Stein {i + 1}", False, summary(result))
    warnings += [e for e in server.drain(1.0) if e["name"] == "tool_low_durability"]
    report.check("tool_low_durability kommt beim Abbauen genau einmal",
                 len(warnings) == 1, str([w["data"] for w in warnings]))

    # Spitzhacke, die schon beim Bekommen fast kaputt ist und erst durch mine in die Hand kommt.
    server.console(f"clear {BOT}")
    server.console(f"give {BOT} dirt 3")
    server.console(f"give {BOT} stone_pickaxe[damage=125]", wait=0.4)
    server.console(f"setblock 307 {STAND} 200 stone")
    typed(server, "equip dirt")
    server.drain(0.8)
    result, events = typed(server, f"mine 307 {STAND} 200")
    events += server.drain(1.0)
    report.check("Schon fast kaputtes Werkzeug: Warnung, sobald mine es in die Hand nimmt",
                 result["status"] == "ok" and sum(e["name"] == "tool_low_durability" for e in events) == 1,
                 summary(result))


if __name__ == "__main__":
    sys.exit(main())

"""Findet Leerlauf in einem Lauf: wo der Bot Sekunden oder Minuten verliert.

Aufruf:  python tests/server/analyze_stalls.py <companion/logs/....jsonl> [Mindestsekunden, Standard 20]

Meldet:
- Befehle, die lange liefen und nichts brachten (failed/cancelled oder ok ohne Fortschritt),
- wait-Befehle und Pausen zwischen zwei Schritten (Modellaufruf, Leerlauf),
- Serien gleicher Befehle, die hintereinander scheitern (im Kreis),
und am Ende eine Summe je Ursache. Auftrag von Hannes (29.10.2026): nach jedem Gesamtlauf Leerlauf finden und beheben.
"""

import json
import sys
from collections import defaultdict


def main():
    path = sys.argv[1]
    minimum = float(sys.argv[2]) if len(sys.argv) > 2 else 20.0
    rows = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    tools = [r for r in rows if r.get("kind") == "tool"]
    totals = defaultdict(float)
    previous_end = None
    streak = []

    def flush_streak():
        if len(streak) >= 3:
            first, last = streak[0], streak[-1]
            print(f"IM KREIS  {len(streak)}x {first['name']} gescheitert ({first['reason']}), Schritt {first['step']}–{last['step']}, "
                  f"{last['t'] - first['t0']:.0f} s")
            totals["im Kreis"] += last["t"] - first["t0"]
        streak.clear()

    for i, row in enumerate(tools):
        t_end = row["t"]
        # Dauer des Befehls: bis zum vorigen Ergebnis zurück (der Start steht nicht im Log, die Lücke enthält den Modellaufruf).
        t_start = previous_end if previous_end is not None else t_end
        duration = t_end - t_start
        try:
            result = json.loads(row["result"])
        except ValueError:
            result = {}
        status = result.get("status")
        reason = result.get("reason", "")
        name = row["name"]
        if name == "wait":
            waited = result.get("waited_seconds", 0) or 0
            if waited >= minimum:
                print(f"WARTEN    Schritt {row['step']}: {waited:.0f} s ({result.get('woke_because', 'Zeit um')})")
                totals["wait"] += waited
        elif duration >= minimum and status != "ok":
            print(f"LEERLAUF  Schritt {row['step']} {name} {json.dumps(row['input'], ensure_ascii=False)[:80]}: "
                  f"{duration:.0f} s -> {status} {reason}")
            totals[f"{name} {status} {reason}".strip()] += duration
        if status == "failed" and streak and streak[-1]["name"] == name and streak[-1]["reason"] == reason:
            streak.append({"name": name, "reason": reason, "step": row["step"], "t": t_end, "t0": streak[0]["t0"]})
        elif status == "failed":
            flush_streak()
            streak.append({"name": name, "reason": reason, "step": row["step"], "t": t_end, "t0": t_start})
        else:
            flush_streak()
        previous_end = t_end
    flush_streak()

    models = [r for r in rows if r.get("kind") == "model"]
    if rows:
        total = rows[-1]["t"] - rows[0]["t"]
        print(f"\nGesamtdauer {total / 60:.1f} min, {len(tools)} Befehle, {len(models)} Modellaufrufe")
    for cause, seconds in sorted(totals.items(), key=lambda item: -item[1]):
        print(f"  {seconds:6.0f} s  {cause}")


if __name__ == "__main__":
    main()

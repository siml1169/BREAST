import sqlite3, sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
DB_PATH = Path(__file__).resolve().parents[2] / 'data' / 'telemetry' / 'today_telemetry.db'
conn = sqlite3.connect(DB_PATH)
cursor = conn.cursor()
cursor.execute("SELECT timestamp_iso, outdoor_temp, supply_temp, return_temp, modulation_pct, demand FROM telemetry_snapshots WHERE timestamp_iso LIKE '2026-10-28%' ORDER BY timestamp_iso ASC")
rows = cursor.fetchall()
print(f"Total rows today: {len(rows)}")
for r in rows:
    mod = r[4]
    demand = r[5]
    if mod is not None and mod > 0:
        dt = (r[2] - r[3]) if (r[2] is not None and r[3] is not None) else None
        dt_str = f"{dt:.1f}K" if dt is not None else "?"
        print(f"{r[0]} | Out={r[1]}°C | Sup={r[2]}°C | Ret={r[3]}°C | ΔT={dt_str} | Mod={mod}% | Demand={demand}")

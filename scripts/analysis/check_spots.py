import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).resolve().parents[2] / 'data' / 'telemetry' / 'today_telemetry.db'

conn = sqlite3.connect(DB_PATH)
c = conn.cursor()
c.execute("SELECT MIN(spot_net_ct), MAX(spot_net_ct), AVG(spot_net_ct) FROM telemetry_snapshots WHERE timestamp_iso LIKE '2026-10-28%';")
print('Today DB spot net ct (min, max, avg):', c.fetchone())

c.execute("SELECT timestamp_iso, spot_gross_ct, spot_net_ct, outdoor_temp FROM telemetry_snapshots WHERE timestamp_iso LIKE '2026-10-28%' AND spot_gross_ct IS NOT NULL ORDER BY epoch_seconds ASC;")
rows = c.fetchall()
print(f"Recorded spot price rows: {len(rows)}")
for r in rows[::5]:
    print(r)

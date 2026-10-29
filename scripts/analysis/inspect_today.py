import sqlite3
from datetime import datetime
from pathlib import Path

DB_PATH = Path(__file__).resolve().parents[2] / 'data' / 'telemetry' / 'today_telemetry.db'

conn = sqlite3.connect(DB_PATH)
cursor = conn.cursor()

cursor.execute("SELECT name FROM sqlite_master WHERE type='table';")
tables = [r[0] for r in cursor.fetchall()]
print("Tables:", tables)

for t in tables:
    cursor.execute(f"SELECT COUNT(*) FROM {t};")
    print(f"  {t}: {cursor.fetchone()[0]} rows")

if 'telemetry_snapshots' in tables:
    cursor.execute("SELECT MIN(timestamp_iso), MAX(timestamp_iso) FROM telemetry_snapshots;")
    print("Telemetry range:", cursor.fetchone())
    
    cursor.execute("SELECT * FROM telemetry_snapshots WHERE timestamp_iso LIKE '2026-10-28%' ORDER BY epoch_seconds ASC;")
    rows = cursor.fetchall()
    print(f"Rows for 2026-10-28: {len(rows)}")
    
    cursor.execute("PRAGMA table_info(telemetry_snapshots);")
    cols = [c[1] for c in cursor.fetchall()]
    print("Columns:", cols)
    
    if rows:
        mod_idx = cols.index('modulation_pct') if 'modulation_pct' in cols else -1
        demand_idx = cols.index('demand') if 'demand' in cols else -1
        tout_idx = cols.index('outdoor_temp_c') if 'outdoor_temp_c' in cols else -1
        tin_idx = cols.index('indoor_temp_c') if 'indoor_temp_c' in cols else -1
        sup_idx = cols.index('supply_temp_c') if 'supply_temp_c' in cols else -1
        ret_idx = cols.index('return_temp_c') if 'return_temp_c' in cols else -1
        
        active_rows = [r for r in rows if r[mod_idx] is not None and r[mod_idx] > 0]
        print(f"Active compressor rows today: {len(active_rows)} / {len(rows)}")
        for r in rows[:5]:
            print("Sample early row:", r)
        for r in rows[-5:]:
            print("Sample recent row:", r)

import json
from pathlib import Path

DATA_FILE = Path(__file__).resolve().parents[2] / 'data' / 'pointt' / 'emon_recordings_detail.json'

with open(DATA_FILE) as f:
    data = json.load(f)

for k, v in data.items():
    endpoint = k.split('/resource')[-1]
    print(f"\n==========================================")
    print(f"ENDPOINT: {endpoint}")
    unit = v.get('unitOfMeasure', '')
    rec = v.get('recording', [])
    print(f"Unit: {unit}, Records count: {len(rec)}")
    
    non_zero = [r for r in rec if r.get('c', 0) > 0]
    total = sum(r.get('c', 0) for r in rec)
    print(f"Total: {total} {unit} across {len(non_zero)} active intervals")
    for r in non_zero:
        print(f"   {r}")

import json
from pathlib import Path

DATA_FILE = Path(__file__).resolve().parents[2] / 'data' / 'pointt' / 'emon_recordings_detail.json'

with open(DATA_FILE) as f:
    d = json.load(f)

for ep, label in [
    ('/emon/ch/outputProduced', 'CH Heat Produced (kWh_th)'),
    ('/emon/ch/compressor', 'CH Compressor Elec (kWh_el)'),
    ('/emon/dhw/outputProduced', 'DHW Heat Produced (kWh_th)'),
    ('/emon/dhw/compressor', 'DHW Compressor Elec (kWh_el)'),
    ('/emon/total/outputProduced', 'Total Heat Produced (kWh_th)'),
    ('/emon/total/compressor', 'Total Compressor Elec (kWh_el)')
]:
    for k in d:
        if ep in k:
            rec = [x for x in d[k].get('recording', []) if x.get('c') == 1]
            tot = sum(x.get('y', 0) for x in rec)
            hourly = [x.get('y', 0) for x in rec]
            print(f"{label:30}: Total = {tot:4.1f} kWh | Hourly (0..{len(rec)-1}): {hourly}")

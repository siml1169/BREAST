import json
from pathlib import Path

DATA_FILE = Path(__file__).resolve().parents[2] / 'data' / 'pointt' / 'emon_recordings_detail.json'

with open(DATA_FILE) as f:
    data = json.load(f)

def get_rec(url_fragment):
    for k, v in data.items():
        if url_fragment in k:
            return v.get('recording', [])
    return []

ch_out = get_rec('/emon/ch/outputProduced')
ch_el = get_rec('/emon/ch/compressor')
dhw_out = get_rec('/emon/dhw/outputProduced')
dhw_el = get_rec('/emon/dhw/compressor')
tot_out = get_rec('/emon/total/outputProduced')
tot_el = get_rec('/emon/total/compressor')

print("Hour | CH Heat (kWh_th) | CH Elec (kWh_el) | DHW Heat | DHW Elec | Tot Heat | Tot Elec | Hourly COP")
print("-----+------------------+------------------+----------+----------+----------+----------+-----------")

total_ch_heat = 0.0
total_ch_elec = 0.0
total_dhw_heat = 0.0
total_dhw_elec = 0.0
total_all_heat = 0.0
total_all_elec = 0.0

for h in range(min(24, len(tot_out))):
    c_out = ch_out[h].get('y', 0.0) if h < len(ch_out) else 0.0
    c_el = ch_el[h].get('y', 0.0) if h < len(ch_el) else 0.0
    d_out = dhw_out[h].get('y', 0.0) if h < len(dhw_out) else 0.0
    d_el = dhw_el[h].get('y', 0.0) if h < len(dhw_el) else 0.0
    t_out = tot_out[h].get('y', 0.0) if h < len(tot_out) else 0.0
    t_el = tot_el[h].get('y', 0.0) if h < len(tot_el) else 0.0
    
    total_ch_heat += c_out
    total_ch_elec += c_el
    total_dhw_heat += d_out
    total_dhw_elec += d_el
    total_all_heat += t_out
    total_all_elec += t_el
    
    cop_str = f"{t_out / t_el:.1f}" if t_el > 0 else "-"
    print(f"{h:02d}:00| {c_out:16.1f} | {c_el:16.1f} | {d_out:8.1f} | {d_el:8.1f} | {t_out:8.1f} | {t_el:8.1f} | {cop_str}")

print("-----+------------------+------------------+----------+----------+----------+----------+-----------")
print(f"SUM  | {total_ch_heat:16.1f} | {total_ch_elec:16.1f} | {total_dhw_heat:8.1f} | {total_dhw_elec:8.1f} | {total_all_heat:8.1f} | {total_all_elec:8.1f} | {(total_all_heat/total_all_elec if total_all_elec>0 else 0):.2f}")

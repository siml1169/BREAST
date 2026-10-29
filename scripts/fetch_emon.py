import urllib.request, json, subprocess, xml.etree.ElementTree as ET
from pathlib import Path

OUTPUT_FILE = Path(__file__).resolve().parents[1] / 'data' / 'pointt' / 'emon_recordings_detail.json'

raw = subprocess.check_output(['adb', 'exec-out', 'run-as', 'de.breast', 'cat', 'shared_prefs/breast_auth_prefs.xml']).decode('utf-8')
root = ET.fromstring(raw)
token_json_str = [s.text for s in root.iter('string') if s.attrib.get('name') == 'oauth_tokens_json'][0]
tokens = json.loads(token_json_str)
access_token = tokens['access_token']

gw_id = '101845229'
base_url = f'https://pointt-api.bosch-thermotechnology.com/pointt-api/api/v1/gateways/{gw_id}/resource'

sub_eps = [
    '/recordings/heatSources/emon/ch/compressor',
    '/recordings/heatSources/emon/ch/outputProduced',
    '/recordings/heatSources/emon/ch/workingTime',
    '/recordings/heatSources/emon/ch/numberOfStarts',
    '/recordings/heatSources/emon/dhw/compressor',
    '/recordings/heatSources/emon/dhw/outputProduced',
    '/recordings/heatSources/emon/dhw/workingTime',
    '/recordings/heatSources/emon/total/compressor',
    '/recordings/heatSources/emon/total/outputProduced',
    '/recordings/heatSources/emon/total/workingTime'
]

data_res = {}
for ep in sub_eps:
    for target_url in [f"{base_url}{ep}?interval=2026-10-28", f"{base_url}{ep}"]:
        try:
            r = urllib.request.Request(target_url, headers={'Authorization': f'Bearer {access_token}', 'Accept': 'application/json'})
            with urllib.request.urlopen(r) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                data_res[target_url] = data
                print(f"SUCCESS {target_url} -> keys: {list(data.keys())}")
                if 'recorded' in data or 'values' in data or 'value' in data:
                    print("   Content:", str(data.get('recorded') or data.get('values') or data.get('value'))[:140])
                break
        except Exception as e:
            print(f"FAILED {target_url}: {e}")

OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
with open(OUTPUT_FILE, 'w') as f:
    json.dump(data_res, f, indent=2)
print(f"Saved all details to {OUTPUT_FILE}")

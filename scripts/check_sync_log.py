import json, sqlite3, math, sys
import subprocess, xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8')

log_raw = subprocess.check_output(['adb', 'exec-out', 'run-as', 'de.breast', 'cat', 'shared_prefs/breast_sync_state.xml']).decode('utf-8')
root = ET.fromstring(log_raw)
state_str = [s.text for s in root.iter('string') if s.attrib.get('name') == 'sync_state_json'][0]
state = json.loads(state_str)
print("Sync log snippet:")
print(state.get("lastRunLog", "")[:1000])

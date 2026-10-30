"""Assert a controlled late-observation failure preserves actual state and receipts."""
import json
from pathlib import Path
import sys

report = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8-sig'))
assert report['passed'] is False
assert report.get('diagnosticAfterPlayDelayMillis', 0) >= 2000
snapshot = report['failureSnapshot']
assert snapshot['stage'] == 'arm_state_check'
assert snapshot['appState']['phase'] != 'WAITING'
assert snapshot['startedElapsed'] <= snapshot['appState']['elapsed'] <= snapshot['finishedElapsed']
assert snapshot['receiptsObservedElapsed'] <= snapshot['finishedElapsed']
assert isinstance(snapshot['targetEvents'], list) and isinstance(snapshot['targetTouches'], list)
assert 'lastPlayControl' in snapshot
assert isinstance(snapshot['lastPlayControl']['actionAccepted'], bool)
print('Controlled diagnostic verified:', snapshot['appState']['phase'], 'events=', len(snapshot['targetEvents']))

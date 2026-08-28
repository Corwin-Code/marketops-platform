import json
import sys
import hashlib
from pathlib import Path

p = Path(__file__).parent
number = int(sys.argv[1])
assert number in [66,73,74,75,76]
m = json.loads((p/'CODEQL-FALSE-POSITIVE-DISPOSITION-MATRIX-v1.1.json').read_bytes())
entry = next(a for a in m['alerts'] if a['alert'] == number)
a = json.loads((p/f'alert-{number}-after.json').read_bytes())
assert a['number'] == number and a['state'] == 'dismissed'
assert a['dismissed_reason'] == 'false positive'
assert a['dismissed_comment'] == entry['dismissed_comment']
assert hashlib.sha256(a['dismissed_comment'].encode()).hexdigest() == entry['dismissed_comment_sha256']
assert a['dismissed_by']['login'] and a['dismissed_at']
assert a['rule']['description'] == entry['rule']
i = a['most_recent_instance']
assert i['commit_sha'] == m['tested_merge'] and i['state'] == 'dismissed'
assert i['location']['path'] == entry['path'] and i['location']['start_line'] == entry['line']
before = json.loads((p/'verified-before.json').read_bytes())
t = next(x for x in before['alerts'] if x['alert'] == number)
query = 'mutation($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{id isResolved}}}'
(p/f'thread-{number}-resolve-request.json').write_text(json.dumps({'query':query,'variables':{'id':t['thread_id']}},indent=2)+'\n')
print(json.dumps({'alert':number,'state':a['state'],'comment_exact':True,'actor':a['dismissed_by']['login'],'at':a['dismissed_at'],'thread':t['thread_id']}))

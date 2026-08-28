from pathlib import Path
from datetime import datetime, timezone
import json
import hashlib
import subprocess

p = Path(__file__).parent
def read(name):
    return json.loads((p/name).read_bytes())
def alerts(scope, when):
    return {a['number']:a for page in read(f'alerts-{scope}-{when}.json') for a in page}
m = read('CODEQL-FALSE-POSITIVE-DISPOSITION-MATRIX-v1.1.json')
approved = {66,73,74,75,76}
changes = {}
for scope in ['pr','repository']:
    b,a = alerts(scope,'before'),alerts(scope,'after')
    assert b.keys() == a.keys()
    changed = sorted(n for n in b if b[n] != a[n])
    assert set(changed) <= approved
    changes[scope] = {'observed_alerts':len(b),'changed_alerts':changed}
assert changes['pr']['changed_alerts'] == sorted(approved)
before = read('threads-before.json')['data']['repository']['pullRequest']['reviewThreads']['nodes']
after = read('threads-after.json')['data']['repository']['pullRequest']['reviewThreads']['nodes']
assert len(before) == len(after) == 11 and all(t['isResolved'] for t in after)
thread_ids = {a['thread_id'] for a in read('verified-before.json')['alerts']}
thread_changes = []
for t in before:
    current = next(a for a in after if a['id'] == t['id'])
    if t['id'] not in thread_ids:
        assert current == t
    else:
        assert not t['isResolved'] and current['isResolved']
        thread_changes.append(t['id'])
pr = read('pr-after.json')
assert pr['headRefOid'] == m['reviewed_checkpoint_head']
assert pr['baseRefOid'] == m['protected_base']
assert pr['state'] == 'OPEN' and pr['isDraft'] and pr['mergedAt'] is None
assert len(pr['statusCheckRollup']) == 13
assert all(c['conclusion'] == 'SUCCESS' for c in pr['statusCheckRollup'])
rows = []
for n in sorted(approved):
    a = read(f'alert-{n}-after.json')
    entry = next(e for e in m['alerts'] if e['alert'] == n)
    assert a['state'] == 'dismissed' and a['dismissed_reason'] == 'false positive'
    assert a['dismissed_comment'] == entry['dismissed_comment']
    assert hashlib.sha256(a['dismissed_comment'].encode()).hexdigest() == entry['dismissed_comment_sha256']
    assert a['dismissed_by']['login'] and a['dismissed_at']
    rows.append({'alert':n,'reason':a['dismissed_reason'],'comment':a['dismissed_comment'],'comment_length':len(a['dismissed_comment']),'actor':a['dismissed_by']['login'],'dismissed_at':a['dismissed_at']})
root = '/Users/chzhengx/Code/personal/marketops-platform'
assert subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=root,text=True).strip() == m['reviewed_checkpoint_tree']
assert not subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip()
summary = {'verified_at':datetime.now(timezone.utc).isoformat(),'matrix_sha256':hashlib.sha256((p/'CODEQL-FALSE-POSITIVE-DISPOSITION-MATRIX-v1.1.json').read_bytes()).hexdigest(),'checkpoint_head':m['reviewed_checkpoint_head'],'checkpoint_tree':m['reviewed_checkpoint_tree'],'tested_merge':m['tested_merge'],'alerts':rows,'alert_comparison':changes,'resolved_named_threads':sorted(thread_changes),'unresolved_threads':0,'aggregate_codeql':'SUCCESS','all_required_and_infrastructure_checks':'SUCCESS','codeql_reruns':0,'source_changes_during_disposition':False,'pr_state':'OPEN_DRAFT_UNMERGED'}
(p/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({'alerts':len(rows),'threads_resolved':len(thread_changes),'unresolved':0,'checks':'13 SUCCESS','changes':changes},indent=2))

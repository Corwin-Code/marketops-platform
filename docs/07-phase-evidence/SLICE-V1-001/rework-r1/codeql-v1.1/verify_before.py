from pathlib import Path
import hashlib
import json
import subprocess

p = Path(__file__).parent
root = Path('/Users/chzhengx/Code/personal/marketops-platform')
matrix_path = p / 'CODEQL-FALSE-POSITIVE-DISPOSITION-MATRIX-v1.1.json'
assert hashlib.sha256(matrix_path.read_bytes()).hexdigest() == 'b0a09962ebb37d257cb9f79a6e3d8f5543b0d3a7a69bc5bc99f578dc37bf4e8a'
m = json.loads(matrix_path.read_bytes())
pr = json.loads((p / 'pr-before.json').read_bytes())
commit = json.loads((p / 'remote-commit-before.json').read_bytes())
assert pr['headRefOid'] == m['reviewed_checkpoint_head'] == commit['sha']
assert pr['baseRefOid'] == m['protected_base']
assert commit['tree']['sha'] == m['reviewed_checkpoint_tree']
assert pr['state'] == 'OPEN' and pr['isDraft'] and pr['mergedAt'] is None
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip() == m['reviewed_checkpoint_head']
assert subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=root,text=True).strip() == m['reviewed_checkpoint_tree']
assert not subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip()
checks = pr['statusCheckRollup']
assert sum(c['conclusion'] == 'SUCCESS' for c in checks) == 12
assert [c['name'] for c in checks if c['conclusion'] != 'SUCCESS'] == ['CodeQL']
threads = json.loads((p/'threads-before.json').read_bytes())['data']['repository']['pullRequest']['reviewThreads']['nodes']
assert len([t for t in threads if not t['isResolved']]) == 5
pr_alerts = [a for page in json.loads((p/'alerts-pr-before.json').read_bytes()) for a in page]
assert sorted(a['number'] for a in pr_alerts if a['state'] == 'open') == [66,73,74,75,76]
summary = []
for entry in m['alerts']:
    number = entry['alert']
    alert = json.loads((p/f'alert-{number}-before.json').read_bytes())[0]
    instances = [i for page in json.loads((p/f'alert-{number}-instances-before.json').read_bytes()) for i in page]
    instance = alert['most_recent_instance']
    scoped_alert = next(a for a in pr_alerts if a['number'] == number)
    assert alert['number'] == number and alert['state'] in (None, 'open')
    assert scoped_alert['state'] == 'open' and instance['state'] == 'open'
    comparable_instance = dict(instance)
    comparable_instance['message'] = {'text': instance['message']['text']}
    assert scoped_alert['most_recent_instance'] == comparable_instance
    assert alert['rule']['description'] == entry['rule']
    assert instance['location']['path'] == entry['path'] and instance['location']['start_line'] == entry['line']
    assert instance['ref'] == 'refs/pull/20/merge' and instance['commit_sha'] == m['tested_merge']
    assert any(i['commit_sha'] == m['tested_merge'] and i['location'] == instance['location'] for i in instances)
    thread = [t for t in threads if t['path'] == entry['path'] and t['line'] == entry['line'] and not t['isResolved']]
    assert len(thread) == 1
    comment = entry['dismissed_comment']
    assert comment.isascii() and len(comment) == entry['dismissed_comment_length'] <= 280
    assert hashlib.sha256(comment.encode()).hexdigest() == entry['dismissed_comment_sha256']
    request = json.loads((p/f'alert-{number}-request.json').read_bytes())
    assert request == {'state':'dismissed','dismissed_reason':'false positive','dismissed_comment':comment}
    source = root / entry['path']
    assert source.read_bytes() == subprocess.check_output(['git','show','HEAD:'+entry['path']],cwd=root)
    summary.append({'alert':number,'rule_id':alert['rule']['id'],'thread_id':thread[0]['id'],'comment_length':len(comment),'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest()})
(p/'verified-before.json').write_text(json.dumps({'head':pr['headRefOid'],'tree':commit['tree']['sha'],'alerts':summary},indent=2)+'\n')
print(json.dumps(summary,indent=2))

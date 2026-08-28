from pathlib import Path
import json
import hashlib
import subprocess
import sys

p = Path(__file__).parent
n = int(sys.argv[1])
assert n in [66,73,74,75,76]
matrix_path = p/'CODEQL-FALSE-POSITIVE-DISPOSITION-MATRIX-v1.1.json'
assert hashlib.sha256(matrix_path.read_bytes()).hexdigest() == 'b0a09962ebb37d257cb9f79a6e3d8f5543b0d3a7a69bc5bc99f578dc37bf4e8a'
m = json.loads(matrix_path.read_bytes())
entry = next(a for a in m['alerts'] if a['alert'] == n)
a = json.loads((p/f'alert-{n}-current.json').read_bytes())
assert a == json.loads((p/f'alert-{n}-before.json').read_bytes())[0]
instances = json.loads((p/f'alert-{n}-instances-current.json').read_bytes())
original = [i for page in json.loads((p/f'alert-{n}-instances-before.json').read_bytes()) for i in page]
assert instances == original
assert a['most_recent_instance']['state'] == 'open'
assert json.loads((p/f'alert-{n}-request.json').read_bytes()) == {'state':'dismissed','dismissed_reason':'false positive','dismissed_comment':entry['dismissed_comment']}
root = '/Users/chzhengx/Code/personal/marketops-platform'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip() == m['reviewed_checkpoint_head']
assert not subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip()
print(f'Alert {n}: current identity, complete instances, exact request and clean checkpoint PASS')

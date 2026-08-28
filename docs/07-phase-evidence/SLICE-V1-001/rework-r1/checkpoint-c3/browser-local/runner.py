import hashlib
import json
import os
import pathlib
import secrets
import socket
import subprocess
import time
import uuid

root = pathlib.Path('/Users/chzhengx/Code/personal/marketops-platform')
out = pathlib.Path('/private/tmp/marketops-s1-r1')
local = root / '.env.local'
allowed = {'MARKETOPS_POSTGRES_SUPERUSER_PASSWORD', 'MARKETOPS_DB_MIGRATION_PASSWORD',
           'MARKETOPS_DB_APP_PASSWORD', 'MARKETOPS_DB_PORT'}
keys = {line.split('=', 1)[0] for line in local.read_text().splitlines()
        if line.strip() and not line.lstrip().startswith('#')}
if not keys <= allowed:
    raise SystemExit('Local configuration has extra keys; isolated browser run refused.')
before = hashlib.sha256(local.read_bytes()).hexdigest()
project = 'marketops-s1-r1-browser-' + uuid.uuid4().hex[:10]
with socket.socket() as listener:
    listener.bind(('127.0.0.1', 0))
    port = listener.getsockname()[1]
env = {k:v for k,v in os.environ.items() if k in {'PATH','HOME','JAVA_HOME','LANG','TMPDIR','USER'}}
env.update({key: secrets.token_hex(32) for key in allowed if key != 'MARKETOPS_DB_PORT'})
env.update({'MARKETOPS_DB_PORT': str(port), 'COMPOSE_PROJECT_NAME': project,
            'MARKETOPS_RAW_CUSTODY_DIRECTORY': str(out / (project + '-raw'))})
commands = []
def run(command, label):
    with (out / label).open('w') as log:
        result = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
    commands.append({'command': command, 'log': label, 'exit_code': result.returncode})
    return result.returncode

started = time.monotonic()
result = 1
cleanup = 1
try:
    if run(['make', 'up'], 'browser-database-up-152.log') == 0:
        result = run(['make', 'frontend-browser'], 'browser-suite-152.log')
finally:
    # The project name was generated here; this removes only this run's synthetic database.
    cleanup = run(['docker', 'compose', '--project-name', project, '--env-file', '.env.local',
                   '-f', 'infra/compose/docker-compose.yml', 'down', '--volumes', '--remove-orphans'],
                  'browser-database-cleanup-152.log')
    unchanged = hashlib.sha256(local.read_bytes()).hexdigest() == before
    evidence = {'commands': commands, 'project': project, 'loopback_database_port': port,
                'elapsed_seconds': round(time.monotonic() - started, 3),
                'root_local_configuration_unchanged': unchanged,
                'synthetic_credentials_generated_in_memory': True,
                'provider_calls': 'NONE; browser routes supply synthetic identity responses',
                'source_input_manifest': 'verification-inputs-export-152.json',
                'result': 'PASS' if result == 0 and cleanup == 0 and unchanged else 'FAIL'}
    (out / 'browser-isolated-152-result.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))
raise SystemExit(result or cleanup or (0 if unchanged else 1))

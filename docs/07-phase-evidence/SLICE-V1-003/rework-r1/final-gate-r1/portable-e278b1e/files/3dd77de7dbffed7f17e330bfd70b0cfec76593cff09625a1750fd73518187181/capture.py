#!/usr/bin/env python3
import hashlib,json,subprocess,sys,time
from datetime import datetime,timezone
from pathlib import Path
root=Path(__file__).resolve().parent
name=sys.argv[1]
if '/' in name or '..' in name:raise ValueError('Simple unique artifact name required')
out=root/name
if out.exists():raise ValueError('Preserve prior response; choose a new name')
argv=['gh','api',*sys.argv[2:]]
start=datetime.now(timezone.utc).isoformat();clock=time.monotonic()
with out.open('wb') as stdout,(root/(name+'.stderr')).open('wb') as stderr:
 result=subprocess.run(argv,stdout=stdout,stderr=stderr)
record={'command':argv,'startedAt':start,'finishedAt':datetime.now(timezone.utc).isoformat(),
        'elapsedSeconds':round(time.monotonic()-clock,3),'exitCode':result.returncode,
        'artifact':{'path':name,'bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest()},
        'stderr':{'path':name+'.stderr','bytes':(root/(name+'.stderr')).stat().st_size,'sha256':hashlib.sha256((root/(name+'.stderr')).read_bytes()).hexdigest()},
        'boundary':'Read-only GitHub API response for checkpoint 7e66cf8; exact source CI only, not independent Controller approval.'}
(root/(name+'.command.json')).write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({'name':name,'exitCode':result.returncode,'bytes':out.stat().st_size}))
sys.exit(result.returncode)

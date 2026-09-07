import subprocess,pathlib,datetime,json,sys
name,endpoint=sys.argv[1:]
out=pathlib.Path('/tmp/slice3-w7-backend-failure-review')
start=datetime.datetime.now(datetime.timezone.utc).isoformat()
p=subprocess.run(['gh','api','--include',endpoint],capture_output=True)
(out/(name+'.response.txt')).write_bytes(p.stdout)
(out/(name+'.stderr.txt')).write_bytes(p.stderr)
raw=p.stdout.decode('utf-8',errors='replace').replace('\r\n','\n');body=raw.split('\n\n',1)[-1]
try:
 data=json.loads(body);(out/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
except Exception:pass
meta={'argv':['gh','api','--include',endpoint],'startedAtUTC':start,'finishedAtUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'exitCode':p.returncode,'httpStatus':raw.splitlines()[0] if raw else None,'stdoutBytes':len(p.stdout),'stderrBytes':len(p.stderr)}
(out/(name+'.request.json')).write_text(json.dumps(meta,indent=2)+'\n');print(json.dumps(meta))

import datetime,hashlib,json,subprocess,sys
from pathlib import Path
base=Path('/tmp/slice3-final-execution-02e6172-r5');out=Path(__file__).resolve().parent
records=[]
for name in ['frontend-quality','governance-r2','infrastructure','migration','supply-chain','security-npm-audit-r2','browser','browser-r2','browser-r3']:
 failed=name.startswith('browser')
 boundary=('Failed browser checkpoint retained. Entire layer failed; no advertising suite or all-browser PASS. Browser r3 has 25 legacy passing nodes but 0 advertising nodes and does not admit the layer.' if failed else 'Completed original 02e local '+name+' command; product nine-layer admission and independent Controller review remain separate.')
 argv=[sys.executable,str(out/'scan_layer_raw.py'),'--input',str(base/name),'--out',str(out/name),'--receipt','receipt.json' if name=='security-npm-audit-r2' else 'layer-candidate.json','--scope',boundary,'--expected-result','COMMAND_FAILED' if failed else 'COMMAND_SUCCEEDED_REVIEW_REQUIRED']
 started=datetime.datetime.now(datetime.timezone.utc).isoformat()
 log=out/(name+'-scanner.log')
 with log.open('w') as stream:process=subprocess.run(argv,stdout=stream,stderr=subprocess.STDOUT)
 record={'layer':name,'argv':argv,'startedAt':started,'finishedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'exitCode':process.returncode,'log':{'path':str(log),'sha256':hashlib.sha256(log.read_bytes()).hexdigest()},'tool':{'path':str(out/'scan_layer_raw.py'),'sha256':hashlib.sha256((out/'scan_layer_raw.py').read_bytes()).hexdigest()}}
 records.append(record);(out/'SCAN-COMMANDS.json').write_text(json.dumps(records,indent=2)+'\n')
 summary=out/name/'SCAN-SUMMARY.json'
 print(json.dumps({'layer':name,'scanExitCode':process.returncode,'summary':json.loads(summary.read_text()) if summary.exists() else 'NO_SUMMARY'}),flush=True)
 if process.returncode:sys.exit(process.returncode)

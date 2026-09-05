import json,re,hashlib,sys,xml.etree.ElementTree as ET
from pathlib import Path
out=Path(sys.argv[1]);inventory=json.loads((out/'test-report-inventory.json').read_text());result={'kind':'RAW_TESTCASE_COUNT_RECONCILIATION','originalRunReceiptUnmodified':True,'scope':'Suite XML declarations, raw testcase nodes and Maven console summaries are separate observations. Each raw testcase failure/error/skipped node is examined; a declaration mismatch is not silently normalized.','suites':[],'testcases':[],'mavenConsoleSummaries':[]}
for row in inventory:
 p=Path(row['path']);root=ET.parse(p).getroot();nodes=root.findall('.//testcase')
 counts={'tests':len(nodes),'failures':sum(n.find('failure') is not None for n in nodes),'errors':sum(n.find('error') is not None for n in nodes),'skipped':sum(n.find('skipped') is not None for n in nodes)}
 declared={k:row[k] for k in counts}
 result['suites'].append({'phase':row['phase'],'name':row['name'],'rawXml':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'suiteDeclaredCounts':declared,'actualTestcaseNodeCounts':counts,'countsMatch':counts==declared})
 for n in nodes:
  result['testcases'].append({'phase':row['phase'],'suite':row['name'],'classname':n.get('classname'),'name':n.get('name'),'seconds':n.get('time'),'failure':n.find('failure') is not None,'error':n.find('error') is not None,'skipped':n.find('skipped') is not None,'xml':str(p),'rawXmlSha256':row['sha256']})
phase=None;pending=False
for i,line in enumerate((out/'maven-clean-verify.log').read_text().splitlines(),1):
 if line.startswith('[INFO] --- surefire:'):phase='surefire'
 if line.startswith('[INFO] --- failsafe:'):phase='failsafe'
 if re.match(r'^\[INFO\] Results:\s*$',line):pending=True
 if pending:
  match=re.match(r'^\[(?:INFO|ERROR)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$',line)
  if match:
   result['mavenConsoleSummaries'].append({'phase':phase,'line':i,'counts':dict(zip(['tests','failures','errors','skipped'],map(int,match.groups())))});pending=False
result['phaseCounts']={phase:{kind:{key:sum(s[kind][key] for s in result['suites'] if s['phase']==phase) for key in ['tests','failures','errors','skipped']} for kind in ['suiteDeclaredCounts','actualTestcaseNodeCounts']} for phase in ['surefire','failsafe']}
result['mismatchedSuites']=[s for s in result['suites'] if not s['countsMatch']]
result['everyActualTestcaseNodePassed']=bool(result['testcases']) and all(not any(t[k] for k in ['failure','error','skipped']) for t in result['testcases'])
result['summary']={'rawTestcaseNodes':len(result['testcases']),'declaredTests':sum(s['suiteDeclaredCounts']['tests'] for s in result['suites']),'actualFailures':sum(t['failure'] for t in result['testcases']),'actualErrors':sum(t['error'] for t in result['testcases']),'actualSkipped':sum(t['skipped'] for t in result['testcases'])}
(out/'testcase-count-reconciliation.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'summary':result['summary'],'mismatches':[{k:s[k] for k in ['name','suiteDeclaredCounts','actualTestcaseNodeCounts']} for s in result['mismatchedSuites']],'console':result['mavenConsoleSummaries']},indent=2))

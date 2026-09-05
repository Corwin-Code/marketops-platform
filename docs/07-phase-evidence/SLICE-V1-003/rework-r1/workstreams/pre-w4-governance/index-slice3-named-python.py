#!/usr/bin/env python3
"""Join current six-shard Python method references to one captured governance run."""
import argparse,collections,hashlib,importlib.util,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--run-dir',type=Path,required=True);p.add_argument('--repo',type=Path,default=Path('/Users/chzhengx/Code/personal/marketops-platform'));p.add_argument('--output',type=Path,required=True);a=p.parse_args()
root=a.repo.resolve();run=a.run_dir.resolve();out=a.output.resolve()
if out.is_relative_to(root):raise SystemExit('Output must be outside repository')
sha=lambda raw:hashlib.sha256(raw).hexdigest()
receipt=json.loads((run/'receipt.json').read_text());raw=(run/'named-unittest-results.json').read_bytes();data=json.loads(raw)
if receipt['namedResultsSha256']!=sha(raw):raise SystemExit('Named result receipt binding mismatch')
spec=importlib.util.spec_from_file_location('named_junit_index','/tmp/index-slice3-named-junit.py');index=importlib.util.module_from_spec(spec);spec.loader.exec_module(index)
ac,findings,refs,shards=index.load_rows(root);results=[]
for ref in refs.values():
 if not ref['path'].endswith('.py'):continue
 row={**ref,'matches':[],'assessment':'NOT_PERFORMED'};method=ref.get('method')
 if not method:row['matchStatus']='FILE_ONLY_REFERENCE_REQUIRES_NAMED_ASSERTION_REVIEW';results.append(row);continue
 matches=[r for r in data['methodsAndFrameworkEvents'] if r.get('sourcePath')==ref['path'] and r.get('method')==method]
 if not matches:row['matchStatus']='NO_EXACT_EXECUTED_METHOD';results.append(row);continue
 if len(matches)!=1:row['matchStatus']='AMBIGUOUS_CLASS_OR_MULTIPLE_EXECUTIONS';results.append(row);continue
 case=matches[0];current=root/ref['path'];current_hash=sha(current.read_bytes()) if current.is_file() else None
 row['currentSourceSha256']=current_hash
 if current_hash!=case['sourceSha256']:row['matchStatus']='CURRENT_TEST_SOURCE_DIFFERS_FROM_MEASURED_RUN';results.append(row);continue
 source=next((s for s in data['sources'] if s['path']==ref['path']),None)
 if not source or not source['stable'] or source['sha256Before']!=case['sourceSha256']:row['matchStatus']='EXECUTED_SOURCE_NOT_STABLE';results.append(row);continue
 declared_classes={c['claimedClass'] for c in ref['shardClaims'] if c.get('claimedClass')}
 allowed={case['runtimeClass'],case['runtimeModule']+'.'+case['runtimeClass'],case['runtimeClass'].split('.')[-1]}
 if declared_classes and not declared_classes.issubset(allowed):row['matchStatus']='SHARD_CLASS_IDENTITY_CONFLICT';results.append(row);continue
 row['matchStatus']='EXACT_NAMED_UNITTEST_RESULT_MATCHED';row['matches']=[case];row['resultPath']=str(run/'named-unittest-results.json');row['resultSha256']=sha(raw);results.append(row)
byid={r['id']:r for r in results}
def rows(values):
 return [{'id':r['id'],'pythonReferenceIds':sorted(r['referenceIds']&byid.keys()),'matches':[{'referenceId':rid,'path':byid[rid]['path'],'method':byid[rid]['method'],'matchStatus':byid[rid]['matchStatus'],'unittestResults':byid[rid]['matches']} for rid in sorted(r['referenceIds']&byid.keys())],'assessment':'NOT_PERFORMED'} for r in sorted(values.values(),key=lambda x:x['id'])]
result={'kind':'NAMED_PYTHON_EXECUTION_INDEX_NOT_CRITERION_ASSESSMENT','runDirectory':str(run),'runReceiptSha256':sha((run/'receipt.json').read_bytes()),'containingGovernanceResult':receipt['result'],'makeExitCode':receipt['exitCode'],'sourceBoundaryStable':receipt['sourceBoundaryStable'],'sourceBefore':receipt['sourceBefore'],'shards':shards,'references':results,'criteria':rows(ac),'findings':rows(findings),'summary':dict(collections.Counter(r['matchStatus'] for r in results)),'limits':['Current test file bytes must equal measured, stable callback-bound source bytes.','File-only and ambiguous references never become method evidence.','A method result only proves its actual assertions; complete governance/AC/finding/Controller assessment is separate.','Non-Python JUnit/frontend/browser evidence is not inherited into this index.'],'controllerVerdict':'NOT_ISSUED'}
out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'output':str(out),'sha256':sha(out.read_bytes()),'summary':result['summary'],'containingGovernanceResult':receipt['result']}))

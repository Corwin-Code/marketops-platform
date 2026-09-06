from pathlib import Path
import hashlib,json,subprocess
ROOT=Path('/Users/chzhengx/Code/personal/marketops-platform');OUT=Path(__file__).resolve().parent
HEAD='7e66cf87afc15773d4f6e6e6717a86e7b8336ae5';MERGE='f9a1d73f324323d13ea72db3890090c9b9d85450'
sha=lambda b:hashlib.sha256(b).hexdigest();load=lambda n:json.loads((OUT/n).read_bytes())
expected=Path('/tmp/slice3-final-execution-7e66cf8-r3/EXPECTED-SOURCE.json');ex=json.loads(expected.read_bytes());inventory=Path(ex['inventory']['path']);before=inventory.read_bytes();assert sha(before)==ex['inventory']['sha256']
(OUT/'executed-source-inventory.json').write_bytes(before);(OUT/'expected-source.json').write_bytes(expected.read_bytes());identity=Path(ex['identityEvidence']['path']);assert sha(identity.read_bytes())==ex['identityEvidence']['sha256'];(OUT/'executed-source-identity.json').write_bytes(identity.read_bytes());ident=json.loads(identity.read_bytes());assert ident['identityScope']=='CLEAN_COMMIT_TREE' and not ident['workingTreeDirty'] and ident['sourceHead']==HEAD
source=load('source-commit.json');merge=load('tested-merge-commit.json');tree=load('source-tree-recursive.json');assert not tree['truncated'];assert source['sha']==HEAD and merge['sha']==MERGE and source['tree']['sha']==merge['tree']['sha']==tree['sha']==ex['sourceTree'];assert [x['sha'] for x in merge['parents']]==['08ad7da7d9e75b4ddd1c387a22ac0affba9e1430',HEAD]
remote={x['path']:x for x in tree['tree'] if x['type']=='blob'}
proc=subprocess.Popen(['git','cat-file','--batch'],cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE)
def blob(path):
 proc.stdin.write((HEAD+':'+path+'\n').encode());proc.stdin.flush();line=proc.stdout.readline().decode().strip().split();assert len(line)==3 and line[1]=='blob',line;b=proc.stdout.read(int(line[2]));assert proc.stdout.read(1)==b'\n';assert hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()==line[0]==remote[path]['sha'];return b,line[0]
attributes,_=blob('.gitattributes');attribute=attributes.decode();assert all('*.'+ext+' text eol=crlf' in attribute for ext in ['bat','cmd','ps1'])
rows=[]
for f in json.loads(before)['files']:
 path=f['path'];raw=(ROOT/path).read_bytes();canonical,blobid=blob(path);conversion='NONE';checkout=canonical
 if raw!=canonical:
  assert path.endswith(('.bat','.cmd','.ps1')) and b'\r' not in canonical,path;checkout=canonical.replace(b'\n',b'\r\n');conversion='DECLARED_CRLF_CHECKOUT'
 ok=raw==checkout and sha(raw)==f['sha256'] and len(raw)==f['bytes'];assert ok,path
 rows.append({'path':path,'executedSha256':f['sha256'],'currentCheckoutSha256':sha(raw),'gitCanonicalSha256':sha(canonical),'gitCanonicalBlobSha1':blobid,'githubTreeBlobSha1':remote[path]['sha'],'gitCheckoutConversion':conversion,'match':ok})
extra=[]
for f in ex['additionalExecutionInputs']:
 raw,blobid=blob(f['path']);assert sha(raw)==f['sha256'];extra.append({'path':f['path'],'sha256':sha(raw),'githubTreeBlobSha1':blobid,'scope':f['scope'],'executionReceipt':f['executionReceipt']})
proc.stdin.close();assert proc.wait()==0
result={'kind':'CHECKPOINT_EXECUTED_SOURCE_TO_EXACT_GITHUB_TREE_COMPARISON','sourceHead':HEAD,'sourceTree':tree['sha'],'testedMerge':MERGE,'testedMergeTree':merge['tree']['sha'],'testedMergeParents':[x['sha'] for x in merge['parents']],'testedMergeSignatureVerified':merge['verification']['verified'],'sourceCommitSignatureStatus':source['verification']['reason'],'inventoryOriginalPath':str(inventory),'inventorySha256':sha(before),'inventoryFiles':len(rows),'allExecutionInputBytesUnchanged':all(x['match'] for x in rows),'allMatchExactRemoteTreeWithDeclaredCheckoutRules':all(x['match'] for x in rows),'directByteMatches':sum(x['gitCheckoutConversion']=='NONE' for x in rows),'declaredCrLfCheckoutConversions':sum(x['gitCheckoutConversion']=='DECLARED_CRLF_CHECKOUT' for x in rows),'method':'Every actual clean collector input SHA-256 is matched against unchanged checkout bytes and the source commit canonical blob. Canonical SHA-1 blobs are independently matched to the non-truncated remote tree; tested merge has exactly the same tree and expected two parents. Only pinned .gitattributes CRLF rules may reversibly differ from canonical bytes.','checkoutRules':{'path':'.gitattributes','sha256':sha(attributes)},'files':rows,'additionalEvidenceDerivationInputs':extra,'boundary':'Complete 1272-input actual source inventory. Evidence derivation inputs are listed separately and do not imply their final validation execution has completed.'}
(OUT/'SOURCE-IDENTITY-COMPARISON.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:result[k] for k in ['inventoryFiles','directByteMatches','declaredCrLfCheckoutConversions','allExecutionInputBytesUnchanged','allMatchExactRemoteTreeWithDeclaredCheckoutRules']}))

"""Rebind previously parsed exact class constants only after whole-leaf byte equality."""
import argparse,hashlib,json,re,io,zipfile,datetime
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--scan',type=Path,required=True);p.add_argument('--head',required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();O=a.out.resolve();assert O.is_relative_to(Path('/tmp').resolve())and not O.exists();O.mkdir();S=a.scan.resolve();P=Path('/tmp/slice3-publication-hit-triage-02e6172-r5-r2/EXACT-HIT-TRIAGE.json');prior=json.loads(P.read_text())
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
summary=json.loads((S/'SCAN-SUMMARY.json').read_text());sin=json.loads((S/'SCAN-INPUT.json').read_text());assert summary['status']=='COMPLETE'and summary['originalBytesUnchanged']is True and len(sin['patterns'])==9
hits=[json.loads(x)for x in (S/'HITS.jsonl').read_text().splitlines()];assert len(hits)==summary['hitCount'];members={r['path']:r for r in (json.loads(x)for x in (S/'MEMBERS.jsonl').read_text().splitlines())};cache={};reviews=[]
for h in hits:
 assert h['view']=='RAW_BYTES';parts=h['path'].split('!');outer=Path(parts[0]);r=ref(outer);assert sin['files'][str(outer)]['sha256']==r['sha256'];data=outer.read_bytes();chain=[r];position=str(outer)
 for part in parts[1:]:
  m=re.fullmatch(r'(.*)#member=(\d+)',part);assert m;name,ordinal=m[1],int(m[2]);position+='!'+part
  if position in cache:data,ci=cache[position];chain.append(ci);continue
  with zipfile.ZipFile(io.BytesIO(data))as z:
   entries=[i for i in z.infolist()if not i.is_dir()];zi=entries[ordinal];assert zi.filename==name;data=z.read(zi);ci={'path':position,'sha256':sha(data),'bytes':len(data),'crc32':f'{zi.CRC:08x}','member':name,'ordinalAmongFiles':ordinal};assert members[position]['sha256']==ci['sha256']and members[position]['bytes']==len(data);cache[position]=(data,ci);chain.append(ci)
 text=data.decode('utf-8',errors='replace');matches=[m for m in re.compile(sin['patterns'][h['rule']-1]).finditer(text)if m.start()==h['characterOffset']];assert len(matches)==1;match=matches[0];assert len(match.group())==h['matchedCharacters']and sha(match.group().encode())==h['matchedSha256'] and text.count('\n',0,match.start())+1==h['line']
 candidates=[x for x in prior['hits']if x['memberSha256']==sha(data)and all(x['originalHit'][k]==h[k]for k in ['rule','view','line','characterOffset','matchedCharacters','matchedSha256'])];assert len(candidates)==1,'No exact previously parsed whole-class and match identity';old=candidates[0];assert old['publishableRawForThisExactHit']is True and old['exactHitRequiresRedaction']is False
 reviews.append({'currentHit':h,'actualArchiveChain':chain,'wholeClassSha256':sha(data),'priorParsedConstantProof':{'evidence':ref(P),'hitId':old['hitId']},'exactWholeClassAndMatchBytesUnchanged':True,'constantPoolOverlap':old['constantPoolOverlap'],'parsedContext':old['parsedContext'],'classification':old['classification'],'reason':old['reason'],'exactHitRequiresRedaction':False,'scope':'Applies only to this actual entire class SHA and exact offset/matched SHA. No class-path, library, source-directory or future-file waiver.'})
assert len(reviews)==5
out={'kind':'EXACT_CURRENT_ARCHIVE_MEMBER_MATCH_REVIEW','sourceHead':a.head,'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scannerInput':ref(S/'SCAN-INPUT.json'),'scanSummary':ref(S/'SCAN-SUMMARY.json'),'originalHits':ref(S/'HITS.jsonl'),'originalMembers':ref(S/'MEMBERS.jsonl'),'triageTool':ref(__file__),'priorExactClassConstantReview':ref(P),'hits':reviews,'reviewedHitCount':len(reviews),'unresolvedHitCount':0,'originalRawOrScanBytesChanged':False,'secretValuesPrinted':False,'productTestsRun':0,'engineeringClosureClaimMade':False,'productionWriteEnabled':False,'disposition':'ALL_EXACT_REHASHED_CLASS_CONSTANT_MATCHES_NEED_NO_SECRET_REDACTION'}
q=O/'EXACT-HIT-TRIAGE.json';q.write_text(json.dumps(out,indent=2)+'\n');print(json.dumps({'review':ref(q),'exactMatches':len(reviews),'unresolved':0}))

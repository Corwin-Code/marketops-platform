#!/usr/bin/env python3
import collections,hashlib,json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
OUT=Path(__file__).resolve().parent
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):return {'path':str(p),'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())}
for short in ['7e66cf8','98344bb']:
 root=Path('/tmp/slice3-final-execution-'+short+'-r3/backend-full');layer=json.loads((root/'layer-candidate.json').read_text());index=json.loads((OUT/(short+'-INDEX.json')).read_text())
 scan=OUT/(short+'-publication-scan');summary=json.loads((scan/'SCAN-SUMMARY.json').read_text());classification=json.loads((scan/'HIT-CLASSIFICATION.json').read_text())
 assert summary['status']=='COMPLETE' and summary['originalBytesUnchanged'] and classification['unreviewedOrCredentialShapedPrivateHits']==0
 data={'kind':'INDEPENDENT_CHECKPOINT_RAW_SCOPE_RECONCILIATION','sourceHead':layer['sourceHead'],'sourceTree':layer['sourceTree'],'sourceInventorySha256':layer['sourceInventorySha256'],'checkpointDisposition':index['checkpointDisposition'],'exactLayerReceipt':ref(root/'layer-candidate.json'),'immutableArchiveIndex':ref(OUT/(short+'-INDEX.json')),'independentRawJUnitCounts':ref(OUT/(short+'-RAW-JUNIT-RECONCILIATION.json')),'publicationShapeScan':ref(scan/'SCAN-SUMMARY.json'),'exactHitClassification':ref(scan/'HIT-CLASSIFICATION.json'),'measuredCommandExitCode':layer['exitCode'],'commandCompletedSuccessfully':layer['exitCode']==0 and layer['result']=='COMMAND_SUCCEEDED_REVIEW_REQUIRED','sourceStable':layer['sourceStable'],'workingTreeDirty':layer['workingTreeDirty'],'testcaseCounts':index['actualRawTestcaseCounts'],'families':index['families'],'ciEvidenceScope':'LOCAL_EXECUTION_ONLY_NO_WORKFLOW_JOB_ARTIFACT_ID_OR_TESTED_MERGE_INFERRED','archiveScan':{'records':summary['scannedRecords'],'nestedMembers':summary['archiveMembers'],'bytesIncludingNestedAndDuplicateCopies':summary['scannedBytesIncludingArchiveContainersAndDuplicatePhysicalCopies'],'rawHits':summary['hitCount'],'exactNonSecretOccurrenceAllowlistCount':classification['exactNonSecretOccurrencesAllowlisted'],'jwtHits':classification['jwtShapedHits'],'unreviewedPrivateHits':classification['unreviewedOrCredentialShapedPrivateHits']},'productionWriteEnabled':False,'controllerApprovalClaimMade':False,'engineeringClosureClaimMade':False,'rawPublicationAuthorized':False,'repositoryModified':False,'productTestsReexecuted':False}
 jars=list(root.rglob('marketops-server-*.jar'));assert len(jars)==1
 with zipfile.ZipFile(jars[0]) as z:
  build=z.read('META-INF/build-info.properties').decode();props=dict(line.split('=',1) for line in build.splitlines() if '=' in line and not line.startswith('#'))
  data['packagedBuildIdentity']={'jar':ref(jars[0]),'member':'META-INF/build-info.properties','memberSha256':sha(build.encode()),'properties':props,'buildGitCommitMatchesMeasuredHead':props.get('build.gitCommit')==layer['sourceHead']}
  assert data['packagedBuildIdentity']['buildGitCommitMatchesMeasuredHead']
 j=list(root.rglob('jacoco.xml'))
 if j:
  assert len(j)==1;tree=ET.parse(j[0]).getroot();rootc={c.attrib['type']:{'missed':int(c.attrib['missed']),'covered':int(c.attrib['covered'])} for c in tree.findall('counter')};classc=collections.defaultdict(lambda:{'missed':0,'covered':0})
  for c in tree.findall('.//class/counter'):
   for k in ['missed','covered']:classc[c.attrib['type']][k]+=int(c.attrib[k])
  source_line={'missed':0,'covered':0}
  for c in tree.findall('.//sourcefile/counter'):
   if c.attrib['type']=='LINE':
    for k in source_line:source_line[k]+=int(c.attrib[k])
  assert source_line==rootc['LINE']
  data['coverage']={'report':ref(j[0]),'rootCounters':rootc,'sumOfActualClassCounters':dict(classc),'classAggregationMatchesRootForTypes':{k:rootc[k]==classc[k] for k in rootc},'sumOfActualSourcefileLineCounters':source_line,'sourcefileLineAggregationMatchesRoot':True,'aggregationBoundary':'Root LINE uses sourcefile line aggregation. Multiple classes from one source file may share a source line, so adding class LINE counters is not the same measure. Every non-LINE class-counter sum matches root here; neither original counter set is rewritten or substituted.'} 
 else:data['coverage']={'report':None,'boundary':'Terminated partial run has no completed report; preserved jacoco.exec is not a replacement full-coverage assertion.'}
 measurements=[]
 for name in ['advertising-capacity-receipt.json','advertising-mixed-capacity-receipt.json']:
  paths=list(root.rglob(name))
  if not paths:continue
  assert len(paths)==1;p=paths[0];r=json.loads(p.read_text());ids=r['identities'];dataset=p.parent/Path(ids['datasetPath']).name;inputs=p.parent/Path(ids['sourceInputsPath']).name
  assert sha(dataset.read_bytes())==ids['datasetSha256'] and sha(inputs.read_bytes())==ids['sourceInputsSha256']
  resource_paths=list(root.rglob('runtime-resources.json'));assert len(resource_paths)==1;resources=resource_paths[0];assert sha(resources.read_bytes())==ids['runtimeResourceReceiptSha256']
  assert ids['measuredLocalGitHead']==layer['sourceHead']==ids['publicationIdentity']['sourceHeadSha']
  assert r['productionWriteEnabled'] is False and r['realProviderAccess'] is False
  targeted=r['targeted'];thresholds={'p95MillisLE300000':targeted['criticalP95Millis']<=300000,'maxMillisLE900000':targeted['maximumMillis']<=900000,'sweepMillisLT1800000':r['sweepWallMillis']<1800000,'noHardBreaches':targeted['hardBreachCount']==0,'noClockDefects':targeted['clockDefectCount']==0,'noFailedRequests':targeted['failedRequests']==0,'noPendingRequests':targeted['pendingRequests']==0}
  assert all(thresholds.values())
  measurements.append({'receipt':ref(p),'dataset':ref(dataset),'sourceInputs':ref(inputs),'datasetId':ids['datasetId'],'publicationIdentity':ids['publicationIdentity'],'outerRuntimeResourceReceipt':ref(resources),'hostAndDockerResources':json.loads(resources.read_text()),'jvmAndDatabaseRuntime':r['runtime'],'postgresContainerResources':r.get('postgresContainerResources'),'actualDatasetCounts':r['dataset'],'actualTargeted':targeted,'targetedWallMillis':r['targetedWallMillis'],'sweepWallMillis':r['sweepWallMillis'],'observedThresholdComparisons':thresholds,'scopeNotice':r.get('scopeNotice'),'boundary':'This exact local checkpoint measurement is retained independently. Targeted before-sweep health is INCIDENT and is not relabelled globally healthy. Container zero quotas mean no extra per-container cap, not no Docker VM limit. No real Provider or current final source claim.'})
 data['capacityMeasurements']=measurements
 data['preservedArtifacts']={'jacocoExec':len(list(root.rglob('jacoco.exec'))),'sbomFiles':[ref(p) for p in root.rglob('*-sbom.json')],'jarCount':len(jars),'totalRawFiles':index['originalFileCount']}
 data['remainingBoundary']='Accepted Contract section 6.24 policy resolution repair remains outside this checkpoint. A later source checkpoint needs full current evidence; this archive does not close criteria, all 22 findings, CV-A..E or independent Controller review.'
 (OUT/(short+'-SCOPE-RECEIPT.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({'checkpoint':short,'packagedHeadMatches':True,'jacocoRootClassConsistency':data['coverage'].get('classAggregationMatchesRootForTypes'),'capacityReceipts':len(measurements),'scopeReceipt':ref(OUT/(short+'-SCOPE-RECEIPT.json'))}))

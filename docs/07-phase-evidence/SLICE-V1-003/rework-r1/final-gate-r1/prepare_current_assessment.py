#!/usr/bin/env python3
"""Prepare all original assessment rows for human review; execution bindings stay empty."""
import hashlib
import json
from pathlib import Path

HERE=Path(__file__).resolve().parent
ROOT=next(parent for parent in HERE.parents if (parent/'AGENTS.md').is_file())
BASE=HERE.parent


def read(path):
    return json.loads(path.read_text())


def ref(path):
    return {'path':path.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    source_map=read(HERE/'FINALIZATION-INPUT-MAP-DRAFT.json')
    old_ac_path=BASE/'workstreams/engineering-assessment-w9/criterion-engineering-assessment.json'
    old_find_path=HERE/'historical-w10-central/FINDING-CLOSURE-MATRIX.json'
    old_ac={row['id']:row for row in read(old_ac_path)['entries']}
    old_find={row['id']:row for row in read(old_find_path)['entries']}
    catalog={}
    def candidates(value):
        found=[]
        if isinstance(value,dict):
            path=value.get('path');method=value.get('method')
            if path and method:
                identity=hashlib.sha256((path+'#'+method).encode()).hexdigest()[:24]
                current=ROOT/path
                catalog[identity]={'id':identity,'path':path,'method':method,'class':value.get('class'),
                    'historicalSourceSha256':value.get('sourceSha256',value.get('currentSha256')),
                    'currentSourceSha256':hashlib.sha256(current.read_bytes()).hexdigest() if current.is_file() else None,
                    'historicalAssertionScope':value.get('assertionScope',value.get('assertionBoundary',value.get('assertion'))),
                    'executionBinding':None}
                found.append(identity)
            for nested in value.values():found.extend(candidates(nested))
        elif isinstance(value,list):
            for nested in value:found.extend(candidates(nested))
        return sorted(set(found))
    criteria=[]
    for index,row in enumerate(source_map['criteria']):
        old=old_ac[row['id']]
        criteria.append({'id':row['id'],'acceptedExact':row['acceptedExact'],
            'historicalAssessment':{**ref(old_ac_path),'jsonPointer':f'/entries/{index}'},
            'historicalEngineeringReasons':[item['engineeringReason'] for item in old['individualContributions']],
            'controllerChecks':row['directControllerChecks'],'transitiveChecks':row['frozenFindingTransitiveChecks'],
            'changedPriorSourceReferences':row['priorSourceOrProofReferencesChanged'],
            'candidateHistoricalProofIds':candidates(old['individualContributions']),
            'engineeringReason':None,'layers':[],'proofs':[],
            'status':'REVIEW_AND_ACTUAL_EXECUTION_BINDING_REQUIRED'})
    findings=[]
    for index,row in enumerate(source_map['findings']):
        old=old_find[row['id']]
        findings.append({'id':row['id'],'title':row['title'],'criteria':row['criteria'],
            'historicalAssessment':{**ref(old_find_path),'jsonPointer':f'/entries/{index}'},
            'historicalControllerDisposition':row['historicalControllerDisposition'],
            'historicalRootCause':old['root_cause'],'historicalImplementedBehavior':old.get('implemented_behavior'),
            'candidateHistoricalProofIds':candidates(old.get('tests',{})),
            'requiredRework':row['frozenRequiredRework'],'requiredVerification':row['frozenVerification'],
            'residualChecks':row['residualChecks'],'engineeringReason':None,'layers':[],'proofs':[],
            'status':'REVIEW_AND_ACTUAL_EXECUTION_BINDING_REQUIRED'})
    checks=[{'id':row['id'],'findings':row['findings'],'directCriteria':row['directCriteria'],
             'requiredClosure':row['controllerRequiredClosure'],'currentSourcePins':row['currentSourcePins'],
             'candidateCurrentTestCatalogPaths':row['testCatalogPaths'],
             'result':'PENDING','engineeringReason':None,'proofLimits':None,'proofs':[]}
            for row in source_map['verificationChecks']]
    result={'kind':'SLICE3_CURRENT_ASSESSMENT_REVIEW_DRAFT','status':'PENDING',
            'engineeringClosureClaimMade':False,'productionWriteEnabled':False,'controllerApprovalClaimMade':False,
            'basisSourceMap':ref(HERE/'FINALIZATION-INPUT-MAP-DRAFT.json'),
            'criteria':criteria,'findings':findings,'verificationChecks':checks,
            'historicalNamedProofCatalog':list(catalog.values()),
            'currentSourceOnlyTestCatalog':source_map['sourceTestCatalog'],
            'boundary':'Historical reasons/identities are review context. Root must review each current reason, select actual positive/adverse assertion nodes and attach preserved execution artifacts; no result is inherited as current PASS.'}
    output=HERE/'CURRENT-ASSESSMENT-DRAFT.json'
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'result':'PENDING_ASSESSMENT_DRAFT','criteria':len(criteria),'findings':len(findings),'historicalNamedProofs':len(catalog),'sha256':ref(output)['sha256']}))


if __name__=='__main__':
    main()

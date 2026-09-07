#!/usr/bin/env python3
"""Isolated synthetic adapter checks, not product verification. Run after Root releases the slot."""
from pathlib import Path
import argparse
import shutil
import hashlib
import json
import subprocess
import sys
import tempfile

HERE=Path(__file__).resolve().parent
ORIGINAL_TOOL=HERE/'assemble_assessment_with_structured.py'
parser=argparse.ArgumentParser(description='Synthetic assembler boundary checks; never product proof')
parser.add_argument('--out',type=Path,required=True)
args=parser.parse_args()
OUT=args.out.resolve();temporary=Path('/tmp').resolve()
assert OUT!=temporary and OUT.is_relative_to(temporary), 'Checks must write only a dedicated /tmp directory'
for start in [Path.cwd().resolve(),HERE]:
 for candidate in [start,*start.parents]:
  if (candidate/'bootstrap-manifest.json').is_file():
   assert not OUT.is_relative_to(candidate), 'Checks cannot write in the repository'
OUT.mkdir(parents=True,exist_ok=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def write(path,value):path.write_text(json.dumps(value,indent=2)+'\n')
def ref(path):return {'path':str(path),'sha256':sha(path)}
results=[]
with tempfile.TemporaryDirectory(prefix='slice3-structured-adapter-',dir='/tmp') as directory:
    base=Path(directory);repository=base/'repository';repository.mkdir()
    (repository/'bootstrap-manifest.json').write_text('{}\n')
    tool_relative='assessment_tools/assemble_assessment_with_structured.py'
    TOOL=repository/tool_relative;TOOL.parent.mkdir();shutil.copyfile(ORIGINAL_TOOL,TOOL)
    source=repository/'actual-input.py';source.write_text('synthetic adapter fixture source\n')
    inventory=base/'source-before.json';write(inventory,{'files':[{'path':'actual-input.py','sha256':sha(source)},{'path':tool_relative,'sha256':sha(TOOL)}]})
    identity=base/'identity.json';write(identity,{'sourceHead':'a'*40,'sourceTree':'b'*40,'identityScope':'WORKTREE_WITH_EXACT_SOURCE_MANIFEST'})
    expected=base/'expected.json';write(expected,{**json.loads(identity.read_text()),'inventory':ref(inventory),
        'identityEvidence':ref(identity),'disposition':'UNDER_VERIFICATION','additionalExecutionInputs':[]})
    raw=base/'raw-receipt.json';write(raw,{'measured':{'complete':True,'failures':0}})
    receipt=base/'layer-candidate.json'
    def execute(name,change=None):
        out=base/name;out.mkdir()
        layer={'id':'backend_full','sourceHead':'a'*40,'sourceTree':'b'*40,'sourceInventorySha256':sha(inventory),
          'runId':'SYNTHETIC_ADAPTER_CHECK_ONLY','result':'COMMAND_SUCCEEDED_REVIEW_REQUIRED','sourceStable':True,
          'exitCode':0,'finishedAt':'2026-01-01T00:01:00Z','evidence':[ref(raw)]}
        adapter={'name':'raw-check','sourcePath':'actual-input.py','evidence':ref(raw),
                 'assertions':[{'pointer':'/measured/complete','expected':True},{'pointer':'/measured/failures','expected':0}]}
        if change:change(layer,adapter)
        write(receipt,layer)
        config=base/(name+'-config.json');write(config,{'sourceHead':'a'*40,'sourceTree':'b'*40,
          'layers':[{'id':'backend_full','receipt':str(receipt),'adapter':'collector','structuredRecordAdapters':[adapter]}]})
        write(out/'METHOD-LOCATORS.json',{'selectors':[]})
        write(out/'ASSESSMENT-SLOTS.json',{'sourceHead':'a'*40,'sourceTree':'b'*40,'sourceInventory':ref(inventory),**{k:[] for k in ['criteria','findings','clauses','verificationChecks']}})
        process=subprocess.run([sys.executable,str(TOOL),'catalog','--source-identity',str(expected),'--out',str(out),'--config',str(config)],capture_output=True,text=True,cwd=repository)
        catalog=json.loads((out/'CURRENT-NODE-CATALOG.json').read_text()) if (out/'CURRENT-NODE-CATALOG.json').exists() else None
        return process,catalog
    process,catalog=execute('registered_positive')
    assert process.returncode==0 and len(catalog['nodes'])==1,(process.stdout,process.stderr,catalog)
    assert catalog['nodes'][0]['admissibleAfterIndependentScopeReview'] is True
    assert catalog['engineeringClosureClaimMade'] is False
    assert catalog['nodes'][0]['kind']=='json'
    results.append({'name':'actual_registered_pointer_values_observed_without_closure_claim','passed':True})
    for name,change in [
      ('wrong_sha',lambda layer,adapter:adapter['evidence'].update(sha256='c'*64)),
      ('absent_pointer',lambda layer,adapter:adapter['assertions'][0].update(pointer='/not-present')),
      ('unregistered_artifact',lambda layer,adapter:layer.update(evidence=[]))]:
        process,catalog=execute(name,change)
        assert process.returncode!=0 and catalog is None,(name,process.stdout,process.stderr)
        results.append({'name':name+'_refused','passed':True})
    for name,change in [
      ('unexecuted_source',lambda layer,adapter:adapter.update(sourcePath=str(base/'unmeasured.py'))),
      ('failed_layer',lambda layer,adapter:layer.update(result='COMMAND_FAILED',exitCode=1)),
      ('running_layer',lambda layer,adapter:layer.update(result='RUNNING_NOT_ASSESSED',finishedAt=None)),
      ('wrong_expected_value',lambda layer,adapter:adapter['assertions'][1].update(expected=1)),
      ('wrong_expected_boolean_type',lambda layer,adapter:adapter['assertions'][1].update(expected=False))]:
        process,catalog=execute(name,change)
        assert process.returncode==0 and all(not n['admissibleAfterIndependentScopeReview'] for n in catalog['nodes']),name
        assert catalog['engineeringClosureClaimMade'] is False
        results.append({'name':name+'_never_admissible','passed':True})
    measured=inventory.read_bytes()
    for name,files in [('unmeasured_assembler',[{'path':'actual-input.py','sha256':sha(source)}]),
                       ('changed_assembler',[{'path':'actual-input.py','sha256':sha(source)},{'path':tool_relative,'sha256':'d'*64}])]:
        write(inventory,{'files':files})
        content=json.loads(expected.read_text());content['inventory']=ref(inventory);write(expected,content)
        process,catalog=execute(name)
        assert process.returncode!=0 and catalog is None
        results.append({'name':name+'_refused','passed':True})
    inventory.write_bytes(measured)
    content=json.loads(expected.read_text());content['inventory']=ref(inventory);write(expected,content)
    process=subprocess.run([sys.executable,str(TOOL),'catalog','--source-identity',str(expected),'--out',str(repository/'forbidden')],capture_output=True,text=True,cwd=repository)
    assert process.returncode!=0 and not (repository/'forbidden').exists()
    results.append({'name':'repository_output_refused_before_directory_creation','passed':True})
    process=subprocess.run([sys.executable,str(TOOL),'catalog','--source-identity',str(expected)],capture_output=True,text=True,cwd=repository)
    assert process.returncode!=0
    results.append({'name':'explicit_tmp_out_required','passed':True})
    closure=base/'EXECUTION-MANIFEST.json';write(closure,{'kind':'SLICE3_FINAL_GATE_EXECUTION_MANIFEST','status':'COMPLETE'})
    process,catalog=execute('manifest_self_proof',lambda layer,adapter:(layer.update(evidence=[ref(closure)]),adapter.update(evidence=ref(closure))))
    assert process.returncode!=0 and catalog is None
    results.append({'name':'generated_manifest_self_proof_refused','passed':True})
    # Exercise all four commands against a real isolated Git checkpoint, using existing
    # authoring data as read-only locators and explicitly synthetic execution records.
    actual_root=next(parent for start in [Path.cwd().resolve(),HERE] for parent in [start,*start.parents]
                     if (parent/'bootstrap-manifest.json').is_file())
    gate='docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1'
    authoring=[gate+'/CURRENT-ASSESSMENT-DRAFT.json',gate+'/FINALIZATION-INPUT-MAP-DRAFT.json',
       'docs/07-phase-evidence/SLICE-V1-003/SLICE-V1-003-FROZEN-FINDING-SET-001.json',
       'docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/engineering-assessment-w9/finding-engineering-assessment.json',
       'docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/engineering-assessment-w9/criterion-engineering-assessment.json',
       'docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/current-named-backend/current-named-backend-evidence.json',
       'scripts/validation/finalize_slice3_rework_assessment.py']
    for relative in authoring:
        target=repository/relative;target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(actual_root/relative,target)
    source.write_text('def test_scoped_node(): pass  # synthetic tool fixture only\n')
    def git(*args):return subprocess.check_output(['git','-c','core.hooksPath=/dev/null',*args],cwd=repository,stderr=subprocess.DEVNULL).decode().strip()
    git('init','-q');git('add','.')
    git('-c','user.name=Tool Boundary Fixture','-c','user.email=fixture@example.invalid','-c','commit.gpgsign=false','commit','-qm','Synthetic assembler boundary fixture')
    head=git('rev-parse','HEAD');tree=git('rev-parse','HEAD^{tree}')
    write(inventory,{'files':[{'path':'actual-input.py','sha256':sha(source)}]})
    write(identity,{'sourceHead':head,'sourceTree':tree,'identityScope':'CLEAN_COMMIT_TREE'})
    write(expected,{**json.loads(identity.read_text()),'inventory':ref(inventory),'identityEvidence':ref(identity),
         'disposition':'SYNTHETIC_TOOL_CHECK_ONLY','additionalExecutionInputs':[{'path':tool_relative,
         'sourceHead':head,'sha256':sha(TOOL),'scope':'EVIDENCE_DERIVATION_ONLY','executionReceipt':None}]})
    workflow=base/'workflow';layer_dir=base/'layer';layer_dir.mkdir()
    config=base/'workflow-inputs.json';review=base/'review.json'
    def run(command,*extra,success=True):
        process=subprocess.run([sys.executable,str(TOOL),command,'--source-identity',str(expected),'--out',str(workflow),*extra],capture_output=True,text=True,cwd=repository)
        assert (process.returncode==0)==success,(command,process.stdout,process.stderr)
        return process
    run('prepare')
    slots=json.loads((workflow/'ASSESSMENT-SLOTS.json').read_text())
    assert [len(slots[k]) for k in ['criteria','findings','clauses','verificationChecks']]==[200,22,115,5]
    assert len(slots['authorityReferences'])==7 and not slots['engineeringClosureClaimMade']
    raw=layer_dir/'raw.json';document={'measured':{'complete':True},'':'empty-key','a/b':{'~':[0]},'records':[{'name':'synthetic named record','status':'passed'}]};write(raw,document)
    write(layer_dir/'receipt.json',{'id':'backend_full','sourceHead':head,'sourceTree':tree,'sourceInventorySha256':sha(inventory),
       'runId':'SYNTHETIC_TOOL_WORKFLOW_ONLY','result':'COMMAND_SUCCEEDED_REVIEW_REQUIRED','sourceStable':True,
       'exitCode':0,'finishedAt':'2026-01-01T00:01:00Z','evidence':[{'path':'raw.json','sha256':sha(raw)}]})
    assertions=[{'pointer':'','expected':document},{'pointer':'/','expected':'empty-key'},{'pointer':'/a~1b/~0/0','expected':0}]
    adapter={'name':'synthetic raw pointer record','sourcePath':'actual-input.py','evidence':{'path':'raw.json','sha256':sha(raw)},'assertions':assertions}
    config_doc={'sourceHead':head,'sourceTree':tree,'layers':[{'id':'backend_full','receipt':'layer/receipt.json','adapter':'collector',
        'structuredRecordAdapters':[adapter],'jsonRecordAdapters':[{'evidence':{'path':'raw.json','sha256':sha(raw)},
         'recordsPointer':'/records','statusPointer':'/status','namePointer':'/name','sourcePath':'actual-input.py','passedValue':'passed'}]}],
        'artifactDestinations':{str(raw.resolve()):gate+'/synthetic-raw.json'}}
    write(config,config_doc)
    selector={'kind':'structured','layer':'backend_full','sourcePath':'actual-input.py','candidateSourceSha256':sha(source),
        'assertions':assertions,'scope':'Synthetic assembler boundary fixture only; no product evidence','selectorId':'synthetic-review'}
    update={'sourceHead':head,'sourceInventorySha256':sha(inventory),'reviewer':'synthetic-boundary-check',
        'criteria':[{'id':'S3-AC-122','acceptedExact':next(r for r in slots['criteria'] if r['id']=='S3-AC-122')['acceptedExact'],
          'engineeringReason':'Synthetic assembler flow fixture; not a project assessment.','addedSelectors':[selector],'proofSelections':[]}]}
    write(review,update);run('merge-reviews','--reviews',str(review))
    run('catalog','--config',str(config))
    nodes=json.loads((workflow/'CURRENT-NODE-CATALOG.json').read_text())['nodes']
    assert len(nodes)==2 and all(n['admissibleAfterIndependentScopeReview'] for n in nodes)
    assert all(n['evidence']['path']==str(raw.resolve()) for n in nodes)
    update['criteria'][0]['proofSelections']=[{'nodeId':n['nodeId'],'role':'supporting','scope':'Synthetic workflow scope, never a product proof.'} for n in nodes]
    write(review,update);run('merge-reviews','--reviews',str(review));run('assemble-pending','--config',str(config))
    candidate=json.loads((workflow/'EXECUTION-MANIFEST-CANDIDATE.json').read_text())
    assert candidate['status']=='PENDING' and not candidate['engineeringClosureClaimMade'] and candidate['source'] is None
    assert len(next(r for r in candidate['criteria'] if r['id']=='S3-AC-122')['proofs'])==2
    assert not (repository/gate/'synthetic-raw.json').exists()
    results.append({'name':'all_four_commands_exact_git_tool_pin_relative_artifacts_strict_pointer_pending_only','passed':True})
    good_review=review.read_bytes();good_config=config.read_bytes();good_slots=(workflow/'ASSESSMENT-SLOTS.json').read_bytes()
    for name,change in [('stale_selector_pin',lambda d:d['criteria'][0]['addedSelectors'][0].update(candidateSourceSha256='c'*64)),
                        ('accepted_clause_mutation',lambda d:d['criteria'][0].update(acceptedExact='changed accepted text'))]:
        value=json.loads(good_review);change(value);write(review,value)
        run('merge-reviews','--reviews',str(review),success=False)
        assert (workflow/'ASSESSMENT-SLOTS.json').read_bytes()==good_slots
        results.append({'name':name+'_refused_without_changing_slots','passed':True})
    review.write_bytes(good_review)
    altered=json.loads(good_config);altered['sourceHead']='c'*40;write(config,altered)
    run('assemble-pending','--config',str(config),success=False);config.write_bytes(good_config)
    results.append({'name':'assemble_cross_checkpoint_config_refused','passed':True})
    catalog_path=workflow/'CURRENT-NODE-CATALOG.json';good_catalog=catalog_path.read_bytes()
    altered=json.loads(good_catalog);altered['sourceHead']='c'*40;write(catalog_path,altered)
    run('assemble-pending','--config',str(config),success=False);catalog_path.write_bytes(good_catalog)
    results.append({'name':'assemble_cross_checkpoint_catalog_refused','passed':True})
    unregistered=layer_dir/'unregistered.json';write(unregistered,document)
    altered=json.loads(good_config);altered['layers'][0]['jsonRecordAdapters'][0]['evidence']={'path':'unregistered.json','sha256':sha(unregistered)};write(config,altered)
    run('catalog','--config',str(config),success=False);config.write_bytes(good_config)
    results.append({'name':'unregistered_named_json_record_artifact_refused','passed':True})
    self_document=layer_dir/'renamed-closure.json';write(self_document,{'kind':'SLICE3_FINAL_GATE_EXECUTION_MANIFEST','records':[{'name':'synthetic','status':'passed'}]})
    receipt_file=layer_dir/'receipt.json';good_receipt=receipt_file.read_bytes()
    changed_receipt=json.loads(good_receipt);changed_receipt['evidence'].append({'path':self_document.name,'sha256':sha(self_document)});write(receipt_file,changed_receipt)
    altered=json.loads(good_config);altered['layers'][0]['jsonRecordAdapters'][0]['evidence']={'path':self_document.name,'sha256':sha(self_document)};write(config,altered)
    run('catalog','--config',str(config),success=False);config.write_bytes(good_config);receipt_file.write_bytes(good_receipt)
    results.append({'name':'renamed_closure_cannot_become_a_named_record_self_proof','passed':True})

    for pointer_value in ['/a~1b/~0/-1','/a~2b','not-a-pointer']:
        altered=json.loads(good_config);altered['layers'][0]['structuredRecordAdapters'][0]['assertions']=[{'pointer':pointer_value,'expected':0}];write(config,altered)
        run('catalog','--config',str(config),success=False)
    config.write_bytes(good_config)
    results.append({'name':'invalid_escape_or_negative_array_or_missing_slash_pointer_refused','passed':True})
    authoring_file=repository/authoring[0];authoring_file.write_bytes(authoring_file.read_bytes()+b'\n')
    other=base/'changed-authoring-output'
    process=subprocess.run([sys.executable,str(TOOL),'prepare','--source-identity',str(expected),'--out',str(other)],capture_output=True,text=True,cwd=repository)
    assert process.returncode!=0 and not (other/'ASSESSMENT-SLOTS.json').exists()
    results.append({'name':'changed_checkpoint_authoring_data_refused','passed':True})
    process=subprocess.run([sys.executable,'-O',str(TOOL),'catalog','--source-identity',str(expected),'--out',str(base/'optimized-output')],capture_output=True,text=True,cwd=repository)
    assert process.returncode!=0
    results.append({'name':'optimized_python_cannot_disable_boundary_checks','passed':True})
write(OUT/'STRUCTURED-ADAPTER-SAFETY-CHECKS.json',{'kind':'SYNTHETIC_TOOL_BOUNDARY_CHECKS','checks':results,
    'toolSha256':sha(ORIGINAL_TOOL),'checkSha256':sha(Path(__file__)),'productVerificationEvidence':False,'closureClaimMade':False})
print(json.dumps({'checks':len(results),'productVerificationEvidence':False,'closureClaimMade':False}))

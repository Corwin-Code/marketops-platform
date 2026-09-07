#!/usr/bin/env python3
"""Capture one root-selected command and immutable evidence; never write a PASS manifest."""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = next(parent for parent in Path(__file__).resolve().parents if (parent/'AGENTS.md').is_file())
SCOPES = ['backend', 'frontend', 'infra', 'scripts', 'tests', 'fixtures', '.github', 'Makefile',
          'bootstrap-manifest.json', '.gitattributes', '.gitignore', '.editorconfig', '.dockerignore',
          '.tool-versions', 'mise.toml', 'AGENTS.md', '.env.example', 'pyproject.toml',
          'requirements.txt', 'requirements-dev.txt', 'uv.lock', 'poetry.lock',
          '.python-version', '.node-version', '.nvmrc']
# Runtime/config files are enumerated through Git and existing untracked source,
# as in collect_slice3_rework_identity.py. Output/report/dependency directories
# never become their own source identity.
EXCLUDED_PARTS = {'target', 'node_modules', 'dist', 'build', 'coverage', 'playwright-report',
                  'test-results', '__pycache__', '.terraform', '.venv', '.git'}
LAYERS = ['backend_full','frontend_quality','browser','governance','infrastructure',
          'migration','security','supply_chain','mixed_capacity']


def now():
    return datetime.now(timezone.utc).isoformat()


def sha(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path: Path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n')


def git(*args):
    return subprocess.check_output(['git',*args],cwd=ROOT)


def inventory():
    tracked = git('ls-files','-z','--',*SCOPES)
    untracked = git('ls-files','--others','--exclude-standard','-z','--',*SCOPES)
    names = set(tracked.decode().split('\0')+untracked.decode().split('\0'))
    names.add(Path(__file__).resolve().relative_to(ROOT).as_posix())
    files = []
    for name in sorted(names):
        path = ROOT/name
        if not name or not path.is_file() or set(Path(name).parts)&EXCLUDED_PARTS or path.suffix == '.pyc':
            continue
        if not path.resolve().is_relative_to(ROOT):
            raise ValueError('Source symlink leaves repository: '+name)
        files.append({'path':name,'sha256':sha(path),'bytes':path.stat().st_size})
    return {'kind':'SLICE3_EXECUTED_SOURCE_INVENTORY','files':files}


def identity():
    status = git('status','--porcelain=v1','--untracked-files=all').decode().splitlines()
    return {'sourceHead':git('rev-parse','HEAD').decode().strip(),
            'sourceTree':git('rev-parse','HEAD^{tree}').decode().strip(),
            'branch':git('branch','--show-current').decode().strip(),
            'workingTreeDirty':bool(status),'workingTreeStatus':status,
            'identityScope':'WORKTREE_WITH_EXACT_SOURCE_MANIFEST' if status else 'CLEAN_COMMIT_TREE'}


def ref(path: Path):
    return {'path':path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else str(path),
            'sha256':sha(path),'bytes':path.stat().st_size}


def selected(patterns):
    files = {}
    for pattern in patterns:
        if Path(pattern).is_absolute() or '..' in Path(pattern).parts:
            raise ValueError('Report patterns must be repository-relative')
        for value in glob.glob(str(ROOT/pattern),recursive=True):
            path=Path(value)
            if path.is_file():
                if not path.resolve().is_relative_to(ROOT):
                    raise ValueError('Report symlink leaves repository')
                files[path.relative_to(ROOT).as_posix()] = {'sha256':sha(path),'mtimeNs':path.stat().st_mtime_ns,'bytes':path.stat().st_size}
    return files


def collect(args):
    argv=args.command[1:] if args.command and args.command[0]=='--' else args.command
    if not argv:
        raise ValueError('A root-selected command argv is required after --')
    out=args.out.resolve();cwd=(ROOT/args.cwd).resolve()
    if not cwd.is_relative_to(ROOT) or not cwd.is_dir():
        raise ValueError('Command working directory must be in this repository')
    if out.is_relative_to(ROOT) and set(out.relative_to(ROOT).parts)&EXCLUDED_PARTS:
        raise ValueError('Evidence destination must survive target/build cleanup')
    out.mkdir(parents=True,exist_ok=False)
    before_id=identity()
    if before_id['branch'] != 'feat/SLICE-V1-003-advertising-traffic-efficiency':
        raise ValueError('Outside named Slice 3 branch')
    if args.expect_head and before_id['sourceHead'] != args.expect_head:
        raise ValueError('Unexpected source HEAD')
    if args.require_clean and before_id['workingTreeDirty']:
        raise ValueError('Root requested a clean checkpoint')
    before=inventory();write(out/'source-before.json',before);write(out/'identity-before.json',before_id)
    report_before=selected(args.capture)
    write(out/'report-state-before.json',report_before)
    receipt={'kind':'SLICE3_LAYER_EXECUTION_CANDIDATE','id':args.layer,'runId':args.run_id,
             'command':shlex.join(argv),'argv':argv,'cwd':str(cwd.relative_to(ROOT)),
             **before_id,'sourceInventorySha256':sha(out/'source-before.json'),
             'sourceInventory':ref(out/'source-before.json'),'startedAt':now(),
             'result':'RUNNING_NOT_ASSESSED','productionWriteEnabled':False,
             'controllerApprovalClaimMade':False,'engineeringClosureClaimMade':False,
             'publicationIdentity':{key:os.environ.get(key) for key in [
                 'GITHUB_SHA','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_JOB',
                 'MARKETOPS_EVIDENCE_SOURCE_HEAD_SHA','MARKETOPS_EVIDENCE_TESTED_MERGE_SHA']}}
    write(out/'layer-candidate.json',receipt)
    start=time.monotonic();code=None;failure=None
    try:
        with (out/'command.log').open('wb') as log:
            code=subprocess.run(argv,cwd=cwd,stdout=log,stderr=subprocess.STDOUT,timeout=args.timeout).returncode
    except (OSError,subprocess.TimeoutExpired) as error:
        failure=type(error).__name__+': '+str(error)
    receipt.update(finishedAt=now(),elapsedSeconds=round(time.monotonic()-start,3),exitCode=code,executionError=failure)
    after_id=identity();after=inventory();write(out/'source-after.json',after);write(out/'identity-after.json',after_id)
    old={row['path']:row['sha256'] for row in before['files']};new={row['path']:row['sha256'] for row in after['files']}
    changed=sorted(key for key in old.keys()|new.keys() if old.get(key)!=new.get(key))
    stable=not changed and before_id['sourceHead']==after_id['sourceHead'] and before_id['sourceTree']==after_id['sourceTree']
    receipt.update(sourceStable=stable,sourceChangedPaths=changed,identityAfter=after_id,sourceInventoryAfterSha256=sha(out/'source-after.json'))
    report_after=selected(args.capture);artifacts=[];stale=[];cases=[];parse_errors=[]
    for relative,state in sorted(report_after.items()):
        path=ROOT/relative;target=out/'raw'/relative;target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(path,target)
        fresh=report_before.get(relative)!=state
        if not fresh:stale.append(relative)
        artifacts.append({**ref(target),'originalPath':relative,'writtenDuringCommand':fresh})
        if path.suffix=='.xml':
            try:
                xml=ET.parse(target).getroot()
                for index,node in enumerate(xml.iter('testcase')):
                    status=next((key.upper() for key in ['failure','error','skipped'] if node.find(key) is not None),'PASSED')
                    cases.append({'class':node.get('classname'),'name':node.get('name'),'status':status,
                                  'report':ref(target),'nodeIndex':index,'seconds':node.get('time')})
            except ET.ParseError as error:parse_errors.append({'path':relative,'error':str(error)})
    write(out/'preserved-reports.json',artifacts);write(out/'named-testcase-nodes.json',cases)
    counts={key:sum(row['status']==value for row in cases) for key,value in [('passed','PASSED'),('failures','FAILURE'),('errors','ERROR'),('skipped','SKIPPED')]}
    receipt.update(evidence=[ref(out/'command.log')]+[{key:row[key] for key in ['path','sha256']} for row in artifacts],
                   capturePatterns=args.capture,stalePreexistingReports=stale,xmlParseErrors=parse_errors,
                   testcaseCounts=counts if cases else None,namedTestcaseNodes=ref(out/'named-testcase-nodes.json'),
                   failures=counts['failures'] if cases else None,errors=counts['errors'] if cases else None,skipped=counts['skipped'] if cases else None)
    receipt['result']=('INVALID_SOURCE_CHANGED' if not stable else 'COMMAND_FAILED' if code!=0 or failure else
                       'INVALID_REPORTS' if stale or parse_errors or any(counts[key] for key in ['failures','errors','skipped']) else
                       'COMMAND_SUCCEEDED_REVIEW_REQUIRED')
    receipt['boundary']='Command exit and raw reports only. Root must review full layer scope, source identity, assertions, capacity/resource bounds and publication evidence before any PASS assessment.'
    write(out/'layer-candidate.json',receipt)
    print(json.dumps({'result':receipt['result'],'receipt':str(out/'layer-candidate.json'),'sourceStable':stable,'exitCode':code,'testcaseCounts':receipt['testcaseCounts']}))
    return 0 if receipt['result']=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' else 1


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--layer',choices=LAYERS,required=True)
    parser.add_argument('--run-id',required=True)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--cwd',default='.')
    parser.add_argument('--capture',action='append',default=[],help='Repository-relative glob, repeat for every raw report family')
    parser.add_argument('--expect-head')
    parser.add_argument('--require-clean',action='store_true')
    parser.add_argument('--timeout',type=int,help='Command wall-clock seconds; omitted means root owns interruption')
    parser.add_argument('command',nargs=argparse.REMAINDER)
    return collect(parser.parse_args())


if __name__=='__main__':
    sys.exit(main())

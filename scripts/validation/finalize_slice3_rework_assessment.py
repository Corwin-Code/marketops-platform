#!/usr/bin/env python3
"""Generate current Slice 3 views from measured final-gate evidence; never replay W10 PASS."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
BASE = Path('docs/07-phase-evidence/SLICE-V1-003/rework-r1')
GATE = BASE / 'final-gate-r1'
HISTORY = GATE / 'historical-w10-central'
MANIFEST = GATE / 'EXECUTION-MANIFEST.json'
CONTRACT = Path('docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md')
FROZEN_MD = BASE.parent / 'SLICE-V1-003-FROZEN-FINDING-SET-001.md'
FROZEN_JSON = BASE.parent / 'SLICE-V1-003-FROZEN-FINDING-SET-001.json'
DEFERRED = BASE / 'S3-REL-DEFERRED-REGISTER.json'
DEFERRED_SHA256 = 'd7491609b66047296ec7fd2a5e1097d71cda3f9b275300b57de77e5c1889392e'
CONTRACT_SHA256 = '1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c'
FROZEN_MD_SHA256 = '15b3c076fc7f1d283a2c7359d9647d91d3ecfccd9b229be1f734f4e7d4ceefc1'
FROZEN_JSON_SHA256 = 'f4af74f5086772dc70c3ec3cc7aa8808e9441e96109d301b145e70c18f6131a0'
W10_HEAD = '3ff042df66d5d6924b587cac96fc652b93bf5e7a'
REPORT = GATE / 'controller-package/VERIFICATION-RESULT.json'
REPORT_SHA256 = '6f9581d9b09485a35fe404b13ab06422dc2672b7182afc52da2442dcc7660127'
HISTORICAL_HASHES = {
    'ENGINEERING_VERIFICATION.json': '52083c8a36be39488ee4e64d4198d9904d1a4306bb0ecfd1f95c8711bdc51e25',
    'FINDING-CLOSURE-MATRIX.json': 'e6089a0a662ae90fd641a5cb864f69e36807286133c886e2e95cb210e47dcb1d',
    'S3-AC-REWORK-STATUS.json': 'f9af2403b1ebe0eecd1bb47b442ad7a5b607e4a6a47eebf6d2590865473c53b5',
}
VERIFICATION_OUTPUT = BASE / 'ENGINEERING_VERIFICATION.json'
AC_MATRIX_OUTPUT = BASE / 'S3-AC-REWORK-STATUS.json'
FINDING_MATRIX_OUTPUT = BASE / 'FINDING-CLOSURE-MATRIX.json'
REQUIRED_LAYERS = {'backend_full', 'frontend_quality', 'browser', 'governance', 'infrastructure',
                   'migration', 'security', 'supply_chain', 'mixed_capacity'}
RESIDUAL_CHECKS = {'S3-DR-004': ['CV-A', 'CV-B'], 'S3-DR-011': ['CV-B'],
                   'S3-DR-015': ['CV-A', 'CV-C'], 'S3-DR-020': ['CV-D'],
                   'S3-DR-022': ['CV-A', 'CV-B', 'CV-C', 'CV-D', 'CV-E']}
CURRENT_PHASE_STATES = {
    'PENDING': {
        'slice_v1_003_rework_status': 'CODEX_RESIDUAL_REWORK_AND_VERIFICATION_IN_PROGRESS',
        'slice_v1_003_implementation_state': 'RESIDUAL_REWORK_IMPLEMENTED_VERIFICATION_IN_PROGRESS',
        'slice_v1_003_engineering_closure_claim': 'NOT_CLAIMED_RESIDUAL_VERIFICATION_PENDING',
        'slice_v1_003_controller_verdict': 'NOT_REVIEWED_NEW_CANDIDATE',
        'active_gate': 'CODEX_SLICE_V1_003_RESIDUAL_REWORK_VERIFICATION',
        'candidate_state_scope': 'SLICE_V1_003_RESIDUAL_REWORK_NOT_CONTROLLER_APPROVED',
        'next_authorized_actor': 'CODEX',
        'next_action': 'COMPLETE_AUTHORIZED_RESIDUAL_REWORK_AND_EXACT_EVIDENCE',
    },
    'COMPLETE': {
        'slice_v1_003_rework_status': 'CODEX_ENGINEERING_COMPLETE_CONTROLLER_PENDING',
        'slice_v1_003_implementation_state': 'RESIDUAL_REWORK_ENGINEERING_VERIFIED',
        'slice_v1_003_engineering_closure_claim': 'CODEX_ENGINEERING_COMPLETE_INDEPENDENT_CONTROLLER_PENDING',
        'slice_v1_003_controller_verdict': 'PENDING_INDEPENDENT_REVIEW',
        'active_gate': 'CONTROLLER_SLICE_V1_003_FINAL_CLOSURE_VERIFICATION',
        'candidate_state_scope': 'SLICE_V1_003_ENGINEERING_COMPLETE_NOT_CONTROLLER_APPROVED',
        'next_authorized_actor': 'CONTROLLER',
        'next_action': 'INDEPENDENT_FINAL_CLOSURE_VERIFICATION_ON_EXACT_CURRENT_HEAD',
    },
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()


def read(path: Path):
    return json.loads((ROOT / path).read_text())


def require(condition: bool, message: str):
    if not condition:
        raise ValueError(message)


def reference(path: Path) -> dict:
    return {'path': path.as_posix(), 'sha256': sha256(ROOT / path)}


def checked_reference(ref: dict) -> Path:
    require(isinstance(ref, dict), 'Evidence must have a path and SHA-256')
    path = Path(ref.get('path', ''))
    require(bool(ref.get('path')) and not path.is_absolute() and '..' not in path.parts,
            'Evidence reference must remain within the repository')
    target = ROOT / path
    require(target.is_file() and target.resolve().is_relative_to(ROOT.resolve()), 'Evidence file missing or outside repository: ' + str(path))
    require(sha256(target) == ref.get('sha256'), 'Evidence/source digest mismatch: ' + str(path))
    return target


def indexed(rows: list, expected: set[str], label: str) -> dict:
    require(isinstance(rows, list) and len(rows) == len(expected), label + ' count is incomplete')
    result = {row.get('id'): row for row in rows}
    require(set(result) == expected, label + ' ids are duplicate, missing or unexpected')
    return result


def authorities():
    for path, expected in [(CONTRACT, CONTRACT_SHA256), (FROZEN_MD, FROZEN_MD_SHA256), (FROZEN_JSON, FROZEN_JSON_SHA256)]:
        require(sha256(ROOT / path) == expected, 'Immutable authority changed: ' + str(path))
    for name, expected in HISTORICAL_HASHES.items():
        require(sha256(ROOT / HISTORY / name) == expected, 'Immutable W10 assessment changed: ' + name)
    require(sha256(ROOT / REPORT) == REPORT_SHA256, 'Immutable Controller report changed')
    require(sha256(ROOT / DEFERRED) == DEFERRED_SHA256, 'Exact deferred release obligations changed')
    report = read(REPORT)
    require(report['verdict'] == 'NOT_PASS_EXISTING_FINDINGS_NOT_FULLY_CLOSED', 'Historical Controller verdict changed')
    criteria = dict(re.findall(r'^- `(S3-AC-\d{3})` — (.+)$', (ROOT / CONTRACT).read_text(), re.M))
    frozen = indexed(read(FROZEN_JSON)['findings'], {f'S3-DR-{i:03}' for i in range(1, 23)}, 'Frozen Findings')
    require(set(criteria) == {f'S3-AC-{i:03}' for i in range(1, 201)}, 'Exact 200 accepted ACs required')
    deferred = read(DEFERRED)['entries']
    require(len(deferred) == 24 and all(row['state'] == 'DEFERRED_PRODUCTION_BLOCKING' and row['real_evidence_obtained'] is False for row in deferred), 'Deferred release obligations changed')
    return criteria, frozen, report


def source_inventory(ref: dict) -> dict:
    document = json.loads(checked_reference(ref).read_text())
    files = document.get('files', [])
    require(bool(files) and len({row['path'] for row in files}) == len(files), 'Source inventory is empty or duplicate')
    for row in files:
        checked_reference(row)
    # Complete runtime/test/validator inputs; generated evidence views are outputs, not compiled source.
    mandatory = set()
    for directory in ['backend/marketops-server/src', 'frontend/marketops-console/src', 'scripts', 'tests']:
        for path in (ROOT / directory).rglob('*'):
            if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc':
                mandatory.add(path.relative_to(ROOT).as_posix())
    mandatory.update(['backend/marketops-server/pom.xml', 'frontend/marketops-console/package.json', 'frontend/marketops-console/package-lock.json'])
    require(mandatory <= {row['path'] for row in files}, 'Source inventory omits required runtime/test/validator inputs')
    return document


def validate_proof(proof: dict, layers: dict, source_digest: str):
    require(proof.get('layer') in layers and bool(proof.get('scope')), 'Named proof lacks execution layer or assertion scope')
    checked_reference(proof['source'])
    path = checked_reference(proof['evidence'])
    require(layers[proof['layer']]['sourceInventorySha256'] == source_digest, 'Proof source inventory differs from execution')
    require(proof['evidence'] in layers[proof['layer']]['evidence'], 'Named proof is not in the actual layer artifact inventory')
    if proof.get('kind') == 'junit':
        nodes = [node for node in ET.parse(path).getroot().iter('testcase')
                 if node.get('classname') == proof.get('class') and node.get('name') == proof.get('name')]
        require(len(nodes) == 1 and not any(nodes[0].find(tag) is not None for tag in ['failure', 'error', 'skipped']), 'Named JUnit proof not uniquely passed')
    elif proof.get('kind') == 'json':
        value = json.loads(path.read_text())
        require(bool(proof.get('assertions')), 'Structured proof lacks exact result assertions')
        for assertion in proof['assertions']:
            cursor = value
            for key in assertion['pointer'].strip('/').split('/'):
                key = key.replace('~1', '/').replace('~0', '~')
                cursor = cursor[int(key)] if isinstance(cursor, list) else cursor[key]
            require(cursor == assertion['expected'], 'Structured execution assertion did not match: ' + assertion['pointer'])
    else:
        raise ValueError('Only measured named JUnit or structured execution proof can admit closure')


def validate_execution(manifest: dict, criteria: dict, frozen: dict) -> dict:
    require(manifest.get('productionWriteEnabled') is False and manifest.get('controllerApprovalClaimMade') is False, 'Evidence cannot enable production or self-approve')
    require(manifest.get('status') in {'PENDING', 'COMPLETE'}, 'Unknown execution manifest status')
    if manifest['status'] == 'PENDING':
        require(manifest.get('engineeringClosureClaimMade') is False, 'Pending evidence cannot claim engineering closure')
        return {}
    source = manifest.get('source')
    require(isinstance(source, dict), 'Complete manifest lacks measured source identity')
    require(bool(re.fullmatch(r'[0-9a-f]{40}', source.get('sourceHead', ''))) and bool(re.fullmatch(r'[0-9a-f]{40}', source.get('sourceTree', ''))), 'Measured source HEAD/tree must be exact')
    require(source.get('identityScope') in {'CLEAN_COMMIT_TREE', 'WORKTREE_WITH_EXACT_SOURCE_MANIFEST'},
            'Source identity must distinguish committed bytes from an amended worktree')
    source_inventory(source['inventory'])
    source_digest = source['inventory']['sha256']
    layers = indexed(manifest.get('layers', []), REQUIRED_LAYERS, 'Required verification layers')
    for name, layer in layers.items():
        require(layer.get('result') == 'PASS' and bool(layer.get('command')) and bool(layer.get('runId')), 'Required layer did not execute/pass: ' + name)
        require(layer.get('sourceInventorySha256') == source_digest, 'Required layer source identity mismatch: ' + name)
        require(layer.get('sourceHead') == source['sourceHead'] and layer.get('sourceTree') == source['sourceTree'],
                'Layer recorded source HEAD/tree differs from assessed identity: ' + name)
        require(all(layer.get(key) == 0 for key in ['failures', 'errors', 'skipped']), 'Required layer has failing/error/skipped evidence: ' + name)
        require(bool(layer.get('evidence')), 'Missing raw execution evidence: ' + name)
        raw_cases = 0
        for ref in layer['evidence']:
            path = checked_reference(ref)
            if path.suffix == '.xml':
                cases = list(ET.parse(path).getroot().iter('testcase'))
                raw_cases += len(cases)
                require(not any(node.find(tag) is not None for node in cases for tag in ['failure', 'error', 'skipped']),
                        'Raw layer report contains failure/error/skipped nodes: ' + name)
        if name == 'backend_full':
            require(raw_cases > 0 and layer.get('scope') == 'FULL_RELEVANT_VERIFICATION',
                    'Full backend layer lacks actual full-run JUnit evidence')
    cv = indexed(manifest.get('verificationChecks', []), {'CV-A', 'CV-B', 'CV-C', 'CV-D', 'CV-E'}, 'Residual checks')
    for identity, row in cv.items():
        require(row.get('result') == 'PASS' and bool(row.get('engineeringReason')) and bool(row.get('proofLimits')), 'Residual assessment incomplete: ' + identity)
        require({'positive', 'adverse'} <= {proof.get('role') for proof in row.get('proofs', [])}, 'Residual lacks positive and adverse proof: ' + identity)
        for proof in row['proofs']:
            validate_proof(proof, layers, source_digest)
    for label, key, expected in [('criteria', 'criteria', set(criteria)), ('findings', 'findings', set(frozen))]:
        rows = indexed(manifest.get(key, []), expected, label)
        for identity, row in rows.items():
            require(bool(row.get('engineeringReason')) and bool(row.get('layers')) and set(row['layers']) <= REQUIRED_LAYERS, 'Individual current assessment absent: ' + identity)
            require(bool(row.get('proofs')), 'Individual named proof absent: ' + identity)
            for proof in row['proofs']:
                validate_proof(proof, layers, source_digest)
    require(manifest.get('productionWriteEnabled') is False and manifest.get('controllerApprovalClaimMade') is False, 'Evidence cannot enable production or self-approve')
    return {'source': source, 'layers': layers, 'checks': cv, 'criteria': {row['id']: row for row in manifest['criteria']}, 'findings': {row['id']: row for row in manifest['findings']}}


def build_outputs(manifest_path: Path = MANIFEST) -> dict[Path, bytes]:
    criteria, frozen, report = authorities()
    manifest = read(manifest_path)
    execution = validate_execution(manifest, criteria, frozen)
    complete = bool(execution)
    controller = {row['id']: row for row in report['findings']}
    manifest_ref = reference(manifest_path)
    historical = {'reviewedHead': W10_HEAD, 'verdict': report['verdict'], 'evidence': reference(REPORT)}
    pending = 'PENDING_INDEPENDENT_REVIEW' if complete else 'NOT_REVIEWED_NEW_CANDIDATE'
    common = {'rework_head': 'EXACT_CONTAINING_COMMIT_SUPPLIED_BY_APPEND_ONLY_EXTERNAL_READBACK',
              'historical_controller': historical, 'independent_controller_verdict': pending,
              'closure_claim_made': False, 'engineering_closure_claim_made': complete,
              'production_write_enabled': False, 'execution_manifest': manifest_ref}
    old_ac = {row['id']: row for row in read(HISTORY / AC_MATRIX_OUTPUT.name)['entries']}
    ac_rows = []
    for index, (identity, text) in enumerate(criteria.items()):
        status = ('CANDIDATE_PREREQUISITES_PASS_CONTROLLER_PENDING' if identity == 'S3-AC-200' else 'VERIFIED') if complete else ('INDEPENDENT_CONTROLLER_NOT_PASSED_NEW_HEAD_NOT_REVIEWED' if identity == 'S3-AC-200' else 'CURRENT_VERIFICATION_PENDING')
        ac_rows.append({'id': identity, 'criterion': text, 'status': status,
            'finding_ids': [key for key, value in frozen.items() if identity in value['criteria']],
            'historical_engineering_evidence': {**reference(HISTORY / AC_MATRIX_OUTPUT.name), 'jsonPointer': f'/entries/{index}'},
            'engineering_evidence': [manifest_ref] if complete else [],
            'current_assessment': execution.get('criteria', {}).get(identity),
            'external_release_obligations': old_ac[identity]['external_release_obligations'],
            'notes': 'Historical Controller NOT_PASS is preserved; new independent closure is not supplied by Codex.'})
    finding_rows = []
    for index, (identity, original) in enumerate(frozen.items()):
        finding_rows.append({'id': identity, 'severity': original['severity'],
            'status': 'CLOSED_WITH_EVIDENCE' if complete else 'REWORK_EVIDENCE_PENDING' if identity in RESIDUAL_CHECKS else 'HISTORICAL_SCOPE_ACCEPTED_CURRENT_REGRESSION_PENDING',
            'reproduction': original['observed'], 'required_rework': original['required_rework'], 'required_verification': original['verification'],
            'acceptance_criteria': original['criteria'], 'residual_checks': RESIDUAL_CHECKS.get(identity, []),
            'historical_controller_disposition': controller[identity]['controller_disposition'],
            'historical_assessment': {**reference(HISTORY / FINDING_MATRIX_OUTPUT.name), 'jsonPointer': f'/entries/{index}'},
            'current_assessment': execution.get('findings', {}).get(identity), 'evidence': [manifest_ref] if complete else [],
            'controller_verdict': pending, 'closed_by_codex_engineering_assessment': complete})
    verification = {'kind': 'SLICE3_FINAL_GATE_CODEX_ENGINEERING_VERIFICATION',
        'status': 'ENGINEERING_COMPLETE_CURRENT_HEAD_READBACK_AND_CONTROLLER_PENDING' if complete else 'RESIDUAL_REWORK_VERIFICATION_IN_PROGRESS',
        'historicalController': historical, 'currentControllerVerdict': pending, 'executionManifest': manifest_ref,
        'assessedSource': execution.get('source'), 'verificationChecks': manifest.get('verificationChecks', []),
        'requiredLayers': sorted(REQUIRED_LAYERS), 'engineeringClosureClaimMade': complete,
        'independentControllerClosed': False, 'productionWriteEnabled': False, 'deferredReleaseObligations': 24,
        'dispositionCounts': {'findings': 22, 'historicallyAcceptedForReviewedScope': 17, 'residualFindings': 5,
            'frozenClauses': sum(len(row['required_rework'])+len(row['verification']) for row in frozen.values()),
            'criteria': 200, 'currentEngineeringVerifiedCriteria': 199 if complete else 0},
        'historicalMeasurementReconciliation': reference(GATE / 'CV-E-MEASUREMENT-RECONCILIATION.json'),
        'publicationBoundary': 'No self-referential current-commit/CI identity is invented. Exact append-only containing commit/CI readback remains separate.'}
    return {VERIFICATION_OUTPUT: json_bytes(verification),
        AC_MATRIX_OUTPUT: json_bytes({'document_type': 'CODEX_FINAL_GATE_CRITERION_ASSESSMENT', 'contract_sha256': CONTRACT_SHA256, **common, 'entries': ac_rows}),
        FINDING_MATRIX_OUTPUT: json_bytes({'document_type': 'CODEX_FINAL_GATE_FINDING_ASSESSMENT', 'finding_set_sha256': FROZEN_MD_SHA256,
            'reviewed_head': 'a0711f1ae430e70ab7ec06917004e9dbfd1fb4eb', **common, 'entries': finding_rows})}


def finalize(check: bool = False, manifest_path: Path = MANIFEST):
    outputs = build_outputs(manifest_path)
    for path, data in outputs.items():
        if check:
            require((ROOT / path).read_bytes() == data, 'Current assessment is stale: ' + str(path))
        else:
            (ROOT / path).write_bytes(data)
    print(json.dumps({'result': 'DERIVATION_VALID', 'mode': 'check' if check else 'write',
                      'engineeringClosureClaimMade': json.loads(outputs[VERIFICATION_OUTPUT])['engineeringClosureClaimMade']}))


def validated_current_phase() -> dict[str, str]:
    """Admit a phase only after exact evidence and all three current views agree."""
    outputs = build_outputs()
    for path, data in outputs.items():
        require((ROOT / path).read_bytes() == data, 'Current assessment is stale: ' + str(path))
    complete = json.loads(outputs[VERIFICATION_OUTPUT])['engineeringClosureClaimMade']
    return dict(CURRENT_PHASE_STATES['COMPLETE' if complete else 'PENDING'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--manifest', type=Path, default=MANIFEST)
    args = parser.parse_args()
    finalize(args.check, args.manifest)


if __name__ == '__main__':
    main()

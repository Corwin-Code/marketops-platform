#!/usr/bin/env python3
from __future__ import annotations

import csv
import hashlib
import json
import re
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

if __package__:
    from .validation.finalize_slice3_rework_assessment import validated_current_phase
else:
    from validation.finalize_slice3_rework_assessment import validated_current_phase

ROOT = Path(__file__).resolve().parents[1]

DR0003_R1_REVIEW_RELATIVE_PATH = (
    "docs/08-handoffs/"
    "CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md"
)
DR0003_R1_PROMPT_RELATIVE_PATH = (
    "docs/08-handoffs/CODEX-PR18-DR-0003-TARGETED-REWORK-R1.md"
)
DR0003_R1_MANIFEST_RELATIVE_PATH = (
    "docs/08-handoffs/DR-0003-PR18-R1-ARTIFACT-HASHES.md"
)
DR0003_R2_REVIEW_RELATIVE_PATH = (
    "docs/08-handoffs/"
    "CONTROLLER-PR18-DR-0003-INDEPENDENT-RE-REVIEW-R2.md"
)
DR0003_R2_PROMPT_RELATIVE_PATH = (
    "docs/08-handoffs/CODEX-PR18-DR-0003-TARGETED-REWORK-R2.md"
)
DR0003_R2_MANIFEST_RELATIVE_PATH = (
    "docs/08-handoffs/DR-0003-PR18-R2-ARTIFACT-HASHES.md"
)

DR0004_REQUIRED_BASE = "dce9eecb9500504c15e63b8939a39822f87f883d"
DR0004_REQUIRED_BASE_TREE = "37feff5306f8c3c63022243bbcdbc6e7d29fd412"
DR0004_DR_RELATIVE_PATH = (
    "docs/00-governance/"
    "DR-0004-engineering-execution-closure-protocol-alignment.md"
)
DR0004_EXECUTION_ENVELOPE_RELATIVE_PATH = (
    "docs/00-governance/EXECUTION_ENVELOPE_POLICY.md"
)
DR0004_CLOSURE_STANDARD_RELATIVE_PATH = (
    "docs/00-governance/CLOSURE_SNAPSHOT_STANDARD.md"
)
DR0004_AMENDMENT_RELATIVE_PATH = (
    "docs/00-governance/"
    "DR-0004-AMENDMENT-001-activation-and-owner-acceptance-provenance.md"
)
DR0004_OWNER_ACCEPTANCE_RELATIVE_PATH = (
    "docs/08-handoffs/OWNER-DR-0004-ACCEPTANCE-EVIDENCE.md"
)
DR0004_R1_DEEP_REVIEW_RELATIVE_PATH = (
    "docs/08-handoffs/CONTROLLER-PR19-DR0004-DEEP-REVIEW-R1.md"
)
DR0004_R1_FROZEN_FINDINGS_RELATIVE_PATH = (
    "docs/08-handoffs/FROZEN-FINDING-SET-DR0004-PR19-R1.md"
)
DR0004_R1_REWORK_AUTH_RELATIVE_PATH = (
    "docs/08-handoffs/CONTROLLER-CODEX-REWORK-AUTHORIZATION-PR19-R1.md"
)
DR0004_REQUIRED_FILES = [
    DR0004_DR_RELATIVE_PATH,
    DR0004_EXECUTION_ENVELOPE_RELATIVE_PATH,
    DR0004_CLOSURE_STANDARD_RELATIVE_PATH,
    DR0004_AMENDMENT_RELATIVE_PATH,
    DR0004_OWNER_ACCEPTANCE_RELATIVE_PATH,
    DR0004_R1_DEEP_REVIEW_RELATIVE_PATH,
    DR0004_R1_FROZEN_FINDINGS_RELATIVE_PATH,
    DR0004_R1_REWORK_AUTH_RELATIVE_PATH,
]
DR0004_ARTIFACT_HASHES = {
    DR0004_DR_RELATIVE_PATH: (
        "dcc073bb8f6593bd24b4a74a96f06d0c45ece2f1c192615deb7301cbb850da9a"
    ),
    DR0004_EXECUTION_ENVELOPE_RELATIVE_PATH: (
        "0dd73e8ed3e29a9903c991d5e723f40eb6a42d63841e6e952bf8f1292194f203"
    ),
    DR0004_CLOSURE_STANDARD_RELATIVE_PATH: (
        "487379bc00badc37cd81bd82dec31621c25fbad2d56a7acd6f40cf2244d7ece1"
    ),
}
DR0004_R1_ARTIFACT_HASHES = {
    DR0004_AMENDMENT_RELATIVE_PATH: (
        "cea88c6b72b480ad7f39a45390e457de316b6be6511dad45a5d0f6c63716779c"
    ),
    DR0004_OWNER_ACCEPTANCE_RELATIVE_PATH: (
        "f83349ea537fd48575787dccfaa624ec39c5079181ccf0da6c69e996768bda88"
    ),
    DR0004_R1_DEEP_REVIEW_RELATIVE_PATH: (
        "f717c4a53abd597d73a0662c956f6f891bc394a144cb2abe72cd462a76cb7742"
    ),
    DR0004_R1_FROZEN_FINDINGS_RELATIVE_PATH: (
        "b6ba27472ab8f0f1150468a48144eed0c20480a15bd32596df0e7834cf573116"
    ),
    DR0004_R1_REWORK_AUTH_RELATIVE_PATH: (
        "83b81a024641f7db5e59515a6edaf1b301a224f57d530d49699098e9a8cd1ce2"
    ),
}
DR0004_PROTECTED_CONTRACT_HASHES = {
    "docs/01-requirements/V1_PRODUCT_CONTRACT.md": (
        "99a02f2b21efa5f265199ed81d3d9826604fbb066b48c35ab00d81cb23dbc5c2"
    ),
    "docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md": (
        "0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5"
    ),
}
DR0004_CURRENT_STATE = {
    "accepted_contract_mutation": "PROHIBITED_APPEND_ONLY_AMENDMENT_REQUIRED",
    "execution_envelope": "EXECUTION_ENVELOPE_V1",
    "maker_remote_git_authority": "DENIED",
    "remote_git_publication_delegate": "CODEX",
    "deep_review_mode": "ONE_SHOT_DISCOVERY_FROZEN_FINDING_SET",
    "final_gate_mode": "CLOSURE_VERIFICATION_ONLY",
    "owner_formal_slice_closure": "HUMAN_OWNER_ACCEPTED",
    "closure_snapshot_before_next_slice": (
        "SATISFIED_EXACT_OWNER_ACCEPTED"
    ),
    "dual_truth_model": "NORMATIVE_AND_IMPLEMENTATION_FACT",
    "dr0004_original_contract": DR0004_DR_RELATIVE_PATH,
    "dr0004_original_contract_sha256": DR0004_ARTIFACT_HASHES[
        DR0004_DR_RELATIVE_PATH
    ],
    "dr0004_amendment": DR0004_AMENDMENT_RELATIVE_PATH,
    "dr0004_amendment_sha256": DR0004_R1_ARTIFACT_HASHES[
        DR0004_AMENDMENT_RELATIVE_PATH
    ],
    "dr0004_owner_acceptance_evidence": DR0004_OWNER_ACCEPTANCE_RELATIVE_PATH,
    "dr0004_owner_acceptance_evidence_sha256": DR0004_R1_ARTIFACT_HASHES[
        DR0004_OWNER_ACCEPTANCE_RELATIVE_PATH
    ],
    "dr0004_acceptance": "HUMAN_OWNER_ACCEPTED",
    "dr0004_repository_effect": "ACTIVE_ON_PROTECTED_MAIN",
    "dr0004_effective_condition": (
        "EXACT_HUMAN_OWNER_ACCEPTANCE_EVIDENCE_AND_PROTECTED_MAIN"
    ),
    "dr0004_frozen_original_status_semantics": "PROPOSAL_TIME_PROVENANCE_ONLY",
    "execution_envelope_state": "ACTIVE_UNDER_DR_0004",
    "closure_snapshot_standard_state": "ACTIVE_UNDER_DR_0004",
}

REQUIRED_FILES = [
    "README.md",
    "CLAUDE.md",
    "AGENTS.md",
    "docs/00-governance/PROJECT_CHARTER.md",
    "docs/00-governance/CURRENT_STATE.md",
    "docs/00-governance/DECISION_LOG.md",
    "docs/00-governance/DR-0001-temporary-codex-git-execution-delegation.md",
    "docs/00-governance/DR-0002-split-controlled-file-import-from-wp-p0-003.md",
    "docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md",
    "docs/00-governance/CONTROLLER_REVIEW_STANDARD.md",
    "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md",
    "docs/00-governance/HANDOFF_PROTOCOL.md",
    "docs/00-governance/OPEN_QUESTIONS.md",
    "docs/00-governance/QUALITY_GATES.md",
    "docs/01-requirements/baseline-v1.0-cn.md",
    "docs/01-requirements/naming-baseline-cn.md",
    "docs/01-requirements/SHA256SUMS.txt",
    "docs/01-requirements/traceability.csv",
    "docs/02-architecture/designs/WP-P0-001-foundation-design.md",
    "docs/03-work-items/BACKLOG-PHASE-0.md",
    "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md",
    "docs/03-work-items/WP-P0-002-organization-store-warehouse-credential-metadata.md",
    "docs/03-work-items/WP-P0-003-durable-ingestion-control-plane-immutable-raw-evidence.md",
    "docs/02-architecture/designs/WP-P0-003-executable-design-validation-addendum.md",
    "docs/07-phase-evidence/WP-P0-003/executable-design-validation.md",
    "docs/07-phase-evidence/WP-P0-003/post-merge-execution-verification.md",
    "docs/05-testing/TEST_STRATEGY.md",
    "docs/07-phase-evidence/WP-P0-002/README.md",
    "docs/07-phase-evidence/WP-P0-002/acceptance-criteria.md",
    ".github/pull_request_template.md",
    ".github/workflows/governance.yml",
    "tests/test_validate_governance.py",
]

DR0003_REQUIRED_FILES = [
    "START_HERE.md",
    "CONTRIBUTING.md",
    "docs/00-governance/AI_OPERATING_MODEL.md",
    "docs/00-governance/ASSET_DISPOSITION_LEDGER.md",
    "docs/00-governance/CHANGE_CONTROL.md",
    "docs/00-governance/CLAUDE_PROJECT_INSTRUCTIONS.md",
    "docs/00-governance/DR-0003-v1-product-delivery-baseline-reset.md",
    "docs/00-governance/GOVERNANCE_RESET_FILE_MATRIX.md",
    "docs/00-governance/OWNER_DECISIONS_V1.md",
    "docs/01-requirements/SOURCE_MANIFEST.md",
    "docs/01-requirements/V1_PRODUCT_CONTRACT.md",
    "docs/01-requirements/v1-traceability.csv",
    "docs/02-architecture/README.md",
    "docs/02-architecture/V1_AI_DATA_AND_EXECUTION_BOUNDARY.md",
    "docs/02-architecture/V1_SHARED_SPINE.md",
    "docs/02-architecture/adr/ADR-0001-modular-monolith-and-technology-baseline.md",
    "docs/02-architecture/adr/ADR-0002-immutable-raw-and-ledgers.md",
    "docs/02-architecture/adr/ADR-0003-read-first-controlled-write.md",
    "docs/02-architecture/adr/ADR-0004-ai-maker-checker-development-model.md",
    "docs/02-architecture/adr/ADR-0005-production-vertical-slices-and-shared-spine.md",
    "docs/02-architecture/adr/ADR-0006-contract-governed-vibe-coding.md",
    "docs/02-architecture/adr/ADR-0007-v1-infrastructure-identity-ai-provider-boundary.md",
    "docs/02-architecture/adr/ADR-0008-unified-capability-model-and-selective-controlled-write.md",
    "docs/03-work-items/V1_DELIVERY_SLICES.md",
    "docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md",
    "docs/03-work-items/"
    "SLICE-V1-002-stockout-availability-risk-and-accountable-response.md",
    "docs/08-handoffs/OWNER-SLICE-V1-002-CONTRACT-ACCEPTANCE-EVIDENCE.md",
    "docs/02-architecture/designs/SLICE-V1-002-design.md",
    "docs/07-phase-evidence/SLICE-V1-002/acceptance-status.md",
    "docs/07-phase-evidence/SLICE-V1-002/executable-evidence.md",
    "docs/07-phase-evidence/SLICE-V1-002/V0034-root-cause-rework-evidence.md",
    "docs/07-phase-evidence/SLICE-V1-002/r1-finding-closure.json",
    "docs/07-phase-evidence/SLICE-V1-002/r1-final-handoff.md",
    "docs/07-phase-evidence/SLICE-V1-002/deferred-release-register.json",
    "docs/07-phase-evidence/SLICE-V1-002/CLOSURE-SNAPSHOT-DRAFT.md",
    "docs/08-handoffs/OWNER-SLICE-V1-002-FORMAL-CLOSURE-ACCEPTANCE-EVIDENCE.md",
    "docs/04-api/V1_CAPABILITY_MATRIX.md",
    "docs/05-testing/V1_PRODUCTION_ASSURANCE_MATRIX.md",
    "docs/07-phase-evidence/README.md",
    "docs/07-phase-evidence/SLICE-V1-001/CLOSURE-SNAPSHOT-DRAFT.md",
    "docs/07-phase-evidence/SLICE-V1-001/post-merge-closure-sync.md",
    "docs/08-handoffs/CONTROLLER-SLICE-V1-001-R2-ENGINEERING-FINAL-GATE-PASS.md",
    "docs/08-handoffs/OWNER-SLICE-V1-001-FORMAL-CLOSURE-ACCEPTANCE-TEMPLATE.md",
    "docs/08-handoffs/OWNER-SLICE-V1-001-FORMAL-CLOSURE-ACCEPTANCE-EVIDENCE.md",
    "docs/07-phase-evidence/V1/Baseline-Reset/README.md",
    "docs/08-handoffs/CONTROLLER-DR-0003-V1-BASELINE-RESET-REVIEW.md",
    "docs/08-handoffs/CODEX-DR-0003-GOVERNANCE-EXECUTION-PROMPT.md",
    "docs/08-handoffs/DR-0003-CONTROLLER-ARTIFACT-HASHES.md",
    DR0003_R1_REVIEW_RELATIVE_PATH,
    DR0003_R1_PROMPT_RELATIVE_PATH,
    DR0003_R1_MANIFEST_RELATIVE_PATH,
    DR0003_R2_REVIEW_RELATIVE_PATH,
    DR0003_R2_PROMPT_RELATIVE_PATH,
    DR0003_R2_MANIFEST_RELATIVE_PATH,
    ".github/ISSUE_TEMPLATE/decision_request.yml",
    ".github/ISSUE_TEMPLATE/delivery_slice.yml",
    ".github/ISSUE_TEMPLATE/work_package.yml",
    "scripts/validate_production_readiness.py",
    "tests/test_validate_production_readiness.py",
]

ACTIVE_AUTHORIZATION_STATES = {"DESIGN_ONLY", "APPROVED_FOR_IMPLEMENTATION"}
CURRENT_AUTHORIZATION_ALLOWED_STATES = ACTIVE_AUTHORIZATION_STATES | {"PLANNING_ONLY"}
WP_EXECUTION_AUTHORIZATION_ALLOWED_STATES = ACTIVE_AUTHORIZATION_STATES | {"CLOSED"}
LIFECYCLE_ALLOWED_STATES = {"INITIATING", "EXECUTING_PHASE_0", "EXECUTING_V1"}

DR0003_REQUIRED_BASE = "52a657f7f6358f43246e03457ba2d48ef658986a"
# SLICE-V1-001's Contract is no longer the active one, but its bytes stay
# pinned: DR-0004 and the closed Slice's own acceptance record quote this hash,
# and a closed Slice's provenance does not move when the next one starts.
V1_SLICE_001_CONTRACT_PATH = (
    "docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md"
)
V1_SLICE_001_CONTRACT_SHA256 = (
    "0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5"
)
V1_SLICE_002_CONTRACT_PATH = (
    "docs/03-work-items/"
    "SLICE-V1-002-stockout-availability-risk-and-accountable-response.md"
)
V1_SLICE_002_CONTRACT_SHA256 = (
    "d89ea296d0ff854c7d57895b448f9467a22106881d26de4c62a0e8629600556e"
)
V1_SLICE_002_CONTRACT_GIT_BLOB_SHA1 = (
    "1caa50f1b33011f7d226c83654835401c00bde1e"
)
V1_SLICE_002_ACCEPTANCE_PATH = (
    "docs/08-handoffs/OWNER-SLICE-V1-002-CONTRACT-ACCEPTANCE-EVIDENCE.md"
)
V1_SLICE_002_ACCEPTANCE_SHA256 = (
    "4e243c85412c549975ef70ee46bb09502a3157c0d4bb6a1b2679b7745b96538e"
)
V1_SLICE_003_CONTRACT_PATH = (
    "docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md"
)
V1_SLICE_003_CONTRACT_SHA256 = (
    "1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c"
)
V1_SLICE_003_CONTRACT_GIT_BLOB_SHA1 = (
    "669c38dc4d9429249e663da0e684dabf570c4a4a"
)
V1_SLICE_003_CONTRACT_BYTES = "129400"
V1_SLICE_003_CONTRACT_LINES = "2687"
V1_SLICE_003_ACCEPTANCE_PATH = (
    "docs/08-handoffs/OWNER-SLICE-V1-003-CONTRACT-ACCEPTANCE-EVIDENCE.md"
)
V1_SLICE_003_ACCEPTANCE_SHA256 = (
    "d0532ff25806c5cbc96411aad81db8524671fba8b987a57a41843bff78bcce7d"
)
V1_SLICE_003_OWNER_STATEMENT_SHA256 = (
    "0ffaf4e865447ad18e0cb18f2527a3183553366295274e1be0811db3e2b19634"
)
# The active Slice is SLICE-V1-003. The SLICE-V1-002 identities above stay
# pinned so a closed Slice cannot lose its accepted bytes when the active
# pointer moves.
V1_ACTIVE_SLICE_CONTRACT_PATH = V1_SLICE_003_CONTRACT_PATH
V1_ACTIVE_SLICE_CONTRACT_SHA256 = V1_SLICE_003_CONTRACT_SHA256
V1_ACTIVE_SLICE_CONTRACT_GIT_BLOB_SHA1 = V1_SLICE_003_CONTRACT_GIT_BLOB_SHA1
V1_ACTIVE_SLICE_ACCEPTANCE_PATH = V1_SLICE_003_ACCEPTANCE_PATH
V1_ACTIVE_SLICE_ACCEPTANCE_SHA256 = V1_SLICE_003_ACCEPTANCE_SHA256
V1_SLICE_002_OWNER_FORMAL_CLOSURE_EVIDENCE_PATH = (
    "docs/08-handoffs/OWNER-SLICE-V1-002-FORMAL-CLOSURE-ACCEPTANCE-EVIDENCE.md"
)
V1_SLICE_002_OWNER_FORMAL_CLOSURE_EVIDENCE_SHA256 = (
    "3b1b0aa0c1ebbc2f8b995ac69e9adcf6cbc6c19548bd33a234071e7941ec1e46"
)
V1_SLICE_002_OWNER_STATEMENT_SHA256 = (
    "be99e247e6a47876ca42dde61b8c1834a59464c6168beb25acb2c2519f57a6ff"
)
V1_SLICE_002_CLOSURE_SNAPSHOT_PATH = (
    "docs/07-phase-evidence/SLICE-V1-002/CLOSURE-SNAPSHOT-DRAFT.md"
)
V1_SLICE_002_CLOSURE_SNAPSHOT_GIT_BLOB_SHA1 = (
    "da35a11b30843603c5defdc10299bcf8b53fbc83"
)
V1_SLICE_002_CLOSURE_SNAPSHOT_SHA256 = (
    "f4847d4fdca8bede97decc02a12f99b2358b196d3d5b31a3aac60362ae41799f"
)
V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_PATH = (
    "docs/08-handoffs/"
    "OWNER-SLICE-V1-002-CLOSURE-SNAPSHOT-ACCEPTANCE-EVIDENCE.md"
)
V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_GIT_BLOB_SHA1 = (
    "658458e0421ecf41bdbf5bba1c466c2ec69f571b"
)
V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_SHA256 = (
    "410d56fcba47ca2ccdd2807b743863e420a3ee49dea34cd3b60c1b71446f8be6"
)
V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_STATEMENT_SHA256 = (
    "ed01ebaac4e92ffc74e02bf9cecd3aafdb8c094305b53a3b66bca0764275763d"
)
V1_SLICE_002_POST_MERGE_CLOSURE_SYNC_PATH = (
    "docs/07-phase-evidence/SLICE-V1-002/post-merge-closure-sync.md"
)
V1_SLICE_002_POST_MERGE_CLOSURE_SYNC_GIT_BLOB_SHA1 = (
    "5c02646a5ef5cb5847d8e792dd6184d9d4ab28b1"
)
V1_SLICE_002_POST_MERGE_CLOSURE_SYNC_SHA256 = (
    "1138ce792d990d069b563af34728220f50b0d25dbc56f3f5ee08a621535cfca6"
)
V1_SLICE_AUTHORIZATION_CONDITION = (
    "EXACT_HASH_INDEPENDENTLY_REVIEWED_AND_OWNER_AUTHORIZED_ON_PROTECTED_MAIN"
)
V1_AMENDMENT_001_PATH = (
    "docs/03-work-items/SLICE-V1-001-AMENDMENT-001-YANDEX-MANAGED-PG-BOOTSTRAP.md"
)
V1_AMENDMENT_001_SHA256 = (
    "8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d"
)
V1_AMENDMENT_001_ACCEPTANCE_PATH = (
    "docs/08-handoffs/OWNER-SLICE-V1-001-AMENDMENT-001-ACCEPTANCE-EVIDENCE.md"
)
V1_AMENDMENT_001_ACCEPTANCE_SHA256 = (
    "e8fc208a4fcd9270b9187b65aa1618ecf6179166a3a44b4a37213bf067a91ee8"
)
V1_AMENDMENT_002_PATH = (
    "docs/03-work-items/"
    "SLICE-V1-001-AMENDMENT-002-DEFERRED-REAL-INTEGRATION-AND-PREPRODUCTION-ASSURANCE.md"
)
V1_AMENDMENT_002_SHA256 = (
    "92fdd8d67b327fbd2288ba99290b5b59f2797106c4b691ce2bff22bb80198b93"
)
V1_AMENDMENT_002_ACCEPTANCE_PATH = (
    "docs/08-handoffs/OWNER-SLICE-V1-001-AMENDMENT-002-ACCEPTANCE-EVIDENCE.md"
)
V1_AMENDMENT_002_ACCEPTANCE_SHA256 = (
    "f28ad2395e22a7dd996ace6db4883f35e408bb4ea24de61e777e03b8616d9923"
)
V1_CLOSURE_SNAPSHOT_PATH = (
    "docs/07-phase-evidence/SLICE-V1-001/CLOSURE-SNAPSHOT-DRAFT.md"
)
V1_CLOSURE_SNAPSHOT_GIT_BLOB_SHA1 = (
    "e26359ec216c04319a4bf1e7126906eb204593d2"
)
V1_CLOSURE_SNAPSHOT_SHA256 = (
    "5abce67327673dc0248f11ece1f31cd11d1ec7c0e69a1e84823ddedf30aab2e3"
)
V1_OWNER_FORMAL_CLOSURE_EVIDENCE_PATH = (
    "docs/08-handoffs/OWNER-SLICE-V1-001-FORMAL-CLOSURE-ACCEPTANCE-EVIDENCE.md"
)
V1_OWNER_FORMAL_CLOSURE_EVIDENCE_SHA256 = (
    "50c171f24037cf36ccb4724288a7b82831b7dd008985f9b594ef2020c1c5ef33"
)
V1_ACTIVE_STATE = {
    "lifecycle_state": "EXECUTING_V1",
    "product_version": "V1",
    "delivery_model": "PRODUCTION_VERTICAL_SLICES",
    "active_delivery_slice": "SLICE-V1-003",
    "active_slice_title": "Advertising & Traffic Efficiency",
    "active_slice_contract": V1_ACTIVE_SLICE_CONTRACT_PATH,
    "active_slice_contract_sha256": V1_ACTIVE_SLICE_CONTRACT_SHA256,
    "active_slice_contract_git_blob_sha1": V1_ACTIVE_SLICE_CONTRACT_GIT_BLOB_SHA1,
    "active_slice_contract_authorization_condition": V1_SLICE_AUTHORIZATION_CONDITION,
    "active_slice_acceptance_evidence": V1_ACTIVE_SLICE_ACCEPTANCE_PATH,
    "active_slice_acceptance_evidence_sha256": V1_ACTIVE_SLICE_ACCEPTANCE_SHA256,
    "active_slice_amendment": "NONE_ACCEPTED",
    "active_slice_contract_bytes": V1_SLICE_003_CONTRACT_BYTES,
    "active_slice_contract_lines": V1_SLICE_003_CONTRACT_LINES,
    "authorization": "FULL_SCOPE_IMPLEMENTATION",
    "slice_v1_003_owner_acceptance": "HUMAN_OWNER_ACCEPTED_EXACT",
    "slice_v1_003_owner_acceptance_statement_sha256": (
        V1_SLICE_003_OWNER_STATEMENT_SHA256
    ),
    "slice_v1_003_source_protected_main": (
        "08ad7da7d9e75b4ddd1c387a22ac0affba9e1430"
    ),
    "slice_v1_003_source_protected_main_tree": (
        "0ca229112bcf351ab5c572dd8d375c647bab61c0"
    ),
    "slice_v1_003_controlled_write_target": "AD_BID_CHANGE",
    "slice_v1_003_controlled_write_provider_paths": (
        "STRUCTURALLY_UNREACHABLE_PENDING_VERIFIED_CAPABILITY_AND_GATE"
    ),
    "slice_v1_003_real_provider_calls": "NONE",
    "slice_v1_003_ordinary_impact_envelope": (
        "ZERO_EVERY_NONZERO_AD_BID_CHANGE_IS_MATERIAL"
    ),
    "slice_v1_003_standing_policy_automation": "NOT_AUTHORIZED",
    "slice_v1_003_governed_manual_shadow": "REQUIRED_ON_BOTH_PLATFORMS",
    "slice_v1_003_deferred_release_obligations": (
        "S3_REL_001_THROUGH_024_PRODUCTION_BLOCKING"
    ),
    "slice_v1_003_owner_decision_count": "47",
    "slice_v1_003_acceptance_criteria_count": "200",
    "slice_v1_002_contract": V1_SLICE_002_CONTRACT_PATH,
    "slice_v1_002_contract_sha256": V1_SLICE_002_CONTRACT_SHA256,
    "slice_v1_002_contract_git_blob_sha1": V1_SLICE_002_CONTRACT_GIT_BLOB_SHA1,
    "slice_v1_002_contract_acceptance_evidence": V1_SLICE_002_ACCEPTANCE_PATH,
    "slice_v1_002_contract_acceptance_evidence_sha256": (
        V1_SLICE_002_ACCEPTANCE_SHA256
    ),
    "slice_v1_002_contract_amendment": "NONE_ACCEPTED",
    "slice_v1_002_state": "CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS",
    "r2_remote_publication_authority": (
        "HUMAN_OWNER_EXPLICIT_FORMAL_CLOSURE_RECORDING_AND_PROTECTED_SQUASH_PR_23"
    ),
    "slice_v1_001_amendment": V1_AMENDMENT_001_PATH,
    "slice_v1_001_amendment_sha256": V1_AMENDMENT_001_SHA256,
    "slice_v1_001_amendment_acceptance": "HUMAN_OWNER_ACCEPTED_FOR_PR20_REWORK",
    "slice_v1_001_amendment_acceptance_evidence": V1_AMENDMENT_001_ACCEPTANCE_PATH,
    "slice_v1_001_amendment_acceptance_evidence_sha256": V1_AMENDMENT_001_ACCEPTANCE_SHA256,
    "slice_v1_001_amendment_002": V1_AMENDMENT_002_PATH,
    "slice_v1_001_amendment_002_sha256": V1_AMENDMENT_002_SHA256,
    "slice_v1_001_amendment_002_acceptance": "HUMAN_OWNER_ACCEPTED_FOR_SUPPLEMENTAL_R2",
    "slice_v1_001_amendment_002_acceptance_evidence": V1_AMENDMENT_002_ACCEPTANCE_PATH,
    "slice_v1_001_amendment_002_acceptance_evidence_sha256": V1_AMENDMENT_002_ACCEPTANCE_SHA256,
    "slice_v1_002_implementation_state": "ENGINEERING_IMPLEMENTATION_MERGED",
    "slice_v1_002_branch": "fix/SLICE-V1-002-root-cause-rework-r1",
    "slice_v1_002_reviewed_source_head": "c5d896a4ca01ecdc6d4add85fb4fd2e33ba8e4c6",
    "slice_v1_002_reviewed_source_tree": "c94341232b5fa67b5c40a1e6be121a7696e748c4",
    "slice_v1_002_frozen_findings_sha256": (
        "60589cfa9303d17e71910e085fd18f1d68b87dd9e3b56a99bf6f799879ebcf94"
    ),
    "slice_v1_002_finding_count": "18",
    "slice_v1_002_engineering_findings_addressed": "18_OF_18_CLOSED",
    "slice_v1_002_controller_review": (
        "CONTROLLER_SLICE_V1_002_FINAL_CLOSURE_VERIFICATION_PR26_R3"
    ),
    "slice_v1_002_controller_verdict": "PASS_R3_ENGINEERING_FINAL_GATE",
    "slice_v1_002_controller_bookkeeping_review": (
        "CONTROLLER_SLICE_V1_002_FINAL_POST_MERGE_BOOKKEEPING_VERIFICATION_R2"
    ),
    "slice_v1_002_controller_bookkeeping_verdict": (
        "PASS_POST_MERGE_CLOSURE_BOOKKEEPING"
    ),
    "slice_v1_002_owner_formal_closure": "HUMAN_OWNER_ACCEPTED",
    "slice_v1_002_owner_formal_closure_statement_sha256": (
        V1_SLICE_002_OWNER_STATEMENT_SHA256
    ),
    "slice_v1_002_owner_formal_closure_evidence": (
        V1_SLICE_002_OWNER_FORMAL_CLOSURE_EVIDENCE_PATH
    ),
    "slice_v1_002_owner_formal_closure_evidence_sha256": (
        V1_SLICE_002_OWNER_FORMAL_CLOSURE_EVIDENCE_SHA256
    ),
    "slice_v1_002_remote_publication": "PR_26_MERGED_PROTECTED_SQUASH",
    "slice_v1_002_draft_pr": "26",
    "slice_v1_002_draft_pr_url": (
        "https://github.com/Corwin-Code/marketops-platform/pull/26"
    ),
    "slice_v1_002_pr_state": "MERGED",
    "slice_v1_002_final_head": "6b5ab03b62d557ee8cb04847ba4418ca2cb3d529",
    "slice_v1_002_final_tree": "f7e02da0bf38922f6c5a80d49b263613ade997d9",
    "slice_v1_002_tested_merge": "12f82ac66d9b023cc158a12f10f97b0e4415fe12",
    "slice_v1_002_actual_squash_commit": (
        "cc42760cfc99c1bab027039fca67410d696e96fa"
    ),
    "slice_v1_002_actual_squash_tree": (
        "f7e02da0bf38922f6c5a80d49b263613ade997d9"
    ),
    "slice_v1_002_actual_squash_sole_parent": (
        "8a7076877374391cf851481c023dfb0e621ab712"
    ),
    "slice_v1_002_actual_squash_signature": "VERIFIED_VALID",
    "slice_v1_002_actual_squash_merged_at": "2026-09-01T10:13:48Z",
    "slice_v1_002_source_branch_cleanup": (
        "GITHUB_AUTO_DELETED_THEN_EXACT_REF_RESTORED"
    ),
    "slice_v1_002_source_branch_preservation_head": (
        "6b5ab03b62d557ee8cb04847ba4418ca2cb3d529"
    ),
    "slice_v1_002_post_merge_findings": (
        "S2_PM_SEC_001_AND_S2_PM_TST_002_CLOSED"
    ),
    "slice_v1_002_security_fix_controller_review": (
        "CONTROLLER_SLICE_V1_002_POST_MERGE_SECURITY_FIX_REVERIFICATION_PR28_R2"
    ),
    "slice_v1_002_security_fix_controller_verdict": (
        "PASS_POST_MERGE_SECURITY_FIX_REVERIFICATION"
    ),
    "slice_v1_002_security_fix_owner_authorization_sha256": (
        "651b949c92de5da484f0715fdb7b255afe294996e5431ca99723a74b4fdfbab9"
    ),
    "slice_v1_002_security_fix_pr": "28",
    "slice_v1_002_security_fix_final_head": (
        "fde6e07f4f5d5856202e52287b7544be0e85c523"
    ),
    "slice_v1_002_security_fix_final_tree": (
        "a18229584c73e1d0535ce407ebe21883224b5c03"
    ),
    "slice_v1_002_security_fix_tested_merge": (
        "3a5db7bb40c8ee8dc8718809dfa605f400e4c1b4"
    ),
    "slice_v1_002_security_fix_actual_squash_commit": (
        "e0184852785f451256a36f52fa3d520ceea2c313"
    ),
    "slice_v1_002_security_fix_actual_squash_tree": (
        "a18229584c73e1d0535ce407ebe21883224b5c03"
    ),
    "slice_v1_002_security_fix_actual_squash_sole_parent": (
        "cc42760cfc99c1bab027039fca67410d696e96fa"
    ),
    "slice_v1_002_security_fix_actual_squash_signature": "VERIFIED_VALID",
    "slice_v1_002_security_fix_merged_at": "2026-09-01T19:37:14Z",
    "slice_v1_002_default_branch_security_run": "33550566209_SUCCESS",
    "slice_v1_002_post_merge_code_scanning_alerts": (
        "116_117_FIXED_BY_CODE_NO_DISMISSAL"
    ),
    "slice_v1_002_post_merge_security_readback": (
        "PASS_NO_OPEN_HIGH_CRITICAL_ALERTS"
    ),
    "slice_v1_002_engineering_acceptance": "100_OF_100",
    "slice_v1_002_deferred_release_obligations": (
        "S2_REL_001_THROUGH_010_PRODUCTION_BLOCKING"
    ),
    "slice_v1_002_closure_sync_branch": (
        "docs/SLICE-V1-002-post-merge-closure-sync"
    ),
    "slice_v1_002_closure_sync_pr": "27",
    "slice_v1_002_closure_snapshot": V1_SLICE_002_CLOSURE_SNAPSHOT_PATH,
    "slice_v1_002_closure_snapshot_source_pr": "27",
    "slice_v1_002_closure_snapshot_source_commit": (
        "dbc09e00a942c53580270a4157da863933502e8b"
    ),
    "slice_v1_002_closure_snapshot_source_head": (
        "dbc09e00a942c53580270a4157da863933502e8b"
    ),
    "slice_v1_002_closure_snapshot_source_tree": (
        "11e209e1991c49e7d2a4706da1b1d2654dfe35d6"
    ),
    "slice_v1_002_closure_snapshot_tested_merge": (
        "b36e057ed6388385f846dfceef96a960c8ff6c45"
    ),
    "slice_v1_002_closure_snapshot_git_blob_sha1": (
        V1_SLICE_002_CLOSURE_SNAPSHOT_GIT_BLOB_SHA1
    ),
    "slice_v1_002_closure_snapshot_sha256": (
        V1_SLICE_002_CLOSURE_SNAPSHOT_SHA256
    ),
    "slice_v1_002_owner_snapshot_acceptance": "HUMAN_OWNER_ACCEPTED",
    "slice_v1_002_owner_snapshot_acceptance_statement_sha256": (
        V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_STATEMENT_SHA256
    ),
    "slice_v1_002_owner_snapshot_acceptance_evidence": (
        V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_PATH
    ),
    "slice_v1_002_owner_snapshot_acceptance_evidence_git_blob_sha1": (
        V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_GIT_BLOB_SHA1
    ),
    "slice_v1_002_owner_snapshot_acceptance_evidence_sha256": (
        V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_SHA256
    ),
    "slice_v1_002_post_merge_closure_sync_record": (
        V1_SLICE_002_POST_MERGE_CLOSURE_SYNC_PATH
    ),
    "slice_v1_002_post_merge_closure_sync_record_git_blob_sha1": (
        V1_SLICE_002_POST_MERGE_CLOSURE_SYNC_GIT_BLOB_SHA1
    ),
    "slice_v1_002_post_merge_closure_sync_record_sha256": (
        V1_SLICE_002_POST_MERGE_CLOSURE_SYNC_SHA256
    ),
    "slice_v1_002_controlled_write_target": "NONE_IN_THIS_SLICE",
    "slice_v1_002_real_provider_calls": "NONE",
    "slice_v1_002_as_built_design": (
        "docs/02-architecture/designs/SLICE-V1-002-design.md"
    ),
    "slice_v1_002_acceptance_status": (
        "docs/07-phase-evidence/SLICE-V1-002/acceptance-status.md"
    ),
    "slice_v1_002_executable_evidence": (
        "docs/07-phase-evidence/SLICE-V1-002/executable-evidence.md"
    ),
    "slice_v1_002_root_cause_rework_evidence": (
        "docs/07-phase-evidence/SLICE-V1-002/V0034-root-cause-rework-evidence.md"
    ),
    "slice_v1_002_r1_finding_closure": (
        "docs/07-phase-evidence/SLICE-V1-002/r1-finding-closure.json"
    ),
    "slice_v1_002_r1_final_handoff": (
        "docs/07-phase-evidence/SLICE-V1-002/r1-final-handoff.md"
    ),
    "slice_v1_002_deferred_release_register": (
        "docs/07-phase-evidence/SLICE-V1-002/deferred-release-register.json"
    ),
    "slice_v1_003_historical_controller_verdict": "NOT_PASS_EXISTING_FINDINGS_NOT_FULLY_CLOSED",
    "slice_v1_003_historical_controller_reviewed_head": "3ff042df66d5d6924b587cac96fc652b93bf5e7a",
    "slice_v1_003_historical_controller_report_sha256": "6f9581d9b09485a35fe404b13ab06422dc2672b7182afc52da2442dcc7660127",
    "slice_v1_003_historical_controller_report": "docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/controller-package/VERIFICATION-RESULT.json",
    "slice_v1_003_rework_authorization": "OWNER_CODEX_SLICE_V1_003_ROOT_CAUSE_REWORK_R1",
    "slice_v1_003_rework_authorization_evidence_sha256":
        "23a2954d68abeebf87d7710f3ab749af5246cdfcbe4a3029dde73dbb34647a11",
    "slice_v1_003_rework_starting_head": "a0711f1ae430e70ab7ec06917004e9dbfd1fb4eb",
    "slice_v1_003_frozen_findings_sha256":
        "15b3c076fc7f1d283a2c7359d9647d91d3ecfccd9b229be1f734f4e7d4ceefc1",
    "slice_v1_001_implementation_state": "ENGINEERING_IMPLEMENTATION_MERGED",
    "slice_v1_001_rework_phase": "R2_FORMAL_CLOSURE_ACCEPTED",
    "slice_v1_001_pr": "22",
    "slice_v1_001_pr_state": "MERGED_PROTECTED_SQUASH",
    "slice_v1_001_branch": "fix/SLICE-V1-001-supplemental-assurance-r2",
    "slice_v1_001_review_state": "PASS_R2_ENGINEERING_FINAL_GATE",
    "slice_v1_001_reviewed_base": "db92cf2f8bd818f36dd8f5aa17b8589c4140b669",
    "slice_v1_001_reviewed_head": "f35327a584b980ec4acf7ace7c88e124d6d79709",
    "slice_v1_001_reviewed_tree": "390ebe37bea778b7a4548381ad357fc99aa0da6b",
    "slice_v1_001_frozen_findings_sha256": "8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8",
    "slice_v1_001_supplemental_r2_review_sha256": "c772c76c89b753d4694ee5ec1eceddad3451ab7ef6acc2e36416d9d4171f26ff",
    "slice_v1_001_rework_commit_state": "MERGED_EXACT_PROTECTED_SQUASH",
    "slice_v1_001_initial_published_head": "c3d2160a9c302d993e2b01a08946f46fae0b01d5",
    "slice_v1_001_initial_published_tree": "9d7641eccc2d233bf2c5615e7c4776721269bc15",
    "slice_v1_001_initial_tested_merge": "353670b4a311f98b56fae593f8b2b34d5f39a80e",
    "slice_v1_001_initial_tested_merge_tree": "9d7641eccc2d233bf2c5615e7c4776721269bc15",
    "slice_v1_001_initial_remote_ci": "PASS_12_OF_12_REQUIRED_CONTEXTS",
    "slice_v1_001_final_candidate_identity_resolution": (
        "PR_22_FINAL_HEAD_TREE_AND_SIGNED_TESTED_MERGE"
    ),
    "slice_v1_001_controller_final_gate": "PASS_R2_ENGINEERING_FINAL_GATE",
    "slice_v1_001_controller_comment_id": "5469390502",
    "slice_v1_001_approved_engineering_head": (
        "f35327a584b980ec4acf7ace7c88e124d6d79709"
    ),
    "slice_v1_001_approved_engineering_tree": (
        "390ebe37bea778b7a4548381ad357fc99aa0da6b"
    ),
    "slice_v1_001_approved_tested_merge": (
        "bcc3b37965003c3ea1af720ea847dc27fb473a9e"
    ),
    "slice_v1_001_actual_squash_commit": (
        "d562b81f4f0271aa33a53b21ccaffc88b5610c0c"
    ),
    "slice_v1_001_actual_squash_tree": (
        "390ebe37bea778b7a4548381ad357fc99aa0da6b"
    ),
    "slice_v1_001_actual_squash_sole_parent": (
        "db92cf2f8bd818f36dd8f5aa17b8589c4140b669"
    ),
    "slice_v1_001_finding_count": "10",
    "slice_v1_001_engineering_findings_closed": "10_OF_10",
    "slice_v1_001_unresolved_blocker": "0",
    "slice_v1_001_unresolved_major": "0",
    "slice_v1_001_closure_claim": "ENGINEERING_IMPLEMENTATION_CLOSED",
    "slice_v1_001_production_readiness": "DEFERRED_TO_RELEASE_V1_001",
    "slice_v1_001_owner_formal_closure": "HUMAN_OWNER_ACCEPTED",
    "slice_v1_001_state": "CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS",
    "slice_v1_001_execution_condition": (
        "FORMALLY_CLOSED_WITH_DEFERRED_RELEASE_OBLIGATIONS"
    ),
    "slice_v1_001_docs_closure_pr_21": "CLOSED_UNMERGED_SUPERSEDED_REF_DELETED",
    "slice_v1_001_closure_sync_branch": (
        "docs/SLICE-V1-001-r2-post-merge-closure-sync"
    ),
    "slice_v1_001_closure_sync_identity_resolution": (
        "THIS_DOCUMENT_CONTAINING_COMMIT_AND_NEW_DRAFT_PR_LIVE_REFS"
    ),
    "slice_v1_001_closure_sync_pr": "23",
    "slice_v1_001_closure_sync_source_head": (
        "7f52b4c0e145cfb86e4982416aa7bdca79da7ec6"
    ),
    "slice_v1_001_closure_sync_source_tree": (
        "619b79844641d299ad6b5283f6dcea21c03e9ab3"
    ),
    "slice_v1_001_closure_sync_source_tested_merge": (
        "d2d05514565e1d19131b02527ed05f698169006c"
    ),
    "slice_v1_001_controller_bookkeeping_verdict": (
        "PASS_POST_MERGE_CLOSURE_BOOKKEEPING"
    ),
    "slice_v1_001_controller_bookkeeping_comment": "5469802650",
    "slice_v1_001_snapshot_source_commit": (
        "7f52b4c0e145cfb86e4982416aa7bdca79da7ec6"
    ),
    "slice_v1_001_snapshot_source_tree": (
        "619b79844641d299ad6b5283f6dcea21c03e9ab3"
    ),
    "slice_v1_001_snapshot_git_blob_sha1": V1_CLOSURE_SNAPSHOT_GIT_BLOB_SHA1,
    "slice_v1_001_snapshot_sha256": V1_CLOSURE_SNAPSHOT_SHA256,
    "slice_v1_001_owner_acceptance_comment": "5469935477",
    "slice_v1_001_owner_acceptance_evidence": (
        V1_OWNER_FORMAL_CLOSURE_EVIDENCE_PATH
    ),
    "slice_v1_001_owner_acceptance_evidence_sha256": (
        V1_OWNER_FORMAL_CLOSURE_EVIDENCE_SHA256
    ),
    "slice_v1_001_handoff_pending": (
        "CONTROLLER_FORMAL_CLOSURE_AND_BRANCH_CLEANUP_READBACK"
    ),
    "merge_authorization": (
        "NOT_AUTHORIZED_SEPARATE_LEVEL_3_AUTHORITY_REQUIRED"
    ),
    "closure_snapshot_before_next_slice": "SATISFIED_EXACT_OWNER_ACCEPTED",
    "production_deployment": "NOT_AUTHORIZED",
    "gate_ev": "NOT_AUTHORIZED",
    "gate_e": "NOT_AUTHORIZED",
    "production_write_enabled": "false",
    "bounded_real_write_verification_authorization": "NONE",
    "bounded_real_write_verification_gate": "REQUIRED_BEFORE_FIRST_REAL_WRITE",
    "ozon_price_write": "DISABLED_PENDING_VERIFIED_CAPABILITY_AND_RELEASE_GATE",
    "wildberries_price_write": (
        "DISABLED_PENDING_VERIFIED_CAPABILITY_AND_RELEASE_GATE"
    ),
}
SLICE_REWORK_ARTIFACT_HASHES = {
    "FROZEN-FINDING-SET-SLICE-V1-001-PR20-R1.json": "8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8",
    "CONTROLLER-SLICE-V1-001-COMPREHENSIVE-DEEP-REVIEW-R1.md": "df8a3cca26d1b4d0efd9f7f883764971cad8adb43ca698c6de545d52d46b6754",
    "CONTROLLER-REVIEW-EVIDENCE-SLICE-V1-001-PR20-R1.json": "a4b0e22c64a89272dfec3b90d91c6181d1a65b7b36c1ba32cec1023fb15ce6bb",
    "ARTIFACT-HASHES.json": "cacb0a2be2bfe0d3de49c7a86b0f60091fa944e2354dc8e5d9ea7be4c2816e2b",
}
V1_AUTHORIZATION_ALLOWED_STATES = {
    "CONTRACT_ONLY",
    "FULL_SCOPE_IMPLEMENTATION",
    "DEEP_REVIEW_ONLY",
    "REWORK_FIX_VERIFY",
    "FINAL_REVIEW_ONLY",
    "RELEASE_READY",
    "CLOSED",
    "PROTECTED_SQUASH_MERGE_ONLY",
}
V1_TRACEABILITY_HEADER = [
    "source_id",
    "source_type",
    "product_version",
    "title",
    "contract_or_adr",
    "delivery_slice",
    "code_location",
    "acceptance_or_test",
    "evidence",
    "status",
    "notes",
]
V1_TRACEABILITY_STATUSES = {
    "CONTRACT_DEFINED",
    "PLANNED",
    "IMPLEMENTING",
    "ACTIVE_CONTROL",
    "VERIFIED",
    "SUPERSEDED",
}
V1_TRACEABILITY_REQUIRED_IDS = {
    *(f"D-{number:02d}" for number in range(18, 26)),
    *(f"OD-S2-{number:03d}" for number in range(1, 21)),
    "HR-01",
    "HR-02",
    "HR-05",
    "IAM-V1",
    "DATA-V1",
    "METRIC-V1",
    "AI-V1",
    "UI-V1",
    "GATE-EV",
}
DR0003_ARTIFACT_HASHES = {
    "docs/08-handoffs/CONTROLLER-DR-0003-V1-BASELINE-RESET-REVIEW.md": (
        "780f3cca7fadfbc00e8b0b6198e15e9c2b1e3c72bf51e0b24e239da2c901823d"
    ),
    "docs/08-handoffs/CODEX-DR-0003-GOVERNANCE-EXECUTION-PROMPT.md": (
        "d33fc9a391905747857cfb3d9295e09214c7afa60e7d57ec1a34bcc504931dd7"
    ),
}
DR0003_R1_ARTIFACT_HASHES = {
    DR0003_R1_REVIEW_RELATIVE_PATH: (
        "d2abcf7ac5569ae3501e78b34bc421bcfa6a9e79275122117f39bc5f5155ac5d"
    ),
    DR0003_R1_PROMPT_RELATIVE_PATH: (
        "782aff3289bbcc3c5443dc534451cdfbaea4b61dc3a7aea5910b1edfc6e7ea80"
    ),
    DR0003_R1_MANIFEST_RELATIVE_PATH: (
        "fd2412530831831e259b52822d2ba703de013dc3e76c58e70736475ec1c9d3ac"
    ),
}
DR0003_R2_ARTIFACT_HASHES = {
    DR0003_R2_REVIEW_RELATIVE_PATH: (
        "dc2f36541acff66f0a656c334aedc78a77d538e80f38dffd5f3308750e430f65"
    ),
    DR0003_R2_PROMPT_RELATIVE_PATH: (
        "de674494ec784d439c26a31721337b8254df55b4dfdc82729ea2801ce7d152a4"
    ),
    DR0003_R2_MANIFEST_RELATIVE_PATH: (
        "9279cc9029646315f98e2e2da1f0f8edbfcbd2c104f55393870dfdda8d168811"
    ),
}
HISTORICAL_PROVENANCE_HASHES = {
    "docs/02-architecture/designs/WP-P0-001-foundation-design.md": (
        "81d3030d5fa0e852aa8ddb330fa0479192b6b707609ff4d44835d172253e7ff8"
    ),
    "docs/02-architecture/designs/WP-P0-002-organization-store-warehouse-credential-metadata-design.md": (
        "3e524c666e56b3d5fdecd6e2098a22d1bd9fd88711dd9c524858ca0cdd3859b2"
    ),
    "docs/02-architecture/designs/WP-P0-003-executable-design-validation-addendum.md": (
        "60f445da194c28d24650b19d641f29bce7de7860e9cb8e30c1a7698cb24f95a8"
    ),
    "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md": (
        "a698e37d33c122347655ed3c767906ae11468651cb21ede834769af62ef3ad97"
    ),
    "docs/03-work-items/WP-P0-002-organization-store-warehouse-credential-metadata.md": (
        "b322aa269f21b96cc66cc57e6b9035fb43a091faf99c571e65e18e1da6841663"
    ),
    "docs/03-work-items/WP-P0-003-durable-ingestion-control-plane-immutable-raw-evidence.md": (
        "d40b2e07ff2aff2d3cfff6eb9477310803bfad7826339d30a59c246487c42c07"
    ),
    "docs/07-phase-evidence/WP-P0-003/post-merge-execution-verification.md": (
        "5a4636ba5189b183c6eb0e12114e1baff768093959ca1438bafffe1e0dd59813"
    ),
}
HISTORICAL_EVIDENCE_TREE_HASHES = {
    "docs/07-phase-evidence/WP-P0-001": (
        "b0a730937aa8d5bd00e3ee793af82ee2f5b7815dfc3e8c3a72f7886ff5e4cb42"
    ),
    "docs/07-phase-evidence/WP-P0-002": (
        "35b259e8885f34bc674954c4215f297a42d471a6d6aebc4592842dbb93bed411"
    ),
    "docs/07-phase-evidence/WP-P0-003": (
        "83464dc567951d1438a0d5c34af6cbe1b02e590b9f0fb39a9eb4483bf20c0fc6"
    ),
}
CANONICAL_DESIGN_RELATIVE_PATH = (
    "docs/02-architecture/designs/WP-P0-001-foundation-design.md"
)
CANONICAL_DESIGN_METADATA = {
    "document_type": "module foundation design",
    "status": "APPROVED_FOR_IMPLEMENTATION",
    "work_package": "WP-P0-001",
    "product": "MarketOps Russia",
    "repository": "marketops-platform",
}
OWNER_GUIDANCE_ALLOWED_STATES = {"REQUIRED", "DISABLED"}
OWNER_GUIDANCE_EXIT_AUTHORITY = "HUMAN_OWNER_EXPLICIT_CONFIRMATION"
OWNER_DELEGATION_ALLOWED_STATES = {"ACTIVE", "INACTIVE"}
OWNER_DELEGATION_ALLOWED_EXECUTORS = {"CODEX", "NONE"}
OWNER_DELEGATION_SCOPE = "PR_READY_AND_MERGE_AFTER_ALL_GATES"
OWNER_DELEGATION_INACTIVE_SCOPE = "NONE"
OWNER_DELEGATION_EXIT_AUTHORITY = "HUMAN_OWNER_EXPLICIT_REVOCATION"
COMPLETED_WP_STATUS = "COMPLETED"
COMPLETED_WP_AUTHORIZATION = "CLOSED"
COMPLETED_WP_RESULT = "VERIFIED"
HISTORIC_DESIGN_VERDICT = "APPROVED_FOR_IMPLEMENTATION"
POST_WP_ACTIVE_GATE = "CONTROLLER_PHASE_0_PLANNING"
DESIGN_ACTIVE_GATE = "READY_FOR_DESIGN"
IMPLEMENTATION_ACTIVE_GATE = "IMPLEMENTING"
WP_P0_003_DESIGN_FINALIZATION_GATE = "CONTROLLER_WP_P0_003_DESIGN_FINALIZATION"
WP_P0_003_DESIGN_FINALIZATION_STATUS = "DESIGN_FINALIZATION_REQUIRED"
WP_P0_001_ID = "WP-P0-001"
WP_P0_002_ID = "WP-P0-002"
WP_P0_003_ID = "WP-P0-003"
WP_P0_001_RELATIVE_PATH = (
    "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md"
)
WP_P0_002_RELATIVE_PATH = (
    "docs/03-work-items/"
    "WP-P0-002-organization-store-warehouse-credential-metadata.md"
)
WP_P0_003_RELATIVE_PATH = (
    "docs/03-work-items/"
    "WP-P0-003-durable-ingestion-control-plane-immutable-raw-evidence.md"
)
WP_P0_003_ADDENDUM_RELATIVE_PATH = (
    "docs/02-architecture/designs/"
    "WP-P0-003-executable-design-validation-addendum.md"
)
WP_P0_003_EVIDENCE_RELATIVE_PATH = (
    "docs/07-phase-evidence/WP-P0-003/executable-design-validation.md"
)
WP_P0_003_POST_MERGE_EVIDENCE_RELATIVE_PATH = (
    "docs/07-phase-evidence/WP-P0-003/post-merge-execution-verification.md"
)
WP_P0_003_AUTHORIZED_HEAD = "27b457bff4a0ed11308efa080993ee6793cae090"
WP_P0_003_AUTHORIZED_TREE = "52704ed54b2499898609a0bdd4041a5c88892fd3"
WP_P0_003_TESTED_MERGE = "cc9e3a91a189702808a3c2643b25ba0a7905237d"
WP_P0_003_SQUASH_COMMIT = "ce054a0c115788c7e7a174daa978af116b100a83"
WP_P0_003_SQUASH_PARENT = "9f7688204950c64b9f6bd8629daf90a115669864"
WP_P0_003_POST_MERGE_CONTROLLER_SHA256 = (
    "cdd964d951a6d994d1942f550a37f39e268337a55ba89348e235a818157e8875"
)
WP_P0_003B_DECISION_REQUEST_RELATIVE_PATH = (
    "docs/00-governance/DR-0002-split-controlled-file-import-from-wp-p0-003.md"
)
WP_P0_003B_ID = "WP-P0-003B"
WP_P0_002_DESIGN_RELATIVE_PATH = (
    "docs/02-architecture/designs/"
    "WP-P0-002-organization-store-warehouse-credential-metadata-design.md"
)
# The approved WP-P0-002 design is reviewed by content hash. The canonical file
# must stay byte-identical to the artifact the Controller approved.
WP_P0_002_DESIGN_SHA256 = (
    "3e524c666e56b3d5fdecd6e2098a22d1bd9fd88711dd9c524858ca0cdd3859b2"
)
WP_P0_002_APPROVED_BASE_SHA = "3c4f6a6210db377b5471d6014da6afd5bfef6127"
WP_P0_002_APPROVED_HEAD_SHA = "ce8eb44f2f750d73d7329fb78a17640ef3fc80c1"
WP_P0_002_APPROVED_TESTED_MERGE_SHA = (
    "fdcbf2bc69a0a80d1b6fb98455e91bf7e6373fef"
)
WP_P0_002_MERGED_SHA = "203b509e765959560fdfbd0edbde428ba9c6d763"
WP_P0_002_MERGED_TREE = "6a2db6f565b29847bed6065d2b04d1df800b516b"
WP_P0_002_CONTROLLER_APPROVAL_SHA256 = (
    "d477bb77846d1c9f3f50de58a6795450327b445853794fc38192ee96d4cd3c9f"
)
WP_P0_002_POST_MERGE_CONTROLLER_SHA256 = (
    "4e65f0a7fb1c997096c5fd98fb56f42211c546cca323fae5b12d39eaa0c1c8ab"
)
WP_P0_002_EVIDENCE_RELATIVE_PATH = "docs/07-phase-evidence/WP-P0-002/README.md"
WP_P0_002_ACCEPTANCE_RELATIVE_PATH = (
    "docs/07-phase-evidence/WP-P0-002/acceptance-criteria.md"
)
WP_P0_002_TEST_STRATEGY_RELATIVE_PATH = "docs/05-testing/TEST_STRATEGY.md"
HISTORIC_CONTRACT_BEGIN = (
    "<!-- BEGIN HISTORIC APPROVED CONTRACT QUOTATION (NON-AUTHORITATIVE) -->"
)
HISTORIC_CONTRACT_END = "<!-- END HISTORIC APPROVED CONTRACT QUOTATION -->"
WORK_PACKAGE_PATHS = {
    WP_P0_001_ID: WP_P0_001_RELATIVE_PATH,
    WP_P0_002_ID: WP_P0_002_RELATIVE_PATH,
    WP_P0_003_ID: WP_P0_003_RELATIVE_PATH,
}
CONTROLLER_REVIEW_STANDARD_RELATIVE_PATH = (
    "docs/00-governance/CONTROLLER_REVIEW_STANDARD.md"
)
BACKLOG_HEADER = ["ID", "Title", "Status", "Dependencies", "Core source requirements"]
BACKLOG_ALLOWED_STATES = {"DRAFT", "READY_FOR_DESIGN", "IMPLEMENTING", "COMPLETED"}
COMPLETED_TRACEABILITY_STATES = {"VERIFIED", "ACTIVE_CONTROL"}
COMPLETED_TRACEABILITY_IDS = {"D-02", "D-03", "D-07", "D-10", "D-15", "D-16", "D-17", "HR-06"}
D03_WORK_PACKAGES = "WP-P0-001;WP-P0-003"
D03_WORKER_WORK_PACKAGE = "WP-P0-003"
PARALLEL_CURRENT_STATE_PATHS = {
    "docs/00-governance/CURRENT_STATE_PROPOSAL_WP-P0-001.md",
}

WP_P0_001_REQUIRED_HEADINGS = [
    "## 1. Metadata",
    "## 2. Outcome",
    "## 3. Source Requirements",
    "## 4. Scope",
    "## 5. Non-goals",
    "## 6. Design Deliverables",
    "## 7. Acceptance Criteria",
    "## 8. Required Evidence",
    "## 9. Risks and Constraints",
    "## 10. Controller Gate",
]

WP_P0_002_REQUIRED_HEADINGS = [
    "## 1. Metadata",
    "## 2. Business Outcome",
    "## 3. Source Requirements and Planning Inputs",
    "## 4. Ownership and Association Semantics",
    "## 5. Requirement Closure Contract",
    "## 6. Scope",
    "## 7. Non-goals",
    "## 8. Inputs, Outputs and Failure States",
    "## 9. Security and Data Boundaries",
    "## 10. Acceptance Criteria",
    "## 11. Migration, Rollback and Observability Expectations",
    "## 12. Design Deliverables and Required Evidence",
    "## 13. Risks and Constraints",
    "## 14. Controller Gate",
]

WP_P0_003_REQUIRED_HEADINGS = [
    "## 1. Metadata",
    "## 2. Business and Operator Outcome",
    "## 3. Requirement Closure Contract",
    "## 4. Scope",
    "## 5. Non-goals",
    "## 6. Dependencies and Accepted Decisions",
    "## 7. Module, Authority and Source-of-Truth Boundary",
    "## 8. Binding Correctness Invariants",
    "## 9. Raw, Object Storage, Hash and Schema Boundary",
    "## 10. Security, No-leak and Intake Boundary",
    "## 11. Migration and Compatibility Boundary",
    "## 12. Observability, Recovery and Runbook Contract",
    "## 13. Evidence and Falsification Plan",
    "## 14. Risks and Owner Gates",
    "## 15. Design Deliverables and Controller Gate",
]

WP_P0_002_METADATA = {
    "ID": WP_P0_002_ID,
    "Title": "Organization, Store, Warehouse & Credential Metadata",
    "Phase": "Sprint 0 / Phase 0",
    "Risk": "HIGH",
    "Target branch": "`main`",
}

WP_P0_003_METADATA = {
    "ID": WP_P0_003_ID,
    "Title": "Durable Ingestion Control Plane & Immutable Raw Evidence",
    "Phase": "Sprint 0 / Phase 0",
    "Status": WP_P0_003_DESIGN_FINALIZATION_STATUS,
    "Authorization": "DESIGN_ONLY",
    "Risk": "HIGH",
    "Target branch": "`main`",
    "Design status": "FINALIZATION_REQUIRED / NOT_FULLY_APPROVED",
    "Design evidence": (
        "Frozen Design v1.11 candidate + "
        f"`{WP_P0_003_ADDENDUM_RELATIVE_PATH}`"
    ),
    "Implementation-backed validation result": "VERIFIED",
    "Bounded validation authorization": "CLOSED",
    "Full implementation authorization": "PROHIBITED",
}

WP_P0_003_CLOSURE_HEADER = [
    "Requirement",
    "Closure model",
    "WP-P0-003 target subset",
    "Excluded or remaining boundary",
    "Later owner / remaining Gate",
]

WP_P0_003_CLOSURE_MODELS = {
    "D-03": "MULTI-WP",
    "D-04": "PARTIAL",
    "HR-01": "MULTI-WP",
    "HR-02": "MULTI-WP",
    "INT-001": "STRUCTURE_ONLY",
    "INT-004": "PARTIAL",
    "INT-006": "PARTIAL",
    "INT-007": "PARTIAL",
    "INT-008": "PARTIAL",
    "INT-009": "MULTI-WP",
    "INT-010": "PARTIAL / MULTI-WP",
    "INT-011": "PARTIAL",
    "INT-012": "PARTIAL",
    "INT-013": "PARTIAL",
    "INT-014": "MULTI-WP",
    "INT-019": "OUT_OF_SCOPE",
    "INT-021": "STRUCTURE_ONLY",
    "ADM-002": "MULTI-WP",
    "ADM-004": "PARTIAL / MULTI-WP",
}

WP_P0_003_LATER_OWNER_TOKENS = {
    "D-03": ("WP-P0-003 acceptance Gate",),
    "D-04": ("WP-P0-007",),
    "HR-01": ("WP-P0-003B", "WP-P0-005", "WP-P0-006", "WP-P0-007"),
    "HR-02": ("WP-P0-005", "WP-P0-006", "WP-P0-007"),
    "INT-001": ("WP-P0-005", "WP-P0-006"),
    "INT-004": ("WP-P0-005/WP-P0-006", "OQ-005"),
    "INT-006": ("WP-P0-005", "WP-P0-006"),
    "INT-007": ("WP-P0-005", "WP-P0-006", "OQ-006"),
    "INT-008": ("WP-P0-005", "WP-P0-006"),
    "INT-009": ("WP-P0-005", "WP-P0-006", "WP-P0-007"),
    "INT-010": ("WP-P0-003B", "WP-P0-005", "WP-P0-006", "OQ-006"),
    "INT-011": ("WP-P0-003B", "WP-P0-005", "WP-P0-006"),
    "INT-012": ("WP-P0-005", "WP-P0-006", "WP-P0-007"),
    "INT-013": ("WP-P0-005", "WP-P0-006", "WP-P0-007"),
    "INT-014": ("source and domain Work Packages",),
    "INT-019": ("WP-P0-003B",),
    "INT-021": ("WP-P0-005", "WP-P0-006", "WP-P0-007"),
    "ADM-002": ("WP-P0-005", "WP-P0-006"),
    "ADM-004": ("WP-P0-008", "OQ-005", "runtime IAM Work Package"),
}

WP_P0_003_CLOSURE_FIELD_TOKENS = {
    "HR-01": {
        "WP-P0-003 target subset": (
            "exact returned response/report/event bytes",
            "business-meaningful failures",
            "request metadata",
            "schema version",
            "source time",
            "ingestion time",
            "provenance",
        ),
        "Excluded or remaining boundary": (
            "no returned source bytes",
            "failure-record-only",
        ),
    },
    "INT-007": {
        "WP-P0-003 target subset": (
            "Account + Endpoint + opaque Credential reference/identity",
        ),
        "Excluded or remaining boundary": (
            "no Secret retrieval",
            "real quota guessing",
        ),
    },
    "INT-010": {
        "WP-P0-003 target subset": (
            "exact returned bytes",
            "schema version",
            "source/ingestion time",
            "provenance",
            "business-meaningful failed calls",
        ),
        "Excluded or remaining boundary": (
            "no-source-byte transport/connectivity failures",
            "failure-record-only",
        ),
    },
    "ADM-004": {
        "WP-P0-003 target subset": (
            "Job Run",
            "Error Queue",
            "Replay",
            "Dead-letter",
            "recovery-command contract",
            "audit linkage",
            "single runtime authority",
        ),
        "Excluded or remaining boundary": (
            "Data Quality/Admin product view",
            "cross-domain UX",
            "final Phase 0 management closure",
            "authenticated/public operator surface",
        ),
    },
}

WP_P0_003_AUTHORITY_HEADER = [
    "Capability",
    "Sole executor / writer",
    "Consumer-only module",
    "Authority mode",
]
WP_P0_003_AUTHORITY_CONTRACT = {
    "Job scheduler/worker": (
        "marketplaceintegration",
        "adminobservability",
        "SINGLE",
    ),
    "Cursor/checkpoint writer": (
        "marketplaceintegration",
        "adminobservability",
        "SINGLE",
    ),
    "Replay/dead-letter recovery command executor": (
        "marketplaceintegration",
        "adminobservability",
        "SINGLE",
    ),
    "Raw object-store intake coordinator": (
        "marketplaceintegration",
        "adminobservability",
        "SINGLE",
    ),
}

WP_P0_003_RAW_OUTCOME_HEADER = [
    "Source outcome",
    "Returned source bytes",
    "Required durable treatment",
]
WP_P0_003_RAW_OUTCOME_CONTRACT = {
    "Successful call": (
        "YES",
        "Immutable Raw exact bytes plus request metadata, hash, schema version, source time, ingestion time and provenance",
    ),
    "Business-meaningful failed call": (
        "YES",
        "Immutable Raw exact bytes plus request metadata, hash, schema version, source time, ingestion time and provenance; never failure-record-only",
    ),
    "Transport/connectivity failure": (
        "NO",
        "Attributable failure-record-only treatment is permitted",
    ),
}

WP_P0_003_RATE_LIMIT_HEADER = ["Dimension / rule", "Required contract"]
WP_P0_003_RATE_LIMIT_CONTRACT = {
    "Account": "Opaque Account identity",
    "Endpoint": "Provider-neutral Endpoint identity",
    "Credential": "Opaque Credential reference/identity; no Secret retrieval",
    "Partitioning": (
        "Distinct Credential scopes/identities under the same Account and Endpoint "
        "must not be silently merged unless future verified platform evidence "
        "explicitly permits it"
    ),
    "Quota semantics": (
        "No real quota guessing; WP-P0-005/WP-P0-006 retain verified platform "
        "quotas and response semantics; OQ-006 remains OPEN"
    ),
}

WP_P0_003_OWNER_GATE_HEADER = [
    "Gate",
    "Status",
    "Allowed before answer",
    "Blocked before answer",
    "Later owner",
]
WP_P0_003_OWNER_GATE_CONTRACT = {
    "OQ-005": (
        "OPEN",
        "Internal provider-neutral worker and operator contract Design",
        "Any authenticated/public operator, webhook, manual-trigger or file-upload runtime surface",
        "Future runtime IAM Work Package selected by the Controller",
    ),
    "OQ-006": (
        "OPEN",
        "Provider-neutral object and opaque Credential-reference contract Design",
        "Concrete Object Storage/Secret Final Design approval, Implementation authorization, bounded Raw acceptance, Secret retrieval and real quota assumptions",
        "Human Owner + Security, then WP-P0-005/WP-P0-006 platform evidence",
    ),
}

DR_0002_HEADING = "# DR-0002 — Split Controlled File Import from WP-P0-003"
DR_0002_LEADING_YAML = {
    "decision_request": "DR-0002",
    "status": "ACCEPTED",
    "trigger": "CONTROLLER_PHASE_0_PLANNING",
    "owner_approval": "EXPLICIT",
    "owner_instruction_date": "2026-08-20",
    "controller_recommendation": "ACCEPT_BOUNDED_SPLIT",
    "effective_condition": "GOVERNANCE_PR_MERGE",
}
DR_0002_UNIQUE_AUTHORITY_KEYS = (
    "status",
    "owner_approval",
    "effective_condition",
)

# The two coherent WP-P0-002 stages. The record's Status selects the stage and
# every stage-dependent field must agree with it; any other combination is an
# invalid mixed state.
WP_P0_002_STAGE_FIELDS = {
    DESIGN_ACTIVE_GATE: {
        "Authorization": "DESIGN_ONLY",
        "Design artifact": "NOT_YET_PRODUCED",
        "Implementation authorization": "PROHIBITED",
    },
    IMPLEMENTATION_ACTIVE_GATE: {
        "Authorization": "APPROVED_FOR_IMPLEMENTATION",
        "Design artifact": f"`{WP_P0_002_DESIGN_RELATIVE_PATH}`",
        "Implementation authorization": "APPROVED_FOR_IMPLEMENTATION",
    },
    COMPLETED_WP_STATUS: {
        "Historic design verdict": HISTORIC_DESIGN_VERDICT,
        "Current execution authorization": COMPLETED_WP_AUTHORIZATION,
        "Implementation result": COMPLETED_WP_RESULT,
        "Design artifact": f"`{WP_P0_002_DESIGN_RELATIVE_PATH}`",
        "Approved Design v1.2 SHA-256": WP_P0_002_DESIGN_SHA256,
    },
}

WP_P0_002_TRACEABILITY_CONTRACT = {
    "IAM-001": (WP_P0_002_ID, "PARTIAL in WP-P0-002", "runtime IAM"),
    "IAM-004": (WP_P0_002_ID, "PARTIAL in WP-P0-002", "runtime IAM"),
    "IAM-006": (WP_P0_002_ID, "PARTIAL in WP-P0-002", "runtime integration"),
    "IAM-007": (WP_P0_002_ID, "PARTIAL in WP-P0-002", "runtime IAM"),
    "INT-002": (
        "WP-P0-002;WP-P0-005;WP-P0-006",
        "PARTIAL in WP-P0-002",
        "WP-P0-005/006",
    ),
    "INT-003": (WP_P0_002_ID, "PARTIAL in WP-P0-002", "OQ-006"),
    "ADM-001": (WP_P0_002_ID, "FULL closure in WP-P0-002", "fail-closed"),
    "ADM-002": (
        "WP-P0-002;WP-P0-003;WP-P0-005;WP-P0-006",
        "PARTIAL in WP-P0-002",
        "WP-P0-005/006",
    ),
}

WP_P0_002_PARTIAL_REQUIREMENTS = {
    "IAM-001", "IAM-004", "IAM-006", "IAM-007", "INT-002", "INT-003", "ADM-002",
}
WP_P0_002_FULL_REQUIREMENTS = {"ADM-001"}
WP_P0_002_ACCEPTANCE_HEADER = [
    "Criterion",
    "Criterion text",
    "Closure status",
    "Production location",
    "Exact tests",
    "Exact evidence",
    "Remaining boundary",
]

TRACEABILITY_HEADER = [
    "source_id",
    "source_type",
    "phase",
    "title",
    "work_package",
    "design_record",
    "code_location",
    "test_case",
    "evidence",
    "status",
    "notes",
]

WP_P0_003_TRACEABILITY_CONTRACT = {
    "D-03": (
        "WP-P0-001;WP-P0-003",
        "ACTIVE_CONTROL",
        WP_P0_003_RELATIVE_PATH,
        (
            "MULTI-WP",
            "WP-P0-001 verified",
            "internal PostgreSQL Task/Worker",
            "INT-017",
            "ACTIVE_CONTROL",
        ),
    ),
    "D-04": (
        "WP-P0-003;WP-P0-007",
        "ACTIVE_CONTROL",
        WP_P0_003_RELATIVE_PATH,
        ("PARTIAL", "WP-P0-007", "ACTIVE_CONTROL"),
    ),
    "HR-01": (
        "WP-P0-003;WP-P0-003B;WP-P0-005;WP-P0-006;WP-P0-007",
        "ACTIVE_CONTROL",
        WP_P0_003_RELATIVE_PATH,
        (
            "MULTI-WP",
            "exact returned response/report/event bytes",
            "business-meaningful failures",
            "no-source-byte transport/connectivity failures",
            "failure-record-only",
            "WP-P0-003B",
            "WP-P0-005/006/007",
            "ACTIVE_CONTROL",
        ),
    ),
    "HR-02": (
        "WP-P0-003;WP-P0-005;WP-P0-006;WP-P0-007",
        "ACTIVE_CONTROL",
        WP_P0_003_RELATIVE_PATH,
        ("MULTI-WP", "WP-P0-005/006/007", "ACTIVE_CONTROL"),
    ),
    "INT-001": (
        "WP-P0-003;WP-P0-005;WP-P0-006",
        "PLANNED",
        WP_P0_003_RELATIVE_PATH,
        ("STRUCTURE_ONLY", "WP-P0-005/006", "no vendor client"),
    ),
    "INT-004": (
        "WP-P0-003;WP-P0-005;WP-P0-006",
        "PLANNED",
        WP_P0_003_RELATIVE_PATH,
        ("PARTIAL", "WP-P0-005/006", "runtime IAM"),
    ),
    "INT-006": (
        "WP-P0-003;WP-P0-005;WP-P0-006",
        "PLANNED",
        WP_P0_003_RELATIVE_PATH,
        ("PARTIAL", "WP-P0-005/006", "endpoint semantics"),
    ),
    "INT-007": (
        "WP-P0-003;WP-P0-005;WP-P0-006",
        "PLANNED",
        WP_P0_003_RELATIVE_PATH,
        (
            "PARTIAL",
            "Account + Endpoint + opaque Credential reference/identity",
            "cannot be silently merged",
            "no Secret retrieval",
            "real quota guessing",
            "WP-P0-005/006",
            "OQ-006 remains OPEN",
        ),
    ),
    "INT-008": (
        "WP-P0-003;WP-P0-005;WP-P0-006",
        "PLANNED",
        WP_P0_003_RELATIVE_PATH,
        ("PARTIAL", "WP-P0-005/006", "platform error taxonomy"),
    ),
    "INT-009": (
        "WP-P0-003;WP-P0-005;WP-P0-006;WP-P0-007",
        "ACTIVE_CONTROL",
        WP_P0_003_RELATIVE_PATH,
        ("MULTI-WP", "WP-P0-005/006/007", "ACTIVE_CONTROL"),
    ),
    "INT-010": (
        "WP-P0-003;WP-P0-003B;WP-P0-005;WP-P0-006",
        "ACTIVE_CONTROL",
        WP_P0_003_RELATIVE_PATH,
        (
            "PARTIAL / MULTI-WP",
            "exact returned bytes",
            "business-meaningful failures",
            "HTTP/business status cannot downgrade",
            "no-source-byte transport/connectivity failures",
            "failure-record-only",
            "WP-P0-003B",
            "WP-P0-005/006",
            "OQ-006",
        ),
    ),
    "INT-011": (
        "WP-P0-003;WP-P0-003B;WP-P0-005;WP-P0-006",
        "PLANNED",
        WP_P0_003_RELATIVE_PATH,
        ("PARTIAL", "WP-P0-003B", "WP-P0-005/006"),
    ),
    "INT-012": (
        "WP-P0-003;WP-P0-005;WP-P0-006;WP-P0-007",
        "PLANNED",
        WP_P0_003_RELATIVE_PATH,
        ("PARTIAL", "WP-P0-005/006/007", "source-specific reconciliation"),
    ),
    "INT-013": (
        "WP-P0-003;WP-P0-005;WP-P0-006;WP-P0-007",
        "PLANNED",
        WP_P0_003_RELATIVE_PATH,
        ("PARTIAL", "WP-P0-005/006/007", "real historical evidence"),
    ),
    "INT-014": (
        "WP-P0-003;WP-P0-003B;WP-P0-005;WP-P0-006;WP-P0-007",
        "ACTIVE_CONTROL",
        WP_P0_003_RELATIVE_PATH,
        ("MULTI-WP", "every later source/domain Work Package", "ACTIVE_CONTROL"),
    ),
    "INT-019": (
        "WP-P0-003B",
        "PLANNED",
        WP_P0_003B_DECISION_REQUEST_RELATIVE_PATH,
        ("OUT_OF_SCOPE", "DRAFT WP-P0-003B", "no file-upload/importer"),
    ),
    "INT-021": (
        "WP-P0-003;WP-P0-005;WP-P0-006;WP-P0-007",
        "PLANNED",
        WP_P0_003_RELATIVE_PATH,
        ("STRUCTURE_ONLY", "WP-P0-005/006/007", "actual mappings"),
    ),
    "ADM-002": (
        "WP-P0-002;WP-P0-003;WP-P0-005;WP-P0-006",
        "ACTIVE_CONTROL",
        WP_P0_003_RELATIVE_PATH,
        (
            "PARTIAL in WP-P0-002",
            "MULTI-WP",
            "WP-P0-002 subset VERIFIED",
            "WP-P0-003 owns Job Schedule/Backfill",
            "WP-P0-005/006",
            "remains OPEN",
        ),
    ),
    "ADM-004": (
        "WP-P0-003;WP-P0-008",
        "PLANNED",
        WP_P0_003_RELATIVE_PATH,
        (
            "PARTIAL / MULTI-WP",
            "Job Run/Error Queue/Replay/Dead-letter state",
            "recovery-command contract",
            "audit linkage",
            "sole runtime executor/writer authority in marketplaceintegration",
            "adminobservability consumes the contract",
            "not a second executor/writer",
            "WP-P0-008 owns the Data Quality/Admin product view",
            "cross-domain UX",
            "final Phase 0 management closure",
            "OQ-005",
            "future runtime IAM Work Package",
            "authenticated/public operator surface",
        ),
    ),
}

WP_P0_003_PRIOR_EVIDENCE_CONTRACT = {
    "D-03": {
        "code_location": (
            "backend/marketops-server/src/test/java/com/mimococo/marketops/"
            "architecture/ArchitectureRules.java"
        ),
        "test_case": (
            "TC-ARCH-001;TC-ARCH-002;TC-ARCH-003;TC-ARCH-004;"
            "TC-ARCH-005;TC-ARCH-006;TC-ARCH-007;TC-ARCH-008"
        ),
        "evidence": "docs/07-phase-evidence/WP-P0-001/local-verification.md",
    },
    "ADM-002": {
        "code_location": (
            "backend/marketops-server/src/main/java/com/mimococo/marketops/"
            "marketplaceintegration/internal/application/FeatureFlagService.java;"
            "backend/marketops-server/src/main/java/com/mimococo/marketops/"
            "shared/ProductionWritePolicy.java"
        ),
        "test_case": (
            "TC-API-040;TC-API-041;TC-API-050;TC-API-085;"
            "TC-FF-101;TC-FF-103;TC-DB-207"
        ),
        "evidence": (
            "docs/07-phase-evidence/WP-P0-002/README.md;"
            "docs/07-phase-evidence/WP-P0-002/acceptance-criteria.md"
        ),
    },
}

WP_P0_003_PREIMPLEMENTATION_EMPTY_TRACEABILITY_IDS = {
    "D-04",
    "HR-01",
    "HR-02",
    "INT-001",
    "INT-004",
    "INT-006",
    "INT-007",
    "INT-008",
    "INT-009",
    "INT-010",
    "INT-011",
    "INT-012",
    "INT-013",
    "INT-014",
    "INT-019",
    "INT-021",
    "ADM-004",
}

TEST_ID_PATTERN = r"TC-[A-Z]+(?:-[A-Z]+)*-[0-9]+[A-Za-z]?"
JAVA_TEST_DISPLAY_NAME = re.compile(
    rf'@DisplayName\("(?P<test_id>{TEST_ID_PATTERN})'
    rf'(?:\s+(?P<display_text>[^\"]+))?"\)'
)
JAVA_TEST_MEMBER = re.compile(
    r"\bvoid\s+(?P<method>[A-Za-z_][A-Za-z0-9_]*)\s*\("
    r"|\bclass\s+(?P<nested_group>[A-Za-z_][A-Za-z0-9_]*)\b"
)


@dataclass(frozen=True)
class JavaTestIdentity:
    """One canonical JUnit display identity found by the bounded repository scanner."""

    test_id: str
    file: str
    class_name: str
    member: str
    member_kind: str
    display_text: str
    line: int
    occurrence_count: int

SCAN_EXTENSIONS = {
    ".yml", ".yaml", ".json", ".properties", ".toml", ".xml",
    ".java", ".kt", ".ts", ".tsx", ".js", ".jsx", ".py", ".sh", ".ps1",
}

SECRET_PATTERNS = [
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bghp_[A-Za-z0-9]{30,}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{30,}\b"),
    re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"),
    re.compile(r"\bsk-[A-Za-z0-9]{24,}\b"),
    re.compile(r"(?i)(?:password|passwd|secret|token|api[_-]?key)\s*[:=]\s*[\"'][^\"']{12,}[\"']"),
]


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def directory_tree_sha256(path: Path) -> str:
    """Hash relative names and bytes so historical evidence cannot drift."""
    h = hashlib.sha256()
    for item in sorted(candidate for candidate in path.rglob("*") if candidate.is_file()):
        h.update(item.relative_to(path).as_posix().encode("utf-8"))
        h.update(b"\0")
        h.update(item.read_bytes())
        h.update(b"\0")
    return h.hexdigest()


def validate_required_file_set(errors: list[str], existing_paths: set[str]) -> None:
    for relative in dict.fromkeys(
        REQUIRED_FILES + DR0003_REQUIRED_FILES + DR0004_REQUIRED_FILES
    ):
        if relative not in existing_paths:
            errors.append(f"missing required file: {relative}")


def validate_required_files(errors: list[str]) -> None:
    required = set(REQUIRED_FILES + DR0003_REQUIRED_FILES + DR0004_REQUIRED_FILES)
    validate_required_file_set(
        errors,
        {relative for relative in required if (ROOT / relative).is_file()},
    )


def validate_source_checksums(errors: list[str]) -> None:
    sums_path = ROOT / "docs/01-requirements/SHA256SUMS.txt"
    if not sums_path.exists():
        return
    base = sums_path.parent
    for raw_line in sums_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            expected, filename = line.split(None, 1)
        except ValueError:
            errors.append(f"invalid checksum line: {raw_line}")
            continue
        target = base / filename.strip()
        if not target.is_file():
            errors.append(f"checksum target missing: {target.relative_to(ROOT)}")
            continue
        actual = sha256(target)
        if actual != expected:
            errors.append(f"source baseline checksum mismatch: {target.relative_to(ROOT)}")


def validate_work_package(errors: list[str]) -> None:
    wp_p0_001 = ROOT / WP_P0_001_RELATIVE_PATH
    if wp_p0_001.exists():
        text = wp_p0_001.read_text(encoding="utf-8")
        for heading in WP_P0_001_REQUIRED_HEADINGS:
            if heading not in text:
                errors.append(f"WP-P0-001 missing heading: {heading}")

    wp_p0_002 = ROOT / WP_P0_002_RELATIVE_PATH
    if not wp_p0_002.exists():
        return
    text = wp_p0_002.read_text(encoding="utf-8")
    for heading in WP_P0_002_REQUIRED_HEADINGS:
        if heading not in text:
            errors.append(f"WP-P0-002 missing heading: {heading}")
    for field, expected in WP_P0_002_METADATA.items():
        actual = work_package_metadata_value(text, field)
        if actual != expected:
            errors.append(f"WP-P0-002 {field} must be exactly: {expected}")

    stage = work_package_metadata_value(text, "Status")
    stage_fields = WP_P0_002_STAGE_FIELDS.get(stage or "")
    if stage_fields is None:
        errors.append(
            "WP-P0-002 Status must be exactly one of: "
            + ", ".join(sorted(WP_P0_002_STAGE_FIELDS))
        )
    else:
        for field, expected in stage_fields.items():
            actual = work_package_metadata_value(text, field)
            if actual != expected:
                errors.append(
                    f"WP-P0-002 {field} must be exactly {expected} while {stage}"
                )
    if stage in {IMPLEMENTATION_ACTIVE_GATE, COMPLETED_WP_STATUS}:
        validate_wp_p0_002_canonical_design(errors)

    for requirement, closure in {
        "IAM-001": "PARTIAL",
        "IAM-004": "PARTIAL",
        "IAM-006": "PARTIAL",
        "IAM-007": "PARTIAL",
        "INT-002": "PARTIAL",
        "INT-003": "PARTIAL",
        "ADM-001": "FULL",
        "ADM-002": "PARTIAL",
    }.items():
        if f"| {requirement} | {closure} |" not in text:
            errors.append(
                f"WP-P0-002 Requirement Closure Contract missing {requirement}: {closure}"
            )

    for required in [
        "Organization → Legal Entity → Marketplace Account → Store",
        "Legal Entity-owned operational node",
        "configurable service / fulfillment association",
        "UNKNOWN until verified platform evidence exists",
        "Marketplace-fulfilled",
        "Seller-fulfilled",
        "local/internal/admin-only and fails closed",
        "audit` is a conceptual responsibility, not an approved PostgreSQL schema",
        "iam / platform / raw / staging / core / ledger / mart / ops",
        "Implementation: PROHIBITED until APPROVED_FOR_IMPLEMENTATION",
    ]:
        if required not in text:
            errors.append(f"WP-P0-002 missing Design contract: {required}")

    wp_p0_003 = ROOT / WP_P0_003_RELATIVE_PATH
    if wp_p0_003.exists():
        validate_wp_p0_003_work_package_text(
            errors,
            wp_p0_003.read_text(encoding="utf-8"),
        )


def validate_wp_p0_002_canonical_design(errors: list[str]) -> None:
    """Pin the approved WP-P0-002 design to the reviewed artifact by hash."""
    design_path = ROOT / WP_P0_002_DESIGN_RELATIVE_PATH
    if not design_path.exists():
        errors.append(
            "WP-P0-002 implementation stage requires the canonical design at: "
            + WP_P0_002_DESIGN_RELATIVE_PATH
        )
        return
    digest = hashlib.sha256(design_path.read_bytes()).hexdigest()
    if digest != WP_P0_002_DESIGN_SHA256:
        errors.append(
            "WP-P0-002 canonical design content differs from the approved "
            f"artifact: expected sha256 {WP_P0_002_DESIGN_SHA256}, found {digest}"
        )


def validate_parallel_current_state_paths(
    errors: list[str], existing_paths: set[str]
) -> None:
    """Reject a second state source even when it calls itself a proposal."""
    for relative in sorted(PARALLEL_CURRENT_STATE_PATHS & existing_paths):
        errors.append(f"parallel Current State source is prohibited: {relative}")


def leading_yaml_body(text: str, expected_heading: str | None = None) -> str | None:
    heading = re.escape(expected_heading) if expected_heading else r"#[^\n]+"
    match = re.search(
        rf"(?ms)\A{heading}\s*$\n\s*```yaml\s*$\n(?P<body>.*?)^```\s*$",
        text,
    )
    return match.group("body") if match else None


def fenced_yaml_body(text: str) -> str | None:
    return leading_yaml_body(text, "# Current State")


def unique_yaml_value(text: str, field: str) -> str | None:
    matches = re.findall(
        rf"(?m)^{re.escape(field)}:\s*(.*?)\s*$",
        text,
    )
    values = [match.strip() for match in matches]
    return values[0] if len(values) == 1 and values[0] else None


def current_state_value(text: str, field: str) -> str | None:
    metadata = fenced_yaml_body(text)
    return unique_yaml_value(metadata if metadata is not None else text, field)


def current_state_metadata_value(text: str, field: str) -> str | None:
    metadata = fenced_yaml_body(text)
    return unique_yaml_value(metadata, field) if metadata is not None else None


def h2_section_body(text: str, heading: str) -> str | None:
    section = re.search(
        rf"(?ms)^{re.escape(heading)}\s*$\n(?P<body>.*?)(?=^## |\Z)",
        text,
    )
    return section.group("body") if section else None


def markdown_table_value(text: str, heading: str, field: str) -> str | None:
    body = h2_section_body(text, heading)
    if body is None:
        return None

    values: list[str] = []
    for line in body.splitlines():
        if not line.startswith("|") or not line.endswith("|"):
            continue
        cells = [cell.strip() for cell in line[1:-1].split("|")]
        if len(cells) == 2 and cells[0] == field:
            values.append(cells[1])
    return values[0] if len(values) == 1 else None


def work_package_metadata_value(text: str, field: str) -> str | None:
    return markdown_table_value(text, "## 1. Metadata", field)


def work_package_execution_authorization(text: str) -> str | None:
    """Return the unambiguous current authorization of a Work Package.

    Completed packages use the explicit field so their historic design verdict
    cannot be mistaken for current permission. Active-package records may use
    the generic field, but both fields at once are ambiguous and invalid.
    """
    explicit = work_package_metadata_value(text, "Current execution authorization")
    generic = work_package_metadata_value(text, "Authorization")
    if explicit is not None and generic is not None:
        return None
    return explicit if explicit is not None else generic


def validate_completed_wp_p0_001_text(errors: list[str], text: str) -> None:
    """Preserve the independently verified WP-P0-001 closure in every later state."""
    expected = {
        "Status": COMPLETED_WP_STATUS,
        "Historic design verdict": HISTORIC_DESIGN_VERDICT,
        "Implementation result": COMPLETED_WP_RESULT,
    }
    for field, value in expected.items():
        if work_package_metadata_value(text, field) != value:
            errors.append(f"WP-P0-001 {field} must be exactly: {value}")
    if work_package_execution_authorization(text) != COMPLETED_WP_AUTHORIZATION:
        errors.append("WP-P0-001 current execution authorization must be CLOSED")


def validate_active_work_package_record_text(
    errors: list[str],
    current_state_text: str,
    work_package_records: dict[str, str],
) -> None:
    """Resolve an active Work Package to its one canonical repository record."""
    active = current_state_metadata_value(current_state_text, "active_work_package")
    if active is None:
        errors.append("CURRENT_STATE active_work_package metadata is missing or duplicated")
        return
    if active == "NONE":
        return
    if active not in WORK_PACKAGE_PATHS:
        errors.append(f"CURRENT_STATE active Work Package is not registered: {active}")
        return
    record = work_package_records.get(active)
    if record is None:
        errors.append(
            f"active Work Package canonical file is missing: {WORK_PACKAGE_PATHS[active]}"
        )
        return
    if work_package_metadata_value(record, "ID") != active:
        errors.append(f"active Work Package canonical file ID must be exactly: {active}")


def markdown_table_cells(line: str) -> list[str] | None:
    """Return trimmed cells for one pipe-delimited Markdown table row."""
    stripped = line.strip()
    if not stripped.startswith("|") or not stripped.endswith("|"):
        return None
    return [cell.strip() for cell in stripped[1:-1].split("|")]


def markdown_table_rows(
    text: str, header: list[str]
) -> list[dict[str, str]] | None:
    """Parse exactly one Markdown table with the supplied header."""
    lines = text.splitlines()
    parsed_tables: list[list[dict[str, str]]] = []
    for index, line in enumerate(lines):
        if markdown_table_cells(line) != header:
            continue
        if index + 1 >= len(lines):
            return None
        separator = markdown_table_cells(lines[index + 1])
        if separator is None or len(separator) != len(header):
            return None
        if any(re.fullmatch(r":?-{3,}:?", cell) is None for cell in separator):
            return None

        rows: list[dict[str, str]] = []
        for row_line in lines[index + 2:]:
            cells = markdown_table_cells(row_line)
            if cells is None:
                break
            if len(cells) != len(header):
                return None
            rows.append(dict(zip(header, cells)))
        parsed_tables.append(rows)
    return parsed_tables[0] if len(parsed_tables) == 1 else None


def validate_exact_keyed_table(
    errors: list[str],
    text: str,
    header: list[str],
    expected_rows: dict[str, tuple[str, ...] | str],
    contract_name: str,
) -> None:
    """Bind one small high-risk table exactly; semantic review remains separate."""
    rows = markdown_table_rows(text, header)
    if rows is None:
        errors.append(f"{contract_name} must be exactly one structurally valid table")
        return
    key_field = header[0]
    keys = [row[key_field] for row in rows]
    if len(keys) != len(set(keys)):
        errors.append(f"{contract_name} contains duplicate {key_field} declarations")
    if set(keys) != set(expected_rows):
        errors.append(f"{contract_name} must contain the exact controlled row set")
    by_key = {row[key_field]: row for row in rows}
    value_fields = header[1:]
    for key, expected_values in expected_rows.items():
        row = by_key.get(key)
        if row is None:
            continue
        normalized = (
            (expected_values,)
            if isinstance(expected_values, str)
            else expected_values
        )
        if len(normalized) != len(value_fields):
            errors.append(f"internal {contract_name} validator contract is malformed")
            continue
        for field, expected in zip(value_fields, normalized):
            if row[field] != expected:
                errors.append(
                    f"{contract_name} {key} {field} must be exactly: {expected}"
                )


def validate_dr_0002_text(errors: list[str], text: str) -> None:
    """Require one exact leading DR authority block plus bounded scope facts."""
    metadata = leading_yaml_body(text, DR_0002_HEADING)
    if metadata is None:
        errors.append("DR-0002 leading YAML metadata is missing or malformed")
    else:
        parsed_fields: list[str] = []
        malformed_lines: list[str] = []
        for line in metadata.splitlines():
            if not line.strip():
                continue
            match = re.fullmatch(r"([a-z][a-z0-9_]*):\s*(\S(?:.*\S)?)\s*", line)
            if match is None:
                malformed_lines.append(line)
            else:
                parsed_fields.append(match.group(1))
        if malformed_lines:
            errors.append("DR-0002 leading YAML contains malformed fields")
        duplicates = sorted(
            field for field, count in Counter(parsed_fields).items() if count > 1
        )
        if duplicates:
            errors.append(
                "DR-0002 leading YAML contains duplicate fields: "
                + ", ".join(duplicates)
            )
        if set(parsed_fields) != set(DR_0002_LEADING_YAML):
            errors.append("DR-0002 leading YAML must contain the exact authority fields")
        for field, expected in DR_0002_LEADING_YAML.items():
            actual = unique_yaml_value(metadata, field)
            if actual != expected:
                errors.append(f"DR-0002 {field} must be uniquely exactly: {expected}")

    for field in DR_0002_UNIQUE_AUTHORITY_KEYS:
        declarations = re.findall(
            rf"(?m)^{re.escape(field)}:\s*(.*?)\s*$",
            text,
        )
        if len(declarations) != 1:
            errors.append(
                f"DR-0002 {field} must appear exactly once in leading YAML "
                "and nowhere else"
            )

    for token in (
        "WP-P0-003B — Controlled File Import & Source Intake Security",
        "No Design, migration or implementation",
        "`ADM-004` is explicitly `PARTIAL / MULTI-WP`",
        "WP-P0-008` owns the Data Quality/Admin product view",
        "OQ-005 and a future runtime IAM Work Package",
        "marketplaceintegration`\n  as the sole scheduler/worker",
        "adminobservability` module consumes that contract",
        "not a second executor/writer",
        "OQ-005 and OQ-006 remain OPEN",
        "No current infrastructure, provider or operating cost",
        "`ACCEPTED`, with repository effective date pending",
    ):
        if token not in text:
            errors.append(f"DR-0002 missing bounded-split contract: {token}")


def validate_wp_p0_003_work_package_text(
    errors: list[str], text: str
) -> None:
    """Bind the canonical WP-P0-003 Design-only Planning Contract."""
    for heading in WP_P0_003_REQUIRED_HEADINGS:
        if heading not in text:
            errors.append(f"WP-P0-003 missing heading: {heading}")

    for field, expected in WP_P0_003_METADATA.items():
        actual = work_package_metadata_value(text, field)
        if actual != expected:
            errors.append(f"WP-P0-003 {field} must be exactly: {expected}")

    rows = markdown_table_rows(text, WP_P0_003_CLOSURE_HEADER)
    if rows is None:
        errors.append(
            "WP-P0-003 must contain exactly one structurally valid closure matrix"
        )
    else:
        by_id = {row["Requirement"]: row for row in rows}
        ids = [row["Requirement"] for row in rows]
        if len(ids) != len(set(ids)):
            errors.append("WP-P0-003 closure matrix contains duplicate requirements")
        if set(ids) != set(WP_P0_003_CLOSURE_MODELS):
            errors.append(
                "WP-P0-003 closure matrix must contain the exact planned requirement set"
            )
        for source_id, expected_model in WP_P0_003_CLOSURE_MODELS.items():
            row = by_id.get(source_id)
            if row is None:
                continue
            actual_model = row["Closure model"]
            if actual_model != expected_model:
                if source_id == "INT-019":
                    errors.append(
                        "WP-P0-003 cannot claim INT-019 FULL; it must remain OUT_OF_SCOPE"
                    )
                else:
                    errors.append(
                        f"WP-P0-003 {source_id} closure model must be exactly: "
                        + expected_model
                    )
            later = row["Later owner / remaining Gate"]
            if not later or later in {"NONE", "N/A", "NOT_APPLICABLE"}:
                errors.append(
                    f"WP-P0-003 non-FULL closure {source_id} must name its later owner or Gate"
                )
            for token in WP_P0_003_LATER_OWNER_TOKENS[source_id]:
                if token not in later:
                    errors.append(
                        f"WP-P0-003 {source_id} later owner/Gate missing: {token}"
                    )
            for field, tokens in WP_P0_003_CLOSURE_FIELD_TOKENS.get(
                source_id, {}
            ).items():
                for token in tokens:
                    if token not in row[field]:
                        errors.append(
                            f"WP-P0-003 {source_id} {field} missing: {token}"
                        )

    validate_exact_keyed_table(
        errors,
        text,
        WP_P0_003_AUTHORITY_HEADER,
        WP_P0_003_AUTHORITY_CONTRACT,
        "WP-P0-003 runtime authority declaration",
    )
    validate_exact_keyed_table(
        errors,
        text,
        WP_P0_003_RAW_OUTCOME_HEADER,
        WP_P0_003_RAW_OUTCOME_CONTRACT,
        "WP-P0-003 Raw outcome contract",
    )
    validate_exact_keyed_table(
        errors,
        text,
        WP_P0_003_RATE_LIMIT_HEADER,
        WP_P0_003_RATE_LIMIT_CONTRACT,
        "WP-P0-003 rate-limit identity contract",
    )
    validate_exact_keyed_table(
        errors,
        text,
        WP_P0_003_OWNER_GATE_HEADER,
        WP_P0_003_OWNER_GATE_CONTRACT,
        "WP-P0-003 Owner Gate allocation",
    )

    for required in (
        "No real Ozon/Wildberries HTTP/SDK Adapter",
        "No public webhook endpoint",
        "No CSV/Excel/file-upload/importer implementation",
        "No INT-017 platform-write Command Outbox",
        "Exact Raw bytes are durably stored and hash-verified before the corresponding",
        "cursor/checkpoint is acknowledged",
        "There may be only one scheduler/worker",
        "marketplaceintegration` is the single owner",
        "adminobservability` consumes module contracts",
        "A stale or expired worker cannot advance cursor",
        "failure Raw must never be discarded",
        "Replay reads saved Raw evidence and performs zero Marketplace outbound calls",
        "Use forward-only V0007+ migrations; never edit V0001–V0006",
        "OQ-006 blocks concrete Object Storage/Secret Final Design approval",
        "Implementation authorization and bounded INT-010/HR-01 Raw acceptance",
        "IMPLEMENTATION_BACKED_DESIGN_VALIDATION: VERIFIED",
        "BOUNDED_VALIDATION_AUTHORIZATION: CLOSED",
        "FULL_IMPLEMENTATION_AUTHORIZATION: PROHIBITED",
        "PRODUCTION_WRITE: DISABLED",
        "WP3-EDV-BC-R4B-01",
        "MANDATORY_BEFORE_FIRST_REAL_ADAPTER_GATE",
        "com.mimococo.marketops.marketplaceintegration",
        WP_P0_003B_DECISION_REQUEST_RELATIVE_PATH,
    ):
        if required not in text:
            errors.append(f"WP-P0-003 missing binding contract: {required}")


def phase_zero_backlog_rows(text: str) -> list[dict[str, str]] | None:
    """Parse the single canonical Phase 0 backlog table structurally."""
    lines = text.splitlines()
    parsed_tables: list[list[dict[str, str]]] = []
    for index, line in enumerate(lines):
        if markdown_table_cells(line) != BACKLOG_HEADER:
            continue
        if index + 1 >= len(lines):
            return None
        separator = markdown_table_cells(lines[index + 1])
        if separator is None or len(separator) != len(BACKLOG_HEADER):
            return None
        if any(re.fullmatch(r":?-{3,}:?", cell) is None for cell in separator):
            return None

        rows: list[dict[str, str]] = []
        for row_line in lines[index + 2:]:
            cells = markdown_table_cells(row_line)
            if cells is None:
                break
            if len(cells) != len(BACKLOG_HEADER):
                return None
            rows.append(dict(zip(BACKLOG_HEADER, cells)))
        parsed_tables.append(rows)
    return parsed_tables[0] if len(parsed_tables) == 1 else None


def validate_backlog_state_text(
    errors: list[str],
    current_state_text: str,
    work_package_text: str,
    backlog_text: str,
    active_work_package_text: str | None = None,
) -> None:
    """Reconcile closed planning and one active READY_FOR_DESIGN transition."""
    rows = phase_zero_backlog_rows(backlog_text)
    if rows is None:
        errors.append("Phase 0 backlog must contain exactly one structurally valid backlog table")
        return

    ids = [row["ID"] for row in rows]
    if len(ids) != len(set(ids)):
        errors.append("Phase 0 backlog contains duplicate Work Package IDs")

    for row in rows:
        if row["Status"] not in BACKLOG_ALLOWED_STATES:
            errors.append(
                f"backlog {row['ID']} has unknown Status: {row['Status']}"
            )

    wp_rows = [row for row in rows if row["ID"] == "WP-P0-001"]
    if len(wp_rows) != 1:
        errors.append("Phase 0 backlog must contain exactly one WP-P0-001 row")
        return
    if wp_rows[0]["Status"] != COMPLETED_WP_STATUS:
        errors.append(
            f"backlog WP-P0-001 Status must be exactly: {COMPLETED_WP_STATUS}"
        )
    validate_completed_wp_p0_001_text(errors, work_package_text)

    active = current_state_metadata_value(current_state_text, "active_work_package")
    ready_rows = [row for row in rows if row["Status"] == DESIGN_ACTIVE_GATE]
    if len(ready_rows) > 1:
        errors.append(
            "Phase 0 backlog cannot contain multiple READY_FOR_DESIGN Work Packages"
        )

    if active is None:
        errors.append(
            "backlog transition requires unique CURRENT_STATE active_work_package"
        )
        expected_current = {}
    elif active == "NONE":
        expected_current = {
            "active_work_package": "NONE",
            "active_gate": POST_WP_ACTIVE_GATE,
            "authorization": "PLANNING_ONLY",
        }
        if ready_rows:
            errors.append(
                "closed planning state cannot retain a READY_FOR_DESIGN backlog row"
            )
    elif active is not None:
        stage_authorization = (
            work_package_execution_authorization(active_work_package_text)
            if active_work_package_text is not None
            else None
        )
        implementing = stage_authorization == "APPROVED_FOR_IMPLEMENTATION"
        finalizing_wp_p0_003 = (
            active == WP_P0_003_ID
            and stage_authorization == "DESIGN_ONLY"
            and current_state_metadata_value(current_state_text, "active_gate")
            == WP_P0_003_DESIGN_FINALIZATION_GATE
        )
        expected_gate = (
            IMPLEMENTATION_ACTIVE_GATE
            if implementing
            else (
                WP_P0_003_DESIGN_FINALIZATION_GATE
                if finalizing_wp_p0_003
                else DESIGN_ACTIVE_GATE
            )
        )
        expected_backlog_status = (
            DESIGN_ACTIVE_GATE if finalizing_wp_p0_003 else expected_gate
        )
        expected_work_package_status = (
            WP_P0_003_DESIGN_FINALIZATION_STATUS
            if finalizing_wp_p0_003
            else expected_gate
        )
        expected_authorization = (
            "APPROVED_FOR_IMPLEMENTATION" if implementing else "DESIGN_ONLY"
        )
        expected_current = {
            "active_work_package": active,
            "active_gate": expected_gate,
            "authorization": expected_authorization,
        }
        active_rows = [row for row in rows if row["ID"] == active]
        if len(active_rows) != 1:
            errors.append(
                f"Phase 0 backlog must contain exactly one active Work Package row: {active}"
            )
        elif active_rows[0]["Status"] != expected_backlog_status:
            errors.append(
                f"backlog active Work Package {active} Status must be exactly: "
                + expected_backlog_status
            )
        if implementing:
            if ready_rows:
                errors.append(
                    "an implementing state cannot retain a READY_FOR_DESIGN backlog row"
                )
        elif len(ready_rows) != 1 or ready_rows[0]["ID"] != active:
            errors.append(
                "the only READY_FOR_DESIGN backlog row must match CURRENT_STATE "
                "active_work_package"
            )
        if active_work_package_text is None:
            errors.append("active Work Package canonical text is required for backlog validation")
        else:
            if (
                work_package_metadata_value(active_work_package_text, "Status")
                != expected_work_package_status
            ):
                errors.append(
                    "active Work Package Status must be exactly: "
                    + expected_work_package_status
                )
            if work_package_execution_authorization(active_work_package_text) != expected_authorization:
                errors.append(
                    "active Work Package authorization must be exactly: "
                    + expected_authorization
                )
    else:
        expected_current = {}

    for field, expected in expected_current.items():
        if current_state_metadata_value(current_state_text, field) != expected:
            errors.append(
                f"backlog transition requires CURRENT_STATE {field}: {expected}"
            )


def open_question_status(text: str, source_id: str) -> str | None:
    """Return the unique status cell for an Open Questions table row."""
    statuses: list[str] = []
    for line in text.splitlines():
        cells = markdown_table_cells(line)
        if cells is not None and cells and cells[0] == source_id:
            statuses.append(cells[-1])
    return statuses[0] if len(statuses) == 1 else None


def validate_wp_p0_003_activation_text(
    errors: list[str],
    current_state_text: str,
    work_package_text: str,
    backlog_text: str,
    open_questions_text: str,
    decision_request_text: str,
) -> None:
    """Bind the coherent WP-P0-003 post-merge Design-finalization transition."""
    expected_current = {
        "active_work_package": WP_P0_003_ID,
        "active_gate": WP_P0_003_DESIGN_FINALIZATION_GATE,
        "authorization": "DESIGN_ONLY",
        "production_write_enabled": "false",
        "implementation_backed_design_validation": "VERIFIED",
        "bounded_validation_authorization": "CLOSED",
        "pr16_merge_execution": "VERIFIED",
        "full_design_approved": "false",
        "full_implementation_authorized": "false",
    }
    for field, expected in expected_current.items():
        actual = current_state_metadata_value(current_state_text, field)
        if actual != expected:
            errors.append(
                f"WP-P0-003 activation requires CURRENT_STATE {field}: {expected}"
            )

    validate_wp_p0_003_work_package_text(errors, work_package_text)

    backlog_rows = phase_zero_backlog_rows(backlog_text)
    if backlog_rows is None:
        errors.append("WP-P0-003 activation requires a valid Phase 0 backlog")
    else:
        backlog_ids = [row["ID"] for row in backlog_rows]
        if len(backlog_ids) != len(set(backlog_ids)):
            errors.append("Phase 0 backlog contains duplicate Work Package IDs")
        ready = [row for row in backlog_rows if row["Status"] == DESIGN_ACTIVE_GATE]
        if [row["ID"] for row in ready] != [WP_P0_003_ID]:
            errors.append("WP-P0-003 must be the only READY_FOR_DESIGN Work Package")
        by_id = {row["ID"]: row for row in backlog_rows}
        wp3 = by_id.get(WP_P0_003_ID)
        wp3b = by_id.get(WP_P0_003B_ID)
        wp8 = by_id.get("WP-P0-008")
        expected_wp3 = {
            "Title": "Durable Ingestion Control Plane & Immutable Raw Evidence",
            "Status": DESIGN_ACTIVE_GATE,
            "Dependencies": "WP-P0-001/002",
        }
        if wp3 is None:
            errors.append("Phase 0 backlog is missing WP-P0-003")
        else:
            for field, expected in expected_wp3.items():
                if wp3[field] != expected:
                    errors.append(f"backlog WP-P0-003 {field} must be exactly: {expected}")
            requirements = wp3["Core source requirements"]
            if "INT-019" in requirements:
                errors.append("backlog WP-P0-003 cannot retain INT-019")
            for token in (
                "D-03/D-04",
                "HR-01/02",
                "INT-001/004/006–014/021",
                "ADM-002",
                "ADM-004 generic runtime/recovery subset",
            ):
                if token not in requirements:
                    errors.append(f"backlog WP-P0-003 allocation missing: {token}")
        if wp3b is None:
            errors.append("Phase 0 backlog is missing DRAFT WP-P0-003B")
        else:
            expected_wp3b = {
                "Title": "Controlled File Import & Source Intake Security",
                "Status": "DRAFT",
                "Dependencies": WP_P0_003_ID,
            }
            for field, expected in expected_wp3b.items():
                if wp3b[field] != expected:
                    errors.append(f"backlog WP-P0-003B {field} must be exactly: {expected}")
            for token in ("INT-019", "manual-file portions of INT-010/011"):
                if token not in wp3b["Core source requirements"]:
                    errors.append(f"backlog WP-P0-003B allocation missing: {token}")
        if wp8 is None:
            errors.append("Phase 0 backlog is missing WP-P0-008")
        else:
            expected_wp8 = {
                "Title": "Data Quality & Daily Business Report v1",
                "Status": "DRAFT",
                "Dependencies": "WP-P0-003–007",
            }
            for field, expected in expected_wp8.items():
                if wp8[field] != expected:
                    errors.append(f"backlog WP-P0-008 {field} must be exactly: {expected}")
            for token in (
                "ADM-003",
                "ADM-004 final product/management closure",
            ):
                if token not in wp8["Core source requirements"]:
                    errors.append(f"backlog WP-P0-008 allocation missing: {token}")

    active_objective = h2_section_body(current_state_text, "## Active objective") or ""
    next_action = h2_section_body(current_state_text, "## Next authorized action") or ""
    for token in ("Controller", WP_P0_003_ID, "Design"):
        if token not in active_objective:
            errors.append(
                "CURRENT_STATE Active objective missing WP-P0-003 "
                f"Design-finalization handoff: {token}"
            )
    for token in (
        WP_P0_003_DESIGN_FINALIZATION_GATE,
        "DESIGN_ONLY",
        "Full Design approval",
        "implementation authorization remain false",
        "OQ-006",
    ):
        if token not in next_action:
            errors.append(f"CURRENT_STATE Next authorized action missing: {token}")

    if open_question_status(open_questions_text, "OQ-006") != "OPEN":
        errors.append("OQ-006 must remain uniquely OPEN")
    oq_section = h2_section_body(
        open_questions_text,
        "## WP-P0-003 Planning dispositions — questions remain OPEN",
    ) or ""
    for token in (
        "OQ-005",
        "OQ-006",
        "OQ-101",
        "OQ-102",
        "OQ-106",
        "OQ-107",
        "Implementation authorization",
        "bounded INT-010/HR-01 Raw acceptance",
        "No provider",
        "No Secret",
    ):
        if token not in oq_section:
            errors.append(f"WP-P0-003 Open Question disposition missing: {token}")

    validate_dr_0002_text(errors, decision_request_text)


def validate_leading_yaml_contract(
    errors: list[str],
    text: str,
    expected: dict[str, str],
    label: str,
) -> None:
    """Require one exact leading YAML value for each protected evidence field."""
    metadata = leading_yaml_body(text)
    if metadata is None:
        errors.append(f"{label} leading YAML is missing or malformed")
        return
    for field, expected_value in expected.items():
        actual = unique_yaml_value(metadata, field)
        if actual != expected_value:
            errors.append(
                f"{label} {field} must be exactly: {expected_value}"
            )


def validate_wp_p0_003_post_merge_closure_text(
    errors: list[str],
    current_state_text: str,
    work_package_text: str,
    addendum_text: str,
    evidence_text: str,
    post_merge_evidence_text: str,
) -> None:
    """Protect merge truth without promoting bounded validation to full approval."""
    expected_current = {
        "active_work_package": WP_P0_003_ID,
        "active_gate": WP_P0_003_DESIGN_FINALIZATION_GATE,
        "authorization": "DESIGN_ONLY",
        "production_write_enabled": "false",
        "implementation_backed_design_validation": "VERIFIED",
        "bounded_validation_authorization": "CLOSED",
        "pr16_merge_execution": "VERIFIED",
        "full_design_approved": "false",
        "full_implementation_authorized": "false",
    }
    for field, expected in expected_current.items():
        actual = current_state_metadata_value(current_state_text, field)
        if actual != expected:
            errors.append(
                f"WP-P0-003 post-merge CURRENT_STATE {field} must be exactly: "
                + expected
            )

    validate_wp_p0_003_work_package_text(errors, work_package_text)
    if work_package_metadata_value(work_package_text, "Status") == COMPLETED_WP_STATUS:
        errors.append("PR #16 merge must not mark WP-P0-003 COMPLETED")

    shared_evidence = {
        "final_package_identity": "PR_16_FINAL_HEAD_27B457B_AND_MERGED_MAIN_CE054A0",
        "controller_verdict": "PASS_WITH_FOLLOW_UPS",
        "design_approved": "false",
        "targeted_rework_status": "CONTROLLER_ACCEPTED_AND_MERGED",
        "merge_execution": "VERIFIED",
        "actual_merge_commit": WP_P0_003_SQUASH_COMMIT,
        "actual_main_tree": WP_P0_003_AUTHORIZED_TREE,
        "bounded_validation_authorization": "CLOSED",
        "full_implementation_authorized": "false",
        "bounded_scope_quality": (
            "PRODUCTION_GRADE_WITH_NON_BLOCKING_PRE_ADAPTER_HARDENING"
        ),
        "project_production_complete": "false",
        "marketplace_outbound": "NONE",
        "secret_retrieval": "NONE",
        "production_write": "DISABLED",
    }
    validate_leading_yaml_contract(
        errors,
        addendum_text,
        {
            **shared_evidence,
            "next_gate": (
                "CONTROLLER_WP_P0_003_DESIGN_FINALIZATION_AND_"
                "NEXT_IMPLEMENTATION_SCOPE_REVIEW"
            ),
        },
        "WP-P0-003 executable validation addendum",
    )
    validate_leading_yaml_contract(
        errors,
        evidence_text,
        shared_evidence,
        "WP-P0-003 executable validation evidence",
    )
    validate_leading_yaml_contract(
        errors,
        post_merge_evidence_text,
        {
            "document_type": "post_merge_execution_verification_evidence",
            "work_package": WP_P0_003_ID,
            "repository": "Corwin-Code/marketops-platform",
            "pull_request": "16",
            "pr_state": "MERGED_CLOSED_NOT_DRAFT",
            "authorized_head": WP_P0_003_AUTHORIZED_HEAD,
            "authorized_head_tree": WP_P0_003_AUTHORIZED_TREE,
            "pre_merge_tested_merge": WP_P0_003_TESTED_MERGE,
            "actual_squash_commit": WP_P0_003_SQUASH_COMMIT,
            "actual_main_tree": WP_P0_003_AUTHORIZED_TREE,
            "actual_squash_parent": WP_P0_003_SQUASH_PARENT,
            "merge_time": "2026-08-25T08:52:52Z",
            "commit_signature": "VERIFIED_VALID",
            "controller_verdict": "PASS_MERGE_EXECUTION_VERIFIED",
            "bounded_executable_design_validation": "VERIFIED",
            "bounded_validation_authorization": "CLOSED",
            "full_design_approved": "false",
            "full_implementation_authorized": "false",
            "production_write": "DISABLED",
        },
        "WP-P0-003 post-merge execution evidence",
    )

    required_post_merge_tokens = (
        "PASS — MERGE_EXECUTION_VERIFIED",
        WP_P0_003_POST_MERGE_CONTROLLER_SHA256,
        "executed jobs: 10 / 10 SUCCESS",
        "conditional skipped jobs: 1",
        "dependency-review` job was skipped",
        "Reviews / review threads / comments | `0 / 0 / 0`",
        "delete_branch_on_merge=true",
        "a3b8ca08b796c1d211f17a042a8ec546cd0009d4457e2d68dcd930dfc36a13d9",
        "WP3-EDV-BC-R4B-01",
        "MANDATORY_BEFORE_FIRST_REAL_ADAPTER_GATE",
    )
    for token in required_post_merge_tokens:
        if token not in post_merge_evidence_text:
            errors.append(
                "WP-P0-003 post-merge execution evidence missing: " + token
            )

    stale_markers = {
        "IMPLEMENTED_AWAITING_CONTROLLER": "awaiting-Controller status",
        "LIVE_PR_16_METADATA_AND_BODY": "mutable live-PR package identity",
        "PR #16 must remain open, draft and unmerged": "pre-merge PR state",
    }
    for marker, description in stale_markers.items():
        for label, text in (
            ("addendum", addendum_text),
            ("evidence", evidence_text),
        ):
            if marker in text:
                errors.append(
                    f"WP-P0-003 {label} retains stale current {description}: "
                    + marker
                )


def project_charter_status(text: str) -> str | None:
    return markdown_table_value(text, "## 1. Identity", "Status")


def validate_lifecycle_state_text(
    errors: list[str],
    current_state_text: str,
    project_charter_text: str,
) -> None:
    lifecycle_state = current_state_metadata_value(
        current_state_text,
        "lifecycle_state",
    )
    charter_status = project_charter_status(project_charter_text)

    if lifecycle_state is None:
        errors.append("CURRENT_STATE lifecycle_state metadata is missing or duplicated")
    elif lifecycle_state not in LIFECYCLE_ALLOWED_STATES:
        errors.append(
            "CURRENT_STATE lifecycle_state must be exactly one of: "
            + ", ".join(sorted(LIFECYCLE_ALLOWED_STATES))
        )

    if charter_status is None:
        errors.append("PROJECT_CHARTER Status metadata is missing or duplicated")
    elif charter_status not in LIFECYCLE_ALLOWED_STATES:
        errors.append(
            "PROJECT_CHARTER Status must be exactly one of: "
            + ", ".join(sorted(LIFECYCLE_ALLOWED_STATES))
        )

    if (
        lifecycle_state in LIFECYCLE_ALLOWED_STATES
        and charter_status in LIFECYCLE_ALLOWED_STATES
        and lifecycle_state != charter_status
    ):
        errors.append(
            "lifecycle mismatch: CURRENT_STATE "
            f"{lifecycle_state} != PROJECT_CHARTER {charter_status}"
        )


def current_state_canonical_design_path(text: str) -> str | None:
    body = h2_section_body(text, "## Approved design of record")
    if body is None:
        return None
    matches = re.findall(r"(?m)^Canonical design:\s*([^\s#]+)\s*$", body)
    return matches[0] if len(matches) == 1 else None


def work_package_canonical_design_path(text: str) -> str | None:
    body = h2_section_body(text, "## 6. Design Deliverables")
    if body is None:
        return None
    matches = re.findall(
        r"(?m)^The approved canonical design at\s*$\n"
        r"`([^`\n]+)` defines:\s*$",
        body,
    )
    return matches[0] if len(matches) == 1 else None


def validate_approved_design_state_text(
    errors: list[str],
    current_state_text: str,
    work_package_text: str,
    canonical_design_text: str | None,
) -> None:
    historic_verdict = work_package_metadata_value(
        work_package_text, "Historic design verdict"
    )
    execution_authorization = work_package_execution_authorization(work_package_text)
    if (
        historic_verdict != HISTORIC_DESIGN_VERDICT
        and execution_authorization != "APPROVED_FOR_IMPLEMENTATION"
    ):
        return

    current_path = current_state_canonical_design_path(current_state_text)
    if current_path is None:
        errors.append("CURRENT_STATE canonical design path is missing or duplicated")
    elif current_path != CANONICAL_DESIGN_RELATIVE_PATH:
        errors.append(
            "CURRENT_STATE canonical design path must be exactly: "
            + CANONICAL_DESIGN_RELATIVE_PATH
        )

    wp_path = work_package_canonical_design_path(work_package_text)
    if wp_path is None:
        errors.append("WP-P0-001 canonical design path is missing or duplicated")
    elif wp_path != CANONICAL_DESIGN_RELATIVE_PATH:
        errors.append(
            "WP-P0-001 canonical design path must be exactly: "
            + CANONICAL_DESIGN_RELATIVE_PATH
        )

    if canonical_design_text is None:
        errors.append("approved canonical design is missing")
        return

    metadata = leading_yaml_body(canonical_design_text)
    if metadata is None:
        errors.append("approved canonical design leading metadata is malformed")
        return

    for field, expected in CANONICAL_DESIGN_METADATA.items():
        actual = unique_yaml_value(metadata, field)
        if actual is None:
            errors.append(
                f"approved canonical design {field} is missing or duplicated"
            )
        elif actual != expected:
            errors.append(
                f"approved canonical design {field} must be exactly: {expected}"
            )


def validate_authorization_state_text(
    errors: list[str],
    current_state_text: str,
    work_package_text: str,
    work_package_id: str = WP_P0_001_ID,
) -> None:
    current_authorization = current_state_metadata_value(current_state_text, "authorization")
    active_work_package = current_state_metadata_value(
        current_state_text, "active_work_package"
    )
    wp_status = work_package_metadata_value(work_package_text, "Status")
    wp_authorization = work_package_execution_authorization(work_package_text)

    if current_authorization is None:
        errors.append("CURRENT_STATE authorization metadata is missing or duplicated")
    elif current_authorization not in CURRENT_AUTHORIZATION_ALLOWED_STATES:
        errors.append(
            "CURRENT_STATE authorization must be exactly one of: "
            + ", ".join(sorted(CURRENT_AUTHORIZATION_ALLOWED_STATES))
        )

    if active_work_package is None:
        errors.append("CURRENT_STATE active_work_package metadata is missing or duplicated")

    if wp_authorization is None:
        errors.append(
            f"{work_package_id} current execution authorization is missing, "
            "duplicated or ambiguous"
        )
    elif wp_authorization not in WP_EXECUTION_AUTHORIZATION_ALLOWED_STATES:
        errors.append(
            f"{work_package_id} current execution authorization must be exactly one of: "
            + ", ".join(sorted(WP_EXECUTION_AUTHORIZATION_ALLOWED_STATES))
        )

    if active_work_package == "NONE":
        if current_authorization != "PLANNING_ONLY":
            errors.append(
                "CURRENT_STATE active_work_package NONE requires authorization PLANNING_ONLY"
            )
        if work_package_id != WP_P0_001_ID:
            errors.append("closed planning state must validate the WP-P0-001 record")
        else:
            validate_completed_wp_p0_001_text(errors, work_package_text)
    elif active_work_package is not None:
        if active_work_package != work_package_id:
            errors.append(
                "active Work Package record mismatch: CURRENT_STATE "
                f"{active_work_package} != validated record {work_package_id}"
            )
        if current_authorization not in ACTIVE_AUTHORIZATION_STATES:
            errors.append(
                "an active Work Package requires DESIGN_ONLY or APPROVED_FOR_IMPLEMENTATION"
            )
        if wp_authorization not in ACTIVE_AUTHORIZATION_STATES:
            errors.append(
                "an active Work Package cannot have CLOSED execution authorization"
            )
        if (
            current_authorization in ACTIVE_AUTHORIZATION_STATES
            and wp_authorization in ACTIVE_AUTHORIZATION_STATES
            and current_authorization != wp_authorization
        ):
            errors.append(
                "authorization mismatch: CURRENT_STATE "
                f"{current_authorization} != active Work Package {wp_authorization}"
            )
        if wp_status == COMPLETED_WP_STATUS:
            errors.append("a COMPLETED Work Package cannot remain active")
        if current_authorization == "DESIGN_ONLY":
            expected_design_gate = (
                WP_P0_003_DESIGN_FINALIZATION_GATE
                if work_package_id == WP_P0_003_ID
                else DESIGN_ACTIVE_GATE
            )
            expected_design_status = (
                WP_P0_003_DESIGN_FINALIZATION_STATUS
                if work_package_id == WP_P0_003_ID
                else DESIGN_ACTIVE_GATE
            )
            if (
                current_state_metadata_value(current_state_text, "active_gate")
                != expected_design_gate
            ):
                errors.append(
                    "DESIGN_ONLY requires CURRENT_STATE active_gate: "
                    + expected_design_gate
                )
            if wp_status != expected_design_status:
                errors.append(
                    "DESIGN_ONLY active Work Package Status must be: "
                    + expected_design_status
                )
        if current_authorization == "APPROVED_FOR_IMPLEMENTATION":
            active_gate = current_state_metadata_value(current_state_text, "active_gate")
            if active_gate != IMPLEMENTATION_ACTIVE_GATE:
                errors.append(
                    "APPROVED_FOR_IMPLEMENTATION requires CURRENT_STATE active_gate: "
                    + IMPLEMENTATION_ACTIVE_GATE
                )
            if wp_status != IMPLEMENTATION_ACTIVE_GATE:
                errors.append(
                    "APPROVED_FOR_IMPLEMENTATION active Work Package Status must be: "
                    + IMPLEMENTATION_ACTIVE_GATE
                )


def validate_owner_control_state_text(errors: list[str], text: str) -> None:
    guidance_state = current_state_value(text, "owner_git_workflow_guidance")
    if guidance_state not in OWNER_GUIDANCE_ALLOWED_STATES:
        errors.append(
            "CURRENT_STATE owner_git_workflow_guidance must be exactly one of: "
            + ", ".join(sorted(OWNER_GUIDANCE_ALLOWED_STATES))
        )

    guidance_exit = current_state_value(text, "owner_git_workflow_guidance_exit")
    if guidance_exit != OWNER_GUIDANCE_EXIT_AUTHORITY:
        errors.append(
            "CURRENT_STATE owner_git_workflow_guidance_exit must be exactly: "
            + OWNER_GUIDANCE_EXIT_AUTHORITY
        )

    delegation_state = current_state_value(text, "owner_git_execution_delegation")
    if delegation_state not in OWNER_DELEGATION_ALLOWED_STATES:
        errors.append(
            "CURRENT_STATE owner_git_execution_delegation must be exactly one of: "
            + ", ".join(sorted(OWNER_DELEGATION_ALLOWED_STATES))
        )

    delegate = current_state_value(text, "owner_git_execution_delegate")
    if delegate not in OWNER_DELEGATION_ALLOWED_EXECUTORS:
        errors.append(
            "CURRENT_STATE owner_git_execution_delegate must be exactly one of: "
            + ", ".join(sorted(OWNER_DELEGATION_ALLOWED_EXECUTORS))
        )

    delegation_scope = current_state_value(text, "owner_git_execution_delegation_scope")
    delegation_exit = current_state_value(text, "owner_git_execution_delegation_exit")
    if delegation_exit != OWNER_DELEGATION_EXIT_AUTHORITY:
        errors.append(
            "Owner Git execution delegation exit must be exactly: "
            + OWNER_DELEGATION_EXIT_AUTHORITY
        )
    if delegation_state == "ACTIVE":
        if delegate == "NONE":
            errors.append("ACTIVE Owner Git execution delegation requires a named delegate")
        if delegation_scope != OWNER_DELEGATION_SCOPE:
            errors.append(
                "ACTIVE Owner Git execution delegation scope must be exactly: "
                + OWNER_DELEGATION_SCOPE
            )
    elif delegation_state == "INACTIVE":
        if delegate != "NONE":
            errors.append("INACTIVE Owner Git execution delegation must use delegate NONE")
        if delegation_scope != OWNER_DELEGATION_INACTIVE_SCOPE:
            errors.append("INACTIVE Owner Git execution delegation scope must be NONE")


def validate_prior_closed_transition_text(errors: list[str], text: str) -> None:
    """Allow old state tokens only as explicit provenance inside Current State."""
    active = current_state_metadata_value(text, "active_work_package")
    if active in {None, "NONE"}:
        return
    body = h2_section_body(
        text,
        "## Prior closed planning transition — historical provenance",
    )
    if body is None:
        errors.append("active Design state must preserve classified WP-P0-001 closure provenance")
        return
    for token in (
        "active_work_package: NONE",
        "active_gate: CONTROLLER_PHASE_0_PLANNING",
        "authorization: PLANNING_ONLY",
        "superseded as live runtime state by the leading YAML",
        "not be interpreted as current authorization or a parallel state source",
    ):
        if token not in body:
            errors.append(f"prior closed transition provenance missing contract: {token}")


def validate_current_state(errors: list[str]) -> None:
    path = ROOT / "docs/00-governance/CURRENT_STATE.md"
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    for required in [
        "lifecycle_state:",
        "active_work_package:",
        "production_write_enabled: false",
        "owner_git_workflow_guidance:",
        "owner_git_workflow_guidance_exit:",
        "owner_git_execution_delegation:",
        "owner_git_execution_delegate:",
        "owner_git_execution_delegation_scope:",
        "owner_git_execution_delegation_exit:",
    ]:
        if required not in text:
            errors.append(f"CURRENT_STATE missing required field: {required}")
    validate_owner_control_state_text(errors, text)
    validate_prior_closed_transition_text(errors, text)


def validate_lifecycle_state(errors: list[str]) -> None:
    current_state_path = ROOT / "docs/00-governance/CURRENT_STATE.md"
    project_charter_path = ROOT / "docs/00-governance/PROJECT_CHARTER.md"
    if not current_state_path.exists() or not project_charter_path.exists():
        return
    validate_lifecycle_state_text(
        errors,
        current_state_path.read_text(encoding="utf-8"),
        project_charter_path.read_text(encoding="utf-8"),
    )


def validate_authorization_state(errors: list[str]) -> None:
    current_state_path = ROOT / "docs/00-governance/CURRENT_STATE.md"
    wp_p0_001_path = ROOT / WP_P0_001_RELATIVE_PATH
    if not current_state_path.exists() or not wp_p0_001_path.exists():
        return
    current_text = current_state_path.read_text(encoding="utf-8")
    wp_p0_001_text = wp_p0_001_path.read_text(encoding="utf-8")
    active = current_state_metadata_value(current_text, "active_work_package")
    records = {
        work_package_id: (ROOT / relative).read_text(encoding="utf-8")
        for work_package_id, relative in WORK_PACKAGE_PATHS.items()
        if (ROOT / relative).exists()
    }
    validate_active_work_package_record_text(errors, current_text, records)
    validate_completed_wp_p0_001_text(errors, wp_p0_001_text)

    if active == "NONE":
        selected_id = WP_P0_001_ID
        selected_text = wp_p0_001_text
    elif active in records:
        selected_id = active
        selected_text = records[active]
    else:
        return
    validate_authorization_state_text(
        errors,
        current_text,
        selected_text,
        selected_id,
    )


def validate_approved_design_state(errors: list[str]) -> None:
    current_state_path = ROOT / "docs/00-governance/CURRENT_STATE.md"
    work_package_path = (
        ROOT / "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md"
    )
    canonical_design_path = ROOT / CANONICAL_DESIGN_RELATIVE_PATH
    if not current_state_path.exists() or not work_package_path.exists():
        return
    validate_approved_design_state_text(
        errors,
        current_state_path.read_text(encoding="utf-8"),
        work_package_path.read_text(encoding="utf-8"),
        (
            canonical_design_path.read_text(encoding="utf-8")
            if canonical_design_path.exists()
            else None
        ),
    )


def validate_owner_git_workflow_guidance(errors: list[str]) -> None:
    guide_path = ROOT / "docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md"
    if not guide_path.exists():
        return

    guide = guide_path.read_text(encoding="utf-8")
    for required in [
        "state_source: docs/00-governance/CURRENT_STATE.md#owner_git_workflow_guidance",
        "supported_states: REQUIRED | DISABLED",
        "activation: every task start while Current State is REQUIRED",
        "exit_authority: Human Owner explicit confirmation only",
        "sync main",
        "Owner-authorized merge execution",
        "local sync/cleanup",
    ]:
        if required not in guide:
            errors.append(f"Owner Git workflow guide missing required contract: {required}")

    if re.search(r"(?m)^status:\s*(?:REQUIRED|DISABLED)\s*$", guide):
        errors.append(
            "Owner Git workflow guide must not duplicate runtime state; "
            "CURRENT_STATE owner_git_workflow_guidance is canonical"
        )

    instruction_files = [
        "AGENTS.md",
        "CLAUDE.md",
        "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md",
        "docs/00-governance/CLAUDE_PROJECT_INSTRUCTIONS.md",
    ]
    for relative in instruction_files:
        path = ROOT / relative
        if path.exists() and "OWNER_GIT_WORKFLOW_GUIDE.md" not in path.read_text(encoding="utf-8"):
            errors.append(f"agent instruction does not load Owner Git workflow guide: {relative}")


def validate_readme_runtime_state_text(errors: list[str], text: str) -> None:
    """Keep README as a stable entry point rather than a second runtime state."""
    if "docs/00-governance/CURRENT_STATE.md" not in text:
        errors.append("README must link to the canonical CURRENT_STATE.md")
    for marker in (
        "项目状态：",
        "当前阶段：",
        "当前活动 Work Package：",
        "当前授权：",
        "INITIAL IMPLEMENTATION",
        "等待总控审查与 Owner 合并",
    ):
        if marker in text:
            errors.append(f"README duplicates or retains stale runtime state: {marker}")


def validate_readme_runtime_state(errors: list[str]) -> None:
    path = ROOT / "README.md"
    if path.exists():
        validate_readme_runtime_state_text(errors, path.read_text(encoding="utf-8"))


def validate_controller_review_standard_text(
    errors: list[str], standard_text: str, instructions_text: str
) -> None:
    required_standard = [
        "## 2. The 11+1 review standard",
        "Full repository cross-check",
        "Full production-grade scope",
        "No in-scope deferred item",
        "No compromise implementation",
        "Three global hard rules",
        "Standalone review and prompt artifacts",
        "+1 — Project-grade distinction",
        "## 4. Artifact Contract",
        "Controller Review `.md`",
        "Next-action Prompt `.md`",
        "SHA-256",
        "NEXT_AUTHORIZED_ACTOR",
        "NEXT_ACTION",
        "natural Chinese",
    ]
    for required in required_standard:
        if required not in standard_text:
            errors.append(f"Controller Review Standard missing contract: {required}")
    for required in (
        "CONTROLLER_REVIEW_STANDARD.md",
        "start of every Controller task",
        "11+1 review standard",
    ):
        if required not in instructions_text:
            errors.append(
                f"ChatGPT Project Instructions do not load Controller Review Standard: {required}"
            )


def validate_controller_review_standard(errors: list[str]) -> None:
    standard_path = ROOT / CONTROLLER_REVIEW_STANDARD_RELATIVE_PATH
    instructions_path = ROOT / "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md"
    if not standard_path.exists() or not instructions_path.exists():
        return
    validate_controller_review_standard_text(
        errors,
        standard_path.read_text(encoding="utf-8"),
        instructions_path.read_text(encoding="utf-8"),
    )


def validate_wp_p0_002_traceability_text(
    errors: list[str], text: str, *, completed: bool = False
) -> None:
    try:
        rows = list(csv.DictReader(text.splitlines()))
    except csv.Error as error:
        errors.append(f"WP-P0-002 traceability is unreadable: {error}")
        return
    by_id = {row.get("source_id", ""): row for row in rows}
    for source_id, (work_packages, closure, later) in (
        WP_P0_002_TRACEABILITY_CONTRACT.items()
    ):
        row = by_id.get(source_id)
        if row is None:
            errors.append(f"WP-P0-002 traceability row is missing: {source_id}")
            continue
        if row.get("work_package") != work_packages:
            errors.append(
                f"traceability {source_id} work_package must be exactly: {work_packages}"
            )
        design_records = row.get("design_record", "").split(";")
        if WP_P0_002_DESIGN_RELATIVE_PATH not in design_records:
            errors.append(
                f"traceability {source_id} must reference the approved WP-P0-002 design"
            )
        expected_status = (
            "VERIFIED"
            if completed and source_id in WP_P0_002_FULL_REQUIREMENTS
            else "ACTIVE_CONTROL"
            if completed
            else "PLANNED"
        )
        if row.get("status") != expected_status:
            if not completed:
                errors.append(
                    f"traceability {source_id} must remain PLANNED until its "
                    "implementation result is independently verified"
                )
            elif source_id in WP_P0_002_PARTIAL_REQUIREMENTS:
                errors.append(
                    f"traceability {source_id} PARTIAL requirement must be "
                    "ACTIVE_CONTROL, not fully VERIFIED"
                )
            else:
                errors.append(
                    f"traceability {source_id} status must be exactly: {expected_status}"
                )
        for field in ("code_location", "test_case", "evidence"):
            if not row.get(field, "").strip():
                errors.append(f"traceability {source_id} missing {field}")
        notes = row.get("notes", "")
        for token in (closure, later):
            if token not in notes:
                errors.append(
                    f"traceability {source_id} notes missing closure disposition: {token}"
                )
        if completed and source_id in WP_P0_002_PARTIAL_REQUIREMENTS:
            for token in (
                "WP-P0-002 subset VERIFIED",
                "whole source requirement remains OPEN",
            ):
                if token not in notes:
                    errors.append(
                        f"traceability {source_id} notes missing completed PARTIAL "
                        f"semantics: {token}"
                    )
        if completed and source_id in WP_P0_002_FULL_REQUIREMENTS:
            if "WP-P0-002 VERIFIED" not in notes:
                errors.append(
                    f"traceability {source_id} notes must record WP-P0-002 VERIFIED"
                )


def validate_wp_p0_003_traceability_text(
    errors: list[str], text: str
) -> None:
    """Bind Design-only allocation and reject all premature implementation proof."""
    try:
        rows = list(csv.DictReader(text.splitlines()))
    except csv.Error as error:
        errors.append(f"WP-P0-003 traceability is unreadable: {error}")
        return
    source_ids = [row.get("source_id", "") for row in rows]
    if len(source_ids) != len(set(source_ids)):
        errors.append("WP-P0-003 traceability contains duplicate source_id rows")
    by_id = {row.get("source_id", ""): row for row in rows}
    for source_id, (
        work_packages,
        status,
        design_token,
        note_tokens,
    ) in WP_P0_003_TRACEABILITY_CONTRACT.items():
        row = by_id.get(source_id)
        if row is None:
            errors.append(f"WP-P0-003 traceability row is missing: {source_id}")
            continue
        if row.get("work_package") != work_packages:
            errors.append(
                f"traceability {source_id} work_package must be exactly: {work_packages}"
            )
        if design_token not in row.get("design_record", "").split(";"):
            errors.append(
                f"traceability {source_id} must reference Planning contract: {design_token}"
            )
        if row.get("status") != status:
            errors.append(
                f"traceability {source_id} status must be exactly: {status}"
            )
        notes = row.get("notes", "")
        for token in note_tokens:
            if token not in notes:
                errors.append(
                    f"traceability {source_id} notes missing WP-P0-003 disposition: {token}"
                )
        prior_evidence = WP_P0_003_PRIOR_EVIDENCE_CONTRACT.get(source_id)
        if prior_evidence is not None:
            for field, expected in prior_evidence.items():
                if row.get(field) != expected:
                    errors.append(
                        f"traceability {source_id} prior evidence {field} must be "
                        f"exactly: {expected}"
                    )
        if source_id in WP_P0_003_PREIMPLEMENTATION_EMPTY_TRACEABILITY_IDS:
            for field in ("code_location", "test_case", "evidence"):
                if row.get(field, "").strip():
                    errors.append(
                        f"traceability {source_id} {field} must remain empty before implementation"
                    )


def validate_traceability(errors: list[str]) -> None:
    path = ROOT / "docs/01-requirements/traceability.csv"
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8-sig")
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f)
        try:
            header = next(reader)
        except StopIteration:
            errors.append("traceability.csv is empty")
            return
        if header != TRACEABILITY_HEADER:
            errors.append(f"traceability.csv header mismatch: {header}")
        rows = list(reader)
        if not rows:
            errors.append("traceability.csv has no seeded rows")
        ids = [row[0] for row in rows if row]
        if len(ids) != len(set(ids)):
            errors.append("traceability.csv contains duplicate source_id rows")
    wp_path = ROOT / WP_P0_002_RELATIVE_PATH
    wp_completed = (
        wp_path.exists()
        and work_package_metadata_value(
            wp_path.read_text(encoding="utf-8"), "Status"
        )
        == COMPLETED_WP_STATUS
    )
    validate_wp_p0_002_traceability_text(errors, text, completed=wp_completed)
    validate_wp_p0_003_traceability_text(errors, text)


def wp_p0_002_acceptance_rows(text: str) -> list[dict[str, str]] | None:
    """Parse the one canonical 16-row WP-P0-002 acceptance matrix."""
    lines = text.splitlines()
    parsed_tables: list[list[dict[str, str]]] = []
    for index, line in enumerate(lines):
        if markdown_table_cells(line) != WP_P0_002_ACCEPTANCE_HEADER:
            continue
        if index + 1 >= len(lines):
            return None
        separator = markdown_table_cells(lines[index + 1])
        if separator is None or len(separator) != len(WP_P0_002_ACCEPTANCE_HEADER):
            return None
        if any(re.fullmatch(r":?-{3,}:?", cell) is None for cell in separator):
            return None

        rows: list[dict[str, str]] = []
        for row_line in lines[index + 2:]:
            cells = markdown_table_cells(row_line)
            if cells is None:
                break
            if len(cells) != len(WP_P0_002_ACCEPTANCE_HEADER):
                return None
            rows.append(dict(zip(WP_P0_002_ACCEPTANCE_HEADER, cells)))
        parsed_tables.append(rows)
    return parsed_tables[0] if len(parsed_tables) == 1 else None


def java_test_identity_inventory(
    java_sources: dict[str, str],
) -> tuple[list[JavaTestIdentity], list[str]]:
    """Build the canonical TC inventory with a bounded scanner for repo test style."""
    raw: list[tuple[str, str, str, str, str, str, int]] = []
    parse_errors: list[str] = []
    for relative, text in sorted(java_sources.items()):
        matches = list(JAVA_TEST_DISPLAY_NAME.finditer(text))
        for index, match in enumerate(matches):
            next_start = (
                matches[index + 1].start()
                if index + 1 < len(matches)
                else len(text)
            )
            member_match = JAVA_TEST_MEMBER.search(text, match.end(), next_start)
            if member_match is None:
                line = text.count("\n", 0, match.start()) + 1
                parse_errors.append(
                    f"unbound Java test display identity {match.group('test_id')}: "
                    f"{relative}:{line}"
                )
                continue
            method = member_match.group("method")
            nested_group = member_match.group("nested_group")
            raw.append(
                (
                    match.group("test_id"),
                    relative,
                    Path(relative).stem,
                    method or nested_group,
                    "method" if method else "nested_group",
                    match.group("display_text") or "",
                    text.count("\n", 0, match.start()) + 1,
                )
            )

    counts = Counter(item[0] for item in raw)
    inventory = [
        JavaTestIdentity(
            test_id=test_id,
            file=relative,
            class_name=class_name,
            member=member,
            member_kind=member_kind,
            display_text=display_text,
            line=line,
            occurrence_count=counts[test_id],
        )
        for (
            test_id,
            relative,
            class_name,
            member,
            member_kind,
            display_text,
            line,
        ) in raw
    ]
    return inventory, parse_errors


def without_explicit_historic_contracts(
    errors: list[str], text: str, document_name: str
) -> str:
    """Exclude only explicitly delimited non-authoritative historic quotations."""
    live_parts: list[str] = []
    cursor = 0
    while cursor < len(text):
        begin = text.find(HISTORIC_CONTRACT_BEGIN, cursor)
        stray_end = text.find(HISTORIC_CONTRACT_END, cursor)
        if stray_end != -1 and (begin == -1 or stray_end < begin):
            errors.append(f"{document_name} has an unmatched historic-contract end marker")
            live_parts.append(text[cursor:stray_end])
            cursor = stray_end + len(HISTORIC_CONTRACT_END)
            continue
        if begin == -1:
            live_parts.append(text[cursor:])
            break
        live_parts.append(text[cursor:begin])
        end = text.find(HISTORIC_CONTRACT_END, begin + len(HISTORIC_CONTRACT_BEGIN))
        if end == -1:
            errors.append(f"{document_name} has an unclosed historic-contract quotation")
            break
        nested_begin = text.find(
            HISTORIC_CONTRACT_BEGIN,
            begin + len(HISTORIC_CONTRACT_BEGIN),
            end,
        )
        if nested_begin != -1:
            errors.append(
                f"{document_name} has a nested historic-contract quotation"
            )
        cursor = end + len(HISTORIC_CONTRACT_END)
    return "".join(live_parts)


def documented_test_ids(text: str, prefix: str) -> set[str]:
    """Expand the explicit numeric ID ranges used by the canonical Test Strategy."""
    ids = set(re.findall(rf"{re.escape(prefix)}[0-9]{{3}}[A-Za-z]?", text))
    range_pattern = re.compile(
        rf"{re.escape(prefix)}(?P<start>[0-9]{{3}})(?:…|\.\.\.)(?P<end>[0-9]{{3}})"
    )
    for match in range_pattern.finditer(text):
        start = int(match.group("start"))
        end = int(match.group("end"))
        if end < start:
            continue
        ids.update(f"{prefix}{number:03d}" for number in range(start, end + 1))
    return ids


def validate_wp_p0_002_test_identity_contract(
    errors: list[str],
    traceability_text: str,
    acceptance_text: str,
    test_strategy_text: str,
    java_sources: dict[str, str],
) -> list[JavaTestIdentity]:
    """Bind unique Java TC identities to traceability, exact methods and strategy."""
    inventory, parse_errors = java_test_identity_inventory(java_sources)
    errors.extend(parse_errors)
    by_id: dict[str, list[JavaTestIdentity]] = {}
    for identity in inventory:
        by_id.setdefault(identity.test_id, []).append(identity)

    for test_id, definitions in sorted(by_id.items()):
        if len(definitions) > 1:
            targets = ", ".join(
                f"{definition.file}#{definition.member}" for definition in definitions
            )
            errors.append(f"duplicate Java test ID {test_id}: {targets}")

    rows = list(csv.DictReader(traceability_text.splitlines()))
    by_source_id = {row.get("source_id", ""): row for row in rows}
    for source_id in WP_P0_002_TRACEABILITY_CONTRACT:
        row = by_source_id.get(source_id)
        if row is None:
            continue
        for test_id in re.findall(TEST_ID_PATTERN, row.get("test_case", "")):
            definitions = by_id.get(test_id, [])
            if not definitions:
                errors.append(f"traceability {source_id} cites missing test ID: {test_id}")
            elif len(definitions) > 1:
                errors.append(
                    f"traceability {source_id} cites ambiguous test ID: {test_id}"
                )

    acceptance_rows = wp_p0_002_acceptance_rows(acceptance_text) or []
    bound_reference = re.compile(
        rf"`(?P<file>(?:backend|tests)/[^`#]+)#"
        rf"(?P<member>[A-Za-z_][A-Za-z0-9_]*)`\s*"
        rf"\(`(?P<test_id>{TEST_ID_PATTERN})`\)"
    )
    for row in acceptance_rows:
        criterion = row["Criterion"]
        exact_tests = row["Exact tests"]
        bound_matches = list(bound_reference.finditer(exact_tests))
        listed_ids = Counter(re.findall(TEST_ID_PATTERN, exact_tests))
        bound_ids = Counter(match.group("test_id") for match in bound_matches)
        if listed_ids != bound_ids:
            errors.append(
                f"WP-P0-002 acceptance criterion {criterion} has test IDs that "
                "are not bound exactly once to file#method references"
            )
        for match in bound_matches:
            test_id = match.group("test_id")
            definitions = by_id.get(test_id, [])
            if not definitions:
                errors.append(
                    f"WP-P0-002 acceptance criterion {criterion} cites missing test ID: "
                    f"{test_id}"
                )
                continue
            if len(definitions) > 1:
                errors.append(
                    f"WP-P0-002 acceptance criterion {criterion} cites ambiguous test ID: "
                    f"{test_id}"
                )
                continue
            definition = definitions[0]
            if (
                definition.file != match.group("file")
                or definition.member != match.group("member")
            ):
                errors.append(
                    f"WP-P0-002 acceptance criterion {criterion} test ID {test_id} "
                    f"belongs to {definition.file}#{definition.member}, not "
                    f"{match.group('file')}#{match.group('member')}"
                )

    implemented_api_ids = {
        identity.test_id
        for identity in inventory
        if identity.test_id.startswith("TC-API-")
    }
    strategy_api_ids = documented_test_ids(test_strategy_text, "TC-API-")
    missing_strategy_ids = sorted(implemented_api_ids - strategy_api_ids)
    extra_strategy_ids = sorted(strategy_api_ids - implemented_api_ids)
    if missing_strategy_ids:
        errors.append(
            "Test Strategy omits implemented canonical API test IDs: "
            + ", ".join(missing_strategy_ids)
        )
    if extra_strategy_ids:
        errors.append(
            "Test Strategy lists unimplemented canonical API test IDs: "
            + ", ".join(extra_strategy_ids)
        )
    return inventory


def validate_wp_p0_002_completion_text(
    errors: list[str],
    current_state_text: str,
    work_package_text: str,
    backlog_text: str,
    traceability_text: str,
    evidence_text: str,
    acceptance_text: str,
) -> None:
    """Require one coherent, reviewable WP-P0-002 completed-state transition."""
    wp_status = work_package_metadata_value(work_package_text, "Status")
    backlog_rows = phase_zero_backlog_rows(backlog_text)
    wp_p0_002_row = None
    wp_p0_003_row = None
    if backlog_rows is not None:
        matching = [row for row in backlog_rows if row["ID"] == WP_P0_002_ID]
        if len(matching) != 1:
            errors.append("closure backlog must contain exactly one WP-P0-002 row")
        else:
            wp_p0_002_row = matching[0]
        next_rows = [row for row in backlog_rows if row["ID"] == "WP-P0-003"]
        if len(next_rows) != 1:
            errors.append("closure backlog must contain exactly one WP-P0-003 row")
        else:
            wp_p0_003_row = next_rows[0]

    backlog_status = wp_p0_002_row["Status"] if wp_p0_002_row else None
    active = current_state_metadata_value(current_state_text, "active_work_package")

    if active == "NONE" and backlog_status in {
        DESIGN_ACTIVE_GATE,
        IMPLEMENTATION_ACTIVE_GATE,
    }:
        errors.append(
            "closed planning state cannot retain WP-P0-002 as READY_FOR_DESIGN "
            "or IMPLEMENTING in the backlog"
        )
    if backlog_status == COMPLETED_WP_STATUS and wp_status != COMPLETED_WP_STATUS:
        errors.append(
            "backlog WP-P0-002 COMPLETED requires the Work Package Status COMPLETED"
        )
    if wp_status == COMPLETED_WP_STATUS and backlog_status != COMPLETED_WP_STATUS:
        errors.append(
            "completed WP-P0-002 requires backlog Status COMPLETED"
        )

    if wp_status != COMPLETED_WP_STATUS:
        return

    expected_wp = {
        "Status": COMPLETED_WP_STATUS,
        "Historic design verdict": HISTORIC_DESIGN_VERDICT,
        "Current execution authorization": COMPLETED_WP_AUTHORIZATION,
        "Implementation result": COMPLETED_WP_RESULT,
        "Design artifact": f"`{WP_P0_002_DESIGN_RELATIVE_PATH}`",
        "Approved Design v1.2 SHA-256": WP_P0_002_DESIGN_SHA256,
    }
    for field, expected in expected_wp.items():
        if work_package_metadata_value(work_package_text, field) != expected:
            errors.append(f"completed WP-P0-002 {field} must be exactly: {expected}")
    if work_package_execution_authorization(work_package_text) != COMPLETED_WP_AUTHORIZATION:
        errors.append("completed WP-P0-002 current execution authorization must be CLOSED")

    if active is None:
        expected_current = {}
        errors.append(
            "WP-P0-002 closure requires unique CURRENT_STATE "
            "active_work_package: NONE or WP-P0-003"
        )
    elif active == "NONE":
        expected_current = {
            "active_work_package": "NONE",
            "active_gate": POST_WP_ACTIVE_GATE,
            "authorization": "PLANNING_ONLY",
        }
    elif active == WP_P0_003_ID:
        expected_current = {
            "active_work_package": WP_P0_003_ID,
            "active_gate": WP_P0_003_DESIGN_FINALIZATION_GATE,
            "authorization": "DESIGN_ONLY",
        }
    else:
        expected_current = {}
        errors.append(
            "completed WP-P0-002 may be followed only by closed planning or "
            "WP-P0-003 Design state"
        )
    expected_current.update({
        "production_write_enabled": "false",
        "owner_git_workflow_guidance": "REQUIRED",
        "owner_git_execution_delegation": "ACTIVE",
        "owner_git_execution_delegate": "CODEX",
        "owner_git_execution_delegation_scope": OWNER_DELEGATION_SCOPE,
    })
    for field, expected in expected_current.items():
        if current_state_metadata_value(current_state_text, field) != expected:
            errors.append(
                f"WP-P0-002 closure requires CURRENT_STATE {field}: {expected}"
            )
    if active == WP_P0_002_ID:
        errors.append("WP-P0-002 COMPLETED cannot remain the active Work Package")
    if active == "NONE":
        for section_name in ("## Active objective", "## Next authorized action"):
            section = h2_section_body(current_state_text, section_name) or ""
            for token in ("Controller", "Phase 0 planning"):
                if token not in section:
                    errors.append(
                        f"WP-P0-002 closure {section_name} must direct {token}"
                    )
        if wp_p0_003_row is not None and wp_p0_003_row["Status"] != "DRAFT":
            errors.append("WP-P0-003 must remain DRAFT during WP-P0-002 closure")
    elif active == WP_P0_003_ID:
        if wp_p0_003_row is not None and wp_p0_003_row["Status"] != DESIGN_ACTIVE_GATE:
            errors.append(
                "active WP-P0-003 Design state requires backlog READY_FOR_DESIGN"
            )
        next_action = h2_section_body(
            current_state_text, "## Next authorized action"
        ) or ""
        for token in (
            WP_P0_003_ID,
            "DESIGN_FINALIZATION",
            "Full Design approval",
            "OQ-006",
        ):
            if token not in next_action:
                errors.append(
                    "WP-P0-002 completed provenance must preserve the next Design Gate: "
                    + token
                )

    validate_wp_p0_002_traceability_text(
        errors, traceability_text, completed=True
    )
    if WP_P0_002_ACCEPTANCE_RELATIVE_PATH not in traceability_text:
        errors.append("completed traceability must reference the acceptance matrix")
    if WP_P0_002_ACCEPTANCE_RELATIVE_PATH not in evidence_text:
        errors.append("WP-P0-002 evidence must link the acceptance matrix")

    acceptance_rows = wp_p0_002_acceptance_rows(acceptance_text)
    if acceptance_rows is None:
        errors.append("WP-P0-002 acceptance evidence must contain one valid matrix")
    else:
        criterion_values = [row["Criterion"] for row in acceptance_rows]
        expected_values = [str(number) for number in range(1, 17)]
        if criterion_values != expected_values:
            errors.append(
                "WP-P0-002 acceptance matrix must contain criteria 1 through 16 "
                "exactly once and in order"
            )
        for row in acceptance_rows:
            criterion = row["Criterion"]
            if row["Closure status"] != "VERIFIED":
                errors.append(
                    f"WP-P0-002 acceptance criterion {criterion} must be VERIFIED"
                )
            for field in WP_P0_002_ACCEPTANCE_HEADER[1:]:
                if not row[field].strip():
                    errors.append(
                        f"WP-P0-002 acceptance criterion {criterion} missing {field}"
                    )

    stale_markers = {
        "repository CI Gate: pending": "repository CI pending",
        "independent Controller implementation/PR approval: pending": "Controller review pending",
        "pending the repair push": "repair-push pending",
        "Deliver the bounded implementation candidate": "implementation delivery",
        "Codex imports, repairs and verifies": "implementation delivery",
        "No WP-P0-002 acceptance criterion is claimed as VERIFIED": "unverified acceptance",
        "This Design-activation PR does not verify any requirement": "Design-activation denial",
        "when WP-P0-002 is eventually implemented": "future implementation",
        "Claude Design must": "live Claude Design instruction",
        "Claude must return": "live Claude delivery instruction",
        "this Planning record": "live Planning record instruction",
        "After Design return": "live post-Design instruction",
        "business/domain tables": "overbroad domain-table absence",
        "awaiting final independent Controller re-review": "final Controller re-review pending",
        "AWAITING_FINAL_CONTROLLER_RE_REVIEW": "final Controller re-review state",
        "Ready: NOT_AUTHORIZED": "Ready authorization pending",
        "Merge: NOT_AUTHORIZED": "merge authorization pending",
        "No Ready action or merge is authorized": "Ready/merge authorization pending",
        "If that exact closure Head": "future closure merge condition",
        "closure candidate": "closure-candidate state",
    }
    documents = {
        "CURRENT_STATE.md": current_state_text,
        "WP-P0-002 Work Package": work_package_text,
        "WP-P0-002 evidence": evidence_text,
        "WP-P0-002 acceptance": acceptance_text,
    }
    completed_documents = {
        name: without_explicit_historic_contracts(errors, text, name)
        for name, text in documents.items()
    }
    completed_text = "\n".join(completed_documents.values())
    normalized_completed_text = re.sub(r"\s+", " ", completed_text)
    for marker, description in stale_markers.items():
        if re.sub(r"\s+", " ", marker) in normalized_completed_text:
            errors.append(
                f"WP-P0-002 completed state retains stale {description} narration"
            )

    stale_patterns = {
        r"PR #10 remains(?:\s+a)?\s+Draft": "Draft PR state",
        r"PR #10 remains[^.]{0,160}not merged": "not-merged PR state",
        r"final (?:governance-)?closure Head.{0,240}Draft PR #10": (
            "Draft closure-evidence location"
        ),
    }
    for pattern, description in stale_patterns.items():
        if re.search(pattern, normalized_completed_text, flags=re.IGNORECASE):
            errors.append(
                f"WP-P0-002 completed state retains stale {description} narration"
            )

    required_post_merge_markers = {
        "CURRENT_STATE.md": (
            "PR #10",
            WP_P0_002_MERGED_SHA,
            WP_P0_002_MERGED_TREE,
            "Controller Phase 0 planning",
            "WP-P0-003 remains DRAFT",
            "production_write_enabled: false",
            WP_P0_002_POST_MERGE_CONTROLLER_SHA256,
        ),
        "WP-P0-002 Work Package": (
            "PR #10",
            "PASS — APPROVE_FOR_HUMAN_MERGE",
            WP_P0_002_CONTROLLER_APPROVAL_SHA256,
            "approved D-17 Ready and squash merge of PR #10 on the exact accepted identity",
            WP_P0_002_APPROVED_BASE_SHA,
            WP_P0_002_APPROVED_HEAD_SHA,
            WP_P0_002_APPROVED_TESTED_MERGE_SHA,
            WP_P0_002_MERGED_SHA,
            WP_P0_002_MERGED_TREE,
            "Squash parent: " + WP_P0_002_APPROVED_BASE_SHA,
            "Commit signature: VERIFIED",
            "PASS — MERGE_EXECUTION_VERIFIED",
            "Controller Phase 0 planning",
            "WP-P0-003 remains DRAFT",
            "Production writes: DISABLED",
        ),
        "WP-P0-002 evidence": (
            "PR #10",
            "PASS — APPROVE_FOR_HUMAN_MERGE",
            WP_P0_002_CONTROLLER_APPROVAL_SHA256,
            "approved D-17 Ready and squash merge of PR #10 on the exact accepted identity",
            WP_P0_002_APPROVED_BASE_SHA,
            WP_P0_002_APPROVED_HEAD_SHA,
            WP_P0_002_APPROVED_TESTED_MERGE_SHA,
            WP_P0_002_MERGED_SHA,
            WP_P0_002_MERGED_TREE,
            "Squash parent | `" + WP_P0_002_APPROVED_BASE_SHA + "`",
            "2026-08-19T17:44:16Z",
            WP_P0_002_POST_MERGE_CONTROLLER_SHA256,
            "Commit signature",
            "VERIFIED",
            "Remote task branch",
            "deleted after merge",
            "Controller Phase 0 planning",
            "WP-P0-003 remains DRAFT",
            "production_write_enabled: false",
        ),
        "WP-P0-002 acceptance": (
            "PR #10",
            WP_P0_002_MERGED_SHA,
            WP_P0_002_MERGED_TREE,
            "WP-P0-003 remains DRAFT",
        ),
    }
    for document_name, required_markers in required_post_merge_markers.items():
        document_text = completed_documents[document_name]
        for marker in required_markers:
            if marker not in document_text:
                errors.append(
                    f"{document_name} missing required post-merge provenance: {marker}"
                )

    for dependency in ("OQ-101", "OQ-005", "OQ-006", "OQ-102"):
        if dependency not in completed_text:
            errors.append(
                f"WP-P0-002 completed evidence must preserve open dependency: {dependency}"
            )


def validate_wp_p0_002_completion_references(
    errors: list[str],
    traceability_text: str,
    acceptance_text: str,
    test_strategy_text: str,
) -> None:
    """Resolve every completed trace/evidence path and cited test against the tree."""
    rows = list(csv.DictReader(traceability_text.splitlines()))
    by_id = {row.get("source_id", ""): row for row in rows}
    test_root = ROOT / "backend/marketops-server/src/test"
    test_files = sorted(test_root.rglob("*.java")) if test_root.exists() else []
    java_sources = {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for path in test_files
    }
    validate_wp_p0_002_test_identity_contract(
        errors,
        traceability_text,
        acceptance_text,
        test_strategy_text,
        java_sources,
    )
    for source_id in WP_P0_002_TRACEABILITY_CONTRACT:
        row = by_id.get(source_id)
        if row is None:
            continue
        for field in ("code_location", "evidence"):
            for relative in row.get(field, "").split(";"):
                relative = relative.strip()
                if relative and not (ROOT / relative).exists():
                    errors.append(
                        f"traceability {source_id} {field} path does not exist: {relative}"
                    )

    acceptance_rows = wp_p0_002_acceptance_rows(acceptance_text) or []
    for row in acceptance_rows:
        criterion = row["Criterion"]
        production_paths = re.findall(
            r"`((?:backend|docs|infra|scripts)/[^`#]+)`",
            row["Production location"],
        )
        if not production_paths:
            errors.append(
                f"WP-P0-002 acceptance criterion {criterion} has no resolvable production path"
            )
        for relative in production_paths:
            if not (ROOT / relative).exists():
                errors.append(
                    f"WP-P0-002 acceptance criterion {criterion} path does not exist: {relative}"
                )

        test_references = re.findall(
            r"`((?:backend|tests)/[^`#]+)#([A-Za-z_][A-Za-z0-9_]*)`",
            row["Exact tests"],
        )
        if not test_references:
            errors.append(
                f"WP-P0-002 acceptance criterion {criterion} has no exact test method"
            )
        for relative, method in test_references:
            path = ROOT / relative
            if not path.is_file():
                errors.append(
                    f"WP-P0-002 acceptance criterion {criterion} test file missing: {relative}"
                )
                continue
            if re.search(rf"\b{re.escape(method)}\s*\(", path.read_text(encoding="utf-8")) is None:
                errors.append(
                    f"WP-P0-002 acceptance criterion {criterion} test method missing: "
                    f"{relative}#{method}"
                )


def validate_wp_p0_002_completion(errors: list[str]) -> None:
    paths = {
        "current": ROOT / "docs/00-governance/CURRENT_STATE.md",
        "work_package": ROOT / WP_P0_002_RELATIVE_PATH,
        "backlog": ROOT / "docs/03-work-items/BACKLOG-PHASE-0.md",
        "traceability": ROOT / "docs/01-requirements/traceability.csv",
        "evidence": ROOT / WP_P0_002_EVIDENCE_RELATIVE_PATH,
        "acceptance": ROOT / WP_P0_002_ACCEPTANCE_RELATIVE_PATH,
        "test_strategy": ROOT / WP_P0_002_TEST_STRATEGY_RELATIVE_PATH,
    }
    if not all(path.exists() for path in paths.values()):
        return
    texts = {
        name: path.read_text(encoding="utf-8-sig" if name == "traceability" else "utf-8")
        for name, path in paths.items()
    }
    validate_wp_p0_002_completion_text(
        errors,
        texts["current"],
        texts["work_package"],
        texts["backlog"],
        texts["traceability"],
        texts["evidence"],
        texts["acceptance"],
    )
    if work_package_metadata_value(texts["work_package"], "Status") == COMPLETED_WP_STATUS:
        validate_wp_p0_002_completion_references(
            errors,
            texts["traceability"],
            texts["acceptance"],
            texts["test_strategy"],
        )


def validate_completion_state_text(
    errors: list[str],
    current_state_text: str,
    work_package_text: str,
    traceability_text: str,
) -> None:
    """Preserve WP-P0-001 closure across planning and later active-WP states."""
    validate_completed_wp_p0_001_text(errors, work_package_text)

    active_work_package = current_state_metadata_value(
        current_state_text, "active_work_package"
    )
    active_gate = current_state_metadata_value(current_state_text, "active_gate")
    authorization = current_state_metadata_value(current_state_text, "authorization")

    active_objective = h2_section_body(current_state_text, "## Active objective") or ""
    next_action = h2_section_body(current_state_text, "## Next authorized action") or ""

    if active_work_package == "NONE":
        if active_gate != POST_WP_ACTIVE_GATE:
            errors.append(
                f"closed planning state requires CURRENT_STATE active_gate: "
                f"{POST_WP_ACTIVE_GATE}"
            )
        if authorization != "PLANNING_ONLY":
            errors.append("closed planning state requires authorization: PLANNING_ONLY")
        for section_name, section in (
            ("Active objective", active_objective),
            ("Next authorized action", next_action),
        ):
            if "Controller" not in section or "Phase 0 planning" not in section:
                errors.append(
                    f"CURRENT_STATE {section_name} must direct Controller Phase 0 planning"
                )
    elif active_work_package == WP_P0_002_ID:
        if authorization == "APPROVED_FOR_IMPLEMENTATION":
            if active_gate != IMPLEMENTATION_ACTIVE_GATE:
                errors.append(
                    "WP-P0-002 implementation state requires CURRENT_STATE "
                    f"active_gate: {IMPLEMENTATION_ACTIVE_GATE}"
                )
            for section_name, section in (
                ("Active objective", active_objective),
                ("Next authorized action", next_action),
            ):
                for token in (WP_P0_002_ID, "implementation", "Controller"):
                    if token not in section:
                        errors.append(
                            f"CURRENT_STATE {section_name} missing implementation "
                            f"handoff: {token}"
                        )
            for token in (
                WP_P0_002_DESIGN_RELATIVE_PATH,
                WP_P0_002_DESIGN_SHA256,
            ):
                if token not in current_state_text:
                    errors.append(
                        "CURRENT_STATE must pin the approved WP-P0-002 design: "
                        + token
                    )
            if "NOT_YET_PRODUCED" in current_state_text:
                errors.append(
                    "CURRENT_STATE retains a stale missing-design claim while "
                    "implementing"
                )
        else:
            if active_gate != DESIGN_ACTIVE_GATE:
                errors.append(
                    f"WP-P0-002 Design state requires CURRENT_STATE active_gate: "
                    f"{DESIGN_ACTIVE_GATE}"
                )
            if authorization != "DESIGN_ONLY":
                errors.append("WP-P0-002 Design state requires authorization: DESIGN_ONLY")
            for section_name, section in (
                ("Active objective", active_objective),
                ("Next authorized action", next_action),
            ):
                for token in ("Claude", WP_P0_002_ID, "Design"):
                    if token not in section:
                        errors.append(
                            f"CURRENT_STATE {section_name} missing Design handoff: {token}"
                        )
            if "Implementation remains prohibited" not in next_action:
                errors.append(
                    "CURRENT_STATE Next authorized action must prohibit implementation"
                )
    elif active_work_package == WP_P0_003_ID:
        if active_gate != WP_P0_003_DESIGN_FINALIZATION_GATE:
            errors.append(
                "WP-P0-003 Design-finalization state requires CURRENT_STATE "
                "active_gate: " + WP_P0_003_DESIGN_FINALIZATION_GATE
            )
        if authorization != "DESIGN_ONLY":
            errors.append(
                "WP-P0-003 Design-finalization state requires authorization: "
                "DESIGN_ONLY"
            )
        for section_name, section in (
            ("Active objective", active_objective),
            ("Next authorized action", next_action),
        ):
            for token in ("Controller", "WP-P0-003", "Design"):
                if token not in section:
                    errors.append(
                        f"CURRENT_STATE {section_name} missing WP-P0-003 "
                        "Design-finalization handoff: "
                        + token
                    )
        for token in (
            "CONTROLLER_WP_P0_003_DESIGN_FINALIZATION",
            "Full Design approval",
            "OQ-006",
        ):
            if token not in next_action:
                errors.append(
                    "CURRENT_STATE WP-P0-003 Next authorized action missing: " + token
                )
    elif active_work_package is not None:
        errors.append(
            "CURRENT_STATE active_work_package is unsupported or selects a "
            f"completed package: {active_work_package}"
        )

    stale_claims = {
        "WP-P0-001 product implementation has not started": "implementation-not-started claim",
        "C1-C10 implementation artifact has not yet been produced": "missing-artifact claim",
        "READY_FOR_IMPLEMENTATION": "ready-for-implementation state",
    }
    for marker, description in stale_claims.items():
        if marker in current_state_text:
            errors.append(f"CURRENT_STATE retains stale {description}")

    try:
        rows = list(csv.DictReader(traceability_text.splitlines()))
    except csv.Error as error:
        errors.append(f"traceability completion state is unreadable: {error}")
        return
    by_id = {row.get("source_id", ""): row for row in rows}
    for source_id in sorted(COMPLETED_TRACEABILITY_IDS):
        row = by_id.get(source_id)
        if row is None:
            errors.append(f"traceability completion row is missing: {source_id}")
            continue
        if row.get("status") not in COMPLETED_TRACEABILITY_STATES:
            errors.append(
                f"traceability {source_id} status must be VERIFIED or ACTIVE_CONTROL"
            )
        for field in ("code_location", "test_case", "evidence"):
            if not row.get(field, "").strip():
                errors.append(f"traceability {source_id} missing {field}")

    d03 = by_id.get("D-03")
    if d03 is not None:
        if d03.get("status") != "ACTIVE_CONTROL":
            errors.append(
                "traceability D-03 must remain ACTIVE_CONTROL until the PostgreSQL "
                "Task/Outbox Worker is implemented and verified"
            )
        if d03.get("work_package") != D03_WORK_PACKAGES:
            errors.append(
                f"traceability D-03 work_package must be exactly: {D03_WORK_PACKAGES}"
            )
        notes = d03.get("notes", "")
        for token in (
            "Modular Monolith",
            "internal PostgreSQL Task/Worker",
            D03_WORKER_WORK_PACKAGE,
            "INT-017",
            "ACTIVE_CONTROL",
        ):
            if token not in notes:
                errors.append(f"traceability D-03 notes missing disposition: {token}")


def validate_completion_state(errors: list[str]) -> None:
    current_state_path = ROOT / "docs/00-governance/CURRENT_STATE.md"
    work_package_path = ROOT / WP_P0_001_RELATIVE_PATH
    traceability_path = ROOT / "docs/01-requirements/traceability.csv"
    backlog_path = ROOT / "docs/03-work-items/BACKLOG-PHASE-0.md"
    if not all(path.exists() for path in (
        current_state_path, work_package_path, traceability_path, backlog_path
    )):
        return
    current_state_text = current_state_path.read_text(encoding="utf-8")
    work_package_text = work_package_path.read_text(encoding="utf-8")
    active = current_state_metadata_value(current_state_text, "active_work_package")
    active_relative = WORK_PACKAGE_PATHS.get(active or "")
    active_path = ROOT / active_relative if active_relative is not None else None
    active_work_package_text = (
        active_path.read_text(encoding="utf-8")
        if active_path is not None and active_path.exists()
        else None
    )
    validate_completion_state_text(
        errors,
        current_state_text,
        work_package_text,
        traceability_path.read_text(encoding="utf-8-sig"),
    )
    validate_backlog_state_text(
        errors,
        current_state_text,
        work_package_text,
        backlog_path.read_text(encoding="utf-8"),
        active_work_package_text,
    )


def validate_wp_p0_003_record_paths(
    errors: list[str], existing_paths: set[str]
) -> None:
    """Keep WP-P0-003B DRAFT by rejecting every canonical record spelling."""
    for relative in sorted(existing_paths):
        path = Path(relative)
        if (
            path.parent.as_posix() == "docs/03-work-items"
            and path.name.startswith(f"{WP_P0_003B_ID}")
            and path.suffix == ".md"
        ):
            errors.append(
                "WP-P0-003B must remain DRAFT without a canonical Work Package file: "
                + relative
            )


def validate_wp_p0_003_activation(errors: list[str]) -> None:
    paths = {
        "current": ROOT / "docs/00-governance/CURRENT_STATE.md",
        "work_package": ROOT / WP_P0_003_RELATIVE_PATH,
        "backlog": ROOT / "docs/03-work-items/BACKLOG-PHASE-0.md",
        "open_questions": ROOT / "docs/00-governance/OPEN_QUESTIONS.md",
        "decision_request": ROOT / WP_P0_003B_DECISION_REQUEST_RELATIVE_PATH,
        "addendum": ROOT / WP_P0_003_ADDENDUM_RELATIVE_PATH,
        "evidence": ROOT / WP_P0_003_EVIDENCE_RELATIVE_PATH,
        "post_merge_evidence": ROOT / WP_P0_003_POST_MERGE_EVIDENCE_RELATIVE_PATH,
    }
    if not all(path.exists() for path in paths.values()):
        return
    validate_wp_p0_003_activation_text(
        errors,
        paths["current"].read_text(encoding="utf-8"),
        paths["work_package"].read_text(encoding="utf-8"),
        paths["backlog"].read_text(encoding="utf-8"),
        paths["open_questions"].read_text(encoding="utf-8"),
        paths["decision_request"].read_text(encoding="utf-8"),
    )
    validate_wp_p0_003_post_merge_closure_text(
        errors,
        paths["current"].read_text(encoding="utf-8"),
        paths["work_package"].read_text(encoding="utf-8"),
        paths["addendum"].read_text(encoding="utf-8"),
        paths["evidence"].read_text(encoding="utf-8"),
        paths["post_merge_evidence"].read_text(encoding="utf-8"),
    )
    wp3b_records = {
        path.relative_to(ROOT).as_posix()
        for path in (ROOT / "docs/03-work-items").glob("WP-P0-003B*.md")
    }
    validate_wp_p0_003_record_paths(errors, wp3b_records)


def require_contract_tokens_text(
    errors: list[str], label: str, text: str, tokens: tuple[str, ...]
) -> None:
    """Require mutation-sensitive contract language in a canonical document."""
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing required contract: {token}")


FORBIDDEN_FINDING_TAXONOMY_PATTERNS = (
    re.compile(
        r"(?is)(?:critical\s*/\s*high|`?critical`?\s*,\s*`?high`?\s*,\s*"
        r"`?medium`?\s*,\s*`?low`?).{0,100}\b(?:finding|findings|defect|defects)\b"
    ),
    re.compile(
        r"(?is)\b(?:finding|findings|defect|defects)\b.{0,100}"
        r"(?:critical\s*/\s*high|`?critical`?\s*,\s*`?high`?\s*,\s*"
        r"`?medium`?\s*,\s*`?low`?)"
    ),
)


def validate_finding_vocabulary_texts(
    errors: list[str], documents: dict[str, str]
) -> None:
    """Keep one Controller finding taxonomy without banning Slice risk labels."""
    requirements = {
        "review_standard": (
            "BLOCKER       unsafe",
            "MAJOR         required behavior/evidence missing",
            "MINOR         bounded defect",
            "INFORMATIONAL non-blocking observation",
        ),
        "quality": ("no unresolved BLOCKER/MAJOR",),
        "assurance": (
            "no BLOCKER/MAJOR finding remains",
            "Findings use only `BLOCKER`, `MAJOR`, `MINOR` or `INFORMATIONAL`",
        ),
        "product": ("no unresolved BLOCKER/MAJOR finding",),
        "slice": ("no unresolved BLOCKER/MAJOR finding",),
        "guide": ("no unresolved BLOCKER/MAJOR finding",),
        "pr_template": ("No unresolved BLOCKER/MAJOR finding",),
    }
    for name, tokens in requirements.items():
        if name in documents:
            require_contract_tokens_text(
                errors, f"finding vocabulary {name}", documents[name], tokens
            )
    for name in requirements:
        text = documents.get(name, "")
        for pattern in FORBIDDEN_FINDING_TAXONOMY_PATTERNS:
            if pattern.search(text):
                errors.append(
                    f"canonical Gate document {name} reintroduces the forbidden "
                    "CRITICAL/HIGH/MEDIUM/LOW finding taxonomy"
                )
                break


def validate_v1_authority_effect_texts(
    errors: list[str], documents: dict[str, str]
) -> None:
    """Reject proposal-only pending metadata from the durable active baseline."""
    requirements = {
        "dr": (
            "status: ACCEPTED_EFFECTIVE_ON_PROTECTED_MAIN",
            "effective_condition: PROTECTED_MAIN_MERGE_AFTER_INDEPENDENT_CONTROLLER_REVIEW_AND_OWNER_AUTHORIZATION",
        ),
        "owner": (
            "repository_effect: EFFECTIVE_WHEN_PRESENT_ON_PROTECTED_MAIN",
            "effective_condition: PROTECTED_MAIN_MERGE_AFTER_INDEPENDENT_CONTROLLER_REVIEW_AND_OWNER_AUTHORIZATION",
        ),
        "product": (
            "status: APPROVED_EFFECTIVE_ON_PROTECTED_MAIN",
            "effective_condition: PROTECTED_MAIN_MERGE_AFTER_INDEPENDENT_CONTROLLER_REVIEW_AND_OWNER_AUTHORIZATION",
        ),
        "slices": (
            "CONTRACT_APPROVED_EFFECTIVE_ON_PROTECTED_MAIN",
            "effective_condition: PROTECTED_MAIN_MERGE_AFTER_INDEPENDENT_CONTROLLER_REVIEW_AND_OWNER_AUTHORIZATION",
        ),
    }
    stale_tokens = (
        "CONTROLLER_APPROVED_PENDING_REPOSITORY_EFFECT",
        "APPROVED_BY_DR_0003_PENDING_REPOSITORY_EFFECT",
        "PENDING_DR_0003_MERGE",
        "PENDING_RESET_MERGE",
    )
    for name, tokens in requirements.items():
        if name in documents:
            require_contract_tokens_text(
                errors, f"durable authority {name}", documents[name], tokens
            )
    for name in requirements:
        text = documents.get(name, "")
        for token in stale_tokens:
            if token in text:
                errors.append(
                    f"canonical authority document {name} retains stale pending-merge metadata: {token}"
                )


def validate_gate_ev_contract_texts(
    errors: list[str], documents: dict[str, str]
) -> None:
    """Keep bounded evidence authority separate from Gate-E Pilot enablement."""
    common_verdicts = (
        "AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION",
        "CHANGES_REQUIRED",
        "BLOCKED_BY_EXTERNAL_CAPABILITY",
        "BLOCKED_EVIDENCE_INCOMPLETE",
    )
    requirements = {
        "current": (
            "bounded_real_write_verification_authorization: NONE",
            "bounded_real_write_verification_gate: REQUIRED_BEFORE_FIRST_REAL_WRITE",
            "production_write_enabled: false",
            "cannot be implemented by changing any default flag to `ENABLED`",
        ),
        "quality": (
            "## Gate EV — Bounded Real-Write Verification Authorization",
            "exact immutable original Slice Contract path and SHA-256",
            "accepted\n  additive Amendment path and SHA-256",
            "editing an accepted original Contract in\n  place is prohibited",
            *common_verdicts,
            "explicit Human Owner authorization",
            "Platform, opaque Account/Store reference, Capability and SKU allowlist",
            "one-time or time-bounded verification window",
            "maximum price delta and cumulative exposure",
            "current official-source and real-account Capability evidence",
            "current deterministic Guardrails and a passing Dry Run",
            "supervised operator, abort owner and manual-stop procedure",
            "global and scoped Kill Switches",
            "captured pre-state",
            "Readback and Restore/Compensate procedure",
            "unknown-result and manual-resolution behavior",
            "complete Audit and durable redacted evidence-retention plan",
            "does not authorize general Pilot",
            "Gate E remains the only Gate",
        ),
        "charter": (
            "bounded evidence generation requires an\n  exact Gate EV",
            "ongoing controlled Pilot use requires Gate E",
        ),
        "operating": (
            "### Bounded real-write verification",
            *common_verdicts,
            "FULL_SCOPE_IMPLEMENTATION`, merge and Gate EV do not authorize ongoing",
        ),
        "assurance": (
            "## 5. Gate EV — Bounded Real-Write Verification Authorization",
            *common_verdicts,
            "Gate E consumes the bounded verification evidence",
            "completion or Gate EV does not automatically enable the Capability",
        ),
        "product": (
            "Gate EV —\nBounded Real-Write Verification Authorization",
            "Full-Scope\nImplementation, a code merge, Dry Run completion or a future Gate-E review does\nnot substitute for Gate EV",
        ),
        "slice": (
            "AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION",
            "Full-Scope\nImplementation does not grant Gate EV, Gate E or any real Marketplace write",
        ),
        "capability": (
            "## 6. Gate-EV authority before write evidence",
            "Gate EV authorizes only evidence generation",
        ),
        "handoff": (
            "## 7. Bounded verification and Capability enablement",
            "Gate EV permits only supervised bounded evidence generation",
        ),
        "guide": (
            "Gate EV for any bounded real-write evidence",
            "ongoing Pilot use separately requires Gate E",
        ),
        "questions": ("OQ-113", "defaults to `NONE`"),
        "traceability": ("GATE-EV,Governance Gate",),
        "pr_template": ("bounded real-write verification cites an exact Gate-EV",),
        "agents": ("never treat implementation, merge or Gate EV as production enablement",),
        "claude": ("requires an exact Gate-EV envelope",),
        "claude_project": ("Gate EV is bounded evidence authority",),
        "chatgpt_project": ("Gate EV is not production",),
    }
    for name, tokens in requirements.items():
        if name in documents:
            require_contract_tokens_text(
                errors, f"Gate EV {name}", documents[name], tokens
            )


def validate_codeql_disposition_artifacts(
    errors: list[str], artifacts: dict[str, bytes]
) -> None:
    """Preserve exact Owner authority and observed security-state evidence."""
    manifest_name = "EXECUTION-HASHES.json"
    expected = "6b7c331995ccd2c8ed593a82ef0a7aef0b1264fbd2051f2c9f5c124693aaf5dc"
    manifest = artifacts.get(manifest_name, b"")
    if hashlib.sha256(manifest).hexdigest() != expected:
        errors.append("SLICE-V1-001 CodeQL v1.1 execution manifest hash mismatch")
        return
    entries = json.loads(manifest)["files"]
    if set(artifacts) != set(entries) | {manifest_name}:
        errors.append("SLICE-V1-001 CodeQL v1.1 evidence inventory changed")
    for name, entry in entries.items():
        content = artifacts.get(name, b"")
        if len(content) != entry["bytes"] or hashlib.sha256(content).hexdigest() != entry["sha256"]:
            errors.append(f"SLICE-V1-001 CodeQL v1.1 evidence hash mismatch: {name}")


def validate_slice_rework_evidence_text(
    errors: list[str], acceptance: str, artifacts: dict[str, bytes]
) -> None:
    """Bind post-merge engineering status to immutable review and merge facts."""
    for name, expected in SLICE_REWORK_ARTIFACT_HASHES.items():
        if name not in artifacts or hashlib.sha256(artifacts[name]).hexdigest() != expected:
            errors.append(f"SLICE-V1-001 frozen artifact missing or hash mismatch: {name}")

    metadata = leading_yaml_body(acceptance, "# SLICE-V1-001 acceptance status") or ""
    required = {
        "assessed_against": (
            "ACTUAL_SQUASH_COMMIT_D562B81F4F0271AA33A53B21CCAFFC88B5610C0C"
        ),
        "assessment_phase": "FORMAL_CLOSURE_ACCEPTED",
        "remote_publication": "PR_22_MERGED_PROTECTED_SQUASH",
        "initial_published_head": "c3d2160a9c302d993e2b01a08946f46fae0b01d5",
        "initial_published_tree": "9d7641eccc2d233bf2c5615e7c4776721269bc15",
        "initial_tested_merge": "353670b4a311f98b56fae593f8b2b34d5f39a80e",
        "initial_tested_merge_tree": "9d7641eccc2d233bf2c5615e7c4776721269bc15",
        "initial_remote_ci": "PASS_12_OF_12_REQUIRED_CONTEXTS",
        "final_candidate_identity_resolution": (
            "APPROVED_HEAD_TREE_SIGNED_TESTED_MERGE_AND_ACTUAL_SQUASH"
        ),
        "controller_final_gate": "PASS_R2_ENGINEERING_FINAL_GATE",
        "controller_comment_id": "5469390502",
        "approved_engineering_head": "f35327a584b980ec4acf7ace7c88e124d6d79709",
        "approved_engineering_tree": "390ebe37bea778b7a4548381ad357fc99aa0da6b",
        "approved_tested_merge": "bcc3b37965003c3ea1af720ea847dc27fb473a9e",
        "actual_squash_commit": "d562b81f4f0271aa33a53b21ccaffc88b5610c0c",
        "actual_squash_tree": "390ebe37bea778b7a4548381ad357fc99aa0da6b",
        "actual_squash_sole_parent": "db92cf2f8bd818f36dd8f5aa17b8589c4140b669",
        "engineering_implementation": "ENGINEERING_IMPLEMENTATION_CLOSED",
        "production_readiness": "DEFERRED_TO_RELEASE_V1_001",
        "owner_formal_closure": "HUMAN_OWNER_ACCEPTED",
        "slice_state": "CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS",
        "controller_bookkeeping_verdict": "PASS_POST_MERGE_CLOSURE_BOOKKEEPING",
        "controller_bookkeeping_comment": "5469802650",
        "snapshot_source_commit": "7f52b4c0e145cfb86e4982416aa7bdca79da7ec6",
        "snapshot_source_tree": "619b79844641d299ad6b5283f6dcea21c03e9ab3",
        "snapshot_git_blob_sha1": V1_CLOSURE_SNAPSHOT_GIT_BLOB_SHA1,
        "snapshot_sha256": V1_CLOSURE_SNAPSHOT_SHA256,
        "owner_acceptance_comment": "5469935477",
        "owner_acceptance_evidence": V1_OWNER_FORMAL_CLOSURE_EVIDENCE_PATH,
        "next_action": "NEXT_SLICE_CONTRACT_SOCRATIC_DISCOVERY",
        "contract_sha256": V1_SLICE_001_CONTRACT_SHA256,
        "frozen_findings_sha256": V1_ACTIVE_STATE["slice_v1_001_frozen_findings_sha256"],
        "supplemental_r2_review_sha256": V1_ACTIVE_STATE[
            "slice_v1_001_supplemental_r2_review_sha256"
        ],
        "amendment_002_sha256": V1_AMENDMENT_002_SHA256,
        "amendment_002_acceptance_evidence_sha256": V1_AMENDMENT_002_ACCEPTANCE_SHA256,
        "production_write_enabled": "false",
    }
    for field, expected in required.items():
        if unique_yaml_value(metadata, field) != expected:
            errors.append(f"SLICE-V1-001 acceptance {field} must be exactly: {expected}")
    rows = re.findall(r"(?m)^\|\s*`(S1-AC-\d{3})`\s*\|\s*`([A-Z0-9_]+)`\s*\|([^\n]*)", acceptance)
    ids = Counter(row[0] for row in rows)
    expected_ids = {f"S1-AC-{number:03d}" for number in range(1, 42)}
    if set(ids) != expected_ids or any(count != 1 for count in ids.values()):
        errors.append("SLICE-V1-001 acceptance must contain exactly 41 unique contract criteria")
    allowed = {
        "EXECUTABLY_VERIFIED",
        "OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001",
    }
    if any(row[1] not in allowed for row in rows):
        errors.append("SLICE-V1-001 post-merge acceptance uses an unsupported status")
    deferred_ids = {
        "S1-AC-001", "S1-AC-003", "S1-AC-005", "S1-AC-006", "S1-AC-007",
        "S1-AC-008", "S1-AC-009", "S1-AC-010", "S1-AC-012", "S1-AC-023",
        "S1-AC-025", "S1-AC-026", "S1-AC-031", "S1-AC-032", "S1-AC-033",
        "S1-AC-038", "S1-AC-040",
    }
    by_id = {row[0]: row for row in rows}
    for criterion in expected_ids:
        expected_status = (
            "OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001"
            if criterion in deferred_ids else "EXECUTABLY_VERIFIED"
        )
        row = by_id.get(criterion)
        if row is None or row[1] != expected_status:
            errors.append(
                f"SLICE-V1-001 {criterion} status must be exactly: {expected_status}"
            )
    counts = Counter(row[1] for row in rows)
    summaries = re.findall(r"(?m)^\|\s*`([A-Z0-9_]+)`\s*\|\s*(\d+)\s*\|\s*$", acceptance)
    if len(summaries) != len(counts) or dict((state, int(count)) for state, count in summaries) != counts:
        errors.append("SLICE-V1-001 acceptance summary counts do not match its 41 rows")
    name = "FROZEN-FINDING-SET-SLICE-V1-001-PR20-R1.json"
    try:
        findings = json.loads(artifacts.get(name, b"{}"))["findings"]
        if {finding["id"] for finding in findings} != {f"S1-F{number:03d}" for number in range(1, 14)}:
            errors.append("SLICE-V1-001 must retain all 13 frozen findings")
    except (KeyError, TypeError, ValueError):
        errors.append("SLICE-V1-001 frozen finding set is not readable")


SLICE_POST_MERGE_DOCUMENT_REQUIREMENTS = {
    "START_HERE.md": (
        "Engineering implementation: MERGED at d562b81f4f0271aa33a53b21ccaffc88b5610c0c",
        "Slice status: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS",
        "Owner Formal Closure: HUMAN_OWNER_ACCEPTED",
        "Next action: NEXT_SLICE_CONTRACT_SOCRATIC_DISCOVERY",
    ),
    "docs/02-architecture/designs/SLICE-V1-001-design.md": (
        "implementation_state: ENGINEERING_IMPLEMENTATION_MERGED",
        "controller_final_gate: PASS_R2_ENGINEERING_FINAL_GATE",
        "actual_squash_commit: d562b81f4f0271aa33a53b21ccaffc88b5610c0c",
        "production_readiness: DEFERRED_TO_RELEASE_V1_001",
        "owner_formal_closure: HUMAN_OWNER_ACCEPTED",
        "slice_state: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS",
        "controller_bookkeeping_verdict: PASS_POST_MERGE_CLOSURE_BOOKKEEPING",
        "owner_acceptance_comment: 5469935477",
    ),
    "docs/05-testing/V1_PRODUCTION_ASSURANCE_MATRIX.md": (
        "## 2c. SLICE-V1-002 implementation evidence state",
        "implementation_state: ENGINEERING_IMPLEMENTATION_MERGED",
        "controlled_write_target: NONE_IN_THIS_SLICE",
        "controller_verdict: PASS_R3_ENGINEERING_FINAL_GATE",
        "actual_squash_commit: cc42760cfc99c1bab027039fca67410d696e96fa",
        "actual_squash_tree: f7e02da0bf38922f6c5a80d49b263613ade997d9",
        "actual_squash_sole_parent: 8a7076877374391cf851481c023dfb0e621ab712",
        "engineering_acceptance: 100_OF_100",
        "owner_formal_closure: HUMAN_OWNER_ACCEPTED",
        "deferred_release_obligations: S2_REL_001_THROUGH_010_PRODUCTION_BLOCKING",
        "closure_snapshot: docs/07-phase-evidence/SLICE-V1-002/CLOSURE-SNAPSHOT-DRAFT.md",
        "production_write_enabled: false",
    ),
    "docs/07-phase-evidence/README.md": (
        "SLICE-V1-001 R2 post-merge record",
        "CLOSURE-SNAPSHOT-DRAFT.md",
        "OWNER-SLICE-V1-001-FORMAL-CLOSURE-ACCEPTANCE-EVIDENCE.md",
        "Owner comment `5469935477` issued Formal Closure",
        "production_write_enabled` remains `false`",
    ),
    "docs/07-phase-evidence/SLICE-V1-001/CLOSURE-SNAPSHOT-DRAFT.md": (
        "status: DRAFT_PENDING_HUMAN_OWNER_FORMAL_CLOSURE",
        "controller_final_gate: PASS_R2_ENGINEERING_FINAL_GATE",
        "controller_comment_id: 5469390502",
        "engineering_implementation: ENGINEERING_IMPLEMENTATION_CLOSED",
        "production_readiness: DEFERRED_TO_RELEASE_V1_001",
        "formal_owner_closure: NOT_ISSUED",
        "Actual protected SQUASH commit | `d562b81f4f0271aa33a53b21ccaffc88b5610c0c`",
        "Actual SQUASH tree | `390ebe37bea778b7a4548381ad357fc99aa0da6b`",
        "Actual SQUASH sole parent | `db92cf2f8bd818f36dd8f5aa17b8589c4140b669`",
        "`EXECUTABLY_VERIFIED` | 24",
        "`OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | 17",
        "production_write_enabled: false",
    ),
    "docs/07-phase-evidence/SLICE-V1-001/executable-evidence.md": (
        "assessment: ENGINEERING_IMPLEMENTATION_CLOSED",
        "controller_comment_id: 5469390502",
        "remote_rework_ci: PASS_12_OF_12_REQUIRED_CONTEXTS_AND_AGGREGATE_CODEQL",
        "controller_bookkeeping_verdict: PASS_POST_MERGE_CLOSURE_BOOKKEEPING",
        "owner_formal_closure: HUMAN_OWNER_ACCEPTED",
        "slice_state: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS",
        "deployment: NOT_EXECUTED",
        "production_write_enabled: false",
    ),
    "docs/07-phase-evidence/SLICE-V1-001/post-merge-closure-sync.md": (
        "mode: DOCS_GOVERNANCE_CLOSURE_SYNC_ONLY",
        "base_actual_merged_main: d562b81f4f0271aa33a53b21ccaffc88b5610c0c",
        "base_tree: 390ebe37bea778b7a4548381ad357fc99aa0da6b",
        "base_sole_parent: db92cf2f8bd818f36dd8f5aa17b8589c4140b669",
        "controller_bookkeeping_verdict: PASS_POST_MERGE_CLOSURE_BOOKKEEPING",
        "formal_owner_closure: HUMAN_OWNER_ACCEPTED",
        "owner_acceptance_comment: 5469935477",
        "slice_state: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS",
        "production_write_enabled: false",
        "CONTROLLER_FORMAL_CLOSURE_AND_BRANCH_CLEANUP_READBACK",
    ),
    "docs/07-phase-evidence/SLICE-V1-001/r2-finding-closure.json": (
        '"documentType": "SLICE_V1_001_SUPPLEMENTAL_R2_FINDING_CLOSURE_ENGINEERING_CLOSED"',
        '"candidateState": "ENGINEERING_IMPLEMENTATION_MERGED"',
        '"controllerVerdict": "PASS_R2_ENGINEERING_FINAL_GATE"',
        '"controllerCommentId": 5469390502',
        '"productionReadiness": "DEFERRED_TO_RELEASE_V1_001"',
        '"ownerFormalClosure": "HUMAN_OWNER_ACCEPTED"',
        '"sliceState": "CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS"',
        '"controllerBookkeepingVerdict": "PASS_POST_MERGE_CLOSURE_BOOKKEEPING"',
        '"controllerBookkeepingComment": 5469802650',
        '"snapshotGitBlobSha1": "e26359ec216c04319a4bf1e7126906eb204593d2"',
        '"snapshotSha256": "5abce67327673dc0248f11ece1f31cd11d1ec7c0e69a1e84823ddedf30aab2e3"',
        '"ownerAcceptanceComment": 5469935477',
        '"actualSquashCommit": "d562b81f4f0271aa33a53b21ccaffc88b5610c0c"',
        '"actualSquashTree": "390ebe37bea778b7a4548381ad357fc99aa0da6b"',
        '"actualSquashSoleParent": "db92cf2f8bd818f36dd8f5aa17b8589c4140b669"',
        '"nextAction": "CONTROLLER_FORMAL_CLOSURE_AND_BRANCH_CLEANUP_READBACK"',
    ),
    "docs/08-handoffs/CONTROLLER-SLICE-V1-001-R2-ENGINEERING-FINAL-GATE-PASS.md": (
        "verdict: PASS_R2_ENGINEERING_FINAL_GATE",
        "controller_comment_id: 5469390502",
        "reviewed_head: f35327a584b980ec4acf7ace7c88e124d6d79709",
        "reviewed_tested_merge: bcc3b37965003c3ea1af720ea847dc27fb473a9e",
        "frozen_r2_findings_closed: 10_OF_10",
        "production_write_enabled: false",
    ),
    "docs/08-handoffs/OWNER-SLICE-V1-001-FORMAL-CLOSURE-ACCEPTANCE-TEMPLATE.md": (
        "template_status: FULFILLED_BY_SEPARATE_EXACT_OWNER_ACCEPTANCE",
        "owner_formal_closure: HUMAN_OWNER_ACCEPTED",
        "controller_bookkeeping_verdict: PASS_POST_MERGE_CLOSURE_BOOKKEEPING",
        "accepted_closure_snapshot_git_blob_sha1: e26359ec216c04319a4bf1e7126906eb204593d2",
        "accepted_closure_snapshot_sha256: 5abce67327673dc0248f11ece1f31cd11d1ec7c0e69a1e84823ddedf30aab2e3",
        "owner_acceptance_comment: 5469935477",
        "production_write_enabled: false",
    ),
    V1_OWNER_FORMAL_CLOSURE_EVIDENCE_PATH: (
        "evidence_id: OWNER_SLICE_V1_001_FORMAL_CLOSURE_ACCEPTANCE",
        "source_comment_id: 5469935477",
        "source_author_association: OWNER",
        "owner_formal_closure: HUMAN_OWNER_ACCEPTED",
        "slice_state: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS",
        "snapshot_source_commit: 7f52b4c0e145cfb86e4982416aa7bdca79da7ec6",
        "snapshot_source_tree: 619b79844641d299ad6b5283f6dcea21c03e9ab3",
        "snapshot_git_blob_sha1: e26359ec216c04319a4bf1e7126906eb204593d2",
        "snapshot_sha256: 5abce67327673dc0248f11ece1f31cd11d1ec7c0e69a1e84823ddedf30aab2e3",
        "controller_bookkeeping_verdict: PASS_POST_MERGE_CLOSURE_BOOKKEEPING",
        "controller_bookkeeping_comment: 5469802650",
        "production_readiness: DEFERRED_TO_RELEASE_V1_001",
        "next_action: NEXT_SLICE_CONTRACT_SOCRATIC_DISCOVERY",
        "production_write_enabled: false",
    ),
}


def validate_slice_post_merge_closure_documents(
    errors: list[str], documents: dict[str, str]
) -> None:
    """Require the exact post-merge identity and preserve every release boundary."""
    for path, tokens in SLICE_POST_MERGE_DOCUMENT_REQUIREMENTS.items():
        text = documents.get(path)
        if text is None:
            errors.append(f"SLICE-V1-001 post-merge document is missing: {path}")
            continue
        for token in tokens:
            if token not in text:
                errors.append(
                    f"SLICE-V1-001 post-merge document {path} missing exact token: {token}"
                )

    snapshot = documents.get(V1_CLOSURE_SNAPSHOT_PATH, "").encode()
    if hashlib.sha256(snapshot).hexdigest() != V1_CLOSURE_SNAPSHOT_SHA256:
        errors.append("SLICE-V1-001 frozen Closure Snapshot SHA-256 changed")
    git_blob = b"blob " + str(len(snapshot)).encode() + b"\0" + snapshot
    if hashlib.sha1(git_blob).hexdigest() != V1_CLOSURE_SNAPSHOT_GIT_BLOB_SHA1:
        errors.append("SLICE-V1-001 frozen Closure Snapshot Git blob SHA-1 changed")

    owner_evidence = documents.get(
        V1_OWNER_FORMAL_CLOSURE_EVIDENCE_PATH, ""
    ).encode()
    if hashlib.sha256(owner_evidence).hexdigest() != V1_OWNER_FORMAL_CLOSURE_EVIDENCE_SHA256:
        errors.append("SLICE-V1-001 Owner Formal Closure evidence SHA-256 changed")

    combined = "\n".join(
        text for path, text in documents.items() if path != V1_CLOSURE_SNAPSHOT_PATH
    )
    prohibited_claims = (
        "production_readiness: PRODUCTION_READY",
        "production_write_enabled: true",
        "gate_ev: AUTHORIZED",
        "gate_e: AUTHORIZED",
        "pilot: ACTIVATED",
        "release_v1_001: ACTIVATED",
    )
    normalized = combined.lower()
    for claim in prohibited_claims:
        if claim.lower() in normalized:
            errors.append(
                "SLICE-V1-001 Formal Closure contains a prohibited release claim: "
                + claim
            )


SLICE_V1_002_POST_MERGE_DOCUMENT_REQUIREMENTS = {
    V1_SLICE_002_OWNER_FORMAL_CLOSURE_EVIDENCE_PATH: (
        "controller_review: CONTROLLER_SLICE_V1_002_FINAL_CLOSURE_VERIFICATION_PR26_R3",
        "controller_verdict: PASS_R3_ENGINEERING_FINAL_GATE",
        "owner_statement_sha256_utf8_lf: " + V1_SLICE_002_OWNER_STATEMENT_SHA256,
        "- Frozen Findings: 18/18 closed",
        "- total engineering Acceptance: 100/100",
        "S2-PM-SEC-001: CLOSED_BY_FIXED_CODE_ON_DEFAULT_BRANCH",
        "S2-PM-TST-002: CLOSED",
        "final_head: fde6e07f4f5d5856202e52287b7544be0e85c523",
        "actual_squash_commit: e0184852785f451256a36f52fa3d520ceea2c313",
        "alert_116: FIXED_BY_CODE_NO_DISMISSAL",
        "alert_116_dismissed_by: null",
        "alert_116_dismissed_at: null",
        "alert_116_dismissed_reason: null",
        "alert_116_dismissed_comment: null",
        "alert_117: FIXED_BY_CODE_NO_DISMISSAL",
        "alert_117_dismissed_by: null",
        "alert_117_dismissed_at: null",
        "alert_117_dismissed_reason: null",
        "alert_117_dismissed_comment: null",
        "new_open_high_critical_alerts: NONE",
        "production_write_enabled: false",
    ),
    V1_SLICE_002_CLOSURE_SNAPSHOT_PATH: (
        "status: DRAFT_PENDING_CONTROLLER_FINAL_POST_MERGE_BOOKKEEPING_VERIFICATION",
        "closure_sync_pr: 27",
        "post_merge_security_readback: PASS_ALERTS_116_117_FIXED_BY_CODE_NO_DISMISSAL",
        "bookkeeping_pass_eligibility: READY_FOR_CONTROLLER_FINAL_VERIFICATION",
        "controller_final_gate: PASS_R3_ENGINEERING_FINAL_GATE",
        "human_owner_formal_closure: COMPLETE",
        "| Actual protected `main` | commit `cc42760cfc99c1bab027039fca67410d696e96fa`",
        "tree `f7e02da0bf38922f6c5a80d49b263613ade997d9`",
        "sole parent `8a7076877374391cf851481c023dfb0e621ab712`",
        "`S2-PM-SEC-001`, finding SHA-256",
        "`S2-PM-TST-002`, finding SHA-256",
        "PR #28 Head `fde6e07f4f5d5856202e52287b7544be0e85c523`",
        "| Corrected protected `main` | commit `e0184852785f451256a36f52fa3d520ceea2c313`",
        "sole parent `cc42760cfc99c1bab027039fca67410d696e96fa`",
        "Security run `33550566209` `SUCCESS`",
        "alerts #116/#117 `FIXED_BY_CODE_NO_DISMISSAL`",
        "new open High/Critical set `[]`",
        "`S2-AC-100` is `CONTROLLER_VERIFIED`",
        "All ten `S2-REL-001` through `S2-REL-010` obligations remain exactly",
        "source ref\n`fix/SLICE-V1-002-root-cause-rework-r1` was restored to",
        "CodeQL alerts\n[#116]",
        "[#117]",
        "`java/concatenated-sql-query`",
        "`dismissed_reason` and `dismissed_comment` fields are all `null`",
        "CONTROLLER_SLICE_V1_002_FINAL_POST_MERGE_BOOKKEEPING_VERIFICATION",
        "production_write_enabled: false",
    ),
    V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_PATH: (
        "controller_activation: CONTROLLER_SLICE_V1_002_OWNER_SNAPSHOT_ACCEPTANCE_ACTIVATION_R1",
        "controller_bookkeeping_review: CONTROLLER_SLICE_V1_002_FINAL_POST_MERGE_BOOKKEEPING_VERIFICATION_R2",
        "controller_bookkeeping_verdict: PASS_POST_MERGE_CLOSURE_BOOKKEEPING",
        "owner_snapshot_acceptance: HUMAN_OWNER_ACCEPTED",
        "owner_snapshot_acceptance_statement_sha256: "
        + V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_STATEMENT_SHA256,
        "source_commit: dbc09e00a942c53580270a4157da863933502e8b",
        "source_head: dbc09e00a942c53580270a4157da863933502e8b",
        "source_tree: 11e209e1991c49e7d2a4706da1b1d2654dfe35d6",
        "tested_merge: b36e057ed6388385f846dfceef96a960c8ff6c45",
        "git_blob_sha1: " + V1_SLICE_002_CLOSURE_SNAPSHOT_GIT_BLOB_SHA1,
        "sha256: " + V1_SLICE_002_CLOSURE_SNAPSHOT_SHA256,
        "statement_sha256: " + V1_SLICE_002_OWNER_STATEMENT_SHA256,
        "alert_116: FIXED_BY_CODE_NO_DISMISSAL",
        "alert_116_dismissed_by: null",
        "alert_116_dismissed_at: null",
        "alert_116_dismissed_reason: null",
        "alert_116_dismissed_comment: null",
        "alert_117: FIXED_BY_CODE_NO_DISMISSAL",
        "alert_117_dismissed_by: null",
        "alert_117_dismissed_at: null",
        "alert_117_dismissed_reason: null",
        "alert_117_dismissed_comment: null",
        "new_open_high_critical_alerts: NONE",
        "deferred_release_obligations: S2_REL_001_THROUGH_010_PRODUCTION_BLOCKING_DEFERRED_TO_RELEASE_V1_001",
        "closure_snapshot_before_next_slice: SATISFIED_EXACT_OWNER_ACCEPTED",
        "next_authorized_actor: CODEX_POST_CLOSURE_GIT_EXECUTOR",
        "next_action: PROTECTED_SQUASH_MERGE_PR27_AND_FINAL_READBACK",
        "production_write_enabled: false",
    ),
    V1_SLICE_002_POST_MERGE_CLOSURE_SYNC_PATH: (
        "status: EXACT_OWNER_ACCEPTED_SNAPSHOT_RECORDED_FOR_PROTECTED_SQUASH",
        "controller_bookkeeping_verdict: PASS_POST_MERGE_CLOSURE_BOOKKEEPING",
        "owner_snapshot_acceptance: HUMAN_OWNER_ACCEPTED",
        "owner_snapshot_acceptance_statement_sha256: "
        + V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_STATEMENT_SHA256,
        "owner_snapshot_acceptance_evidence_git_blob_sha1: "
        + V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_GIT_BLOB_SHA1,
        "owner_snapshot_acceptance_evidence_sha256: "
        + V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_SHA256,
        "source_commit: dbc09e00a942c53580270a4157da863933502e8b",
        "source_head: dbc09e00a942c53580270a4157da863933502e8b",
        "source_tree: 11e209e1991c49e7d2a4706da1b1d2654dfe35d6",
        "tested_merge: b36e057ed6388385f846dfceef96a960c8ff6c45",
        "statement_sha256: " + V1_SLICE_002_OWNER_STATEMENT_SHA256,
        "frozen_findings: 18_OF_18_CLOSED",
        "engineering_acceptance: 100_OF_100",
        "alert_116: FIXED_BY_CODE_NO_DISMISSAL",
        "alert_116_dismissed_by: null",
        "alert_116_dismissed_at: null",
        "alert_116_dismissed_reason: null",
        "alert_116_dismissed_comment: null",
        "alert_117: FIXED_BY_CODE_NO_DISMISSAL",
        "alert_117_dismissed_by: null",
        "alert_117_dismissed_at: null",
        "alert_117_dismissed_reason: null",
        "alert_117_dismissed_comment: null",
        "new_open_high_critical_alerts: NONE",
        "deferred_release_obligations: S2_REL_001_THROUGH_010_PRODUCTION_BLOCKING_DEFERRED_TO_RELEASE_V1_001",
        "closure_snapshot_before_next_slice: SATISFIED_EXACT_OWNER_ACCEPTED",
        "next_authorized_actor: CODEX_POST_CLOSURE_GIT_EXECUTOR",
        "next_action: PROTECTED_SQUASH_MERGE_PR27_AND_FINAL_READBACK",
        "deployment: PROHIBITED",
        "provider_calls: PROHIBITED",
        "production_write_enabled: false",
    ),
}


def exact_fenced_text_after_heading(content: bytes, heading: str) -> bytes | None:
    """Return the byte-exact text payload, including its final LF."""
    marker = f"## {heading}\n\n```text\n".encode()
    start = content.find(marker)
    if start < 0:
        return None
    start += len(marker)
    end = content.find(b"```\n", start)
    if end < 0:
        return None
    return content[start:end]


def validate_slice_v1_002_post_merge_closure_documents(
    errors: list[str], documents: dict[str, bytes]
) -> None:
    """Freeze the exact Owner evidence and fail closed on Slice 2 release claims."""
    for path, tokens in SLICE_V1_002_POST_MERGE_DOCUMENT_REQUIREMENTS.items():
        content = documents.get(path)
        if content is None:
            errors.append(f"SLICE-V1-002 post-merge document is missing: {path}")
            continue
        text = content.decode("utf-8")
        for token in tokens:
            if token not in text:
                errors.append(
                    f"SLICE-V1-002 post-merge document {path} missing exact token: {token}"
                )

    owner = documents.get(V1_SLICE_002_OWNER_FORMAL_CLOSURE_EVIDENCE_PATH, b"")
    if hashlib.sha256(owner).hexdigest() != V1_SLICE_002_OWNER_FORMAL_CLOSURE_EVIDENCE_SHA256:
        errors.append("SLICE-V1-002 Owner Formal Closure evidence SHA-256 changed")
    owner_statement = exact_fenced_text_after_heading(
        owner, "Exact Human Owner statement"
    )
    if (
        owner_statement is None
        or hashlib.sha256(owner_statement).hexdigest()
        != V1_SLICE_002_OWNER_STATEMENT_SHA256
    ):
        errors.append("SLICE-V1-002 exact Human Owner Formal Closure statement changed")

    snapshot = documents.get(V1_SLICE_002_CLOSURE_SNAPSHOT_PATH, b"")
    if hashlib.sha256(snapshot).hexdigest() != V1_SLICE_002_CLOSURE_SNAPSHOT_SHA256:
        errors.append("SLICE-V1-002 Closure Snapshot SHA-256 changed")
    git_blob = b"blob " + str(len(snapshot)).encode() + b"\0" + snapshot
    if hashlib.sha1(git_blob).hexdigest() != V1_SLICE_002_CLOSURE_SNAPSHOT_GIT_BLOB_SHA1:
        errors.append("SLICE-V1-002 Closure Snapshot Git blob SHA-1 changed")

    snapshot_acceptance = documents.get(
        V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_PATH, b""
    )
    if (
        hashlib.sha256(snapshot_acceptance).hexdigest()
        != V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_SHA256
    ):
        errors.append("SLICE-V1-002 Owner Snapshot acceptance evidence SHA-256 changed")
    acceptance_git_blob = (
        b"blob "
        + str(len(snapshot_acceptance)).encode()
        + b"\0"
        + snapshot_acceptance
    )
    if (
        hashlib.sha1(acceptance_git_blob).hexdigest()
        != V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_EVIDENCE_GIT_BLOB_SHA1
    ):
        errors.append("SLICE-V1-002 Owner Snapshot acceptance Git blob SHA-1 changed")
    snapshot_acceptance_statement = exact_fenced_text_after_heading(
        snapshot_acceptance, "Exact Human Owner Snapshot acceptance statement"
    )
    if (
        snapshot_acceptance_statement is None
        or hashlib.sha256(snapshot_acceptance_statement).hexdigest()
        != V1_SLICE_002_OWNER_SNAPSHOT_ACCEPTANCE_STATEMENT_SHA256
    ):
        errors.append("SLICE-V1-002 exact Human Owner Snapshot acceptance statement changed")

    closure_sync = documents.get(V1_SLICE_002_POST_MERGE_CLOSURE_SYNC_PATH, b"")
    if hashlib.sha256(closure_sync).hexdigest() != V1_SLICE_002_POST_MERGE_CLOSURE_SYNC_SHA256:
        errors.append("SLICE-V1-002 post-merge closure-sync record SHA-256 changed")
    closure_sync_git_blob = b"blob " + str(len(closure_sync)).encode() + b"\0" + closure_sync
    if (
        hashlib.sha1(closure_sync_git_blob).hexdigest()
        != V1_SLICE_002_POST_MERGE_CLOSURE_SYNC_GIT_BLOB_SHA1
    ):
        errors.append("SLICE-V1-002 post-merge closure-sync Git blob SHA-1 changed")

    deferred_path = "docs/07-phase-evidence/SLICE-V1-002/deferred-release-register.json"
    try:
        deferred = json.loads(documents.get(deferred_path, b"{}"))
        entries = deferred["entries"]
        expected_ids = {f"S2-REL-{number:03d}" for number in range(1, 11)}
        if {entry["releaseObligationId"] for entry in entries} != expected_ids:
            errors.append("SLICE-V1-002 deferred register must retain S2-REL-001..010")
        if deferred.get("futureGate") != "RELEASE-V1-001":
            errors.append("SLICE-V1-002 deferred register future Gate changed")
        if deferred.get("productionWriteEnabled") is not False:
            errors.append("SLICE-V1-002 deferred register enabled production write")
        for entry in entries:
            for field in ("currentStatus", "releaseEvidenceState"):
                if entry.get(field) != "PRODUCTION_BLOCKING_DEFERRED_TO_RELEASE_V1_001":
                    errors.append(
                        f"SLICE-V1-002 {entry.get('releaseObligationId')} {field} is not production-blocking"
                    )
    except (KeyError, TypeError, ValueError):
        errors.append("SLICE-V1-002 deferred release register is unreadable")

    combined = "\n".join(
        content.decode("utf-8", errors="replace") for content in documents.values()
    ).lower()
    for claim in (
        "production_write_enabled: true",
        "gate_ev: authorized",
        "gate_e: authorized",
        "pilot: activated",
        "release-v1-001: activated",
        "production_readiness: production_ready",
        "deployment: authorized",
        "provider_calls: authorized",
        "terraform_apply: authorized",
        "production_database_operation: authorized",
        "real_credentials: authorized",
    ):
        if claim in combined:
            errors.append(
                "SLICE-V1-002 Formal Closure contains a prohibited release claim: "
                + claim
            )


SLICE3_R1_AUTHORITY_HASHES = {
    "docs/08-handoffs/OWNER-SLICE-V1-003-CODEX-REWORK-AUTHORIZATION-EVIDENCE.md":
        "23a2954d68abeebf87d7710f3ab749af5246cdfcbe4a3029dde73dbb34647a11",
    "docs/07-phase-evidence/SLICE-V1-003/SLICE-V1-003-FROZEN-FINDING-SET-001.md":
        "15b3c076fc7f1d283a2c7359d9647d91d3ecfccd9b229be1f734f4e7d4ceefc1",
    "docs/07-phase-evidence/SLICE-V1-003/SLICE-V1-003-FROZEN-FINDING-SET-001.json":
        "f4af74f5086772dc70c3ec3cc7aa8808e9441e96109d301b145e70c18f6131a0",
}


def validate_slice3_r1_authority(errors: list[str], documents: dict[str, bytes]) -> None:
    """R1 actor/transport comes from exact Owner evidence and one frozen set."""
    for relative, expected in SLICE3_R1_AUTHORITY_HASHES.items():
        actual = documents.get(relative)
        if actual is None or hashlib.sha256(actual).hexdigest() != expected:
            errors.append(f"SLICE-V1-003 R1 authority missing or changed: {relative}")


def validate_v1_current_state_text(
    errors: list[str],
    current_state_text: str,
    project_charter_text: str,
    slice_contract_bytes: bytes | None = None,
) -> None:
    """Validate the one live post-DR-0003 Slice authority set."""
    metadata = fenced_yaml_body(current_state_text)
    if metadata is None:
        errors.append("CURRENT_STATE must begin with one fenced YAML metadata block")
        return

    validate_slice3_r1_authority(errors, {
        relative: path.read_bytes()
        for relative in SLICE3_R1_AUTHORITY_HASHES
        if (path := ROOT / relative).is_file()
    })
    try:
        phase = validated_current_phase()
    except (OSError, ValueError, KeyError, TypeError, AttributeError, SyntaxError) as error:
        errors.append(f"SLICE-V1-003 current phase evidence is invalid: {error}")
        phase = {}
    for field, expected in {**V1_ACTIVE_STATE, **phase}.items():
        actual = unique_yaml_value(metadata, field)
        if actual != expected:
            errors.append(f"CURRENT_STATE {field} must be exactly: {expected}")

    evidence_root = ROOT / "docs/07-phase-evidence/SLICE-V1-001"
    artifacts = {name: path.read_bytes() for name in SLICE_REWORK_ARTIFACT_HASHES
                 if (path := evidence_root / "rework-r1/frozen" / name).is_file()}
    acceptance = evidence_root / "acceptance-status.md"
    validate_slice_rework_evidence_text(errors, acceptance.read_text() if acceptance.is_file() else "", artifacts)
    post_merge_documents = {}
    for relative in SLICE_POST_MERGE_DOCUMENT_REQUIREMENTS:
        path = ROOT / relative
        if path.is_file():
            post_merge_documents[relative] = path.read_text()
    validate_slice_post_merge_closure_documents(errors, post_merge_documents)
    slice_v1_002_paths = {
        *SLICE_V1_002_POST_MERGE_DOCUMENT_REQUIREMENTS,
        "docs/07-phase-evidence/SLICE-V1-002/deferred-release-register.json",
    }
    slice_v1_002_documents = {
        relative: path.read_bytes()
        for relative in slice_v1_002_paths
        if (path := ROOT / relative).is_file()
    }
    validate_slice_v1_002_post_merge_closure_documents(
        errors, slice_v1_002_documents
    )
    disposition_root = evidence_root / "rework-r1/codeql-v1.1"
    validate_codeql_disposition_artifacts(
        errors, {path.name: path.read_bytes() for path in disposition_root.glob("*") if path.is_file()}
    )
    for stale in ("No pull request exists", "Nothing was pushed", "LOCAL_CHECKPOINT_COMPLETE_UNPUBLISHED"):
        if stale in current_state_text:
            errors.append(f"CURRENT_STATE retains stale unpublished state: {stale}")

    authorization = unique_yaml_value(metadata, "authorization")
    if authorization is not None and authorization not in V1_AUTHORIZATION_ALLOWED_STATES:
        errors.append(
            "CURRENT_STATE authorization must be exactly one of: "
            + ", ".join(sorted(V1_AUTHORIZATION_ALLOWED_STATES))
        )

    if slice_contract_bytes is None:
        contract_path = ROOT / V1_ACTIVE_SLICE_CONTRACT_PATH
        if not contract_path.is_file():
            errors.append(
                "CURRENT_STATE active Slice Contract path does not exist: "
                + V1_ACTIVE_SLICE_CONTRACT_PATH
            )
            slice_contract_bytes = b""
        else:
            slice_contract_bytes = contract_path.read_bytes()
    actual_contract_sha256 = hashlib.sha256(slice_contract_bytes).hexdigest()
    recorded_contract_sha256 = unique_yaml_value(
        metadata, "active_slice_contract_sha256"
    )
    if actual_contract_sha256 != V1_ACTIVE_SLICE_CONTRACT_SHA256:
        errors.append(
            "active Slice Contract bytes changed in place; the accepted original "
            "is immutable and normative change requires an additive Amendment: "
            f"expected {V1_ACTIVE_SLICE_CONTRACT_SHA256}, found "
            f"{actual_contract_sha256}"
        )
    if recorded_contract_sha256 != actual_contract_sha256:
        errors.append(
            "CURRENT_STATE active_slice_contract_sha256 does not match the active "
            f"Slice Contract bytes: recorded {recorded_contract_sha256}, found "
            f"{actual_contract_sha256}"
        )
    binding_valid = (
        unique_yaml_value(metadata, "active_slice_contract")
        == V1_ACTIVE_SLICE_CONTRACT_PATH
        and recorded_contract_sha256 == V1_ACTIVE_SLICE_CONTRACT_SHA256
        and unique_yaml_value(
            metadata, "active_slice_contract_authorization_condition"
        )
        == V1_SLICE_AUTHORIZATION_CONDITION
        and actual_contract_sha256 == V1_ACTIVE_SLICE_CONTRACT_SHA256
    )
    if authorization == "FULL_SCOPE_IMPLEMENTATION" and not binding_valid:
        errors.append(
            "FULL_SCOPE_IMPLEMENTATION requires the exact immutable original "
            "active Slice Contract path/hash and its accepted authorization binding"
        )

    for path, expected in (
        (V1_AMENDMENT_002_PATH, V1_AMENDMENT_002_SHA256),
        (V1_AMENDMENT_002_ACCEPTANCE_PATH, V1_AMENDMENT_002_ACCEPTANCE_SHA256),
    ):
        candidate = ROOT / path
        actual = hashlib.sha256(candidate.read_bytes()).hexdigest() if candidate.is_file() else None
        if actual != expected:
            errors.append(
                f"SLICE-V1-001 accepted Amendment-002 authority hash mismatch for {path}: "
                f"expected {expected}, found {actual}"
            )

    # The active Slice's own acceptance evidence is checked the same way. An
    # authorization to implement is only as good as the exact bytes the Owner
    # accepted, and a hash recorded in one place and never re-derived is a
    # claim rather than a check.
    acceptance = ROOT / V1_ACTIVE_SLICE_ACCEPTANCE_PATH
    acceptance_sha256 = (
        hashlib.sha256(acceptance.read_bytes()).hexdigest()
        if acceptance.is_file()
        else None
    )
    if acceptance_sha256 != V1_ACTIVE_SLICE_ACCEPTANCE_SHA256:
        errors.append(
            "active Slice Owner acceptance evidence hash mismatch for "
            f"{V1_ACTIVE_SLICE_ACCEPTANCE_PATH}: expected "
            f"{V1_ACTIVE_SLICE_ACCEPTANCE_SHA256}, found {acceptance_sha256}"
        )
    recorded_blob = unique_yaml_value(metadata, "active_slice_contract_git_blob_sha1")
    if recorded_blob != V1_ACTIVE_SLICE_CONTRACT_GIT_BLOB_SHA1:
        errors.append(
            "CURRENT_STATE active_slice_contract_git_blob_sha1 must be exactly: "
            + V1_ACTIVE_SLICE_CONTRACT_GIT_BLOB_SHA1
        )

    for field, expected in {
        "conditional_design_gate": "ENABLED",
        "mandatory_design_gate_for_every_slice": "DISABLED",
        "controlled_write_enablement": "CAPABILITY_SPECIFIC_GATE_REQUIRED",
    }.items():
        if unique_yaml_value(metadata, field) != expected:
            errors.append(f"CURRENT_STATE {field} must be exactly: {expected}")

    validate_owner_control_state_text(errors, current_state_text)

    if re.search(r"(?m)^active_work_package:\s*", metadata):
        errors.append(
            "CURRENT_STATE must not retain an old Work Package as live authority"
        )

    charter_status = project_charter_status(project_charter_text)
    if charter_status != "EXECUTING_V1":
        errors.append("PROJECT_CHARTER Status must be exactly: EXECUTING_V1")

    require_contract_tokens_text(
        errors,
        "CURRENT_STATE",
        current_state_text,
        (
            "full_legacy_wp_completion: NOT_CLAIMED",
            "new_role: SHARED_SPINE_PROVENANCE",
            WP_P0_003_AUTHORIZED_HEAD,
            WP_P0_003_AUTHORIZED_TREE,
            WP_P0_003_SQUASH_COMMIT,
            "V0001–V0010 remain immutable and byte-pinned",
            "No real Ozon/WB client, credential retrieval, platform call or production write",
        ),
    )


def validate_decision_log_v1_text(errors: list[str], text: str) -> None:
    rows = re.findall(
        r"(?m)^\|\s*(D-\d{2})\s*\|\s*[^|]+\|\s*([A-Z_]+)\s*\|",
        text,
    )
    counts = Counter(decision_id for decision_id, _ in rows)
    statuses = {decision_id: status for decision_id, status in rows}
    required_statuses = {
        "D-01": "SUPERSEDED",
        "D-02": "SUPERSEDED",
        "D-10": "SUPERSEDED",
        **{f"D-{number:02d}": "ACCEPTED" for number in range(3, 10)},
        **{f"D-{number:02d}": "ACCEPTED" for number in range(15, 26)},
    }
    for decision_id, expected in required_statuses.items():
        if counts[decision_id] != 1:
            errors.append(f"Decision Log must contain {decision_id} exactly once")
        elif statuses[decision_id] != expected:
            errors.append(f"Decision Log {decision_id} must be exactly: {expected}")

    require_contract_tokens_text(
        errors,
        "Decision Log D-25",
        text,
        (
            "DR-0004-AMENDMENT-001",
            "frozen proposal-status fields are provenance only",
            "repository effect requires the accepted result on protected main",
            "No V1 Product scope change",
            "no SLICE-V1-001 scope change",
        ),
    )


def validate_owner_decisions_v1_text(errors: list[str], text: str) -> None:
    metadata = leading_yaml_body(text)
    if metadata is None:
        errors.append("Owner Decisions missing leading YAML metadata")
    else:
        for field, expected_value in {
            "repository_effect": "EFFECTIVE_WHEN_PRESENT_ON_PROTECTED_MAIN",
            "effective_condition": "PROTECTED_MAIN_MERGE_AFTER_INDEPENDENT_CONTROLLER_REVIEW_AND_OWNER_AUTHORIZATION",
        }.items():
            if unique_yaml_value(metadata, field) != expected_value:
                errors.append(
                    f"Owner Decisions {field} must be exactly: {expected_value}"
                )
    found = Counter(re.findall(r"\b(?:OD|CD)-V1-\d{3}\b", text))
    expected = {
        *(f"OD-V1-{number:03d}" for number in range(1, 25)),
        *(f"CD-V1-{number:03d}" for number in range(1, 12)),
    }
    for decision_id in sorted(expected):
        if found[decision_id] != 1:
            errors.append(f"Owner Decisions must contain {decision_id} exactly once")
    for decision_id in sorted(set(found) - expected):
        errors.append(f"Owner Decisions contains unexpected decision ID: {decision_id}")


DR0003_BACKLOG_BANNER = """> **DR-0003 status — HISTORICAL PROVENANCE ONLY**
>
> This Phase 0 Work Package backlog is preserved as the planning record that
> produced WP-P0-001/002 and the bounded WP-P0-003 evidence. It is superseded as
> active execution authority by `docs/01-requirements/V1_PRODUCT_CONTRACT.md` and
> `docs/03-work-items/V1_DELIVERY_SLICES.md`. Its rows do not authorize Design,
> implementation or production behavior. Existing WP records/evidence remain
> valid historical provenance.

---

"""


def validate_backlog_v1_text(errors: list[str], text: str) -> None:
    if not text.startswith(DR0003_BACKLOG_BANNER):
        errors.append("Phase 0 backlog missing the exact DR-0003 historical banner")
    if text.count("DR-0003 status — HISTORICAL PROVENANCE ONLY") != 1:
        errors.append("Phase 0 backlog must contain exactly one DR-0003 banner")


def validate_delivery_slices_v1_text(errors: list[str], text: str) -> None:
    metadata = leading_yaml_body(text)
    expected = {
        "document_type": "active_delivery_plan",
        "product_version": "V1",
        "delivery_model": "PRODUCTION_VERTICAL_SLICES",
        "source_contract": "docs/01-requirements/V1_PRODUCT_CONTRACT.md",
        "active_slice": "SLICE-V1-003",
        "old_phase_zero_backlog": "SUPERSEDED_AS_ACTIVE_EXECUTION_PLAN",
        "effective_condition": "PROTECTED_MAIN_MERGE_AFTER_INDEPENDENT_CONTROLLER_REVIEW_AND_OWNER_AUTHORIZATION",
    }
    if metadata is None:
        errors.append("V1 Delivery Slices missing leading YAML metadata")
    else:
        for field, value in expected.items():
            if unique_yaml_value(metadata, field) != value:
                errors.append(f"V1 Delivery Slices {field} must be exactly: {value}")
    require_contract_tokens_text(
        errors,
        "V1 Delivery Slices",
        text,
        (
            "SLICE-V1-001 — SKU Growth & Profit Diagnostic Loop",
            "CONTRACT_APPROVED_EFFECTIVE_ON_PROTECTED_MAIN",
            "SLICE-V1-002 — Stockout & Availability Risk with Accountable Response",
            "ENGINEERING_MERGED_FORMAL_CLOSURE_ACCEPTED_RELEASE_DEFERRED",
            # The narrowing is only honest while the row it replaced is still
            # readable next to it.
            "SLICE-V1-002 — Inventory & Availability Optimization",
            "SLICE-V1-003 — Advertising & Traffic Efficiency",
            "CONTRACT_ACCEPTED_FULL_SCOPE_IMPLEMENTATION",
            # The same honesty rule as above: the open-ended controlled-write
            # phrase this Contract narrowed must stay readable beside the row.
            "new controlled-write target: selected budget/bid/campaign command",
            "production enablement is separate from merge",
            "AI cannot become the Metric, Policy, Approval, Command or Credential authority",
        ),
    )


def validate_slice_v1_001_text(errors: list[str], text: str) -> None:
    metadata = leading_yaml_body(text)
    expected_metadata = {
        "document_type": "production_delivery_slice_contract",
        "slice_id": "SLICE-V1-001",
        "product_version": "V1",
        "status_after_reset_merge": "CONTRACT_APPROVED",
        "implementation_authorization_after_reset_merge": "FULL_SCOPE_IMPLEMENTATION",
        "production_enablement": "DISABLED_PENDING_FINAL_AND_CAPABILITY_GATES",
        "first_controlled_write": "PRICE_CHANGE",
        "platforms": "OZON_AND_WILDBERRIES",
    }
    if metadata is None:
        errors.append("SLICE-V1-001 missing leading YAML metadata")
    else:
        for field, value in expected_metadata.items():
            if unique_yaml_value(metadata, field) != value:
                errors.append(f"SLICE-V1-001 {field} must be exactly: {value}")

    required_headings = (
        "## 1. Observable business outcome",
        "## 3. In scope",
        "## 4. Explicit non-goals",
        "## 5. Source of truth and authority boundaries",
        "## 6. Binding invariants",
        "## 8. Production Acceptance Contract",
        "## 9. Required evidence classes",
        "## 11. Conditional Pre-Implementation Design Gate triggers",
        "## 12. Stop conditions during implementation",
        "## 14. Authorization after DR-0003 merge",
    )
    for heading in required_headings:
        if text.count(heading) != 1:
            errors.append(f"SLICE-V1-001 must contain heading exactly once: {heading}")

    acceptance_counts = Counter(re.findall(r"\bS1-AC-\d{3}\b", text))
    expected_acceptance = {f"S1-AC-{number:03d}" for number in range(1, 42)}
    for acceptance_id in sorted(expected_acceptance):
        if acceptance_counts[acceptance_id] != 1:
            errors.append(
                f"SLICE-V1-001 must contain {acceptance_id} exactly once"
            )
    for acceptance_id in sorted(set(acceptance_counts) - expected_acceptance):
        errors.append(f"SLICE-V1-001 contains unexpected acceptance ID: {acceptance_id}")

    invariant_body = h2_section_body(text, "## 6. Binding invariants") or ""
    invariant_numbers = {
        int(value) for value in re.findall(r"(?m)^(\d+)\.\s", invariant_body)
    }
    if invariant_numbers != set(range(1, 21)):
        errors.append("SLICE-V1-001 must retain binding invariants 1 through 20")

    require_contract_tokens_text(
        errors,
        "SLICE-V1-001",
        text,
        (
            "Unknown source fields/states/results remain unknown and fail closed.",
            "Timeout/unknown platform result is never blindly resubmitted",
            "Platform success is not final success until required Readback converges.",
            "Restore/compensate cannot overwrite a later legitimate external change.",
            "global and scoped Kill Switches prevent new writes",
            "AI cannot directly create/approve/execute Marketplace Commands.",
        ),
    )


def validate_capability_matrix_v1_text(errors: list[str], text: str) -> None:
    platform_rows = 0
    price_rows: set[tuple[str, str]] = set()
    for line in text.splitlines():
        cells = markdown_table_cells(line)
        if not cells or cells[0] not in {"Ozon", "WB"}:
            continue
        platform_rows += 1
        if cells[-1] != "UNVERIFIED":
            errors.append(
                "V1 Capability Matrix platform rows must start UNVERIFIED: "
                + " | ".join(cells[:2])
            )
        if len(cells) > 1 and cells[1].strip("`") == "PRICE_CHANGE":
            price_rows.add((cells[0], cells[-1]))
    if platform_rows < 2:
        errors.append("V1 Capability Matrix must contain Ozon and WB rows")
    if price_rows != {("Ozon", "UNVERIFIED"), ("WB", "UNVERIFIED")}:
        errors.append("V1 Capability Matrix requires UNVERIFIED PRICE_CHANGE for Ozon and WB")

    guessed_fact_patterns = (
        re.compile(r"https?://", re.I),
        re.compile(r"\b(?:GET|POST|PUT|PATCH|DELETE)\s+/(?:v\d+|api)/", re.I),
        re.compile(
            r"\b\d+\s*(?:requests?|req|rps|rpm)\s*(?:/|per)\s*"
            r"(?:second|minute|hour|s|m|h)\b",
            re.I,
        ),
        re.compile(r"(?m)^\s*(?:endpoint|role|quota)\s*:\s*(?!UNVERIFIED\b)\S+", re.I),
    )
    for pattern in guessed_fact_patterns:
        if pattern.search(text):
            errors.append(
                "V1 Capability Matrix contains a guessed endpoint/role/quota fact: "
                + pattern.pattern
            )


def validate_ai_execution_boundary_text(errors: list[str], text: str) -> None:
    require_contract_tokens_text(
        errors,
        "V1 AI boundary",
        text,
        (
            "AI is a core analysis and recommendation capability, not a parallel database",
            "Secret and Buyer-PII Exclusion",
            "do not send Credential, access token, signed object URL, Buyer name/phone/full",
            "Fact[]",
            "Inference[]",
            "Recommendation[]",
            "Unknown[]",
            "AI never owns the approval decision, idempotency key, Outbox writer, Marketplace",
            "deterministic Gate cannot be bypassed by model text",
        ),
    )


def validate_v1_product_contract_text(errors: list[str], text: str) -> None:
    metadata = leading_yaml_body(text)
    if metadata is None:
        errors.append("V1 Product Contract missing leading YAML metadata")
    else:
        for field, expected_value in {
            "status": "APPROVED_EFFECTIVE_ON_PROTECTED_MAIN",
            "effective_condition": "PROTECTED_MAIN_MERGE_AFTER_INDEPENDENT_CONTROLLER_REVIEW_AND_OWNER_AUTHORIZATION",
        }.items():
            if unique_yaml_value(metadata, field) != expected_value:
                errors.append(
                    f"V1 Product Contract {field} must be exactly: {expected_value}"
                )
    require_contract_tokens_text(
        errors,
        "V1 Product Contract",
        text,
        (
            "one Russian operating entity",
            "Ozon and Wildberries",
            "FBO/FBS semantics",
            "Yandex Cloud `ru-central1`",
            "external production-grade OIDC IdP + mandatory MFA",
            "Buyer PII stays outside AI and general Analytics/Mart by default",
            "each capability has a Kill Switch",
            "unknown/timeout never becomes blind retry success",
            "production write enablement is a separate Capability Gate",
            "→ Approval or bounded Owner Policy Authorization",
            "→ Idempotent Command / Outbox",
            "→ Provider State + Readback",
            "→ Audit + Restore/Compensate + Outcome Follow-up",
            "Gate EV —",
            "Gate E separately consumes that evidence",
        ),
    )


def validate_ai_operating_model_v1_text(errors: list[str], text: str) -> None:
    require_contract_tokens_text(
        errors,
        "AI Operating Model",
        text,
        (
            "## 5. Conditional Design Gate",
            "Ordinary class decomposition, SQL/index choice, package internals",
            "not trigger the Gate.",
            "Detailed Design + Initial Full Implementation",
        ),
    )


def validate_v1_traceability_text(errors: list[str], text: str) -> None:
    reader = csv.DictReader(text.splitlines())
    if reader.fieldnames != V1_TRACEABILITY_HEADER:
        errors.append(
            "v1 traceability header must be exactly: "
            + ",".join(V1_TRACEABILITY_HEADER)
        )
        return
    rows = list(reader)
    counts = Counter((row.get("source_id") or "").strip() for row in rows)
    for source_id in sorted(V1_TRACEABILITY_REQUIRED_IDS):
        if counts[source_id] != 1:
            errors.append(f"v1 traceability must contain {source_id} exactly once")
    for source_id, count in sorted(counts.items()):
        if not source_id:
            errors.append("v1 traceability contains a blank source_id")
        elif count != 1:
            errors.append(f"v1 traceability source_id is duplicated: {source_id}")

    for row in rows:
        source_id = (row.get("source_id") or "").strip()
        status = (row.get("status") or "").strip()
        if status not in V1_TRACEABILITY_STATUSES:
            errors.append(f"v1 traceability {source_id} has invalid status: {status}")
        if status == "VERIFIED" and not (
            (row.get("acceptance_or_test") or "").strip()
            and (row.get("evidence") or "").strip()
        ):
            errors.append(
                f"v1 traceability {source_id} VERIFIED requires test and evidence"
            )
        if source_id.startswith("OD-S2-") and status != "VERIFIED":
            errors.append(
                f"v1 traceability {source_id} must remain VERIFIED after exact "
                "Controller and Human Owner engineering closure"
            )
        if source_id in {"D-19", "HR-05"} and status == "VERIFIED":
            errors.append(
                f"v1 traceability {source_id} cannot verify write enablement from DR-0003"
            )
        if status == "VERIFIED" and (
            source_id in {"SLICE-V1-001", "V1"}
            or re.search(r"\bcomplete\b", (row.get("title") or ""), re.I)
        ):
            errors.append(f"v1 traceability cannot claim completion in reset: {source_id}")


def validate_open_questions_v1_text(errors: list[str], text: str) -> None:
    require_contract_tokens_text(
        errors,
        "Open Questions",
        text,
        (
            "No item below blocks DR-0003 or the start of SLICE-V1-001",
            "An external evidence item becomes a blocker only at the boundary that consumes",
            "No Secret, real Token, Buyer PII or unredacted production payload",
            "OQ-113",
            "defaults to `NONE`",
        ),
    )
    for line in text.splitlines():
        cells = markdown_table_cells(line)
        if cells and cells[0].startswith("OQ-") and len(cells) >= 4:
            if cells[3].strip("`") == "IMPLEMENTATION_START":
                errors.append(
                    f"Open Question {cells[0]} must not block Slice implementation start"
                )


def validate_dr0003_controller_review_text(errors: list[str], text: str) -> None:
    require_contract_tokens_text(
        errors,
        "DR-0003 Controller review",
        text,
        (
            "verdict: APPROVE_RESET_PACKAGE_FOR_CODEX_GOVERNANCE_EXECUTION",
            "merge_verdict: NOT_ISSUED",
            "production_enablement: NOT_AUTHORIZED",
            "It is **not** an `APPROVE_FOR_HUMAN_MERGE` verdict.",
        ),
    )
    if re.search(r"(?m)^merge_verdict:\s*(?!NOT_ISSUED\s*$)\S+", text):
        errors.append("DR-0003 Controller review cannot grant merge authorization")
    if re.search(r"(?m)^production_enablement:\s*(?!NOT_AUTHORIZED\s*$)\S+", text):
        errors.append("DR-0003 Controller review cannot authorize production")


def validate_dr0003_hash_binding_text(errors: list[str], text: str) -> None:
    require_contract_tokens_text(
        errors,
        "DR-0003 artifact hash binding",
        text,
        (
            f"reviewed_base: {DR0003_REQUIRED_BASE}",
            "next_authorized_actor: CODEX",
            "next_action: CONTENT_PRESERVING_DR_0003_GOVERNANCE_GIT_EXECUTION",
            "merge_verdict: NOT_ISSUED",
            "production_enablement: NOT_AUTHORIZED",
        ),
    )


def validate_dr0003_artifacts(errors: list[str]) -> None:
    hashes_path = ROOT / "docs/08-handoffs/DR-0003-CONTROLLER-ARTIFACT-HASHES.md"
    hashes_text = (
        hashes_path.read_text(encoding="utf-8") if hashes_path.exists() else ""
    )
    for relative, expected in DR0003_ARTIFACT_HASHES.items():
        path = ROOT / relative
        if not path.is_file():
            continue
        actual = sha256(path)
        if actual != expected:
            errors.append(
                f"DR-0003 artifact hash mismatch for {relative}: expected {expected}, found {actual}"
            )
        if relative not in hashes_text or expected not in hashes_text:
            errors.append(f"DR-0003 artifact hash binding missing for: {relative}")

    validate_dr0003_hash_binding_text(errors, hashes_text)
    review_path = ROOT / next(iter(DR0003_ARTIFACT_HASHES))
    if review_path.exists():
        validate_dr0003_controller_review_text(
            errors, review_path.read_text(encoding="utf-8")
        )


def validate_dr0003_r1_whitespace_exception(
    errors: list[str],
    attributes_text: str,
    *,
    root: Path = ROOT,
    required_files: set[str] | None = None,
) -> None:
    """Bind the sole whitespace exception to the real, required R1 review file."""
    whitespace_exception = f"{DR0003_R1_REVIEW_RELATIVE_PATH} -whitespace"
    whitespace_rules: list[str] = []
    for line in attributes_text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "-whitespace" in stripped.split():
            whitespace_rules.append(line)

    if whitespace_rules != [whitespace_exception]:
        errors.append(
            "DR-0003 R1 CommonMark whitespace exception must be exact and singular"
        )

    inventory = (
        set(DR0003_REQUIRED_FILES) if required_files is None else required_files
    )
    if DR0003_R1_REVIEW_RELATIVE_PATH not in inventory:
        errors.append(
            "DR-0003 R1 whitespace exception target must be a required file: "
            f"{DR0003_R1_REVIEW_RELATIVE_PATH}"
        )
    if not (root / DR0003_R1_REVIEW_RELATIVE_PATH).is_file():
        errors.append(
            "DR-0003 R1 whitespace exception target does not exist: "
            f"{DR0003_R1_REVIEW_RELATIVE_PATH}"
        )


def validate_dr0003_r1_artifacts(errors: list[str]) -> None:
    """Pin the independent R1 finding ledger and its targeted rework authority."""
    manifest_path = ROOT / DR0003_R1_MANIFEST_RELATIVE_PATH
    manifest_text = (
        manifest_path.read_text(encoding="utf-8") if manifest_path.exists() else ""
    )
    attributes_path = ROOT / ".gitattributes"
    attributes_text = (
        attributes_path.read_text(encoding="utf-8")
        if attributes_path.exists()
        else ""
    )
    validate_dr0003_r1_whitespace_exception(errors, attributes_text)
    for relative, expected in DR0003_R1_ARTIFACT_HASHES.items():
        path = ROOT / relative
        if not path.is_file():
            continue
        actual = sha256(path)
        if actual != expected:
            errors.append(
                f"DR-0003 R1 artifact hash mismatch for {relative}: expected {expected}, found {actual}"
            )
        if relative != DR0003_R1_MANIFEST_RELATIVE_PATH and (
            relative not in manifest_text or expected not in manifest_text
        ):
            errors.append(f"DR-0003 R1 artifact hash binding missing for: {relative}")

    require_contract_tokens_text(
        errors,
        "DR-0003 R1 artifact hash binding",
        manifest_text,
        (
            f"reviewed_base: {DR0003_REQUIRED_BASE}",
            "reviewed_head: d933bd91cd7396999776e157cb3cf9223d888c34",
            "reviewed_head_tree: 83fd24cf57d75f1931e9c705f965552a8a2e6e60",
            "controller_verdict: CHANGES_REQUIRED",
            "merge_authorization: NOT_GRANTED",
            "production_enablement: NOT_AUTHORIZED",
            "next_action: DR_0003_PR18_TARGETED_GOVERNANCE_REWORK_R1",
        ),
    )

    review_path = ROOT / DR0003_R1_REVIEW_RELATIVE_PATH
    if review_path.exists():
        review = review_path.read_text(encoding="utf-8")
        require_contract_tokens_text(
            errors,
            "DR-0003 R1 Controller review",
            review,
            (
                "reviewed_head: d933bd91cd7396999776e157cb3cf9223d888c34",
                "controller_verdict: CHANGES_REQUIRED",
                "Four `MAJOR` findings remain.",
                "merge_authorization: NOT_GRANTED",
                "production_enablement: NOT_AUTHORIZED",
                "NEXT_ACTION: DR_0003_PR18_TARGETED_GOVERNANCE_REWORK_R1",
            ),
        )

    prompt_path = ROOT / DR0003_R1_PROMPT_RELATIVE_PATH
    if prompt_path.exists():
        prompt = prompt_path.read_text(encoding="utf-8")
        require_contract_tokens_text(
            errors,
            "DR-0003 R1 targeted rework prompt",
            prompt,
            (
                "controller_reviewed_starting_head: d933bd91cd7396999776e157cb3cf9223d888c34",
                "authorization: TARGETED_GOVERNANCE_REWORK_ONLY",
                "requested_next_verdict: INDEPENDENT_DR_0003_RESET_PR_RE_REVIEW",
                "Do not mark Ready and do not merge.",
            ),
        )


def validate_dr0003_r2_artifacts(errors: list[str]) -> None:
    """Pin the Controller R2 finding and the bounded F05 rework authority."""
    manifest_path = ROOT / DR0003_R2_MANIFEST_RELATIVE_PATH
    manifest_text = (
        manifest_path.read_text(encoding="utf-8") if manifest_path.exists() else ""
    )
    for relative, expected in DR0003_R2_ARTIFACT_HASHES.items():
        path = ROOT / relative
        if not path.is_file():
            continue
        actual = sha256(path)
        if actual != expected:
            errors.append(
                f"DR-0003 R2 artifact hash mismatch for {relative}: expected {expected}, found {actual}"
            )
        if relative != DR0003_R2_MANIFEST_RELATIVE_PATH and (
            relative not in manifest_text or expected not in manifest_text
        ):
            errors.append(f"DR-0003 R2 artifact hash binding missing for: {relative}")

    require_contract_tokens_text(
        errors,
        "DR-0003 R2 artifact hash binding",
        manifest_text,
        (
            f"reviewed_base: {DR0003_REQUIRED_BASE}",
            "reviewed_head: 37e04f7f02de8a52f0c8fd026724ec2dbaf99d60",
            "reviewed_head_tree: 89b1caccf390f6abd9f1a30f8ff268f5091166da",
            "controller_verdict: CHANGES_REQUIRED",
            "merge_authorization: NOT_GRANTED",
            "production_enablement: NOT_AUTHORIZED",
            "next_action: DR_0003_PR18_WHITESPACE_ATTRIBUTE_PATH_TARGETED_REWORK_R2",
        ),
    )

    review_path = ROOT / DR0003_R2_REVIEW_RELATIVE_PATH
    if review_path.exists():
        review = review_path.read_text(encoding="utf-8")
        require_contract_tokens_text(
            errors,
            "DR-0003 R2 Controller review",
            review,
            (
                "reviewed_head: 37e04f7f02de8a52f0c8fd026724ec2dbaf99d60",
                "controller_verdict: CHANGES_REQUIRED",
                "DR3-PR18-F05 — MAJOR",
                "merge_authorization: NOT_GRANTED",
                "production_enablement: NOT_AUTHORIZED",
                "NEXT_ACTION: DR_0003_PR18_WHITESPACE_ATTRIBUTE_PATH_TARGETED_REWORK_R2",
            ),
        )

    prompt_path = ROOT / DR0003_R2_PROMPT_RELATIVE_PATH
    if prompt_path.exists():
        prompt = prompt_path.read_text(encoding="utf-8")
        require_contract_tokens_text(
            errors,
            "DR-0003 R2 targeted rework prompt",
            prompt,
            (
                "controller_reviewed_starting_head: 37e04f7f02de8a52f0c8fd026724ec2dbaf99d60",
                "authorization: TARGETED_GOVERNANCE_REWORK_ONLY",
                "finding: DR3-PR18-F05",
                "requested_next_verdict: INDEPENDENT_DR_0003_RESET_PR_FINAL_RE_REVIEW",
                "Do not mark Ready, self-approve or merge.",
            ),
        )


def validate_historical_provenance_hashes(errors: list[str]) -> None:
    for relative, expected in HISTORICAL_PROVENANCE_HASHES.items():
        path = ROOT / relative
        if not path.is_file():
            continue
        actual = sha256(path)
        if actual != expected:
            errors.append(
                f"historical provenance changed: {relative}; expected {expected}, found {actual}"
            )
    for relative, expected in HISTORICAL_EVIDENCE_TREE_HASHES.items():
        path = ROOT / relative
        if not path.is_dir():
            continue
        actual = directory_tree_sha256(path)
        if actual != expected:
            errors.append(
                f"historical evidence tree changed: {relative}; expected {expected}, found {actual}"
            )


def validate_owner_git_workflow_guidance_v2(errors: list[str]) -> None:
    guide_path = ROOT / "docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md"
    if not guide_path.exists():
        return
    guide = guide_path.read_text(encoding="utf-8")
    require_contract_tokens_text(
        errors,
        "Owner Git workflow guide",
        guide,
        (
            "state_source: docs/00-governance/CURRENT_STATE.md#owner_git_workflow_guidance",
            "supported_states: REQUIRED | DISABLED",
            "exit_authority: Human Owner explicit confirmation only",
            "sync main → create/reuse Slice/task branch",
            "Human Owner-authorized merge execution",
            "separate Gate-E production/capability enablement",
            "D-17 mechanical delegation",
        ),
    )
    if re.search(r"(?m)^status:\s*(?:REQUIRED|DISABLED)\s*$", guide):
        errors.append(
            "Owner Git workflow guide must not duplicate runtime state; CURRENT_STATE is canonical"
        )
    instruction_requirements = {
        "AGENTS.md": ("OWNER_GIT_WORKFLOW_GUIDE.md",),
        "CLAUDE.md": ("Owner Git Workflow Guidance", "guide"),
        "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md": (
            "OWNER_GIT_WORKFLOW_GUIDE.md",
        ),
        "docs/00-governance/CLAUDE_PROJECT_INSTRUCTIONS.md": (
            "OWNER_GIT_WORKFLOW_GUIDE.md",
        ),
    }
    for relative, tokens in instruction_requirements.items():
        path = ROOT / relative
        if not path.exists():
            continue
        instruction_text = path.read_text(encoding="utf-8")
        for token in tokens:
            if token not in instruction_text:
                errors.append(
                    f"agent instruction does not load Owner Git guidance: {relative}: {token}"
                )


def validate_controller_review_standard_v2(errors: list[str]) -> None:
    standard_path = ROOT / "docs/00-governance/CONTROLLER_REVIEW_STANDARD.md"
    instructions_path = ROOT / "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md"
    if not standard_path.exists() or not instructions_path.exists():
        return
    standard = standard_path.read_text(encoding="utf-8")
    require_contract_tokens_text(
        errors,
        "Controller Review Standard v2",
        standard,
        (
            "## 2. Mandatory review dimensions",
            "## 3. Source-first rule",
            "## 4. Finding contract",
            "## 9. Artifact contract",
            "Development Baseline Reset / Decision Request",
            "Product or Slice Contract Gate",
            "Implementation Deep Review",
            "Final PR Gate",
            "Bounded Real-Write Verification Authorization",
            "Controlled Capability Enablement",
            "V1 Product Complete Gate",
            "SHA-256",
            "NEXT_AUTHORIZED_ACTOR",
            "NEXT_ACTION",
            "Use readable Chinese by default.",
        ),
    )
    instructions = instructions_path.read_text(encoding="utf-8")
    for token in ("CONTROLLER_REVIEW_STANDARD.md", "Apply"):
        if token not in instructions:
            errors.append(
                f"ChatGPT Project Instructions do not load Controller Review Standard v2: {token}"
            )


def validate_dr0004_artifact_hashes(
    errors: list[str], *, root: Path = ROOT
) -> None:
    """Pin the exact Owner-accepted DR-0004 normative bytes and frozen Contracts."""
    for relative, expected in {
        **DR0004_ARTIFACT_HASHES,
        **DR0004_R1_ARTIFACT_HASHES,
        **DR0004_PROTECTED_CONTRACT_HASHES,
    }.items():
        path = root / relative
        if not path.is_file():
            errors.append(f"DR-0004 hash target missing: {relative}")
            continue
        actual = sha256(path)
        if actual != expected:
            errors.append(
                f"DR-0004 immutable hash mismatch for {relative}: "
                f"expected {expected}, found {actual}"
            )


def validate_dr0004_protocol_texts(
    errors: list[str], documents: dict[str, str]
) -> None:
    """Reject regression of the DR-0004 execution and closure protocol."""
    current = documents.get("current", "")
    metadata = fenced_yaml_body(current)
    if metadata is None:
        errors.append("DR-0004 CURRENT_STATE requires leading YAML metadata")
    else:
        for field, expected in DR0004_CURRENT_STATE.items():
            actual = unique_yaml_value(metadata, field)
            if actual != expected:
                errors.append(
                    f"DR-0004 CURRENT_STATE {field} must be exactly: {expected}"
                )

    requirements = {
        "dr0004": (
            f"required_base: {DR0004_REQUIRED_BASE}",
            f"required_base_tree: {DR0004_REQUIRED_BASE_TREE}",
            f"active_slice_contract_sha256: {V1_SLICE_001_CONTRACT_SHA256}",
            "change_class: GOVERNANCE_ONLY",
            "active_slice_contract_change: PROHIBITED",
            "production_enablement: NOT_AUTHORIZED",
            "D4-AC-001",
            "D4-AC-012",
        ),
        "envelope": (
            "policy_id: EXECUTION_ENVELOPE_V1",
            "remote_git_default: DENY",
            "production_side_effect_default: DENY",
            "## Level 1 — default local implementation authority",
            "## Level 2 — explicit Contract pre-authorization",
            "## Level 3 — dedicated authority required",
            "It may not reconstruct or improve the implementation.",
            "Gate EV and Gate E remain separate",
        ),
        "closure_standard": (
            "standard_id: CLOSURE_SNAPSHOT_V1",
            "owner_formal_closure_required: true",
            "engineering_review_gate: false",
            "Controller Deep Review and Frozen Finding Set SHA-256",
            "## Owner Formal Closure",
            "The next Slice starts from",
        ),
        "current": (
            "The accepted original Contract is permanently byte-frozen.",
            "proposal-time provenance only, not live\nrepository-effect state",
            "A proposal branch does\nnot activate repository authority.",
            DR0004_AMENDMENT_RELATIVE_PATH,
            DR0004_OWNER_ACCEPTANCE_RELATIVE_PATH,
            "exact local commit/tree and evidence",
            "one-shot discovery/falsification",
            "CONTROLLER_REVIEW_COVERAGE_FAILURE",
            "Final Gate is closure verification",
            "Owner-accepted Closure Snapshot is required before the next Slice",
            "Normative Truth is Owner Decision",
            "Implementation\nFact is runtime/DB/external evidence",
            "IMPLEMENTATION_DEFECT",
            "CONTRACT_DEFECT",
            "DOCUMENTATION_DRIFT",
        ),
        "change": (
            "An accepted original Product or Slice Contract is permanently byte-frozen.",
            "Original Contract + Accepted\nAmendment(s)",
            "Editing the\noriginal in place and coordinating a new hash is prohibited.",
            "may not accumulate into hidden scope, authority, risk or\nAcceptance expansion",
        ),
        "claude_project": (
            "Ordinary Claude authority is Level 1 plus only a Level 2 envelope explicitly",
            "It does not include `git push`, remote branch/tag",
            "exact local-checkpoint package for remote publication",
        ),
        "claude": (
            "Ordinary authority is local Level 1 plus only a Level 2 envelope explicitly",
            "Do not push, mutate a remote branch/tag, create or\nupdate a PR",
            "Claude does not perform ordinary remote Git publication",
        ),
        "operating": (
            "immutable original Contract plus accepted Amendments and the accepted Execution\n  Envelope",
            "exact remote publication to Draft PR without reconstruction",
            "one-shot Deep Review + SHA-256-bound Frozen Finding Set",
            "CONTROLLER_FINAL_CLOSURE_VERIFICATION",
            "Owner Formal Closure follows Controller Slice Closure",
            "IMPLEMENTATION_DEFECT",
            "CONTRACT_DEFECT",
            "DOCUMENTATION_DRIFT",
        ),
        "review_standard": (
            "## 7. One-shot Deep Review and Frozen Finding Set",
            "CONTROLLER_REVIEW_COVERAGE_FAILURE",
            "## 8. Final Gate is closure verification",
            "It is not an open-ended second discovery review.",
            "materially\nnew, previously unavailable severe evidence",
            "## 13. Controller Slice Closure and Owner Formal Closure",
        ),
        "quality": (
            "editing an accepted original Contract in\n  place is prohibited",
            "one complete Frozen Finding Set artifact",
            "CONTROLLER_REVIEW_COVERAGE_FAILURE",
            "Gate F is closure verification",
            "not an open-ended second discovery review",
            "Closure Snapshot conforming to",
            "Docs\nremain part of Definition of Done",
        ),
        "handoff": (
            "Claude's ordinary authority ends at an exact local commit/tree",
            "Under a dedicated Level-3 Remote Publication authority",
            "It may not reconstruct, redesign or improve the\nimplementation during publication.",
            "one complete severity-labeled Frozen Finding Set",
            "CONTROLLER_REVIEW_COVERAGE_FAILURE",
            "It is not a third engineering review.",
            "before the next Slice starts",
        ),
        "source": (
            "## DR-0004 effective-source binding",
            DR0004_ARTIFACT_HASHES[DR0004_DR_RELATIVE_PATH],
            DR0004_R1_ARTIFACT_HASHES[DR0004_AMENDMENT_RELATIVE_PATH],
            DR0004_R1_ARTIFACT_HASHES[DR0004_OWNER_ACCEPTANCE_RELATIVE_PATH],
            "proposal-time provenance only",
            "ACTIVE_ON_PROTECTED_MAIN",
            "A proposal branch is not active\nrepository authority.",
            "## Dual truth and conflict order",
            "Normative Truth is ordered as:",
            "Implementation Fact is ordered as:",
            "IMPLEMENTATION_DEFECT",
            "CONTRACT_DEFECT",
            "DOCUMENTATION_DRIFT",
            "cannot\naccumulate into hidden normative expansion",
        ),
        "guide": (
            "Codex exact remote publication",
            "one GPT Deep Review + Frozen Finding Set",
            "The delegate may not reconstruct or\nimprove the checkpoint.",
            "Final Gate is closure verification",
            "Owner-accepted Closure Snapshot is required before the next Slice",
        ),
        "agents": (
            "Original Contract + Accepted Amendments + Frozen Finding Set",
            "Remote Git publication is separate Level-3 transport authority.",
            "must not\nreconstruct or improve it during transport",
        ),
        "chatgpt_project": (
            "CONTROLLER_REVIEW_COVERAGE_FAILURE",
            "Final Gate as closure verification",
            "Owner Formal Closure",
            "Owner-accepted Closure Snapshot before the next Slice",
        ),
        "contributing": (
            "accepted original Contract is byte-frozen",
            "freezes one SHA-256-bound Finding Set",
            "Final Gate verifies closure",
            "Owner-accepted Closure Snapshot is required before the next Slice",
        ),
        "readme": (
            "exact Owner-accepted\n`DR-0004-AMENDMENT-001-activation-and-owner-acceptance-provenance.md`",
            "durable\nOwner acceptance evidence",
            "when the accepted result is on protected `main`",
            "Codex exact remote publication to Draft PR",
            "one GPT Controller Deep Review + Frozen Finding Set",
            "Controller Slice Closure → Owner Formal Closure",
            "mandatory Closure Snapshot → next Slice",
        ),
        "start": (
            "exact local checkpoint",
            "Codex exact remote publication → Draft PR and CI",
            "Frozen Finding Set SHA-256",
            "Closure Snapshot → next Slice",
        ),
        "pr_template": (
            "Immutable original Contract path / SHA-256",
            "Accepted Amendment paths / SHA-256",
            "Frozen Finding Set path / SHA-256",
            "Closure Snapshot path / SHA-256",
        ),
        "decision": (
            "| D-25 |",
            "DR-0004-AMENDMENT-001",
            "frozen proposal-status fields are provenance only",
            "No V1 Product scope change",
            "no SLICE-V1-001 scope change",
        ),
        "v1_traceability": (
            "D-25,Owner Decision,V1,DR-0004 engineering execution and closure protocol",
            "DR-0004;DR-0004-AMENDMENT-001",
            "No V1 Product scope change and no SLICE-V1-001 scope change",
        ),
    }
    for name, tokens in requirements.items():
        if name in documents:
            require_contract_tokens_text(
                errors, f"DR-0004 {name}", documents[name], tokens
            )

    claude_authority = re.compile(
        r"(?im)^\s*(?:[-*]\s*)?Claude\s+"
        r"(?:may|can|is authorized to)\s+.*"
        r"(?:git\s+push|push\b|remote\s+branch|create\s+(?:a\s+)?PR|update\s+(?:a\s+)?PR)"
    )
    for name in ("claude", "claude_project"):
        text = documents.get(name, "")
        if claude_authority.search(text):
            errors.append(
                f"DR-0004 {name} grants prohibited ordinary Claude remote Git authority"
            )

    maker_responsibilities = re.findall(
        r"(?m)^- performs Detailed Design(?: \+| and) Initial Full Implementation",
        documents.get("operating", ""),
    )
    if len(maker_responsibilities) != 1:
        errors.append(
            "DR-0004 operating must contain exactly one Claude implementation "
            "responsibility bound to Contract/Amendments and Execution Envelope"
        )


def validate_dr0004_governance(errors: list[str]) -> None:
    paths = {
        "dr0004": ROOT / DR0004_DR_RELATIVE_PATH,
        "envelope": ROOT / DR0004_EXECUTION_ENVELOPE_RELATIVE_PATH,
        "closure_standard": ROOT / DR0004_CLOSURE_STANDARD_RELATIVE_PATH,
        "current": ROOT / "docs/00-governance/CURRENT_STATE.md",
        "change": ROOT / "docs/00-governance/CHANGE_CONTROL.md",
        "claude_project": ROOT / "docs/00-governance/CLAUDE_PROJECT_INSTRUCTIONS.md",
        "claude": ROOT / "CLAUDE.md",
        "operating": ROOT / "docs/00-governance/AI_OPERATING_MODEL.md",
        "review_standard": ROOT / "docs/00-governance/CONTROLLER_REVIEW_STANDARD.md",
        "quality": ROOT / "docs/00-governance/QUALITY_GATES.md",
        "handoff": ROOT / "docs/00-governance/HANDOFF_PROTOCOL.md",
        "source": ROOT / "docs/01-requirements/SOURCE_MANIFEST.md",
        "guide": ROOT / "docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md",
        "agents": ROOT / "AGENTS.md",
        "chatgpt_project": ROOT / "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md",
        "contributing": ROOT / "CONTRIBUTING.md",
        "readme": ROOT / "README.md",
        "start": ROOT / "START_HERE.md",
        "pr_template": ROOT / ".github/pull_request_template.md",
        "decision": ROOT / "docs/00-governance/DECISION_LOG.md",
        "v1_traceability": ROOT / "docs/01-requirements/v1-traceability.csv",
    }
    documents = {
        name: path.read_text(encoding="utf-8-sig")
        for name, path in paths.items()
        if path.is_file()
    }
    validate_dr0004_artifact_hashes(errors)
    validate_dr0004_protocol_texts(errors, documents)


def validate_v1_governance(errors: list[str]) -> None:
    paths = {
        "current": ROOT / "docs/00-governance/CURRENT_STATE.md",
        "charter": ROOT / "docs/00-governance/PROJECT_CHARTER.md",
        "decisions": ROOT / "docs/00-governance/DECISION_LOG.md",
        "owner": ROOT / "docs/00-governance/OWNER_DECISIONS_V1.md",
        "dr": ROOT / "docs/00-governance/DR-0003-v1-product-delivery-baseline-reset.md",
        "source": ROOT / "docs/01-requirements/SOURCE_MANIFEST.md",
        "product": ROOT / "docs/01-requirements/V1_PRODUCT_CONTRACT.md",
        "backlog": ROOT / "docs/03-work-items/BACKLOG-PHASE-0.md",
        "slices": ROOT / "docs/03-work-items/V1_DELIVERY_SLICES.md",
        "slice": ROOT / "docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md",
        "capability": ROOT / "docs/04-api/V1_CAPABILITY_MATRIX.md",
        "ai": ROOT / "docs/02-architecture/V1_AI_DATA_AND_EXECUTION_BOUNDARY.md",
        "operating": ROOT / "docs/00-governance/AI_OPERATING_MODEL.md",
        "questions": ROOT / "docs/00-governance/OPEN_QUESTIONS.md",
        "traceability": ROOT / "docs/01-requirements/v1-traceability.csv",
        "review_standard": ROOT / "docs/00-governance/CONTROLLER_REVIEW_STANDARD.md",
        "quality": ROOT / "docs/00-governance/QUALITY_GATES.md",
        "assurance": ROOT / "docs/05-testing/V1_PRODUCTION_ASSURANCE_MATRIX.md",
        "guide": ROOT / "docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md",
        "handoff": ROOT / "docs/00-governance/HANDOFF_PROTOCOL.md",
        "pr_template": ROOT / ".github/pull_request_template.md",
        "agents": ROOT / "AGENTS.md",
        "claude": ROOT / "CLAUDE.md",
        "claude_project": ROOT / "docs/00-governance/CLAUDE_PROJECT_INSTRUCTIONS.md",
        "chatgpt_project": ROOT / "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md",
    }
    texts = {
        name: path.read_text(encoding="utf-8-sig")
        for name, path in paths.items()
        if path.exists()
    }
    if "current" in texts and "charter" in texts:
        validate_v1_current_state_text(errors, texts["current"], texts["charter"])
    if "decisions" in texts:
        validate_decision_log_v1_text(errors, texts["decisions"])
    if "owner" in texts:
        validate_owner_decisions_v1_text(errors, texts["owner"])
    if "backlog" in texts:
        validate_backlog_v1_text(errors, texts["backlog"])
    if "slices" in texts:
        validate_delivery_slices_v1_text(errors, texts["slices"])
    if "slice" in texts:
        validate_slice_v1_001_text(errors, texts["slice"])
    if "capability" in texts:
        validate_capability_matrix_v1_text(errors, texts["capability"])
    if "ai" in texts:
        validate_ai_execution_boundary_text(errors, texts["ai"])
    if "traceability" in texts:
        validate_v1_traceability_text(errors, texts["traceability"])
    if "questions" in texts:
        validate_open_questions_v1_text(errors, texts["questions"])

    if "dr" in texts:
        require_contract_tokens_text(
            errors,
            "DR-0003",
            texts["dr"],
            (
                "status: ACCEPTED_EFFECTIVE_ON_PROTECTED_MAIN",
                f"reviewed_repository_base: {DR0003_REQUIRED_BASE}",
                "effective_condition: PROTECTED_MAIN_MERGE_AFTER_INDEPENDENT_CONTROLLER_REVIEW_AND_OWNER_AUTHORIZATION",
                "migration_effect: NONE",
                "production_write_effect: NONE",
                "APPROVE_RESET_PACKAGE_FOR_CODEX_GOVERNANCE_EXECUTION",
            ),
        )
    if "source" in texts:
        require_contract_tokens_text(
            errors,
            "Source Manifest",
            texts["source"],
            (
                "# Source Manifest and V1 Precedence",
                "Highest authority for V1 supersession and delivery model",
                "unchanged Requirement IDs, NFRs and hard rules",
                "A superseded Phase/WP allocation remains historical provenance",
                "does\nnot create a second active finding taxonomy",
                "BLOCKER / MAJOR / MINOR /\nINFORMATIONAL",
            ),
        )
    if "product" in texts:
        validate_v1_product_contract_text(errors, texts["product"])
    if "operating" in texts:
        validate_ai_operating_model_v1_text(errors, texts["operating"])

    validate_finding_vocabulary_texts(errors, texts)
    validate_v1_authority_effect_texts(errors, texts)
    validate_gate_ev_contract_texts(errors, texts)

    validate_historical_provenance_hashes(errors)
    validate_dr0003_artifacts(errors)
    validate_dr0003_r1_artifacts(errors)
    validate_dr0003_r2_artifacts(errors)


def git_scan_paths(root: Path = ROOT) -> list[Path]:
    """Return tracked and new candidate paths, excluding ignored build outputs."""
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(detail or "git ls-files failed")
    return [root / item.decode("utf-8") for item in result.stdout.split(b"\0") if item]


def validate_common_secrets(errors: list[str]) -> None:
    excluded = {
        ROOT / "scripts/validate_governance.py",
    }
    try:
        paths = git_scan_paths()
    except RuntimeError as error:
        errors.append(f"cannot enumerate tracked files for secret scan: {error}")
        return
    for path in paths:
        if not path.is_file() or path in excluded:
            continue
        if path.suffix.lower() not in SCAN_EXTENSIONS and path.name != ".env":
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for pattern in SECRET_PATTERNS:
            if pattern.search(text):
                errors.append(f"possible secret pattern in {path.relative_to(ROOT)}: {pattern.pattern}")
                break


REQUIRED_STATUS_CONTEXTS = {
    "governance",
    "backend-build",
    "architecture-boundary",
    "backend-integration",
    "frontend-lint",
    "frontend-typecheck",
    "frontend-test",
    "frontend-build",
    "dependency-review",
    "codeql-java",
    "codeql-typescript",
    "infrastructure-validation",
}


def validate_required_status_inventory(
    errors: list[str], inventory: dict[str, object] | None = None
) -> None:
    """Bind the protected-main inventory to every stable CI context."""
    path = ROOT / ".github/required-status-checks.json"
    if inventory is None:
        try:
            inventory = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            errors.append(f"required status inventory is unreadable: {error}")
            return
    contexts = inventory.get("requiredContexts")
    if not isinstance(contexts, list) or set(contexts) != REQUIRED_STATUS_CONTEXTS:
        errors.append(
            "required status inventory must contain the exact stable context set, "
            "including infrastructure-validation"
        )
    if inventory.get("strictRequiredStatusChecksPolicy") is not True:
        errors.append("required status inventory must preserve strict branch currency")
    workflow = ROOT / ".github/workflows/infrastructure.yml"
    try:
        workflow_text = workflow.read_text(encoding="utf-8")
    except OSError as error:
        errors.append(f"infrastructure workflow is unreadable: {error}")
        return
    if "infrastructure-validation:" not in workflow_text or (
        "name: infrastructure-validation" not in workflow_text
    ):
        errors.append("infrastructure-validation must remain a stable workflow context")


def main() -> int:
    errors: list[str] = []
    validate_parallel_current_state_paths(
        errors,
        {
            relative
            for relative in PARALLEL_CURRENT_STATE_PATHS
            if (ROOT / relative).exists()
        },
    )
    validate_required_files(errors)
    validate_source_checksums(errors)
    validate_work_package(errors)
    validate_v1_governance(errors)
    validate_dr0004_governance(errors)
    validate_owner_git_workflow_guidance_v2(errors)
    validate_readme_runtime_state(errors)
    validate_controller_review_standard_v2(errors)
    validate_traceability(errors)
    validate_required_status_inventory(errors)
    validate_common_secrets(errors)

    if errors:
        for error in errors:
            fail(error)
        print(f"Governance validation failed with {len(errors)} error(s).", file=sys.stderr)
        return 1

    print("Governance validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

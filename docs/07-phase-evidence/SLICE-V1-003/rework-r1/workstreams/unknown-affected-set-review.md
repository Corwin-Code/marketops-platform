# Unknown affected-set narrow diff review

Bounded read-only review: no remaining defect identified in these changed paths and immediate authority consumers. This is not runtime acceptance or a Controller verdict; R37/W4 execution remains required.

## NARROW_NULL_PERSISTENCE

V0038:250–252 permits null only DATA_REPAIR + AFFECTED_SET_UNRESOLVED + AFFECTED_SET_NEVER_RESOLVED. lane/cause/blocker array are NOT NULL; existing ad_case_blockers_ck rejects NULL array elements, so the ANY condition cannot use SQL UNKNOWN to bypass the check. Other lane/cause and referenced foreign keys remain constrained.



## UNKNOWN_IS_NOT_ZERO_IMPACT

The absent set remains null digest and UNRESOLVED. SQL returns an empty known-members list/count 0 for iteration, not a complete set or financial zero; financial value-state constraints are unchanged. Current frontend does not consume affectedVariantCount numerically, labels the section Affected set, explicitly says full impact is unknown and no complete membership is established.

The numeric 0 field is known structural member count only; it must not be reused as a known full-impact count. No code in this reviewed path treats it as a complete set.

## NO_GENERIC_PERMISSION_FALLBACK

TaskGovernance left join now retains the ad_case_responsibility context even when set is missing. require returns true after actual organization and ADVERTISING_VIEW/ADVERTISING_TASK_ACT Store checks, and scoped role check for mutation. WorkTaskService only uses generic DIAGNOSTIC_VIEW/TASK_ASSIGN when no advertising context exists. Workflow likewise explicitly requires ADVERTISING_VIEW Store access.



## EXACT_HISTORICAL_NULL_DOES_NOT_BORROW_FUTURE

caseObjectScope calls resolveObjectScope with allowLatest=false. A null digest then matches no affected-set row, while the outer object remains available via LEFT JOIN. caseView full disclosure requires non-null exact digest and COMPLETE nonempty full evidence scope. Three-argument mayReadDecisionEvidence returns false for null; the two-argument method retains separate current-object semantics.



## MISSING_SET_CANNOT_OBTAIN_WRITE_AUTHORITY

Proposal requires a resolved affected set and direction eligibility; HumanDecision uses exact digest overload. V0058 ad_actor_covers_affected_set uses EXISTS on the exact nonempty COMPLETE set, so null set returns false. Seal binds baseline affected_set_id exactly to Case and baseline V0059 affected_set_id/digest are NOT NULL. Existing manual packet/reservation/command exact-set constraints are unchanged.

Workflow allowedActions are affordances only; actual candidate/approval/command sinks retain their independent complete-scope checks. This review does not newly execute a crafted app-role direct seal attack.

## EXCEPTION_INVALIDATION

The invalidation query retains a Case with no set through LEFT JOIN, and IS DISTINCT FROM invalidates missing digest or missing COMPLETE state instead of SQL NULL silently skipping invalidation.



## REGRESSION_TEST_ASSERTIONS

AdvertisingEfficiencyFlowIT.futureAffectedSetRemainsUnknownWithAGovernedRepairTask uses actual refresh of object with only future set, checks DATA_REPAIR/NEVER_RESOLVED/null/no candidate/exact Task context, advertising permission deny→grant→revoke without journal fabrication, workflow no candidate, wrong-cause CHECK 23514 and no Command. AdvertisingDisclosureIT.anUnknownHistoricalCaseIsReadableWithoutBorrowingFutureMembershipOrGenericPermission uses an explicit historical projection fixture, real HTTP/identity, null masked structure, generic current positive vs exact null negative and permission revocation 403. Frontend test requires UNRESOLVED/unknown text/no members/no Complete heading.

Second PG method is a read projection oracle; classification persistence is proved by the first PG method. It must not be described as a second full calculation journey.

All V0001–V0035 bytes were compared against protected Base and current committed Head and remain identical. Accepted Slice 3 Contract and both Frozen representations match their immutable SHA-256 pins. No repository file was changed and no test was executed by this review.

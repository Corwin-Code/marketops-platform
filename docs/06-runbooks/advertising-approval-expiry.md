# Advertising approval expiry

A command reports `APPROVAL_LEASE_EXPIRED` or `SEALED_AUTHORIZATION_MISSING_OR_EXPIRED`. The final approval is sealed to the selected recommendation, candidate, complete affected set, endorsed Bundle, three human identities and the baseline selected before approval.

The frozen expiry is the minimum of the final-approval lease, material lease, recommendation validity, Owner scope limit, referenced policy periods, required purpose evidence, frozen Outcome baseline, exact Gate window, credential authority and current approval/endorsement grants. Waiting, retrying or rotating a credential cannot extend it. `ops.expire_ad_action_authority` appends an invalidation; it does not edit historical approval or transmit anything.

1. Read the scoped case, current blockers, task age and command history in the console. Distinguish never-sent work from transmitted, pending or unknown work.
2. Refresh the evidence and prepare a new candidate/recommendation generation when the cause still holds. Preserve original demand age and prior decisions.
3. Repeat Marketplace Operator selection, independent Operations Lead endorsement and the required final approval. Initial material actions require a distinct Owner. A previously approved candidate is not a renewable token.
4. Let the worker reconcile the existing command. Only proven unsent work can terminate without a Provider call. Already transmitted or unknown work remains under observation and keeps its reservation until factual release conditions pass.

If a new actionable generation cannot be produced, retain the blocked task and escalate the specific missing authority. Do not extend timestamps or set completion fields manually. Frequent expiry is an Owner policy/review-capacity issue; a queue delay is not permission to widen a lease.

Read-only diagnostics: `ops.evaluate_ad_bid_write_gate(command_id)`, `ops.ad_action_authorization.bounds`, and `ops.ad_authority_invalidation`. Keep `production_write_enabled=false` during R1. A future real Provider verification requires its separate exact Owner-approved Gate envelope.

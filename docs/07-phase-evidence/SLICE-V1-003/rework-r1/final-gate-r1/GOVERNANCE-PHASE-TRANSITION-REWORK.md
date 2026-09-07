# Governance phase-transition verification correction

The complete document-phase regression executed all 421 tests and found two
failures in `test_rework_identity_phase_and_actor_cannot_drift`. Its expected
source strings were fixed to `next_authorized_actor: CODEX` and
`candidate_state_scope: SLICE_V1_003_RESIDUAL_REWORK_NOT_CONTROLLER_APPROVED`.
The existing finalizer legitimately moves these fields to the Controller review
phase after validating completed engineering evidence. The test failed before
it could exercise either intended mutation.

Both source strings now use the class's existing `current_line` helper. The
replacement actor `SOMEBODY_ELSE`, replacement scope `PRODUCTION_READY`, presence
checks and refusal assertions remain unchanged. The test consequently mutates
the actual current fields in either validated phase. No validator, accepted
authority, engineering predicate, runtime application or migration is changed.

This changes one of the 1,280 verification-source inventory files:
`tests/test_validate_governance.py`, from SHA-256
`6cadc2b0c79df34000d69674cd7d96af110c60bb7c05960ebae7944ebb86f778`
to `476a71bf881a6826a80a3306e0862f7c504333fc01ba5d2ce23170036d824be4`.
The other 1,279 source files remain identical to product H
`e278b1e3d8541aeb806e41d6cbef4deac8d16d06`. A new exact checkpoint and complete
verification will bind this changed source; no previous H parent is restamped.
Current central views are pending until that verification is complete.

The preceding H raw local/CI artifacts, 342-row atomic review, 115-clause audit,
actual generated views and the two-failure log remain preserved. The earlier
slow document test invocation ended by SIGINT and remains incomplete. Its stack
showed repeated JSON decoding in the finalizer. Exact same-source/raw/run/role
JSON atoms were subsequently conjoined, preserving all original names, scopes
and typed assertions. Independent re-expansion reproduced all 5,118 original
uses in the manifest's 227 rows; all 2,142 JUnit uses were unchanged. The
unmodified validator passed in about three seconds and rejected an intentionally
incorrect typed assertion. These metadata records do not create product tests.

Official SARIF help examples are published as byte-exact native ZIP artifacts,
with member hashes, CRC verification and exact recursive pattern classification.
The repository secret-pattern validator remains unchanged. Original acquisition
and analysis identities remain available through the portable relocation map.

The Owner R1 authorization remains effective. The same PR remains Draft, and
`production_write_enabled=false`. Independent Controller approval and production
enablement are not supplied by this verification correction.

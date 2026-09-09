# Listing manual execution path

Applying an approved description by hand, and verifying it independently.

## The packet

A packet is issued from a launched `MANUAL` action to one executor. It carries
exactly the bound material: the affected-set digest and the full approved
Russian text, and it expires no later than the approval binding. The executor
applies the text in the platform's own console and reports the operation time
and the result (`APPLIED`, `NOT_APPLIED`, `PARTIAL`) with a note.

## Verification

A different person verifies. The database refuses the executor and any
reporter as verifier. The verification records:

- the basis: `INDEPENDENT_HUMAN`, or `OFFICIAL_EVIDENCE` backed by a
  marketplace-sourced description observation;
- the management match: `MATCHED_TARGET`, `MATCHED_PRIOR`, `DIFFERENT` or
  `UNKNOWN`, checked against the observation digest when one is named;
- the display state.

`MATCHED_TARGET` closes the action as `VERIFIED`; `MATCHED_PRIOR` proves the
change was not applied and allows the occupation to be released with
`NOT_APPLIED_PROVEN`; `DIFFERENT` is a later change by someone else and goes to
investigation.

## Promotion engagements

Official promotion participation and seller direct discount are never written
through this product. An engagement is adopted (already running) or entered
(from an approved action) with its terms and evidence, exited only with a
pre-approved exit reason and a one-use proof, and released in two steps:
`NEW_TRANSACTIONS_STOPPED`, then `OBLIGATIONS_CLEARED`. Residual obligations are
recorded, never assumed gone.

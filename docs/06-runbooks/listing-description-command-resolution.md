# Listing description command resolution

What to do when a description change will not progress: an unknown result, a
readback that did not match, a closed gate, or a restore that is not possible.

## Where to look

The action page in the listing console shows the command, its state, every
attempt and every readback under "描述写入命令 / Команда записи описания". The
gate section lists the exact reasons the database gives; an empty list means
nothing stands in the way except the worker's next pass. The same data is at
`GET /api/v1/console/listing-description-commands/actions/{actionId}` and
`GET /api/v1/console/listing-description-commands/{commandId}/gate`.

## The gate is closed

Every reason is a named condition, and the fix is the condition, not the gate:

| Reason | What it means | Who acts |
| --- | --- | --- |
| `CAPABILITY_NOT_VERIFIED`, `CAPABILITY_NOT_AVAILABLE_FOR_STORE` | the description capability is not verified, or not for this store | capability verification (`capability-verification.md`) |
| `GLOBAL_SWITCH_DISABLED`, `CAPABILITY_SWITCH_DISABLED`, `SCOPED_SWITCH_DISABLED` | the `listing-description-write` kill switch is off at some scope | whoever stopped it; see `kill-switch.md` |
| `ENTITY_NOT_ALLOWLISTED` | the listing is not on the Pilot allowlist | Owner |
| `PRODUCTION_WRITE_DISABLED` | no active Owner gate authority names this listing with production writes enabled | Owner, through Gate EV; there is no console or API that changes this |
| `AUTHORIZATION_INVALID_OR_EXPIRED`, `APPROVAL_LEASE_EXPIRED`, `BINDING_EXPIRED` | the approval ran out | the action goes back through review and approval |
| `ACTION_NOT_LAUNCHED`, `ALLOWANCE_NOT_OCCUPIED` | the action is not launched, or its allowance was released | launch it again when allowance exists |
| `SCOPE_CONTAINED` | a containment covers the listing | `listing-containment-and-reenablement.md` |
| `EXECUTION_PASS_MISSING` | no execution-time guardrail PASS names the bound calibration package | re-run the execution preview; a BLOCK names its own reasons |
| `NON_TARGET_FIELD_RISK` | the verified APPLY operation does not name the one description attribute | capability verification; never widen the template |
| `KIZ_MARKED_UNDECLARED`, `TEXT_LENGTH_OUT_OF_BOUNDS` | the action lacks a marking declaration or the text is outside the calibration bound | the author prepares a new action |
| `PRIOR_TEXT_MOVED` | the platform text is no longer the text the approval was bound to | `CURRENT_TEXT_MOVED`: the action is re-prepared against the new current text |

A lease refused by the gate is the ordinary case, not an incident; the command
stays `PENDING` and the worker skips it.

## The result is unknown

`UNKNOWN_REQUIRES_READBACK` has two exits and neither is a retry: a readback
(the worker does this on its own, or "readback" on the command page), or manual
resolution. A readback that matches the target closes the command; one that
matches the prior text allows a retry only when the database proves the retry
is safe (`ops.lc_description_retry_is_proven`); a third value moves the command
to `LATER_CHANGE_OR_MISMATCH_INVESTIGATION` because somebody else owns that text
now.

## Restore is not possible

Compensation restores the exact prior text and only that. When the prior text
was empty or was not captured, the command reports `RESTORE_UNSUPPORTED` and
goes to manual resolution: nothing is written, no space, no placeholder and no
whole-card import. The person resolving it decides in the platform console with
the packet's own record of what the prior text was, if anything.

## Closing by hand

"take over", "compensation" and "failure" on the command page need the
`COMMAND_RESOLVE` scope and a fresh step-up. Every transition is audited with the
reason typed by the person.

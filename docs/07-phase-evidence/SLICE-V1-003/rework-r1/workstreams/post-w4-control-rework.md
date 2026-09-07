# Post-W4 control repairs and proof boundaries

These repairs belong to the existing Frozen F009/F012/F014/F016/F017 requirements.
They change neither the accepted Contract nor the Frozen Finding Set. The current
working tree remains uncommitted and requires another clean full measurement.
`production_write_enabled=false`; every database and transport response used here
is an isolated synthetic fixture, with no Provider or shared/production access.

R15's actual named XML proves all 17 minimum-expiry cases, 18 retry-proof cases,
14 actor-revocation cases, four command-substitution cases, eight exact Gate-scope
cases, 13 Manual cases, eight adversarial Gate cases and seven transmission cases.
The containing R15 run fails with 40 errors in two other classes. R16 repairs the
two duplicate fixture inputs and passes all 48 Reservation/Operations-read cases.
The exact two-file source delta and original raw reports are preserved in
[final-controls](final-controls/index.json); no earlier failure is relabelled PASS.
R17 records a disposable Testcontainers/Ryuk connection failure. R18 records a
SQL parse error in the newly added isolation function; the reserved table alias
was repaired before R19 passed all ten privilege-boundary cases and all 65
migrations. R20 executes 262 actual named cases: 7 failures and 14 errors, with
stable source bytes. The containing run is not a pass. Its failures identify two
actual worker lifecycle defects and invalid synthetic inputs in Gate E,
authority-version publication and compensated Shared PriceCommand fixtures.
Their repairs and the precise authority-version cause attribution still await
the next measured run; historical results are never overwritten.

The minimum-expiry calculation now consumes optional finite Semantic Profile
validity and the exact required Freshness Profile end, together with the Maker,
endorser and final approver's relevant role and grant ends. The 17-axis test uses
legal 900-second leases and actual database time: an approval established 885
seconds earlier is exercised before and after its real earliest deadline. It does
not replace the clock or shorten a business threshold. R12's freshness-axis
failure demonstrated the missing Profile bound; R13's 13-axis pass and R15's
17-axis pass preserve the repair's causal evidence.

Retry requires an actual frozen operation result, a later complete scoped
Readback proving the prior value, current fencing/budget/authority and a recognized
idempotency basis. With verified native keys, an initial timeout need not invent a
response body: a later resolving status response and prior-value Readback can
prove safe retry. Without that proof, repeated prior values alone, incomplete
bodies, mismatched currencies/units or a third current value still refuse APPLY.
The narrow timeout-body exception is APPLY-only; Compensation keeps its separate
existing authority and ownership checks. R20 passes all 18 SQL retry-proof cases,
but the four new actual Java worker cases expose two separate lifecycle defects:
leasing a retry advances the fence and makes its old Readback stale; releasing
the lease between read-only status polls violates the existing pending-state
constraint. The worker repair must obtain a new-fence Readback before retry and
retain live lease/fence checks at each transport boundary. The synthetic port
returns frozen responses while the real repository, Raw custody and classifier
run against isolated PostgreSQL. No state-oracle fixture result is represented
as proof that the complete worker lifecycle succeeds.

Revoking a Maker, endorser or final approver's account, relevant role, action scope
or authenticated-session authority appends permanent invalidation. Retiring the
identity provider has the same effect. Restoring that authority cannot revive the
old approval; changing only a display name does not invent a revocation. Started
Manual packets become uncertain and lose their current verification pointer while
preserving execution/report/history. Unexecuted packets are revoked. Readback and
independent verification continue during containment; reservations do not release
early. The real API gate also evaluates each current human's relevant authority.

The operations reader consumes the same SQL six-axis snapshot as admission for
all applicable Envelopes, retaining exact scope/version and nullable measurements.
It neither selects a single legacy envelope nor combines independent percentages.
R16 proves simultaneous organization and store envelopes and retirement handling.
R20 passes all ten reader cases, including actual incomplete Retained coverage:
unknown sales share, company sales and affected sales stay null while real known
zero counts remain zero. A known Envelope does not manufacture known usage.
Frontend full quality separately passes 308 named tests at its own exact source
fingerprint; see the latest Exposure quality receipt under post-w4-ui.

Gate E now checks the exact native value pair demonstrated by its Gate EV
predecessor and its maximum-change bound. The current representation contains
exact pairs, not an inferred numeric range. Its Bundle's Envelope must retain the
same scope/currency/measurement bases and may only tighten each independent hard
axis, preserving at least the demonstrated recovery headroom. A different value
range or wider exposure cannot be inferred from a nonempty reference. A new Owner
Pilot window remains distinct from the historical Gate EV evidence window; the
actual seal intersects it with every current dependent expiry. The one-time EV
command count is not misrepresented as an ongoing Pilot aggregate limit. New
publication-trigger positives and negatives execute in R20; ten cases encounter
illegal overlapping/current measurement fixtures and five assertions incorrectly
expect complete company coverage after introducing an unobserved adjacent Store.
The corrected fixtures preserve those controls and assert exact publication
refusal without manufacturing missing company coverage; their next run is pending.

The existing AC131 isolation repair reads actual committed Shared PriceCommands
and accepted comparable price, promotion, sellability and inventory context.
R20 passes 20 of 21 cases; the remaining COMPENSATED fixture correctly fails the
Shared command restoration-proof constraint and is being given legal evidence.
Missing context remains explicit uncertainty and is not described as isolated.
The existing AC139 stop entry validates an exact consumed authority version and
organization-wide actor/review-owner scope through the signed service boundary.
R20 passes eight of eleven cases; three multi-consumer fixtures correctly fail
the Bundle pending-validation constraint before their business assertions.
The fixtures now carry legal pending failure codes. Exact cause attribution
under a concurrent unrelated hold is additionally repaired and tested as the
same containment-scope defect. Actual human publication, consumer propagation
and multi-Store recovery each remain separate proof obligations.

R21 executes all nineteen selected classes with 289 actual named cases: 288 pass
and only the existing missing-Bundle diagnostic assertion fails. All nine actual
worker tests, twelve authority-version tests, twenty-five Gate E matrix tests
and twenty-one cross-domain tests pass at R21's source fingerprint. The worker
proof includes new-fence prior re-observation before a same-key retry; target,
third-value and timeout observations prevent another APPLY; expired retry and
first-write leases preserve their distinct safe recovery states. The new
authority-version test proves that a concurrent unrelated hold cannot acquire
the new version's cause reference or obstruct its independent recovery.

The R21 failure is repaired by returning the original BUNDLE_NOT_FOUND result
before inspecting absent components. R22 changes only that one SQL line and
passes all 83 actual cases in the five complete related Bundle/activation, Gate
and privilege classes. See r21-r22-source-delta.json and both original run
receipts. The frontend's authorized legacy Exposure fixture upgrade additionally
passes all 308 unit/component tests and full quality checks at r3. These exact
selected results are preparatory evidence; a new clean full run, 12 advertising
and 25 legacy browser journeys, and final exact-Head CI remain required.

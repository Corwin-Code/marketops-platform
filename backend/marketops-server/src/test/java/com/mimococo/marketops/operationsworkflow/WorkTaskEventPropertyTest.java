package com.mimococo.marketops.operationsworkflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.internal.domain.AdvertisingSlo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the journal, the response bounds and the verdicts hold for every input.
 *
 * <p>An example test asserts that {@code ACTION_RECORDED} is an action. That is
 * true and it is not the guarantee anyone depends on. The guarantee is that no
 * event of any kind is two things at once, that nothing missing its evidence can
 * pass for an action, and that a verdict names every reason it was built from.
 * Those are statements about all inputs, which is why they are generated here
 * rather than illustrated.
 *
 * <p>Generation is a {@link Random} built from one fixed literal seed, so a
 * failure is reproduced by rerunning the same test rather than by catching the
 * same shuffle twice. Every assertion carries the generated input in its
 * description: a property failure that says only "expected true" leaves the
 * reader to find the case themselves, which is most of the cost of a property
 * test failing.
 *
 * <p>These are record-level properties and they deliberately do not reach the
 * database. The schema enforces the same distinctions with CHECK constraints and
 * the schema tests cover those; the point of asserting them again here is that a
 * record which disagreed with the schema would let every in-memory reader of a
 * task journal reach a conclusion the database would have refused.
 */
class WorkTaskEventPropertyTest {

    /** The one seed every generator in this file starts from. */
    private static final long SEED = 20_260_905L;

    /** Cases per generated property, where a property runs a single loop. */
    private static final int CASES = 500;

    /** The event kinds {@code ops.work_task_event} admits. */
    private static final List<String> SCHEMA_EVENT_KINDS = List.of(
            "RAISED", "VIEWED", "ACKNOWLEDGED", "ASSIGNED", "REASSIGNED",
            "ACTION_RECORDED", "OUTCOME_OBSERVED", "REOPENED", "ESCALATED",
            "COMPLETED", "CANCELLED");

    /**
     * Kinds the schema would refuse, which the record still has to answer for.
     *
     * <p>Most of these are near-misses of {@code ACTION_RECORDED} rather than
     * nonsense, because that is the shape a real defect takes: a caller that
     * trims badly, lower-cases a code, or drops the separator. A record whose
     * predicates answered yes to any of them would report an action that the
     * database never accepted as one.
     */
    private static final List<String> FOREIGN_EVENT_KINDS = List.of(
            "", "  ", "action_recorded", "ACTION_RECORDED ", " ACTION_RECORDED",
            "ACTIONRECORDED", "ACTION_RECORDED\n", "outcome_observed", "Reassigned",
            "REASSIGNED_BACK", "OUTCOME_OBSERVED_LATE", "UNKNOWN");

    /** Both sets, since the predicates are asked about kinds from either. */
    private static final List<String> EVERY_EVENT_KIND =
            joined(SCHEMA_EVENT_KINDS, FOREIGN_EVENT_KINDS);

    private static List<String> joined(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static UUID uuid(Random random) {
        return new UUID(random.nextLong(), random.nextLong());
    }

    private static UUID maybeUuid(Random random) {
        return random.nextBoolean() ? uuid(random) : null;
    }

    private static String maybe(Random random, String value) {
        return random.nextBoolean() ? value : null;
    }

    /** A digest-shaped string; nothing here validates it, so any 64 hex will do. */
    private static String digest(Random random) {
        StringBuilder text = new StringBuilder(64);
        for (int position = 0; position < 64; position++) {
            text.append(Character.forDigit(random.nextInt(16), 16));
        }
        return text.toString();
    }

    /**
     * An event with the three action-stage elements chosen by the caller.
     *
     * <p>Everything else is drawn, present or absent, so a property is not being
     * asserted about one tidy shape. The assignment, outcome and role fields are
     * filled independently of the kind on purpose: the schema keeps them
     * consistent, and the point of these properties is what the record answers
     * when they are not.
     */
    private static WorkTaskEventView event(Random random, String eventKind, String actionKind,
                                           String evidenceReference, UUID actorUserId) {
        return new WorkTaskEventView(uuid(random), uuid(random), 1 + random.nextInt(500),
                eventKind, "lineage-" + random.nextInt(1000), actionKind,
                maybe(random, "{\"reference\":\"ev-" + random.nextInt(1000) + "\"}"),
                evidenceReference, maybe(random, "OPERATIONAL"),
                maybe(random, "obs-" + random.nextInt(1000)), maybeUuid(random),
                maybeUuid(random), actorUserId, maybe(random, "OPERATOR"), "generated case",
                Instant.EPOCH.plusSeconds(random.nextInt(1_000_000)));
    }

    /** An event whose action-stage elements are drawn too. */
    private static WorkTaskEventView event(Random random, String eventKind) {
        return event(random, eventKind, maybe(random, "DECISION_APPROVED"),
                maybe(random, "evidence-" + random.nextInt(1000)), maybeUuid(random));
    }

    /**
     * A verdict built the way {@code GuardrailService} builds one.
     *
     * <p>The pass is derived from the emptiness of the reason set here because
     * the record does not derive it. See {@link VerdictNamesEveryReason} for
     * what that costs this file.
     */
    private static GuardrailVerdict verdict(Random random, List<GuardrailReason> reasons) {
        GuardrailPurpose[] purposes = GuardrailPurpose.values();
        boolean policyNamed = random.nextBoolean();
        return new GuardrailVerdict(uuid(random), purposes[random.nextInt(purposes.length)],
                reasons.isEmpty(), reasons, policyNamed ? uuid(random) : null,
                policyNamed ? 1 + random.nextInt(20) : null,
                Map.of("comparison", "value-" + random.nextInt(1000)), digest(random));
    }

    /**
     * The distinction the whole journal exists to keep.
     *
     * <p>A service level reported from this journal is only worth reading if
     * opening a page cannot be counted as acting on the work. The three
     * predicates are the record's answer to that, and if any input could make
     * two of them true at once, one event would be counted twice in different
     * columns of the same report and nothing downstream would notice.
     *
     * <p>Kinds outside the schema's set are generated deliberately. The database
     * would refuse them, but these predicates run against whatever a caller put
     * in the field, and a near-miss such as a trailing newline is exactly what a
     * caller sends before anybody notices.
     */
    @Nested
    @DisplayName("an event is one kind of thing, never two")
    class OneKindOnly {

        /** Cases per event kind; twenty-three kinds put the property over 500. */
        private static final int CASES_PER_KIND = 30;

        static List<String> everyEventKind() {
            return EVERY_EVENT_KIND;
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("everyEventKind")
        @DisplayName("TC-WF-PROP-001 at most one of action, outcome and handover answers true")
        void atMostOneOfTheThreePredicatesAnswersTrue(String eventKind) {
            Random random = new Random(SEED);

            for (int caseNo = 0; caseNo < CASES_PER_KIND; caseNo++) {
                WorkTaskEventView view = event(random, eventKind);

                int answeredTrue = (view.action() ? 1 : 0) + (view.outcome() ? 1 : 0)
                        + (view.handover() ? 1 : 0);

                assertThat(answeredTrue)
                        .as("case %d of kind \"%s\": action=%s outcome=%s handover=%s",
                                caseNo, eventKind, view.action(), view.outcome(),
                                view.handover())
                        .isLessThanOrEqualTo(1);
            }
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("everyEventKind")
        @DisplayName("TC-WF-PROP-001b nothing but an action satisfies the action stage")
        void satisfyingTheActionStageMeansTheEventIsAnAction(String eventKind) {
            Random random = new Random(SEED);

            for (int caseNo = 0; caseNo < CASES_PER_KIND; caseNo++) {
                WorkTaskEventView view = event(random, eventKind);

                if (view.satisfiesActionStage()) {
                    assertThat(view.action())
                            .as("case %d of kind \"%s\" satisfied the action stage without "
                                    + "being an action", caseNo, eventKind)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("TC-WF-PROP-001c the generator reaches all three answers it asserts about")
        void theGeneratorReachesEveryPredicate() {
            // A property that never observed a true answer would pass on an
            // event record whose predicates all returned false, which is the
            // failure most worth catching here.
            Random random = new Random(SEED);
            int actions = 0;
            int outcomes = 0;
            int handovers = 0;

            for (String eventKind : EVERY_EVENT_KIND) {
                for (int caseNo = 0; caseNo < CASES_PER_KIND; caseNo++) {
                    WorkTaskEventView view = event(random, eventKind);
                    actions += view.action() ? 1 : 0;
                    outcomes += view.outcome() ? 1 : 0;
                    handovers += view.handover() ? 1 : 0;
                }
            }

            assertThat(actions).as("actions among %d generated events",
                    EVERY_EVENT_KIND.size() * CASES_PER_KIND).isPositive();
            assertThat(outcomes).as("outcomes among the same events").isPositive();
            assertThat(handovers).as("handovers among the same events").isPositive();
        }
    }

    /**
     * Why all three elements, and not two of them plus goodwill.
     *
     * <p>The action stage is what a service level is met by. An event that
     * satisfied it without naming the action leaves nobody able to say what was
     * done; without the evidence reference there is nothing to check it against;
     * without the actor there is nobody accountable for it. Any of the three
     * missing turns a met service level into an assertion nobody can audit,
     * which is worse than an unmet one because it looks fine.
     */
    @Nested
    @DisplayName("an action stage needs its action, its evidence and its actor")
    class ActionStageNeedsAllThree {

        /** Passes over the kind and presence grid; three keeps the property over 500. */
        private static final int PASSES = 3;

        @Test
        @DisplayName("TC-WF-PROP-002 any missing element refuses the action stage")
        void anyMissingElementRefusesTheActionStage() {
            // The kind and the three presence bits are walked exhaustively
            // rather than sampled, so no combination is left to chance; the
            // other thirteen components stay generated. Twenty-three kinds by
            // eight masks by three passes is 552 cases.
            Random random = new Random(SEED);

            for (int pass = 0; pass < PASSES; pass++) {
                for (String eventKind : EVERY_EVENT_KIND) {
                    for (int present = 0; present < 8; present++) {
                        String actionKind = (present & 1) == 0 ? null : "DECISION_APPROVED";
                        String evidenceReference =
                                (present & 2) == 0 ? null : "evidence-" + random.nextInt(1000);
                        UUID actorUserId = (present & 4) == 0 ? null : uuid(random);

                        WorkTaskEventView view = event(random, eventKind, actionKind,
                                evidenceReference, actorUserId);

                        boolean complete = present == 7;
                        boolean expected = "ACTION_RECORDED".equals(eventKind) && complete;

                        assertThat(view.satisfiesActionStage())
                                .as("pass %d, kind \"%s\", actionKind=%s evidenceReference=%s "
                                        + "actorUserId=%s", pass, eventKind, actionKind,
                                        evidenceReference, actorUserId)
                                .isEqualTo(expected);
                    }
                }
            }
        }

        @Test
        @DisplayName("TC-WF-PROP-002b engagement carrying every element is still not an action")
        void engagementIsNeverAnAction() {
            // The hardest case for the record: a view or an acknowledgement
            // arriving with an action kind, an evidence reference and an actor,
            // which is exactly what a caller reusing one write path would send.
            // The kind alone has to refuse it.
            Random random = new Random(SEED);
            List<String> notActions = EVERY_EVENT_KIND.stream()
                    .filter(kind -> !"ACTION_RECORDED".equals(kind))
                    .toList();

            for (int caseNo = 0; caseNo < CASES; caseNo++) {
                String eventKind = notActions.get(caseNo % notActions.size());
                String evidenceReference = "evidence-" + random.nextInt(1000);
                UUID actorUserId = uuid(random);

                WorkTaskEventView view = event(random, eventKind, "DECISION_APPROVED",
                        evidenceReference, actorUserId);

                assertThat(view.satisfiesActionStage())
                        .as("case %d: kind \"%s\" fully equipped with evidenceReference=%s "
                                + "actorUserId=%s", caseNo, eventKind, evidenceReference,
                                actorUserId)
                        .isFalse();
            }
        }
    }

    /**
     * Two bounds are asserted here, not three, and that is the honest limit.
     *
     * <p>Why the bounds matter: they are the promise the Slice makes about how
     * quickly an accepted fact becomes an updated answer, and they are constants
     * so that a deployment cannot report itself healthy by relaxing the number
     * it is judged against. An ordering that broke would let a latency breach
     * the hard bound while still counting as having met the tighter target,
     * which is a health report that improves as the system gets slower.
     *
     * <p>What could not be asserted: {@link AdvertisingSlo} fixes an
     * internal-latency hard bound, a critical distribution target, the
     * percentile that target is judged at, and a reconciliation interval. It
     * fixes no acknowledgement, action or escalation bound at all. Those three
     * are a per-lane policy row, {@code core.ad_human_slo_profile}, where
     * {@code >= 1} and {@code acknowledgement <= action <= escalation} are
     * database CHECK constraints. No type on the Java side exposes the triple,
     * so there are no configurations here to generate against, and asserting the
     * minima would mean asserting against the database, which this file does not
     * reach.
     *
     * <p>For the expiry minima and their ordering to be a property of this
     * class, AdvertisingSlo would have to fix those floors as constants and
     * offer a validating factory — something on the order of
     * {@code humanBounds(Duration acknowledgement, Duration action, Duration
     * escalation)} that refuses an unordered or sub-minimum triple. What is
     * asserted below is what the class does expose: that its own bounds are
     * ordered, that the ordering is visible through its two predicates for every
     * generated latency, and that a latency exactly at a bound is not a breach.
     */
    @Nested
    @DisplayName("the internal response bounds are ordered and strict at the edge")
    class ResponseBoundsAreOrdered {

        /** A latency somewhere in the regions the two bounds divide. */
        private static Duration latency(Random random) {
            // Spread across the regions the two bounds divide, with the exact
            // bounds reachable at the seams of the first three branches.
            return switch (random.nextInt(4)) {
                case 0 -> Duration.ofMillis(random.nextInt(300_001));
                case 1 -> Duration.ofMillis(300_000 + random.nextInt(600_001));
                case 2 -> Duration.ofMillis(900_000 + random.nextInt(3_600_001));
                default -> Duration.ofNanos(random.nextInt(1_000_000_000));
            };
        }

        @Test
        @DisplayName("TC-WF-PROP-003 a hard-bound breach always misses the critical target too")
        void breachingTheHardBoundAlwaysMissesTheCriticalTarget() {
            Random random = new Random(SEED);
            int breaches = 0;
            int misses = 0;

            for (int caseNo = 0; caseNo < CASES; caseNo++) {
                Duration latency = latency(random);
                boolean breached = AdvertisingSlo.breached(latency);
                boolean missed = AdvertisingSlo.missedCriticalTarget(latency);

                if (breached) {
                    assertThat(missed)
                            .as("case %d: %s breached the hard bound %s yet met the critical "
                                    + "target %s", caseNo, latency, AdvertisingSlo.HARD_BOUND,
                                    AdvertisingSlo.CRITICAL_DISTRIBUTION_TARGET)
                            .isTrue();
                }
                breaches += breached ? 1 : 0;
                misses += missed ? 1 : 0;
            }

            // Without these the implication above would hold vacuously on a
            // class whose predicates always answered false.
            assertThat(breaches).as("hard-bound breaches among %d generated latencies", CASES)
                    .isPositive().isLessThan(CASES);
            assertThat(misses).as("critical-target misses among the same latencies")
                    .isPositive().isLessThan(CASES);
        }

        @Test
        @DisplayName("TC-WF-PROP-003b a longer latency never turns a breach back into a pass")
        void bothPredicatesAreMonotoneInTheLatency() {
            // Monotonicity is what makes a percentile of these answers mean
            // anything: if a slower run could be judged better than a faster
            // one, a lane could improve its reported health by getting slower.
            Random random = new Random(SEED);

            for (int caseNo = 0; caseNo < CASES; caseNo++) {
                Duration first = latency(random);
                Duration second = latency(random);
                boolean firstIsShorter = first.compareTo(second) <= 0;
                Duration shorter = firstIsShorter ? first : second;
                Duration longer = firstIsShorter ? second : first;

                if (AdvertisingSlo.breached(shorter)) {
                    assertThat(AdvertisingSlo.breached(longer))
                            .as("case %d: %s breached the hard bound but the longer %s did not",
                                    caseNo, shorter, longer)
                            .isTrue();
                }
                if (AdvertisingSlo.missedCriticalTarget(shorter)) {
                    assertThat(AdvertisingSlo.missedCriticalTarget(longer))
                            .as("case %d: %s missed the critical target but the longer %s did "
                                    + "not", caseNo, shorter, longer)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("TC-WF-PROP-003c the fixed bounds are ordered and the edge is not a breach")
        void theFixedBoundsAreOrdered() {
            assertThat(AdvertisingSlo.CRITICAL_DISTRIBUTION_TARGET)
                    .as("the distribution target sits inside the hard bound")
                    .isPositive()
                    .isLessThan(AdvertisingSlo.HARD_BOUND);
            // A reconciliation asked to run more often than a single targeted
            // path is allowed to take would be sweeping work that has not had
            // its allotted time yet.
            assertThat(AdvertisingSlo.RECONCILIATION_INTERVAL)
                    .as("the full reconciliation is slower than one targeted path")
                    .isGreaterThan(AdvertisingSlo.HARD_BOUND);
            // A P100 target is a hard bound wearing a percentile's name, and
            // this class already has a hard bound.
            assertThat(AdvertisingSlo.TARGET_PERCENTILE)
                    .as("the target percentile is a percentile")
                    .isGreaterThan(0)
                    .isLessThan(100);

            assertThat(AdvertisingSlo.breached(AdvertisingSlo.HARD_BOUND))
                    .as("a latency exactly at the hard bound")
                    .isFalse();
            assertThat(AdvertisingSlo.missedCriticalTarget(
                    AdvertisingSlo.CRITICAL_DISTRIBUTION_TARGET))
                    .as("a latency exactly at the distribution target")
                    .isFalse();
        }

        @Test
        @DisplayName("TC-WF-PROP-003d an unmeasured latency is answered rather than thrown on")
        void anUnmeasuredLatencyIsNotABreach() {
            // A missing measurement is a reporting gap for the caller to
            // surface. What it must not do is take down the sweep that is
            // walking a batch of them.
            assertThat(AdvertisingSlo.breached(null)).isFalse();
            assertThat(AdvertisingSlo.missedCriticalTarget(null)).isFalse();
        }
    }

    /**
     * A refusal is a union of conditions, and it has to survive as one.
     *
     * <p>Why it matters: an operator who fixes the one condition they were told
     * about and is then refused for a second one nobody mentioned loses a day,
     * and does it again for the third. That is the failure the verdict is shaped
     * to prevent, so every reason has to reach the reader together, exactly, and
     * stay that way once the decision is recorded.
     *
     * <p>What could not be asserted: half of "a PASS only when the set is
     * empty". {@link GuardrailVerdict} takes {@code passed} as a constructor
     * argument and its compact constructor only copies the two collections, so a
     * verdict that passes while naming a reason is constructible and nothing in
     * this file can rule it out. The rule lives one layer up, in the service
     * that computes {@code passed = reasons.isEmpty()} before building the
     * record. For it to be a property of the type, the record would need to
     * enforce the pair itself — a check in the compact constructor, or static
     * factories along the lines of {@code GuardrailVerdict.pass(...)} and
     * {@code GuardrailVerdict.blocked(...)} that make an incoherent verdict
     * unconstructible.
     *
     * <p>So every verdict below is built the way the service builds one, and
     * this group says so rather than presenting a flag the test itself derived
     * as an invariant the type checks. What the type does own, and what is
     * asserted, is the union: a verdict carries every reason it was given, names
     * each of them and no others, and cannot lose or gain one afterwards.
     */
    @Nested
    @DisplayName("a verdict names every reason it was built from")
    class VerdictNamesEveryReason {

        /** Passes over every reason; thirteen puts the property over 500. */
        private static final int PASSES_PER_REASON = 13;

        /** A random subset of the reasons, empty often enough to matter. */
        private static List<GuardrailReason> reasonSubset(Random random) {
            // A quarter of the cases are empty, so the passing branch is
            // exercised properly rather than turning up once in a thousand.
            if (random.nextInt(4) == 0) {
                return List.of();
            }
            List<GuardrailReason> chosen = new ArrayList<>();
            for (GuardrailReason reason : GuardrailReason.values()) {
                if (random.nextInt(6) == 0) {
                    chosen.add(reason);
                }
            }
            return chosen;
        }

        @Test
        @DisplayName("TC-WF-PROP-004 a verdict names exactly the reasons it was given")
        void aVerdictNamesExactlyTheReasonsItWasGiven() {
            Random random = new Random(SEED);

            for (int caseNo = 0; caseNo < CASES; caseNo++) {
                List<GuardrailReason> chosen = reasonSubset(random);
                GuardrailVerdict built = verdict(random, chosen);
                boolean namesNothing = true;

                for (GuardrailReason reason : GuardrailReason.values()) {
                    boolean named = built.blockedBy(reason);
                    assertThat(named)
                            .as("case %d: %s against the set %s", caseNo, reason, chosen)
                            .isEqualTo(chosen.contains(reason));
                    namesNothing &= !named;
                }

                assertThat(built.reasons())
                        .as("case %d: the set the verdict carries", caseNo)
                        .containsExactlyElementsOf(chosen);
                // Not a check of the record's own invariant — it has none — but
                // of the two accessors agreeing across the defensive copy: a
                // copy that dropped an element, or a blockedBy comparing by
                // identity, would break this while leaving reasons() plausible.
                assertThat(built.passed())
                        .as("case %d: passing while naming one of %s", caseNo, chosen)
                        .isEqualTo(namesNothing);
            }
        }

        @Test
        @DisplayName("TC-WF-PROP-004b a set of one names that reason and no other")
        void aSetOfOneNamesThatReasonAndNoOther() {
            // One reason at a time, across all of them, because the reason an
            // operator is refused for has to be the reason the guardrail found.
            // A verdict that named a neighbouring code would send somebody to
            // the wrong runbook, and the code exists to point at one.
            GuardrailReason[] reasons = GuardrailReason.values();
            Random random = new Random(SEED);

            for (int pass = 0; pass < PASSES_PER_REASON; pass++) {
                for (int index = 0; index < reasons.length; index++) {
                    GuardrailReason reason = reasons[index];
                    GuardrailReason neighbour = reasons[(index + 1) % reasons.length];
                    GuardrailVerdict passing = verdict(random, List.of());
                    GuardrailVerdict blocked = verdict(random, List.of(reason));

                    assertThat(passing.blockedBy(reason))
                            .as("pass %d: a verdict built from no reasons named %s", pass, reason)
                            .isFalse();
                    assertThat(passing.reasons())
                            .as("pass %d: the set behind a verdict built from no reasons", pass)
                            .isEmpty();
                    assertThat(blocked.blockedBy(reason))
                            .as("pass %d: a verdict built from %s did not name it", pass, reason)
                            .isTrue();
                    assertThat(blocked.blockedBy(neighbour))
                            .as("pass %d: a verdict built from %s also named %s", pass, reason,
                                    neighbour)
                            .isFalse();
                    assertThat(blocked.reasons())
                            .as("pass %d: the set behind a single-reason verdict", pass)
                            .containsExactly(reason);
                }
            }
        }

        @Test
        @DisplayName("TC-WF-PROP-004c the reason set cannot be changed after the verdict exists")
        void theReasonSetCannotBeChangedAfterwards() {
            // A verdict is evidence of a decision already made. If the caller's
            // list stayed live, a later append would rewrite what the guardrail
            // is recorded as having said.
            Random random = new Random(SEED);

            for (int caseNo = 0; caseNo < CASES; caseNo++) {
                List<GuardrailReason> chosen = reasonSubset(random);
                List<GuardrailReason> handedOver = new ArrayList<>(chosen);
                GuardrailVerdict built = verdict(random, handedOver);

                handedOver.add(GuardrailReason.COOLDOWN_ACTIVE);
                handedOver.clear();

                assertThat(built.reasons())
                        .as("case %d: the set after the caller's list was emptied under it",
                                caseNo)
                        .containsExactlyElementsOf(chosen);
            }
        }
    }
}

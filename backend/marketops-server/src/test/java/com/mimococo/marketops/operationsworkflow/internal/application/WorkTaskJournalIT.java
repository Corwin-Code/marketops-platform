package com.mimococo.marketops.operationsworkflow.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.AdvertisingGraphFixture;
import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.operationsworkflow.WorkTaskEventView;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What actually happened to a piece of work, as distinct from what a queue says.
 *
 * <p>{@code ops.work_task} can say who holds a task and whether it is closed. It
 * cannot say when somebody first looked at it, whether looking was the same as
 * taking it on, whether taking it on was the same as doing anything, or how old
 * the work is after it has changed hands twice. Those four questions are what a
 * service level is made of, and every one of them can be answered dishonestly by
 * a journal that blurs the distinctions.
 *
 * <p>So the distinctions are asserted twice over. Once through
 * {@link WorkTaskService}, which is the module's sole authority for this and the
 * only writer these tests use; and once directly against the schema, because a
 * property enforced only by the service that writes it is a property the next
 * service can quietly drop.
 *
 * <p>Nothing here is a claim about a marketplace. The graph is synthetic, the
 * people are fixtures, and the only thing under test is whether the record can
 * be made to say something that did not happen.
 */
@SpringBootTest
@ActiveProfiles("ci")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkTaskJournalIT {

    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE =
            TestDatabase.isolatedContainer();

    private static JdbcClient seed;
    private static AdvertisingGraphFixture.Graph graph;
    private static AdvertisingGraphFixture.Decision decision;
    private static UUID taskId;
    private static Instant raisedAt;
    private static AuthenticatedActor holder;
    private static AuthenticatedActor successor;

    @Autowired
    private WorkTaskService tasks;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @BeforeAll
    static void openSeedConnection() {
        seed = JdbcClient.create(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword()));
    }

    @BeforeEach
    void seedOnce() {
        if (graph != null) {
            return;
        }
        graph = AdvertisingGraphFixture.seed(seed);
        decision = AdvertisingGraphFixture.seedDecision(seed, graph, "PROTECTION_DECREASE",
                "MAX_CPC_BOUNDED");
        grantTaskAssignment(graph.executorUserId());
        grantTaskAssignment(graph.verifierUserId());

        // Raised two days ago, so the age a queue reports is a number with room
        // to be wrong in either direction.
        taskId = UUID.randomUUID();
        raisedAt = Instant.now().minus(Duration.ofDays(2));
        seed.sql("""
                INSERT INTO ops.work_task (id, organization_id, recommendation_id, title, state,
                        due_at, created_at, updated_at, version)
                VALUES (:id, :organization, :recommendation,
                        'act on a protection case', 'OPEN', :due, :raised, :raised, 0)
                """)
                .param("id", taskId)
                .param("organization", graph.organizationId())
                .param("recommendation", decision.recommendationId())
                .param("due", Timestamp.from(raisedAt.plus(Duration.ofHours(4))))
                .param("raised", Timestamp.from(raisedAt))
                .update();

        holder = actor(graph.executorUserId());
        successor = actor(graph.verifierUserId());
    }

    @Test
    @Order(1)
    @DisplayName("TC-WF-JOURNAL-001 opening the page is recorded, and cannot be read as engagement")
    void aViewIsNotAnAcknowledgement() {
        // Assignment authority does not grant diagnostic read access. A refused
        // page open must not leave a journal entry claiming that it was read.
        assertThatThrownBy(() -> tasks.recordView(holder, taskId))
                .isInstanceOfSatisfying(OperationRejectedException.class,
                        refusal -> assertThat(refusal.errorCode())
                                .isEqualTo(ErrorCode.RESOURCE_SCOPE_DENIED));
        assertThat(tasks.journal(taskId)).isEmpty();
        UUID readGrant = grantDiagnosticView(graph.executorUserId());
        tasks.recordView(holder, taskId);

        List<WorkTaskEventView> journal = tasks.journal(taskId);
        assertThat(journal).hasSize(1);
        WorkTaskEventView view = journal.getFirst();
        assertThat(view.eventKind()).isEqualTo("VIEWED");

        // The view names a person, which is why it is worth recording at all —
        // an unopened task and one somebody read and left are different
        // situations. What it must not do is carry anything that could be
        // presented as engagement.
        assertThat(view.actorUserId()).isEqualTo(graph.executorUserId());
        assertThat(view.action()).isFalse();
        assertThat(view.satisfiesActionStage()).isFalse();
        assertThat(view.outcome()).isFalse();

        // Revocation denies a new view and preserves the existing journal entry.
        seed.sql("""
                UPDATE iam.user_scope_grant SET status='REVOKED',
                    reason='Synthetic diagnostic read revocation', updated_at=now() WHERE id=:id
                """)
                .param("id", readGrant).update();
        assertThatThrownBy(() -> tasks.recordView(holder, taskId))
                .isInstanceOfSatisfying(OperationRejectedException.class,
                        refusal -> assertThat(refusal.errorCode())
                                .isEqualTo(ErrorCode.RESOURCE_SCOPE_DENIED));
        assertThat(tasks.journal(taskId)).hasSize(1);

        // And the schema refuses one that tried. A console that logged every
        // render could not turn those renders into acknowledgements even by
        // writing the rows itself.
        assertThatThrownBy(() -> appendRaw("VIEWED", "action_kind", "'DATA_OR_MAPPING_REPAIR'"))
                .hasMessageContaining("work_task_event_action_exclusive_ck");
        assertThatThrownBy(() -> appendRaw("VIEWED", "outcome_kind", "'SETTLED'"))
                .hasMessageContaining("work_task_event_outcome_exclusive_ck");
        // And evidence, which only the view's own shape forbids: a page open
        // rests on nothing, so a view that cited something would be claiming to
        // be more than it is.
        assertThatThrownBy(() ->
                appendRaw("VIEWED", "evidence_reference", "'evidence://fixture/looked'"))
                .hasMessageContaining("work_task_event_view_shape_ck");
    }

    @Test
    @Order(2)
    @DisplayName("TC-WF-JOURNAL-002 an acknowledgement names a person and claims nothing more")
    void anAcknowledgementIsNotAnAction() {
        tasks.acknowledge(holder, taskId);

        WorkTaskEventView acknowledgement = tasks.journal(taskId).getLast();
        assertThat(acknowledgement.eventKind()).isEqualTo("ACKNOWLEDGED");
        assertThat(acknowledgement.actorUserId()).isEqualTo(graph.executorUserId());

        // The whole point. "I have seen it" cannot satisfy a stage that asks
        // whether anything was done, whatever a dashboard counts.
        assertThat(acknowledgement.satisfiesActionStage()).isFalse();
        assertThat(tasks.journal(taskId))
                .noneMatch(WorkTaskEventView::satisfiesActionStage);

        // An acknowledgement carrying an action would be an action recorded
        // under a quieter name.
        assertThatThrownBy(() ->
                appendRaw("ACKNOWLEDGED", "action_kind", "'DECISION_APPROVED'"))
                .hasMessageContaining("work_task_event_acknowledgement_shape_ck");

        // And an acknowledgement with nobody behind it is not an
        // acknowledgement. This is the constraint that makes the count on a
        // service-level report mean people rather than requests.
        assertThatThrownBy(() -> seed.sql("""
                INSERT INTO ops.work_task_event (id, task_id, organization_id, sequence_no,
                        event_kind, lineage_key, reason, occurred_at, correlation_id)
                VALUES (gen_random_uuid(), :task, :organization, 800, 'ACKNOWLEDGED',
                        :lineage, 'an acknowledgement with nobody behind it', now(),
                        'journal-it')
                """)
                .param("task", taskId).param("organization", graph.organizationId())
                .param("lineage", lineageKey()).update())
                .hasMessageContaining("work_task_event_acknowledgement_shape_ck");
    }

    @Test
    @Order(3)
    @DisplayName("TC-WF-JOURNAL-003 an action carries its evidence and its actor, or it is refused")
    void anActionCarriesWhatItRestsOn() {
        tasks.recordAction(holder, taskId, "DATA_OR_MAPPING_REPAIR",
                "evidence://fixture/repair/1", "the mapping was corrected at source");

        WorkTaskEventView action = tasks.journal(taskId).getLast();
        assertThat(action.eventKind()).isEqualTo("ACTION_RECORDED");
        assertThat(action.satisfiesActionStage()).isTrue();
        assertThat(action.actionKind()).isEqualTo("DATA_OR_MAPPING_REPAIR");
        assertThat(action.evidenceReference()).isEqualTo("evidence://fixture/repair/1");

        // An action is a thing done. What it achieved is observed later, against
        // evidence this moment does not have, so an action event may not carry
        // an outcome at all.
        assertThat(action.outcome()).isFalse();
        assertThatThrownBy(() -> seed.sql("""
                INSERT INTO ops.work_task_event (id, task_id, organization_id, sequence_no,
                        event_kind, lineage_key, action_kind, action_evidence,
                        evidence_reference, outcome_kind, outcome_reference, actor_user_id,
                        reason, occurred_at, correlation_id)
                VALUES (gen_random_uuid(), :task, :organization, 901, 'ACTION_RECORDED',
                        :lineage, 'DATA_OR_MAPPING_REPAIR',
                        '{"reference":"evidence://fixture/repair/2"}'::jsonb,
                        'evidence://fixture/repair/2', 'SETTLED',
                        'ad-outcome-observation:none', :actor,
                        'an action claiming to know what it achieved', now(), 'journal-it')
                """)
                .param("task", taskId).param("organization", graph.organizationId())
                .param("lineage", lineageKey()).param("actor", graph.executorUserId()).update())
                .hasMessageContaining("work_task_event_outcome_exclusive_ck");

        // Three requirements, and the schema refuses a row missing any of them.
        assertThatThrownBy(() -> seed.sql("""
                INSERT INTO ops.work_task_event (id, task_id, organization_id, sequence_no,
                        event_kind, lineage_key, action_kind, reason, occurred_at,
                        correlation_id)
                VALUES (gen_random_uuid(), :task, :organization, 900, 'ACTION_RECORDED',
                        :lineage, 'DATA_OR_MAPPING_REPAIR',
                        'an action with no evidence and no actor', now(), 'journal-it')
                """)
                .param("task", taskId).param("organization", graph.organizationId())
                .param("lineage", lineageKey()).update())
                .hasMessageContaining("work_task_event_action_shape_ck");
    }

    @Test
    @Order(4)
    @DisplayName("TC-WF-JOURNAL-004 what it achieved is a later reading, made against an observation")
    void anOutcomeIsNotTheAction() {
        tasks.recordOutcome(taskId, "SETTLED", "ad-outcome-observation:" + UUID.randomUUID(),
                "the settled window improved", "journal-it");

        WorkTaskEventView outcome = tasks.journal(taskId).getLast();
        assertThat(outcome.eventKind()).isEqualTo("OUTCOME_OBSERVED");
        assertThat(outcome.outcome()).isTrue();

        // It names the observation it read, rather than asserting a result of
        // its own. "We did it" and "it worked" are separate claims and this is
        // the second one.
        assertThat(outcome.outcomeReference()).startsWith("ad-outcome-observation:");
        assertThat(outcome.satisfiesActionStage()).isFalse();

        // The action and the outcome are two rows, in that order, and the action
        // still says exactly what it said before the outcome existed.
        List<WorkTaskEventView> journal = tasks.journal(taskId);
        assertThat(journal.stream().filter(WorkTaskEventView::action).count()).isEqualTo(1);
        assertThat(journal.stream().filter(WorkTaskEventView::outcome).count()).isEqualTo(1);
        assertThat(journal.stream().filter(WorkTaskEventView::action).findFirst().orElseThrow()
                .sequenceNo())
                .isLessThan(outcome.sequenceNo());
    }

    @Test
    @Order(5)
    @DisplayName("TC-WF-JOURNAL-005 a task that changes hands keeps the age of the work")
    void aReassignmentDoesNotResetTheAge() {
        tasks.assign(holder, taskId, graph.executorUserId(), version());
        WorkTaskEventView first = tasks.journal(taskId).getLast();
        assertThat(first.eventKind()).isEqualTo("ASSIGNED");
        assertThat(first.handover()).isFalse();
        assertThat(first.toAssigneeUserId()).isEqualTo(graph.executorUserId());

        tasks.assign(holder, taskId, graph.verifierUserId(), version());
        WorkTaskEventView handover = tasks.journal(taskId).getLast();

        // A handover is a different event from a first assignment, and it names
        // both people. A journal that recorded only the new holder could not
        // answer who had it when the clock was running.
        assertThat(handover.eventKind()).isEqualTo("REASSIGNED");
        assertThat(handover.handover()).isTrue();
        assertThat(handover.fromAssigneeUserId()).isEqualTo(graph.executorUserId());
        assertThat(handover.toAssigneeUserId()).isEqualTo(graph.verifierUserId());

        // The age is the age of the work, not of the current holder's
        // involvement. A reassignment that reset this would let a queue look
        // healthy by being passed around.
        assertThat(firstRaisedAt()).isCloseTo(raisedAt, within100Millis());

        // And the instant cannot be moved, by the owning role either.
        assertThatThrownBy(() -> seed.sql(
                        "UPDATE ops.work_task SET first_raised_at = now() WHERE id = :id")
                .param("id", taskId).update())
                .hasMessageContaining("a task keeps the instant it was raised");
    }

    @Test
    @Order(6)
    @DisplayName("TC-WF-JOURNAL-006 a reopen continues the lineage rather than starting one")
    void aReopenPreservesTheLineage() {
        tasks.reopen(successor, taskId, false, "the same fault came back");
        tasks.reopen(successor, taskId, true, "and it came back again");

        List<WorkTaskEventView> lineage =
                tasks.lineage(graph.organizationId(), lineageKey());

        // Everything that ever happened to this work, in one story. Several
        // reopens in one lineage is the signal a recurring fault gives; several
        // lineages for the same fault is how one stays invisible.
        assertThat(lineage).isNotEmpty();
        assertThat(lineage).allMatch(event -> lineageKey().equals(event.lineageKey()));
        assertThat(lineage.stream().map(WorkTaskEventView::eventKind))
                .contains("VIEWED", "ACKNOWLEDGED", "ACTION_RECORDED", "OUTCOME_OBSERVED",
                        "ASSIGNED", "REASSIGNED", "REOPENED", "ESCALATED");

        // An escalation is not a new task and not a new lineage. It is the same
        // work, raised at the same instant, going up.
        assertThat(firstRaisedAt()).isCloseTo(raisedAt, within100Millis());
        assertThat(tasks.journal(taskId)).hasSameSizeAs(lineage);
    }

    @Test
    @Order(7)
    @DisplayName("TC-WF-JOURNAL-007 the journal is append-only, and the grants are why")
    void theJournalCannotBeEdited() {
        long before = tasks.journal(taskId).size();

        // Not a policy the service enforces. There is no UPDATE and no DELETE to
        // grant, so a future writer cannot decide to be more helpful.
        assertThat(seed.sql("""
                SELECT count(*) FROM information_schema.role_table_grants
                 WHERE grantee = 'marketops_app' AND table_schema = 'ops'
                   AND table_name = 'work_task_event'
                   AND privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE')
                """).query(Long.class).single()).isZero();
        assertThat(seed.sql("""
                SELECT count(*) FROM information_schema.role_table_grants
                 WHERE grantee = 'marketops_app' AND table_schema = 'ops'
                   AND table_name = 'work_task_event' AND privilege_type = 'INSERT'
                """).query(Long.class).single()).isEqualTo(1);

        // The sequence number is allocated inside the insert, so two appends
        // racing for the same task collide rather than both claiming third
        // place. A duplicate is refused by the constraint, not by a comment.
        assertThatThrownBy(() -> seed.sql("""
                INSERT INTO ops.work_task_event (id, task_id, organization_id, sequence_no,
                        event_kind, lineage_key, actor_user_id, reason, occurred_at,
                        correlation_id)
                VALUES (gen_random_uuid(), :task, :organization, 1, 'VIEWED', :lineage,
                        :actor, 'a second event claiming the first place', now(),
                        'journal-it')
                """)
                .param("task", taskId).param("organization", graph.organizationId())
                .param("lineage", lineageKey()).param("actor", graph.executorUserId()).update())
                .hasMessageContaining("work_task_event_sequence_uq");

        assertThat(tasks.journal(taskId)).hasSize((int) before);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Append a row of one kind carrying one column it must not carry. */
    private void appendRaw(String eventKind, String column, String value) {
        seed.sql("""
                INSERT INTO ops.work_task_event (id, task_id, organization_id, sequence_no,
                        event_kind, lineage_key, actor_user_id, %s, reason, occurred_at,
                        correlation_id)
                VALUES (gen_random_uuid(), :task, :organization,
                        (SELECT coalesce(max(sequence_no), 0) + 500 FROM ops.work_task_event
                          WHERE task_id = :task),
                        :eventKind, :lineage, :actor, %s,
                        'a row this schema must refuse', now(), 'journal-it')
                """.formatted(column, value))
                .param("task", taskId)
                .param("organization", graph.organizationId())
                .param("eventKind", eventKind)
                .param("lineage", lineageKey())
                .param("actor", graph.executorUserId())
                .update();
    }

    private String lineageKey() {
        return "recommendation:" + decision.recommendationId();
    }

    private Instant firstRaisedAt() {
        return seed.sql("SELECT first_raised_at FROM ops.work_task WHERE id = :id")
                .param("id", taskId).query(Timestamp.class).single().toInstant();
    }

    private long version() {
        return tasks.find(taskId).orElseThrow().version();
    }

    private static org.assertj.core.data.TemporalUnitOffset within100Millis() {
        return org.assertj.core.api.Assertions.within(100, java.time.temporal.ChronoUnit.MILLIS);
    }

    private static AuthenticatedActor actor(UUID userId) {
        Instant now = Instant.now();
        return new AuthenticatedActor(userId, graph.organizationId(),
                UUID.fromString("bbbbbbbb-0000-4000-8000-0000000010d1"),
                "https://identity.fixture.invalid/journal", "Journal fixture",
                "a".repeat(64), "b".repeat(64), now, now.plus(Duration.ofMinutes(10)), true,
                Set.of(BusinessRoleCode.OWNER));
    }

    /**
     * Assignment authority at organization scope, separate from diagnostic read access.
     *
     * <p>Seeded rather than assumed, because a test whose actor could do
     * anything would prove nothing about a product whose whole authorization
     * model is that people can do exactly what they were granted.
     */
    private static void grantTaskAssignment(UUID userId) {
        seed.sql("""
                INSERT INTO iam.user_role_assignment (id, organization_id, user_id, role_code,
                        effective_from, status, created_at, updated_at)
                VALUES (gen_random_uuid(), :organization, :user, 'OWNER',
                        now() - interval '1 hour', 'ACTIVE', now(), now())
                """).param("organization", graph.organizationId()).param("user", userId).update();
        seed.sql("""
                INSERT INTO iam.user_scope_grant (id, organization_id, user_id, action_code,
                        organization_ref_id, effective_from, status, created_at, updated_at)
                VALUES (gen_random_uuid(), :organization, :user, 'TASK_ASSIGN', :organization,
                        now() - interval '1 hour', 'ACTIVE', now(), now())
                """).param("organization", graph.organizationId()).param("user", userId).update();
    }

    private static UUID grantDiagnosticView(UUID userId) {
        UUID grantId = UUID.randomUUID();
        seed.sql("""
                INSERT INTO iam.user_scope_grant (id, organization_id, user_id, action_code,
                        organization_ref_id, effective_from, status, created_at, updated_at)
                VALUES (:id, :organization, :user, 'DIAGNOSTIC_VIEW', :organization,
                        now() - interval '1 hour', 'ACTIVE', now(), now())
                """).param("id", grantId).param("organization", graph.organizationId())
                .param("user", userId).update();
        return grantId;
    }
}

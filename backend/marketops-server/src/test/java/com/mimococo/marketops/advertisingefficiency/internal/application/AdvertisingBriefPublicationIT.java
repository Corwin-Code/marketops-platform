package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.AdvertisingGraphFixture;
import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.advertisingefficiency.AdvertisingBriefView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * The daily brief and the weekly review, published and then restated.
 *
 * <p>What these cases are really about is the difference between a report and an
 * authority. A brief links to canonical rows and holds no figure of its own, it
 * is never edited after somebody has read it, and when late facts change what a
 * period contained it says so in a further publication rather than quietly
 * becoming a different document.
 *
 * <p>The last case is the one worth reading twice: it proves the database
 * refuses to update a published report at all. Everything else in this file
 * could be arranged by a careful service; that one cannot be undone by a
 * careless one.
 */
@SpringBootTest
@ActiveProfiles("ci")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdvertisingBriefPublicationIT {

    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE =
            TestDatabase.isolatedContainer();

    /** A Friday, so an operating-day calendar that excludes weekends still publishes. */
    private static final Instant AS_OF = Instant.parse("2026-09-04T18:00:00Z");

    private static JdbcClient seed;
    private static AdvertisingGraphFixture.Graph graph;
    private static UUID calendarId;

    @Autowired
    private AdvertisingBriefService briefs;

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
        calendarId = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_reporting_calendar (
                    id, organization_id, policy_version, scope_kind, reporting_timezone,
                    daily_cut_minute, operating_days, weekly_cut_weekday, weekly_cut_minute,
                    late_revision_horizon_hours, owner_user_id, reason, evidence_reference,
                    effective_from, status, created_at)
                VALUES (:id, :organizationId, 1, 'ORGANIZATION', 'Europe/Moscow',
                    540, ARRAY[1,2,3,4,5]::smallint[], 1, 540, 168, :owner,
                    'synthetic reporting calendar for a publication test',
                    'evidence://fixture/ad/calendar', now() - interval '7 days', 'ACTIVE', now())
                """)
                .param("id", calendarId)
                .param("organizationId", graph.organizationId())
                .param("owner", graph.executorUserId())
                .update();
    }

    @Test
    @Order(1)
    @DisplayName("TC-AD-BRIEF-001 nothing is published for an organization with no calendar")
    void noCalendarPublishesNothing() {
        // An operating day is a business fact. Publishing on a day nobody chose
        // would be inventing the schedule the calendar exists to state.
        assertThat(briefs.publish(UUID.randomUUID(), "DAILY_ACTION_BRIEF", AS_OF, null))
                .isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("TC-AD-BRIEF-002 a daily brief emits every named section, empty ones included")
    void everySectionIsEmitted() {
        var published = briefs.publish(graph.organizationId(), "DAILY_ACTION_BRIEF", AS_OF, null)
                .orElseThrow();
        assertThat(published.revisionNo()).isEqualTo(1);
        assertThat(published.revisionKind()).isEqualTo("ORIGINAL");

        AdvertisingBriefView view = briefs
                .latest(graph.organizationId(), "DAILY_ACTION_BRIEF", published.periodKey())
                .orElseThrow();

        // A section that vanished when empty would make "we looked and found
        // nothing" and "we never looked" the same page.
        assertThat(view.sections()).hasSize(AdvertisingBriefService.DAILY_SECTIONS.size());
        assertThat(view.sections().stream().map(AdvertisingBriefView.Section::sectionCode))
                .containsExactlyElementsOf(AdvertisingBriefService.DAILY_SECTIONS);
        assertThat(view.asOf()).isEqualTo(AS_OF);
    }

    @Test
    @Order(3)
    @DisplayName("TC-AD-BRIEF-003 a topic with no canonical source says so rather than showing zero")
    void anUnsourcedTopicSaysSo() {
        AdvertisingBriefView view = briefs
                .latest(graph.organizationId(), "WEEKLY_EVIDENCE_REVIEW",
                        briefs.publish(graph.organizationId(), "WEEKLY_EVIDENCE_REVIEW", AS_OF,
                                null).orElseThrow().periodKey())
                .orElseThrow();

        var gate = view.sections().stream()
                .filter(section -> "GATE_EVIDENCE".equals(section.sectionCode()))
                .findFirst().orElseThrow();
        // Gate evidence lives in an Owner's decision, not in a table this
        // product owns. Reporting it as zero would say it had been checked.
        assertThat(gate.coverageState()).isEqualTo("NOT_AVAILABLE");
        assertThat(gate.blockerCodes()).containsExactly("NO_CANONICAL_GATE_EVIDENCE_SOURCE");
        assertThat(gate.complete()).isFalse();
        assertThat(view.fullyCovered()).isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("TC-AD-BRIEF-004 republishing an unchanged period writes nothing")
    void anUnchangedPeriodIsNotRestated() {
        var first = briefs.publish(graph.organizationId(), "DAILY_ACTION_BRIEF", AS_OF, null)
                .orElseThrow();
        var again = briefs.publish(graph.organizationId(), "DAILY_ACTION_BRIEF", AS_OF, null)
                .orElseThrow();

        // A revision that restated nothing would be noise in a lineage whose
        // whole purpose is to make a real change visible.
        assertThat(again.unchanged()).isTrue();
        assertThat(again.publicationId()).isEqualTo(first.publicationId());
        assertThat(briefs.history(graph.organizationId(), "DAILY_ACTION_BRIEF",
                first.periodKey())).hasSize(1);
    }

    @Test
    @Order(5)
    @DisplayName("TC-AD-BRIEF-005 a late fact produces a revision beside the original, not over it")
    void aLateFactProducesARevision() {
        var original = briefs.publish(graph.organizationId(), "DAILY_ACTION_BRIEF", AS_OF, null)
                .orElseThrow();
        AdvertisingBriefView before = briefs
                .latest(graph.organizationId(), "DAILY_ACTION_BRIEF", original.periodKey())
                .orElseThrow();

        // A case calculated inside the period, arriving after the first reading.
        // The instant is read from the publication rather than guessed: the
        // period a daily brief covers is the day that closed at the calendar's
        // last cut, which is not the day the report was published on.
        seedLateCase(seed.sql("""
                SELECT period_starts_at + interval '1 hour'
                  FROM ops.ad_brief_publication WHERE id = :id
                """).param("id", before.id()).query(Timestamp.class).single().toInstant());

        var revised = briefs.publish(graph.organizationId(), "DAILY_ACTION_BRIEF", AS_OF,
                "fact://late/ad-case").orElseThrow();

        assertThat(revised.unchanged()).isFalse();
        assertThat(revised.revisionNo()).isEqualTo(2);
        assertThat(revised.revisionKind()).isEqualTo("REVISION");

        List<AdvertisingBriefView> history = briefs.history(graph.organizationId(),
                "DAILY_ACTION_BRIEF", original.periodKey());
        assertThat(history).hasSize(2);
        // The original still says what it said. Somebody read it, and may have
        // acted on it.
        assertThat(history.getFirst().id()).isEqualTo(before.id());
        assertThat(history.getFirst().contentDigest()).isEqualTo(before.contentDigest());
        assertThat(history.getFirst().restatement()).isFalse();

        AdvertisingBriefView after = history.getLast();
        assertThat(after.restatement()).isTrue();
        assertThat(after.supersedesPublicationId()).isEqualTo(before.id());
        assertThat(after.lateFactReference()).isEqualTo("fact://late/ad-case");
        assertThat(after.contentDigest()).isNotEqualTo(before.contentDigest());

        // And the change is stated, not left to be found by diffing two bodies.
        assertThat(seed.sql("""
                SELECT count(*) FROM mart.ad_brief_delta
                 WHERE publication_id = :id AND change_kind = 'ADDED'
                """).param("id", after.id()).query(Long.class).single()).isPositive();
    }

    @Test
    @Order(6)
    @DisplayName("TC-AD-BRIEF-006 every item names exactly one canonical row")
    void everyItemNamesOneCanonicalRow() {
        // The schema enforces it, and this asserts the producer actually writes
        // items rather than a brief of empty sections that would satisfy the
        // constraint vacuously.
        assertThat(seed.sql("""
                SELECT count(*) FROM mart.ad_brief_item
                 WHERE organization_id = :organizationId
                """).param("organizationId", graph.organizationId())
                .query(Long.class).single()).isPositive();
        assertThat(seed.sql("""
                SELECT count(*) FROM mart.ad_brief_item
                 WHERE organization_id = :organizationId
                   AND num_nonnulls(case_id, work_task_id, recommendation_id,
                                    outcome_observation_id, slo_observation_id, containment_id,
                                    reservation_id, bid_command_id, manual_packet_id,
                                    bundle_id, metric_value_id) <> 1
                """).param("organizationId", graph.organizationId())
                .query(Long.class).single()).isZero();
    }

    @Test
    @Order(7)
    @DisplayName("TC-AD-BRIEF-007 a published brief cannot be edited or removed by anybody")
    void aPublishedBriefIsPermanent() {
        UUID published = briefs
                .latest(graph.organizationId(), "WEEKLY_EVIDENCE_REVIEW",
                        briefs.publish(graph.organizationId(), "WEEKLY_EVIDENCE_REVIEW", AS_OF,
                                null).orElseThrow().periodKey())
                .orElseThrow().id();

        // Refused for the owning role too, not merely ungranted to the
        // application. A report somebody read is the basis of a decision, and
        // editing it afterwards would make that decision unauditable.
        assertThatThrownBy(() -> seed.sql(
                "UPDATE ops.ad_brief_publication SET content_digest = :digest WHERE id = :id")
                .param("digest", "0".repeat(64)).param("id", published).update())
                .hasMessageContaining("a published brief is a permanent record");
        assertThatThrownBy(() -> seed.sql(
                "DELETE FROM ops.ad_brief_publication WHERE id = :id")
                .param("id", published).update())
                .hasMessageContaining("a published brief is a permanent record");
    }

    @Test
    @Order(8)
    @DisplayName("TC-AD-BRIEF-008 the application role may add to a brief and may not change one")
    void theApplicationRoleCanOnlyAppend() {
        for (String table : List.of("ops.ad_brief_publication", "mart.ad_brief_section",
                "mart.ad_brief_item", "mart.ad_brief_delta")) {
            assertThat(seed.sql(
                    "SELECT has_table_privilege(:role, :table, 'INSERT')")
                    .param("role", TestDatabase.applicationRole()).param("table", table)
                    .query(Boolean.class).single()).as(table + " insert").isTrue();
            for (String privilege : List.of("UPDATE", "DELETE")) {
                assertThat(seed.sql(
                        "SELECT has_table_privilege(:role, :table, :privilege)")
                        .param("role", TestDatabase.applicationRole()).param("table", table)
                        .param("privilege", privilege)
                        .query(Boolean.class).single())
                        .as(table + " " + privilege).isFalse();
            }
        }
    }

    @Test
    @Order(9)
    @DisplayName("TC-AD-BRIEF-009 the weekly review covers every governance topic it names")
    void theWeeklyReviewCoversItsTopics() {
        var published = briefs.publish(graph.organizationId(), "WEEKLY_EVIDENCE_REVIEW", AS_OF,
                null).orElseThrow();
        AdvertisingBriefView review = briefs
                .latest(graph.organizationId(), "WEEKLY_EVIDENCE_REVIEW", published.periodKey())
                .orElseThrow();

        // Shadow decisions, governed actions, configuration verification, early
        // guards, the two outcome stages, regression and compensation,
        // exceptions, both service levels, aggregate exposure, bundle maturity,
        // gate evidence and the deferred release obligations. Twelve topics, all
        // emitted — a review that dropped the ones with nothing in them would be
        // a review of whatever happened to be non-empty.
        assertThat(review.sections()).extracting(AdvertisingBriefView.Section::sectionCode)
                .containsExactly("SHADOW_DECISION_REASONS", "GOVERNED_ACTIONS",
                        "CONFIGURATION_VERIFICATION", "EARLY_GUARDS",
                        "OPERATIONAL_AND_SETTLED_TRANSITIONS",
                        "REGRESSION_QUARANTINE_AND_COMPENSATION", "EXCEPTIONS",
                        "SYSTEM_AND_HUMAN_SLO", "AGGREGATE_EXPOSURE", "POLICY_BUNDLE_MATURITY",
                        "GATE_EVIDENCE", "DEFERRED_RELEASE_OBLIGATIONS");

        // The week is a period, not a day, and it is named as one.
        assertThat(published.periodKey()).matches("\\d{4}-W\\d{2}");
        assertThat(review.periodEndsAt()).isAfter(review.periodStartsAt());

        // The two topics this database holds no source for say so, and the
        // publication carries their reasons as its own gaps.
        assertThat(review.fullyCovered()).isFalse();
        assertThat(review.gapCodes()).contains("NO_CANONICAL_GATE_EVIDENCE_SOURCE");
        assertThat(review.sections().stream()
                .filter(section -> !section.complete())
                .map(AdvertisingBriefView.Section::sectionCode))
                .containsExactlyInAnyOrder("GATE_EVIDENCE", "DEFERRED_RELEASE_OBLIGATIONS");
    }

    @Test
    @Order(10)
    @DisplayName("TC-AD-BRIEF-010 a day the calendar does not operate publishes nothing")
    void aNonOperatingDayPublishesNothing() {
        // The calendar names Monday to Friday. A Saturday is not a day this
        // organization operates, and a brief produced on one would be inventing
        // a schedule nobody chose — which matters because the brief's period is
        // what a service level is measured against.
        Instant saturday = AS_OF.plus(java.time.Duration.ofDays(1));
        assertThat(saturday.atZone(java.time.ZoneId.of("Europe/Moscow")).getDayOfWeek())
                .isEqualTo(java.time.DayOfWeek.SATURDAY);
        assertThat(briefs.publish(graph.organizationId(), "DAILY_ACTION_BRIEF", saturday, null))
                .isEmpty();

        // The weekly review is not bound to operating days: a week is a week
        // whichever days inside it were worked.
        assertThat(briefs.publish(graph.organizationId(), "WEEKLY_EVIDENCE_REVIEW", saturday,
                null)).isPresent();
    }

    /** One more live case inside the period, as a late fact would deliver it. */
    private void seedLateCase(Instant calculatedAt) {
        UUID caseId = UUID.randomUUID();
        UUID calculationId = UUID.randomUUID();
        seed.sql("""
                INSERT INTO mart.ad_case (
                    id, organization_id, store_id, platform_code, ad_native_object_id,
                    affected_set_id, semantic_profile_id, lineage_generation, case_key, lane,
                    cause_code, evidence_state, confidence_state, blocker_codes,
                    contribution_profit_state, profit_per_ad_rub_state, official_spend_state,
                    eligible_traffic_state, ad_linked_conversion_state, max_cpc_state,
                    attribution_gap_state, current_bid_state, rank_score, policy_version_digest,
                    as_of, calculated_at, calculation_kind, calculation_id, created_at,
                    updated_at)
                SELECT :caseId, obj.organization_id, obj.store_id, obj.platform_code, obj.id,
                       a.id, obj.semantic_profile_id, obj.lineage_generation,
                       'LATE|' || obj.id, 'WATCH', 'IMMATURE_SIGNAL', 'INCOMPLETE', 'LOW',
                       '{}', 'NOT_AVAILABLE', 'NOT_AVAILABLE', 'NOT_AVAILABLE',
                       'NOT_AVAILABLE', 'NOT_AVAILABLE', 'NOT_AVAILABLE', 'NOT_AVAILABLE',
                       'NOT_AVAILABLE', 100.0000, :digest, :at, :at, 'TARGETED',
                       :calculationId, now(), now()
                  FROM core.ad_native_object obj
                  JOIN core.ad_affected_set a
                    ON a.ad_native_object_id = obj.id AND a.organization_id = obj.organization_id
                 WHERE obj.id = :objectId
                """)
                .param("caseId", caseId)
                .param("digest", "e".repeat(64))
                .param("at", Timestamp.from(calculatedAt))
                .param("calculationId", calculationId)
                .param("objectId", graph.objectId())
                .update();
        assertThat(Optional.of(caseId)).isPresent();
    }
}

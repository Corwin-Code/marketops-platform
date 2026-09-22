package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.Category;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.Event;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.PurposeRequirement;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.Value;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only access to calibration packages, their values, governance and events.
 *
 * <p>Nothing here writes: drafting, validation, acceptance and activation go
 * through the {@code ops.*_lc_calibration} functions, which consume a one-use
 * invocation proof. Completeness and combination checks are the database's own
 * functions, called here so the console shows exactly what validation and
 * activation will check.
 */
@Repository
public class ListingCalibrationRepository {

    /** Where a package applies, for authorization before it is read. */
    public record PackageScope(UUID id, UUID organizationId, String scopeKind, UUID storeId) {
    }

    /** A store a store-scoped package may name. */
    public record StoreRow(UUID storeId, String code, String displayName, String platformCode, String currencyCode) {
    }

    /** One lifecycle step as recorded: who, when, on what evidence, over which digest. */
    public record Step(UUID userId, String displayName, Instant at, String reference, String digest) {
    }

    /**
     * One package with its governance, the latest activation and retirement events,
     * and what the database's completeness functions say about it now.
     *
     * @param combinationFailures raw answer of {@code ops.lc_calibration_combination_failures}
     *        (missing categories included, not deduplicated), or null when not evaluated
     * @param latestVersionOfCode the highest version of this code in the organization, which is
     *        where the code and version are unique
     * @param latestVersionInScope the highest version of this code in this package's own scope,
     *        which is where one package supersedes another
     * @param activeOverlapIds other active packages of the same scope key and purpose whose
     *        effective period overlaps this one, as activation looks them up
     */
    public record PackageRow(UUID id, String code, int version, String purposeCode, String scopeKind,
                             String platformCode, UUID storeId, String storeName, String storePlatformCode,
                             String status, Instant effectiveFrom, Instant effectiveTo, String evidenceReference,
                             UUID replacesPackageId, UUID replacedByPackageId, String rationale, String impact,
                             String differences, Step drafted, Step validated, Step accepted, Step activated,
                             Step retired, String currentDigest, List<String> requiredCategories,
                             List<String> missingCategories, List<String> combinationFailures,
                             int latestVersionOfCode, int latestVersionInScope, List<UUID> activeOverlapIds) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ListingCalibrationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Instant databaseNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
    }

    /**
     * Every package of the organization, or only {@code packageId} when it is given.
     * Combination failures are evaluated for drafts, and for the single package asked for.
     */
    public List<PackageRow> packages(UUID organizationId, UUID packageId) {
        return jdbc.sql("""
                SELECT p.id, p.package_code, p.package_version, p.purpose_code, p.scope_kind, p.platform_code,
                       p.store_ref_id, s.display_name AS store_name, account.platform_code AS store_platform,
                       p.status, p.effective_from, p.effective_to, p.evidence_reference,
                       g.replaces_package_id, g.rationale, g.impact, g.differences,
                       g.drafted_by_user_id, drafter.display_name AS drafted_name, g.drafted_at, g.draft_digest,
                       g.validated_by_user_id, validator.display_name AS validated_name, g.validated_at,
                       g.validation_reference, g.validated_digest,
                       g.accepted_by_user_id, acceptor.display_name AS accepted_name, g.accepted_at,
                       g.acceptance_reference, g.accepted_digest,
                       activation.actor_user_id AS activated_by, activator.display_name AS activated_name,
                       activation.occurred_at AS activated_at, activation.evidence_reference AS activation_reference,
                       activation.package_digest AS activation_digest,
                       retirement.actor_user_id AS retired_by, retirer.display_name AS retired_name,
                       retirement.occurred_at AS retired_at, retirement.evidence_reference AS retirement_reference,
                       retirement.package_digest AS retirement_digest,
                       ops.lc_calibration_digest(p.id) AS current_digest,
                       core.lc_calibration_required_categories(p.purpose_code) AS required,
                       core.lc_calibration_package_failures(p.id) AS missing,
                       CASE WHEN p.status = 'DRAFT' OR CAST(:single AS uuid) IS NOT NULL
                            THEN ops.lc_calibration_combination_failures(p.id) END AS combination,
                       (SELECT max(n.package_version) FROM core.lc_calibration_package n
                         WHERE n.organization_id = p.organization_id AND n.package_code = p.package_code)
                         AS latest_version,
                       (SELECT max(n.package_version) FROM core.lc_calibration_package n
                         WHERE n.organization_id = p.organization_id AND n.package_code = p.package_code
                           AND n.scope_key = p.scope_key)
                         AS latest_version_in_scope,
                       (SELECT r.package_id FROM ops.lc_calibration_governance r
                          JOIN core.lc_calibration_package rp ON rp.id = r.package_id
                         WHERE r.replaces_package_id = p.id AND rp.status <> 'DRAFT'
                         ORDER BY rp.activated_at DESC NULLS LAST, r.package_id LIMIT 1) AS replaced_by,
                       ARRAY(SELECT c.id::text FROM core.lc_calibration_package c
                              WHERE c.organization_id = p.organization_id AND c.scope_key = p.scope_key
                                AND c.purpose_code = p.purpose_code AND c.status = 'ACTIVE' AND c.id <> p.id
                                AND tstzrange(c.effective_from, c.effective_to, '[)')
                                    && tstzrange(p.effective_from, p.effective_to, '[)')
                              ORDER BY c.id) AS active_overlaps
                FROM core.lc_calibration_package p
                JOIN ops.lc_calibration_governance g ON g.package_id = p.id
                LEFT JOIN core.store s ON s.id = p.store_ref_id
                LEFT JOIN core.marketplace_account account ON account.id = s.marketplace_account_id
                LEFT JOIN iam.user_account drafter ON drafter.id = g.drafted_by_user_id
                LEFT JOIN iam.user_account validator ON validator.id = g.validated_by_user_id
                LEFT JOIN iam.user_account acceptor ON acceptor.id = g.accepted_by_user_id
                LEFT JOIN LATERAL (SELECT e.actor_user_id, e.occurred_at, e.evidence_reference, e.package_digest
                                     FROM ops.lc_calibration_event e
                                    WHERE e.package_id = p.id AND e.event_kind = 'ACTIVATED'
                                    ORDER BY e.occurred_at DESC, e.id LIMIT 1) activation ON true
                LEFT JOIN iam.user_account activator ON activator.id = activation.actor_user_id
                LEFT JOIN LATERAL (SELECT e.actor_user_id, e.occurred_at, e.evidence_reference, e.package_digest
                                     FROM ops.lc_calibration_event e
                                    WHERE e.package_id = p.id AND e.event_kind = 'RETIRED'
                                    ORDER BY e.occurred_at DESC, e.id LIMIT 1) retirement ON true
                LEFT JOIN iam.user_account retirer ON retirer.id = retirement.actor_user_id
                WHERE p.organization_id = :org AND (CAST(:single AS uuid) IS NULL OR p.id = CAST(:single AS uuid))
                ORDER BY p.purpose_code, p.scope_kind, p.package_code, p.package_version DESC, p.id
                """).param("org", organizationId).param("single", packageId)
                .query((rs, n) -> packageRow(rs)).list();
    }

    /** The values of one package, in catalogue order. */
    public List<Value> values(UUID packageId) {
        return jdbc.sql("""
                SELECT v.category_code, c.value_shape, v.value_numeric, v.value_text, v.value_json::text AS value_json,
                       v.unit_code, v.window_days, v.scope_note, v.evidence_reference
                FROM core.lc_calibration_value v
                LEFT JOIN core.lc_calibration_category c ON c.code = v.category_code
                WHERE v.package_id = :id
                ORDER BY c.ordinal NULLS LAST, v.category_code
                """).param("id", packageId)
                .query((rs, n) -> {
                    String document = rs.getString("value_json");
                    Object window = rs.getObject("window_days");
                    return new Value(rs.getString("category_code"), rs.getString("value_shape"),
                            plain(rs.getBigDecimal("value_numeric")), rs.getString("value_text"),
                            document == null ? null : json.readTree(document), rs.getString("unit_code"),
                            window == null ? null : ((Number) window).intValue(), rs.getString("scope_note"),
                            rs.getString("evidence_reference"));
                }).list();
    }

    /** The lifecycle events of one package, oldest first. */
    public List<Event> events(UUID packageId) {
        return jdbc.sql("""
                SELECT e.id, e.event_kind, e.actor_user_id, u.display_name, e.occurred_at, e.package_digest,
                       e.evidence_reference
                FROM ops.lc_calibration_event e
                LEFT JOIN iam.user_account u ON u.id = e.actor_user_id
                WHERE e.package_id = :id
                ORDER BY e.occurred_at, e.id
                """).param("id", packageId)
                .query((rs, n) -> new Event(rs.getObject("id", UUID.class), rs.getString("event_kind"),
                        rs.getObject("actor_user_id", UUID.class), rs.getString("display_name"),
                        instant(rs, "occurred_at"), rs.getString("package_digest"),
                        rs.getString("evidence_reference")))
                .list();
    }

    public Optional<PackageScope> scope(UUID packageId) {
        return jdbc.sql("SELECT id, organization_id, scope_kind, store_ref_id FROM core.lc_calibration_package WHERE id = :id")
                .param("id", packageId)
                .query((rs, n) -> new PackageScope(rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getString("scope_kind"),
                        rs.getObject("store_ref_id", UUID.class)))
                .optional();
    }

    public List<Category> categories() {
        return jdbc.sql("SELECT code, display_name, value_shape, ordinal FROM core.lc_calibration_category ORDER BY ordinal, code")
                .query((rs, n) -> new Category(rs.getString("code"), rs.getString("display_name"),
                        rs.getString("value_shape"), rs.getInt("ordinal")))
                .list();
    }

    /** What {@code core.lc_calibration_required_categories} requires for each purpose. */
    public List<PurposeRequirement> requirements() {
        return jdbc.sql("""
                SELECT x.purpose, core.lc_calibration_required_categories(x.purpose) AS required
                FROM unnest(ARRAY['LISTING_CONVERSION', 'DESCRIPTION_CORRECTION', 'BOUNDED_EXPLORATION',
                                  'PROMOTION']) WITH ORDINALITY x(purpose, position)
                ORDER BY x.position
                """)
                .query((rs, n) -> new PurposeRequirement(rs.getString("purpose"), texts(rs.getArray("required"))))
                .list();
    }

    /** The organization's stores that are not retired, as the allowance page lists them. */
    public List<StoreRow> stores(UUID organizationId) {
        return jdbc.sql("""
                SELECT s.id, s.code, s.display_name, account.platform_code, s.currency_code
                FROM core.store s JOIN core.marketplace_account account ON account.id = s.marketplace_account_id
                WHERE s.organization_id = :org AND s.status <> 'RETIRED'
                ORDER BY s.display_name, s.id
                """).param("org", organizationId)
                .query((rs, n) -> new StoreRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("display_name"), rs.getString("platform_code"), rs.getString("currency_code")))
                .list();
    }

    public List<String> platforms() {
        return jdbc.sql("SELECT code FROM core.marketplace_platform WHERE status = 'ACTIVE' ORDER BY code")
                .query(String.class).list();
    }

    private static PackageRow packageRow(ResultSet rs) throws SQLException {
        Array combination = rs.getArray("combination");
        Object latest = rs.getObject("latest_version");
        Object latestInScope = rs.getObject("latest_version_in_scope");
        List<UUID> overlaps = new ArrayList<>();
        for (String id : texts(rs.getArray("active_overlaps"))) {
            overlaps.add(UUID.fromString(id));
        }
        return new PackageRow(rs.getObject("id", UUID.class), rs.getString("package_code"),
                rs.getInt("package_version"), rs.getString("purpose_code"), rs.getString("scope_kind"),
                rs.getString("platform_code"), rs.getObject("store_ref_id", UUID.class), rs.getString("store_name"),
                rs.getString("store_platform"), rs.getString("status"), instant(rs, "effective_from"),
                instant(rs, "effective_to"), rs.getString("evidence_reference"),
                rs.getObject("replaces_package_id", UUID.class), rs.getObject("replaced_by", UUID.class),
                rs.getString("rationale"), rs.getString("impact"), rs.getString("differences"),
                step(rs, "drafted_by_user_id", "drafted_name", "drafted_at", "evidence_reference", "draft_digest"),
                step(rs, "validated_by_user_id", "validated_name", "validated_at", "validation_reference",
                        "validated_digest"),
                step(rs, "accepted_by_user_id", "accepted_name", "accepted_at", "acceptance_reference",
                        "accepted_digest"),
                step(rs, "activated_by", "activated_name", "activated_at", "activation_reference",
                        "activation_digest"),
                step(rs, "retired_by", "retired_name", "retired_at", "retirement_reference", "retirement_digest"),
                rs.getString("current_digest"), texts(rs.getArray("required")), texts(rs.getArray("missing")),
                combination == null ? null : texts(combination),
                latest == null ? rs.getInt("package_version") : ((Number) latest).intValue(),
                latestInScope == null ? rs.getInt("package_version") : ((Number) latestInScope).intValue(),
                overlaps);
    }

    /** A recorded step, or null when the step has not been taken. */
    private static Step step(ResultSet rs, String user, String name, String at, String reference, String digest)
            throws SQLException {
        UUID userId = rs.getObject(user, UUID.class);
        return userId == null ? null : new Step(userId, rs.getString(name), instant(rs, at),
                rs.getString(reference), rs.getString(digest));
    }

    private static List<String> texts(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : (Object[]) array.getArray()) {
            if (item != null) {
                out.add(item.toString());
            }
        }
        return out;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** A decimal without trailing zeros and never in exponent notation. */
    private static String plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}

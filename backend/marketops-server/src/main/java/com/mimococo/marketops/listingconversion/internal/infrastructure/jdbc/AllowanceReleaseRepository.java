package com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc;

import com.mimococo.marketops.shared.JsonValues;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The matured-outcome release of description occupations.
 *
 * <p>Eligibility, the release itself and its journal row all live in the database functions
 * {@code ops.lc_description_outcome_maturity} and {@code ops.release_lc_matured_description_occupations};
 * this class only calls them and reads their answers.
 */
@Repository
public class AllowanceReleaseRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AllowanceReleaseRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record ReleasedOccupation(UUID occupationId, UUID actionId, String axisCode) {
    }

    public record Skipped(UUID actionId, String state) {
    }

    public record PassResult(String trigger, Instant evaluatedAt, int considered, List<UUID> releasedActions,
                             List<ReleasedOccupation> releasedOccupations, List<Skipped> skipped,
                             boolean limitReached) {
    }

    public record Maturity(UUID actionId, UUID platformListingId, String executionPath, String state,
                           String confirmationPath, UUID confirmationEvidenceId, Instant confirmedAt,
                           Integer outcomeMaturityDays, Instant maturesAt, UUID calibrationPackageId,
                           Integer calibrationVersion, List<String> acquiredAxes) {
    }

    public PassResult releaseMatured(int limit, String trigger, String operator, String correlationId) {
        String text = jdbc.sql("SELECT ops.release_lc_matured_description_occupations(:limit,:trigger,:operator,:correlation)::text")
                .param("limit", limit).param("trigger", trigger).param("operator", operator)
                .param("correlation", correlationId).query(String.class).single();
        JsonNode value = JsonValues.read(json, text);
        List<UUID> actions = new ArrayList<>();
        value.path("releasedActions").forEach(id -> actions.add(UUID.fromString(id.asText())));
        List<ReleasedOccupation> occupations = new ArrayList<>();
        value.path("releasedOccupations").forEach(item -> occupations.add(new ReleasedOccupation(
                UUID.fromString(item.path("occupationId").asText()), UUID.fromString(item.path("actionId").asText()),
                item.path("axisCode").asText())));
        List<Skipped> skipped = new ArrayList<>();
        value.path("skipped").forEach(item -> skipped.add(new Skipped(
                UUID.fromString(item.path("actionId").asText()), item.path("state").asText())));
        return new PassResult(value.path("trigger").asText(), instant(value.path("evaluatedAt")),
                value.path("considered").asInt(), actions, occupations, skipped, value.path("limitReached").asBoolean());
    }

    /** Every description action still holding an ACQUIRED occupation, with where it stands. */
    public List<Maturity> pending(Instant at, int limit) {
        return jdbc.sql("""
                SELECT ops.lc_description_outcome_maturity(a.id,:at)::text AS maturity,
                       array_agg(o.axis_code ORDER BY o.axis_code) AS axes
                  FROM ops.lc_action a JOIN ops.lc_exposure_occupation o ON o.action_id=a.id AND o.state='ACQUIRED'
                 WHERE a.action_kind='LISTING_DESCRIPTION_CHANGE'
                 GROUP BY a.id,a.updated_at
                 ORDER BY a.updated_at,a.id
                 LIMIT :limit
                """).param("at", Timestamp.from(at)).param("limit", limit)
                .query((rs, n) -> {
                    JsonNode value = JsonValues.read(json, rs.getString("maturity"));
                    List<String> axes = new ArrayList<>();
                    for (Object axis : (Object[]) rs.getArray("axes").getArray()) axes.add(String.valueOf(axis));
                    return new Maturity(UUID.fromString(value.path("actionId").asText()),
                            uuid(value.path("platformListingId")), text(value.path("executionPath")),
                            value.path("state").asText(), text(value.path("confirmationPath")),
                            uuid(value.path("confirmationEvidenceId")), instant(value.path("confirmedAt")),
                            value.path("outcomeMaturityDays").isNumber() ? value.path("outcomeMaturityDays").asInt() : null,
                            instant(value.path("maturesAt")), uuid(value.path("calibrationPackageId")),
                            value.path("calibrationVersion").isNumber() ? value.path("calibrationVersion").asInt() : null,
                            axes);
                }).list();
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private static UUID uuid(JsonNode node) {
        return node.isTextual() ? UUID.fromString(node.asText()) : null;
    }

    /** jsonb renders timestamptz as ISO-8601 with an offset, e.g. 2026-09-22T10:00:00.123+00:00. */
    private static Instant instant(JsonNode node) {
        return node.isTextual() ? java.time.OffsetDateTime.parse(node.asText()).toInstant() : null;
    }
}

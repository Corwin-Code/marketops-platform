package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Weekly snapshots of a store's followed actions (P10, V0033): one per store and week. One taken while
 * its week was still running is provisional and is taken again; one taken after the week ended is final.
 */
@Repository
public class WeeklyReviewRepository {

    private final JdbcClient jdbc;

    WeeklyReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Keep one week's snapshot.
     *
     * @return whether it was kept now; {@code false} when the week was already kept
     */
    public boolean insert(Row row) {
        return jdbc.sql("""
                        INSERT INTO ops.weekly_review (
                            id, organization_id, store_id, week_start, week_end, orders_from, orders_to,
                            orders_days_covered, ordered_units, listings_with_orders, actions_acted,
                            readings_recorded, actions_observing, improved_count, unchanged_count,
                            regressed_count, indeterminate_count, actions, ai_invocation_id, compiled_at)
                        VALUES (:id, :organizationId, :storeId, :weekStart, :weekEnd, :ordersFrom, :ordersTo,
                            :ordersDays, :orderedUnits, :listingsWithOrders, :actionsActed,
                            :readingsRecorded, :actionsObserving, :improved, :unchanged,
                            :regressed, :indeterminate, CAST(:actions AS jsonb), NULL, :compiledAt)
                        ON CONFLICT (store_id, week_start) DO NOTHING
                        """)
                .param("id", row.id())
                .param("organizationId", row.organizationId())
                .param("storeId", row.storeId())
                .param("weekStart", row.weekStart())
                .param("weekEnd", row.weekEnd())
                .param("ordersFrom", row.ordersFrom())
                .param("ordersTo", row.ordersTo())
                .param("ordersDays", row.ordersDaysCovered())
                .param("orderedUnits", row.orderedUnits())
                .param("listingsWithOrders", row.listingsWithOrders())
                .param("actionsActed", row.actionsActed())
                .param("readingsRecorded", row.readingsRecorded())
                .param("actionsObserving", row.actionsObserving())
                .param("improved", row.improvedCount())
                .param("unchanged", row.unchangedCount())
                .param("regressed", row.regressedCount())
                .param("indeterminate", row.indeterminateCount())
                .param("actions", row.actionsJson())
                .param("compiledAt", Timestamp.from(row.compiledAt()))
                .update() == 1;
    }

    /**
     * Take a provisional snapshot again: its figures are replaced and it no longer points at the model
     * answer about the old figures. Refused (returns {@code false}) once the snapshot is final.
     */
    public boolean retake(Row row, Instant finalFrom) {
        return jdbc.sql("""
                        UPDATE ops.weekly_review
                           SET orders_from = :ordersFrom, orders_to = :ordersTo, orders_days_covered = :ordersDays,
                               ordered_units = :orderedUnits, listings_with_orders = :listingsWithOrders,
                               actions_acted = :actionsActed, readings_recorded = :readingsRecorded,
                               actions_observing = :actionsObserving, improved_count = :improved,
                               unchanged_count = :unchanged, regressed_count = :regressed,
                               indeterminate_count = :indeterminate, actions = CAST(:actions AS jsonb),
                               ai_invocation_id = NULL, compiled_at = :compiledAt
                         WHERE store_id = :storeId AND week_start = :weekStart AND compiled_at < :finalFrom
                        """)
                .param("ordersFrom", row.ordersFrom())
                .param("ordersTo", row.ordersTo())
                .param("ordersDays", row.ordersDaysCovered())
                .param("orderedUnits", row.orderedUnits())
                .param("listingsWithOrders", row.listingsWithOrders())
                .param("actionsActed", row.actionsActed())
                .param("readingsRecorded", row.readingsRecorded())
                .param("actionsObserving", row.actionsObserving())
                .param("improved", row.improvedCount())
                .param("unchanged", row.unchangedCount())
                .param("regressed", row.regressedCount())
                .param("indeterminate", row.indeterminateCount())
                .param("actions", row.actionsJson())
                .param("compiledAt", Timestamp.from(row.compiledAt()))
                .param("storeId", row.storeId())
                .param("weekStart", row.weekStart())
                .param("finalFrom", Timestamp.from(finalFrom))
                .update() == 1;
    }

    /** Point a week's snapshot at the newest model answer about it. */
    public void pointAt(UUID reviewId, UUID invocationId) {
        jdbc.sql("UPDATE ops.weekly_review SET ai_invocation_id = :invocationId WHERE id = :id")
                .param("invocationId", invocationId)
                .param("id", reviewId)
                .update();
    }

    /** One store's snapshot of one week, if it was kept. */
    public Optional<Row> find(UUID storeId, LocalDate weekStart) {
        return jdbc.sql(SELECT + " WHERE store_id = :storeId AND week_start = :weekStart")
                .param("storeId", storeId)
                .param("weekStart", weekStart)
                .query(WeeklyReviewRepository::map)
                .optional();
    }

    /** One store's newest snapshots, newest week first. */
    public List<Row> recent(UUID organizationId, UUID storeId, int limit) {
        return jdbc.sql(SELECT + """
                         WHERE organization_id = :organizationId AND store_id = :storeId
                         ORDER BY week_start DESC
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("limit", limit)
                .query(WeeklyReviewRepository::map)
                .list();
    }

    private static final String SELECT = """
            SELECT id, organization_id, store_id, week_start, week_end, orders_from, orders_to,
                   orders_days_covered, ordered_units, listings_with_orders, actions_acted, readings_recorded,
                   actions_observing, improved_count, unchanged_count, regressed_count, indeterminate_count,
                   actions::text AS actions, ai_invocation_id, compiled_at
              FROM ops.weekly_review
            """;

    private static Row map(ResultSet rows, int rowNumber) throws SQLException {
        return new Row(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getObject("week_start", LocalDate.class),
                rows.getObject("week_end", LocalDate.class),
                rows.getObject("orders_from", LocalDate.class),
                rows.getObject("orders_to", LocalDate.class),
                rows.getInt("orders_days_covered"),
                (Long) rows.getObject("ordered_units"),
                (Integer) rows.getObject("listings_with_orders"),
                rows.getInt("actions_acted"),
                rows.getInt("readings_recorded"),
                rows.getInt("actions_observing"),
                rows.getInt("improved_count"),
                rows.getInt("unchanged_count"),
                rows.getInt("regressed_count"),
                rows.getInt("indeterminate_count"),
                rows.getString("actions"),
                rows.getObject("ai_invocation_id", UUID.class),
                rows.getTimestamp("compiled_at").toInstant());
    }

    /** One week's snapshot as kept; the actions are the JSON array the review saw. */
    public record Row(UUID id, UUID organizationId, UUID storeId, LocalDate weekStart, LocalDate weekEnd,
                      LocalDate ordersFrom, LocalDate ordersTo, int ordersDaysCovered, Long orderedUnits,
                      Integer listingsWithOrders, int actionsActed, int readingsRecorded, int actionsObserving,
                      int improvedCount, int unchangedCount, int regressedCount, int indeterminateCount,
                      String actionsJson, UUID aiInvocationId, Instant compiledAt) {
    }
}

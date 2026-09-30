package com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The store's own standing as the newest answers stated it: the rating summary, every rating and
 * every warehouse. Each list comes from one answer, the store's newest of its kind, so a rating or
 * warehouse the marketplace stopped naming is not taken for a current one.
 */
@Repository
public class StoreStandingRepository {

    private final JdbcClient jdbc;

    StoreStandingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The store's newest rating summary, or empty when none was recorded. */
    public Optional<Summary> summary(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT summary.observed_at, summary.premium, summary.premium_plus,
                               summary.penalty_score_exceeded, summary.localization_calculated_at,
                               summary.localization_percentage
                          FROM core.seller_rating_summary_observation AS summary
                         WHERE summary.organization_id = :organizationId AND summary.store_id = :storeId
                         ORDER BY summary.observed_at DESC
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new Summary(instant(rows, "observed_at"),
                        flag(rows, "premium"), flag(rows, "premium_plus"), flag(rows, "penalty_score_exceeded"),
                        instant(rows, "localization_calculated_at"),
                        rows.getBigDecimal("localization_percentage")))
                .optional();
    }

    /** Every rating of the store's newest rating answer, by group and rating key. */
    public List<Rating> ratings(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT item.observed_at, item.rating_key, item.group_name, item.rating_name,
                               item.value_type, item.direction, item.status, item.current_value,
                               item.past_value, item.change_direction, item.change_meaning
                          FROM core.seller_rating_item_observation AS item
                         WHERE item.organization_id = :organizationId AND item.store_id = :storeId
                           AND item.observed_at = (
                               SELECT max(newest.observed_at)
                                 FROM core.seller_rating_item_observation AS newest
                                WHERE newest.organization_id = :organizationId AND newest.store_id = :storeId)
                         ORDER BY item.group_name, item.rating_key
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new Rating(instant(rows, "observed_at"), rows.getString("rating_key"),
                        rows.getString("group_name"), rows.getString("rating_name"), rows.getString("value_type"),
                        rows.getString("direction"), rows.getString("status"), rows.getBigDecimal("current_value"),
                        rows.getBigDecimal("past_value"), rows.getString("change_direction"),
                        rows.getString("change_meaning")))
                .list();
    }

    /** Every warehouse of the store's newest warehouse answer, by warehouse key. */
    public List<Warehouse> warehouses(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT warehouse.observed_at, warehouse.native_warehouse_key, warehouse.warehouse_type,
                               warehouse.status, warehouse.rfbs, warehouse.express, warehouse.large_goods,
                               warehouse.auto_assembly, warehouse.first_mile_kind, warehouse.working_day_count,
                               warehouse.handover_minutes, warehouse.assembly_minutes, warehouse.postings_limit,
                               warehouse.min_postings_limit, warehouse.has_postings_limit, warehouse.paused_at,
                               warehouse.source_updated_at, warehouse.time_zone
                          FROM core.warehouse_observation AS warehouse
                         WHERE warehouse.organization_id = :organizationId AND warehouse.store_id = :storeId
                           AND warehouse.observed_at = (
                               SELECT max(newest.observed_at)
                                 FROM core.warehouse_observation AS newest
                                WHERE newest.organization_id = :organizationId AND newest.store_id = :storeId)
                         ORDER BY warehouse.native_warehouse_key
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new Warehouse(instant(rows, "observed_at"),
                        rows.getString("native_warehouse_key"), rows.getString("warehouse_type"),
                        rows.getString("status"), flag(rows, "rfbs"), flag(rows, "express"),
                        flag(rows, "large_goods"), flag(rows, "auto_assembly"), rows.getString("first_mile_kind"),
                        integer(rows, "working_day_count"), integer(rows, "handover_minutes"),
                        integer(rows, "assembly_minutes"), integer(rows, "postings_limit"),
                        integer(rows, "min_postings_limit"), flag(rows, "has_postings_limit"),
                        instant(rows, "paused_at"), instant(rows, "source_updated_at"), rows.getString("time_zone")))
                .list();
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Boolean flag(ResultSet rows, String column) throws SQLException {
        boolean value = rows.getBoolean(column);
        return rows.wasNull() ? null : value;
    }

    private static Integer integer(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    /** The store's rating summary. */
    public record Summary(Instant observedAt, Boolean premium, Boolean premiumPlus, Boolean penaltyScoreExceeded,
                          Instant localizationCalculatedAt, BigDecimal localizationPercentage) {
    }

    /** One rating as the store's newest rating answer stated it. */
    public record Rating(Instant observedAt, String ratingKey, String groupName, String ratingName, String valueType,
                         String direction, String status, BigDecimal currentValue, BigDecimal pastValue,
                         String changeDirection, String changeMeaning) {
    }

    /** One warehouse as the store's newest warehouse answer stated it. */
    public record Warehouse(Instant observedAt, String nativeWarehouseKey, String warehouseType, String status,
                            Boolean rfbs, Boolean express, Boolean largeGoods, Boolean autoAssembly,
                            String firstMileKind, Integer workingDayCount, Integer handoverMinutes,
                            Integer assemblyMinutes, Integer postingsLimit, Integer minPostingsLimit,
                            Boolean hasPostingsLimit, Instant pausedAt, Instant sourceUpdatedAt, String timeZone) {
    }
}

package com.mimococo.marketops.shared.internal.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Says which time zone the application's database sessions run in, once at startup.
 *
 * <p>Verified registry evidence compares configuration snapshots that render timestamps in the
 * session zone, which {@code spring.datasource.hikari.connection-init-sql} fixes per environment.
 * A session in another zone than the one the evidence was recorded in reads every capability as
 * changed; this line shows the zone without a database console.
 */
@Component
class DatabaseSessionTimeZoneReporter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSessionTimeZoneReporter.class);

    private final JdbcClient jdbc;

    DatabaseSessionTimeZoneReporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    void report() {
        try {
            String zone = jdbc.sql("SELECT current_setting('TimeZone')").query(String.class).single();
            log.atInfo()
                    .addKeyValue("event", "database_session_time_zone")
                    .addKeyValue("timeZone", zone)
                    .log("Database sessions run in this time zone");
        } catch (RuntimeException unavailable) {
            log.atWarn()
                    .addKeyValue("event", "database_session_time_zone_unknown")
                    .addKeyValue("errorClass", unavailable.getClass().getSimpleName())
                    .log("The database session time zone could not be read");
        }
    }
}

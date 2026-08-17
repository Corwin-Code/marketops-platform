package com.mimococo.marketops.testfixture.violation.weblayer.api;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A web resource that opens its own connection.
 *
 * <p>This is the arrangement the layering rule exists to reject: there is no
 * place left to put a transaction boundary, and the behaviour can only be
 * exercised through HTTP.
 */
@RestController
public final class ResourceReadingTheDatabase {

    private final DataSource dataSource;

    /**
     * Accept the data source directly.
     *
     * @param dataSource the connection pool the resource reads from
     */
    public ResourceReadingTheDatabase(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Read from the database inside the resource. */
    @GetMapping("/fixture/rows")
    public boolean rows() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(1);
        }
    }
}

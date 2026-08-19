package com.mimococo.marketops.testsupport;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Creates a fluent JDBC client whose reads are empty and whose writes affect no rows. */
public final class EmptyJdbcClient {

    private EmptyJdbcClient() {
    }

    /** Return a deterministic empty-store collaborator for repository unit tests. */
    public static JdbcClient emptyJdbcClient() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        when(jdbc.sql(anyString())).thenReturn(statement);

        JdbcClient.MappedQuerySpec<Long> longs = mockQuerySpec();
        when(longs.single()).thenReturn(0L);
        when(statement.query(Long.class)).thenReturn(longs);

        JdbcClient.MappedQuerySpec<Object> mapped = mockQuerySpec();
        when(statement.query(ArgumentMatchers.<RowMapper<Object>>any())).thenReturn(mapped);
        return jdbc;
    }

    @SuppressWarnings("unchecked") // JdbcClient erases the mapped result type at runtime.
    private static <T> JdbcClient.MappedQuerySpec<T> mockQuerySpec() {
        return mock(JdbcClient.MappedQuerySpec.class);
    }
}

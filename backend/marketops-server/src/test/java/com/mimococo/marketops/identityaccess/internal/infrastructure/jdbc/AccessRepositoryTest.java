package com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static com.mimococo.marketops.testsupport.EmptyJdbcClient.emptyJdbcClient;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.identityaccess.internal.domain.AllowedSource;
import com.mimococo.marketops.identityaccess.internal.domain.AllowedSourceStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeGrant;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeGrantStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeResourceType;
import com.mimococo.marketops.identityaccess.internal.domain.ServiceAccount;
import com.mimococo.marketops.identityaccess.internal.domain.ServiceAccountStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class AccessRepositoryTest {

    private static final UUID ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

    @Test
    void repositoriesExposeVersionedWriteAndEmptyReadSemantics() {
        JdbcClient jdbc = emptyJdbcClient();
        PermissionKindRepository permissions = new PermissionKindRepository(jdbc);
        ServiceAccountRepository accounts = new ServiceAccountRepository(jdbc);
        ScopeGrantRepository grants = new ScopeGrantRepository(jdbc);

        ServiceAccount account = mock(ServiceAccount.class);
        when(account.status()).thenReturn(ServiceAccountStatus.ACTIVE);
        when(account.expiresAt()).thenReturn(NOW.plusSeconds(3600));
        when(account.createdAt()).thenReturn(NOW);
        when(account.updatedAt()).thenReturn(NOW);
        AllowedSource source = mock(AllowedSource.class);
        when(source.status()).thenReturn(AllowedSourceStatus.ACTIVE);
        when(source.createdAt()).thenReturn(NOW);
        when(source.updatedAt()).thenReturn(NOW);
        ScopeGrant grant = mock(ScopeGrant.class);
        when(grant.resourceType()).thenReturn(ScopeResourceType.STORE);
        when(grant.status()).thenReturn(ScopeGrantStatus.ACTIVE);
        when(grant.effectiveFrom()).thenReturn(NOW);
        when(grant.effectiveTo()).thenReturn(NOW.plusSeconds(3600));
        when(grant.createdAt()).thenReturn(NOW);
        when(grant.updatedAt()).thenReturn(NOW);

        assertThat(permissions.permissionExists("ORDERS_READ")).isFalse();

        accounts.insert(account);
        assertThat(accounts.update(account, 1)).isFalse();
        assertThat(accounts.findById(ID)).isEmpty();
        assertThat(accounts.findByCode(ID, "SERVICE")).isEmpty();
        assertThat(accounts.list(ID, null, 50)).isEmpty();
        assertThat(accounts.countNotRevokedByOrganization(ID)).isZero();
        accounts.insertSource(source);
        assertThat(accounts.updateSource(source, 1)).isFalse();
        assertThat(accounts.findSourceById(ID)).isEmpty();
        assertThat(accounts.findActiveSource(ID, "127.0.0.1/32")).isEmpty();
        assertThat(accounts.listSources(ID)).isEmpty();

        grants.insert(grant);
        assertThat(grants.update(grant, 1)).isFalse();
        assertThat(grants.findById(ID)).isEmpty();
        assertThat(grants.findActiveGrant(ID, "ORDERS_READ", ScopeResourceType.STORE, ID))
                .isEmpty();
        assertThat(grants.listBySubject(ID, 50)).isEmpty();
        assertThat(grants.listActiveBySubject(ID)).isEmpty();
        assertThat(grants.countActiveByResource(ScopeResourceType.STORE, ID)).isZero();

        verify(jdbc, atLeastOnce()).sql(anyString());
    }
}

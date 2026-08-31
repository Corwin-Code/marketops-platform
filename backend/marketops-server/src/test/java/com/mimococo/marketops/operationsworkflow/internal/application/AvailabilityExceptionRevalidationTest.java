package com.mimococo.marketops.operationsworkflow.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionState;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionView;
import com.mimococo.marketops.operationsworkflow.ExceptionAuthorityLevel;
import com.mimococo.marketops.operationsworkflow.ExceptionReasonCode;
import com.mimococo.marketops.operationsworkflow.ExceptionScopeKind;
import com.mimococo.marketops.operationsworkflow.internal.domain.ExceptionMaterialityPolicy;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityCaseRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityExceptionRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AvailabilityExceptionRevalidationTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID EXCEPTION = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final UUID CHILD = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();
    private static final UUID POLICY = UUID.randomUUID();

    @Mock AvailabilityExceptionRepository exceptions;
    @Mock AvailabilityCaseRepository cases;
    @Mock AvailabilityCaseService caseService;
    @Mock MetadataAuditRecorder audit;
    @Mock IdGenerator ids;

    private AvailabilityExceptionService service;

    @BeforeEach
    void setUp() {
        service = new AvailabilityExceptionService(exceptions, cases, caseService, audit, ids);
        when(exceptions.active(ORGANIZATION, NOW)).thenReturn(List.of(accepted()));
    }

    @Test
    @DisplayName("TC-EXC-REVAL-001 unchanged evidence and authority keep the acceptance active")
    void unchangedAcceptanceStaysActive() {
        when(exceptions.resolveMateriality(ORGANIZATION, NOW)).thenReturn(Optional.of(policy(POLICY)));
        when(exceptions.currentRisk(EXCEPTION)).thenReturn(Optional.of(risk("same", "same")));
        when(exceptions.approvalAuthorityLive(EXCEPTION, NOW)).thenReturn(true);

        assertThat(service.revalidateActive(ORGANIZATION, NOW)).isEmpty();

        verify(exceptions, never()).close(eq(EXCEPTION), eq(AcceptedExceptionState.INVALIDATED),
                contains("EVIDENCE_CONFLICT"), eq(NOW));
    }

    @Test
    @DisplayName("TC-EXC-REVAL-002 a changed risk-evidence fingerprint invalidates automatically")
    void evidenceChangeInvalidates() {
        when(exceptions.find(EXCEPTION)).thenReturn(Optional.of(accepted()));
        when(exceptions.resolveMateriality(ORGANIZATION, NOW)).thenReturn(Optional.of(policy(POLICY)));
        when(exceptions.currentRisk(EXCEPTION)).thenReturn(Optional.of(risk("new", "old")));

        assertThat(service.revalidateActive(ORGANIZATION, NOW)).hasSize(1);

        verify(exceptions).close(eq(EXCEPTION), eq(AcceptedExceptionState.INVALIDATED),
                contains("EVIDENCE_CONFLICT"), eq(NOW));
    }

    @Test
    @DisplayName("TC-EXC-REVAL-003 a replacement materiality authority invalidates automatically")
    void policyChangeInvalidates() {
        when(exceptions.find(EXCEPTION)).thenReturn(Optional.of(accepted()));
        when(exceptions.resolveMateriality(ORGANIZATION, NOW))
                .thenReturn(Optional.of(policy(UUID.randomUUID())));

        assertThat(service.revalidateActive(ORGANIZATION, NOW)).hasSize(1);

        verify(exceptions).close(eq(EXCEPTION), eq(AcceptedExceptionState.INVALIDATED),
                contains("GOVERNING_POLICY_CHANGED"), eq(NOW));
    }

    @Test
    @DisplayName("TC-EXC-REVAL-004 loss of the approving role invalidates and escalates")
    void authorityLossInvalidates() {
        when(exceptions.find(EXCEPTION)).thenReturn(Optional.of(accepted()));
        when(exceptions.resolveMateriality(ORGANIZATION, NOW)).thenReturn(Optional.of(policy(POLICY)));
        when(exceptions.currentRisk(EXCEPTION)).thenReturn(Optional.of(risk("same", "same")));
        when(exceptions.approvalAuthorityLive(EXCEPTION, NOW)).thenReturn(false);

        assertThat(service.revalidateActive(ORGANIZATION, NOW)).hasSize(1);

        verify(exceptions).close(eq(EXCEPTION), eq(AcceptedExceptionState.INVALIDATED),
                contains("AUTHORITY_LOST"), eq(NOW));
        verify(caseService).escalate(CASE, "accepted risk invalidated: AUTHORITY_LOST", NOW);
    }

    private static AcceptedExceptionView accepted() {
        return new AcceptedExceptionView(EXCEPTION, ORGANIZATION, CASE, CHILD,
                "COMPANY_SUPPLY_SHORT", ExceptionScopeKind.CHILD, CHILD.toString(),
                ExceptionReasonCode.SEASONAL_PAUSE, "planned pause", "lost sales",
                new BigDecimal("10"), "RUB", "ev://decision", UUID.randomUUID(),
                NOW.minus(Duration.ofDays(1)), "RISK_AUTHORITY",
                ExceptionAuthorityLevel.RISK_AUTHORITY, AcceptedExceptionState.ACTIVE,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofDays(1)),
                NOW.plus(Duration.ofHours(12)), null, null, POLICY, 1);
    }

    private static ExceptionMaterialityPolicy policy(UUID id) {
        return new ExceptionMaterialityPolicy(id, 1, "RUB", new BigDecimal("1000"),
                Duration.ofDays(10), 3, Duration.ofDays(30), Duration.ofDays(14));
    }

    private static AvailabilityExceptionRepository.CurrentRisk risk(String currentDigest,
                                                                     String acceptedDigest) {
        return new AvailabilityExceptionRepository.CurrentRisk("COMPANY_SUPPLY_SHORT", "HIGH",
                null, null, null, VARIANT, new BigDecimal("10"), "RUB",
                currentDigest, acceptedDigest);
    }
}

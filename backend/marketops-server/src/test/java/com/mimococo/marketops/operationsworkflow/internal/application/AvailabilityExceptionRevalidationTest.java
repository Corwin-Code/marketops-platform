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
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseState;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
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
        when(exceptions.resolveMateriality(ORGANIZATION, NOW)).thenReturn(Optional.of(policy(POLICY)));
    }

    @Test
    @DisplayName("TC-EXC-REVAL-001 unchanged evidence, scope, materiality and authority stay active")
    void unchangedAcceptanceStaysActive() {
        when(exceptions.currentRisk(EXCEPTION)).thenReturn(Optional.of(risk("same", "same")));
        when(exceptions.approvalAuthorityLive(EXCEPTION, NOW)).thenReturn(true);

        assertThat(service.revalidateActive(ORGANIZATION, NOW)).isEmpty();
        verify(exceptions, never()).close(eq(EXCEPTION), eq(AcceptedExceptionState.INVALIDATED),
                contains("EVIDENCE_CONFLICT"), eq(NOW));
    }

    @Test
    @DisplayName("TC-EXC-REVAL-002 cause replacement invalidates and reopens the same case")
    void causeChangeInvalidates() {
        invalidate(risk("same", "same", "OTHER_CAUSE", "HIGH",
                new BigDecimal("10"), 0, 0, VARIANT), accepted(), "CAUSE_CHANGED", false);
    }

    @Test
    @DisplayName("TC-EXC-REVAL-003 scope movement invalidates and reopens the same case")
    void scopeChangeInvalidates() {
        AcceptedExceptionView scoped = accepted(ExceptionScopeKind.VARIANT,
                UUID.randomUUID().toString());
        when(exceptions.active(ORGANIZATION, NOW)).thenReturn(List.of(scoped));
        invalidate(risk("same", "same"), scoped, "SCOPE_CHANGED", false);
    }

    @Test
    @DisplayName("TC-EXC-REVAL-004 severity increase invalidates and escalates")
    void severityIncreaseInvalidates() {
        invalidate(risk("same", "same", "COMPANY_SUPPLY_SHORT", "CRITICAL",
                new BigDecimal("10"), 0, 0, VARIANT), accepted(),
                "MATERIALITY_INCREASED", true);
    }

    @Test
    @DisplayName("TC-EXC-REVAL-005 consequence amount increase invalidates and escalates")
    void consequenceIncreaseInvalidates() {
        invalidate(risk("same", "same", "COMPANY_SUPPLY_SHORT", "HIGH",
                new BigDecimal("20"), 0, 0, VARIANT), accepted(),
                "MATERIALITY_INCREASED", true);
    }

    @Test
    @DisplayName("TC-EXC-REVAL-006 repeated occurrence invalidates and escalates")
    void repeatInvalidates() {
        invalidate(risk("same", "same", "COMPANY_SUPPLY_SHORT", "HIGH",
                new BigDecimal("10"), 0, 1, VARIANT), accepted(),
                "REPEATED_CONDITION", true);
    }

    @Test
    @DisplayName("TC-EXC-REVAL-007 loss of direct approver authority invalidates and escalates")
    void directAuthorityLossInvalidates() {
        authorityLossInvalidates();
    }

    @Test
    @DisplayName("TC-EXC-REVAL-008 revoked or expired delegated authority invalidates and escalates")
    void delegatedAuthorityLossInvalidates() {
        authorityLossInvalidates();
    }

    @Test
    @DisplayName("TC-EXC-REVAL-009 governing policy replacement invalidates automatically")
    void policyChangeInvalidates() {
        when(exceptions.resolveMateriality(ORGANIZATION, NOW))
                .thenReturn(Optional.of(policy(UUID.randomUUID())));
        prepareEnd(accepted());

        assertThat(service.revalidateActive(ORGANIZATION, NOW)).hasSize(1);
        assertClosed("GOVERNING_POLICY_CHANGED", false);
    }

    @Test
    @DisplayName("TC-EXC-REVAL-010 evidence conflict invalidates after semantic checks")
    void evidenceChangeInvalidates() {
        invalidate(risk("new", "old"), accepted(), "EVIDENCE_CONFLICT", false);
    }

    private void authorityLossInvalidates() {
        when(exceptions.currentRisk(EXCEPTION)).thenReturn(Optional.of(risk("same", "same")));
        when(exceptions.approvalAuthorityLive(EXCEPTION, NOW)).thenReturn(false);
        prepareEnd(accepted());

        assertThat(service.revalidateActive(ORGANIZATION, NOW)).hasSize(1);
        assertClosed("AUTHORITY_LOST", true);
    }

    private void invalidate(AvailabilityExceptionRepository.CurrentRisk current,
                            AcceptedExceptionView accepted, String cause, boolean escalated) {
        when(exceptions.currentRisk(EXCEPTION)).thenReturn(Optional.of(current));
        if (!"CAUSE_CHANGED".equals(cause) && !"SCOPE_CHANGED".equals(cause)) {
            when(exceptions.approvalAuthorityLive(EXCEPTION, NOW)).thenReturn(true);
        }
        prepareEnd(accepted);

        assertThat(service.revalidateActive(ORGANIZATION, NOW)).hasSize(1);
        assertClosed(cause, escalated);
    }

    private void prepareEnd(AcceptedExceptionView accepted) {
        when(exceptions.find(EXCEPTION)).thenReturn(Optional.of(accepted));
        AvailabilityCaseView governed = org.mockito.Mockito.mock(AvailabilityCaseView.class);
        when(governed.state()).thenReturn(AvailabilityCaseState.ACCEPTED_RISK);
        when(cases.find(CASE)).thenReturn(Optional.of(governed));
    }

    private void assertClosed(String cause, boolean escalated) {
        verify(exceptions).close(eq(EXCEPTION), eq(AcceptedExceptionState.INVALIDATED),
                contains(cause), eq(NOW));
        verify(caseService).reopenFromException(eq(CASE), contains(cause), eq(NOW));
        if (escalated) {
            verify(caseService).escalate(CASE, "accepted risk invalidated: " + cause, NOW);
        }
    }

    private static AcceptedExceptionView accepted() {
        return accepted(ExceptionScopeKind.CHILD, CHILD.toString());
    }

    private static AcceptedExceptionView accepted(ExceptionScopeKind scope, String reference) {
        return new AcceptedExceptionView(EXCEPTION, ORGANIZATION, CASE, CHILD,
                "COMPANY_SUPPLY_SHORT", scope, reference,
                ExceptionReasonCode.SEASONAL_PAUSE, "planned pause", "lost sales",
                new BigDecimal("10"), "RUB", "ev://decision", UUID.randomUUID(),
                NOW.minus(Duration.ofDays(1)), "RISK_AUTHORITY",
                ExceptionAuthorityLevel.RISK_AUTHORITY, AcceptedExceptionState.ACTIVE,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofDays(1)),
                NOW.plus(Duration.ofHours(12)), null, null, POLICY, 1,
                "HIGH", new BigDecimal("10"), "RUB", 0);
    }

    private static ExceptionMaterialityPolicy policy(UUID id) {
        return new ExceptionMaterialityPolicy(id, 1, "RUB", new BigDecimal("1000"),
                Duration.ofDays(10), 3, Duration.ofDays(30), Duration.ofDays(14));
    }

    private static AvailabilityExceptionRepository.CurrentRisk risk(String currentDigest,
                                                                     String acceptedDigest) {
        return risk(currentDigest, acceptedDigest, "COMPANY_SUPPLY_SHORT", "HIGH",
                new BigDecimal("10"), 0, 0, VARIANT);
    }

    private static AvailabilityExceptionRepository.CurrentRisk risk(
            String currentDigest, String acceptedDigest, String cause, String severity,
            BigDecimal profit, int acceptedReopens, int currentReopens, UUID variant) {
        return new AvailabilityExceptionRepository.CurrentRisk(cause, severity,
                null, null, null, variant, profit, "RUB", "HIGH",
                new BigDecimal("10"), "RUB", acceptedReopens, currentReopens,
                currentDigest, acceptedDigest);
    }
}

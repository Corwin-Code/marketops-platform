package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.ProfitLane;
import com.mimococo.marketops.availabilityrisk.RiskConfidence;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builders shared by the channel and company risk tests. */
final class RiskFixtures {

    static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    static final long FRESHNESS_MINUTES = 360;
    static final UUID LISTING = UUID.fromString("00000000-0000-0000-0000-00000000l1".replace('l', '1'));
    static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-000000000052");
    static final UUID VARIANT = UUID.fromString("00000000-0000-0000-0000-000000000053");
    static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000054");

    private RiskFixtures() {
    }

    /** Lead time 14 days, safety 7: horizon 21. */
    static LeadTimeResolution leadTime() {
        return LeadTimeResolution.resolved(
                UUID.fromString("00000000-0000-0000-0000-0000000000a1"), 4,
                "ORGANIZATION", 14, 7);
    }

    static LeadTimeResolution blockedLeadTime() {
        return LeadTimeResolution.blocked("no active version resolves for any scope");
    }

    /** A confirmed demand decision at the given units-per-day. */
    static DemandDecision demand(String perDay) {
        return new DemandDecision(new BigDecimal(perDay), DemandWindow.D30,
                "stable baseline: longest eligible window D30",
                RiskEvidenceState.CONFIRMED, RiskConfidence.HIGH,
                List.of(DemandFixtures.observed(DemandWindow.D30, 300)), null, null);
    }

    static DemandDecision blockedDemand() {
        return new DemandDecision(null, null, "no source answered for any window",
                RiskEvidenceState.DATA_BLOCKED, RiskConfidence.UNUSABLE, List.of(), null, null);
    }

    static DemandDecision carriedForwardDemand(String perDay) {
        return new DemandDecision(new BigDecimal(perDay), DemandWindow.D30,
                "carried forward: every recent window is materially censored",
                RiskEvidenceState.CARRIED_FORWARD, RiskConfidence.LOW,
                List.of(), NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(12)));
    }

    static ProfitAssessment profit() {
        return new ProfitAssessment(ProfitLane.CONFIRMED_ELIGIBLE, new BigDecimal("120.0000"),
                "RUB", UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
                "fresh complete positive settled contribution profit");
    }

    static ChannelObservation channel(Integer units, Instant observedAt, Sellability sellability) {
        return new ChannelObservation(LISTING, STORE, "MARKETPLACE_FULFILLED", units, observedAt,
                sellability, null, UUID.fromString("00000000-0000-0000-0000-0000000000c1"));
    }

    static CompanyObservation.WarehouseHolding warehouse(int onHand, Integer reserved,
                                                         Integer locked, Instant observedAt) {
        return new CompanyObservation.WarehouseHolding(WAREHOUSE, onHand, reserved, locked,
                observedAt, UUID.fromString("00000000-0000-0000-0000-0000000000c2"));
    }

    static CompanyObservation.PlatformHolding platform(Integer units, SupplyDistinctness distinctness,
                                                       Instant observedAt) {
        return new CompanyObservation.PlatformHolding(STORE, "MARKETPLACE_FULFILLED", units,
                distinctness, observedAt, UUID.fromString("00000000-0000-0000-0000-0000000000c3"));
    }

    static InboundConsignment inbound(int quantity, Instant from, Instant to,
                                      InboundConsignment.Status status, Instant verifiedAt) {
        return new InboundConsignment(UUID.randomUUID(), quantity, from, to, status, verifiedAt,
                "ev://purchase-order/1");
    }
}

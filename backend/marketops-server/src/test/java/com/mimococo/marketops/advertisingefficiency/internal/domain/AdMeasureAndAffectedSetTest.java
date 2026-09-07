package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.analyticsdecision.ValueState;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Absence is a value, and the affected set is an identity. */
class AdMeasureAndAffectedSetTest {

    private static final UUID VARIANT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID VARIANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID VARIANT_C = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    @Nested
    @DisplayName("a measure carries a value exactly when it is available")
    class Measures {

        @Test
        @DisplayName("TC-ADV-MEAS-001 an available measure carries its number")
        void availableCarriesItsNumber() {
            AdMeasure measure = AdMeasure.available(BigDecimal.TEN, AdEvidenceState.OPERATIONAL);

            assertThat(measure.present()).isTrue();
            assertThat(measure.value()).isEqualByComparingTo("10");
            assertThat(measure.orElse(BigDecimal.ZERO)).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("TC-ADV-MEAS-002 an unavailable measure is not zero")
        void unavailableIsNotZero() {
            AdMeasure measure = AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE);

            assertThat(measure.present()).isFalse();
            assertThat(measure.value()).isNull();
            assertThat(measure.valueState()).isEqualTo(ValueState.NOT_AVAILABLE);
        }

        @Test
        @DisplayName("TC-ADV-MEAS-003 an undefined measure is distinct from an unavailable one")
        void undefinedIsDistinctFromUnavailable() {
            assertThat(AdMeasure.undefined(AdEvidenceState.NOT_AVAILABLE).valueState())
                    .isEqualTo(ValueState.UNDEFINED);
        }

        @Test
        @DisplayName("TC-ADV-MEAS-004 an available measure without a value is unrepresentable")
        void availableWithoutValueIsRefused() {
            assertThatThrownBy(() -> new AdMeasure(
                    ValueState.AVAILABLE, null, AdEvidenceState.CANONICAL_CONFIRMED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly when it is AVAILABLE");
        }

        @Test
        @DisplayName("TC-ADV-MEAS-005 an unavailable measure carrying a value is unrepresentable")
        void unavailableWithValueIsRefused() {
            assertThatThrownBy(() -> new AdMeasure(
                    ValueState.NOT_AVAILABLE, BigDecimal.ONE, AdEvidenceState.CANONICAL_CONFIRMED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @EnumSource(value = AdEvidenceState.class,
                names = {"PROVISIONAL_OR_ESTIMATED", "STALE", "INCOMPLETE", "CONFLICTED",
                         "UNKNOWN", "NOT_AVAILABLE", "DATA_BLOCKED", "POLICY_BLOCKED",
                         "PROFILE_UNRESOLVED", "BUNDLE_UNRESOLVED"})
        @DisplayName("TC-ADV-MEAS-006 a present number on insufficient evidence is not write-sufficient")
        void presentNumberOnWeakEvidenceIsNotWriteSufficient(AdEvidenceState state) {
            AdMeasure measure = AdMeasure.available(BigDecimal.TEN, state);

            assertThat(measure.present()).isTrue();
            assertThat(measure.sufficientForWrite())
                    .describedAs("evidence state %s must not be write-sufficient", state)
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the affected set is an identity, not a list")
    class AffectedSets {

        @Test
        @DisplayName("TC-ADV-SET-001 membership order does not change the digest")
        void orderDoesNotChangeTheDigest() {
            AffectedSet one = AffectedSet.complete(
                    List.of(VARIANT_A, VARIANT_B, VARIANT_C), List.of());
            AffectedSet another = AffectedSet.complete(
                    List.of(VARIANT_C, VARIANT_A, VARIANT_B), List.of());

            assertThat(one.digest()).isEqualTo(another.digest());
            assertThat(one.productVariantIds()).containsExactlyElementsOf(another.productVariantIds());
        }

        @Test
        @DisplayName("TC-ADV-SET-002 a duplicate member does not change the digest")
        void duplicatesDoNotChangeTheDigest() {
            AffectedSet one = AffectedSet.complete(List.of(VARIANT_A, VARIANT_B), List.of());
            AffectedSet withDuplicate = AffectedSet.complete(
                    List.of(VARIANT_A, VARIANT_B, VARIANT_A), List.of());

            assertThat(one.digest()).isEqualTo(withDuplicate.digest());
        }

        @Test
        @DisplayName("TC-ADV-SET-003 a different membership changes the digest, invalidating frozen assets")
        void differentMembershipChangesTheDigest() {
            AffectedSet approved = AffectedSet.complete(List.of(VARIANT_A, VARIANT_B), List.of());
            AffectedSet atExecution = AffectedSet.complete(
                    List.of(VARIANT_A, VARIANT_B, VARIANT_C), List.of());

            assertThat(atExecution.matches(approved.digest())).isFalse();
        }

        @Test
        @DisplayName("TC-ADV-SET-004 an empty complete set is unrepresentable")
        void emptyCompleteSetIsRefused() {
            assertThatThrownBy(() -> AffectedSet.complete(List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one variant");
        }

        @Test
        @DisplayName("TC-ADV-SET-005 an unresolved set must say why")
        void unresolvedSetMustSayWhy() {
            assertThatThrownBy(() -> AffectedSet.unresolved(
                    List.of(VARIANT_A), List.of(), AffectedSet.Resolution.INCOMPLETE, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must say why");
        }

        @Test
        @DisplayName("TC-ADV-SET-006 an incomplete set never supports a controlled write")
        void incompleteSetNeverSupportsAWrite() {
            AffectedSet incomplete = AffectedSet.unresolved(
                    List.of(VARIANT_A), List.of(), AffectedSet.Resolution.INCOMPLETE,
                    List.of("MAPPING_UNRESOLVED"));

            assertThat(incomplete.sufficientForWrite()).isFalse();
            assertThat(AffectedSet.complete(List.of(VARIANT_A), List.of()).sufficientForWrite())
                    .isTrue();
        }

        @Test
        @DisplayName("TC-ADV-SET-007 complete(...) is the only route to a COMPLETE resolution")
        void completeResolutionCannotBeForgedThroughUnresolved() {
            assertThatThrownBy(() -> AffectedSet.unresolved(
                    List.of(VARIANT_A), List.of(), AffectedSet.Resolution.COMPLETE, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("complete(...)");
        }
    }

    @Nested
    @DisplayName("a one-sided danger proof is a proof")
    class DangerProofs {

        @Test
        @DisplayName("TC-ADV-PROOF-001 a missing fact that could reverse the direction refuses the proof")
        void reversibleMissingFactRefusesTheProof() {
            OneSidedDangerProof proof = OneSidedDangerProof.of(
                    "PROVEN_ADVERTISING_LOSS", List.of("SPEND_OBSERVED"),
                    List.of("AD_LINKED_CONVERSION"), true);

            assertThat(proof.established()).isFalse();
            assertThat(proof.refusalCode()).isEqualTo("MISSING_FACT_COULD_REVERSE_DIRECTION");
        }

        @Test
        @DisplayName("TC-ADV-PROOF-002 a missing fact that cannot reverse the direction leaves the proof standing")
        void irreversibleMissingFactLeavesTheProofStanding() {
            OneSidedDangerProof proof = OneSidedDangerProof.of(
                    "PROMOTED_VARIANT_NOT_SELLABLE",
                    List.of("EVERY_PROMOTED_VARIANT_NOT_SELLABLE", "SPEND_CONTINUING"),
                    List.of("AD_LINKED_CONVERSION"), false);

            assertThat(proof.established()).isTrue();
            assertThat(proof.missingFactCodes()).containsExactly("AD_LINKED_CONVERSION");
        }

        @Test
        @DisplayName("TC-ADV-PROOF-003 a proof with no proven term is refused")
        void proofWithNoProvenTermIsRefused() {
            OneSidedDangerProof proof = OneSidedDangerProof.of(
                    "PROVEN_ADVERTISING_LOSS", List.of(), List.of(), false);

            assertThat(proof.established()).isFalse();
            assertThat(proof.refusalCode()).isEqualTo("NO_PROVEN_TERM");
        }

        @Test
        @DisplayName("TC-ADV-PROOF-004 an established proof carrying no terms is unrepresentable")
        void establishedProofWithoutTermsIsRefused() {
            assertThatThrownBy(() -> new OneSidedDangerProof(
                    true, "CAUSE", List.of(), List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("names the terms");
        }

        @Test
        @DisplayName("TC-ADV-PROOF-005 a refused proof without a reason is unrepresentable")
        void refusedProofWithoutReasonIsRefused() {
            assertThatThrownBy(() -> new OneSidedDangerProof(
                    false, "CAUSE", List.of(), List.of("TERM"), "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("names why");
        }
    }
}

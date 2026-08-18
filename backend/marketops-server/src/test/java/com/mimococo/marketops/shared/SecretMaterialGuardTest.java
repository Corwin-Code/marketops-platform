package com.mimococo.marketops.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The secret-material refusal heuristics guarding every writable text field.
 *
 * <p>Each shape the guard describes must fire, and ordinary operational prose
 * must not, because a guard that misfires teaches operators to expect refusals
 * and a guard that stays silent lets pasted key material reach persistence.
 */
class SecretMaterialGuardTest {

    @Test
    @DisplayName("TC-SEC-101 an uninterrupted encoded run is refused")
    void refusesEncodedRuns() {
        assertRefused("prefix " + "Qq0".repeat(22) + " suffix");
    }

    @Test
    @DisplayName("TC-SEC-102 a bearer-token shape is refused")
    void refusesBearerShapes() {
        assertRefused("Authorization uses Bearer abc123token");
    }

    @Test
    @DisplayName("TC-SEC-103 an armored key header is refused")
    void refusesArmorHeaders() {
        assertRefused("-----BEGIN CERTIFICATE-----");
    }

    @Test
    @DisplayName("TC-SEC-104 a credential-named assignment is refused")
    void refusesCredentialAssignments() {
        for (String candidate : new String[] {
                "password: hunter",
                "passwd=old",
                "secret : value",
                "token= abc",
                "api-key: k",
                "api_key=k"}) {
            assertRefused(candidate);
        }
    }

    @Test
    @DisplayName("ordinary prose about credentials passes")
    void acceptsOperationalProse() {
        for (String candidate : new String[] {
                null,
                "Custodian of the finance secrets for the Ozon main account",
                "Rotated because the previous custodian left the team",
                "The token was revoked upstream; registration pending",
                "secret-ref://vault/marketops/ozon-main/read"}) {
            assertThatCode(() ->
                    SecretMaterialGuard.requireNonSecret("note", candidate))
                    .doesNotThrowAnyException();
        }
    }

    private static void assertRefused(String candidate) {
        assertThatThrownBy(() -> SecretMaterialGuard.requireNonSecret("field", candidate))
                .isInstanceOfSatisfying(OperationRejectedException.class, rejection ->
                        assertThat(rejection.errorCode())
                                .isEqualTo(ErrorCode.SECRET_MATERIAL_SUSPECTED));
    }
}

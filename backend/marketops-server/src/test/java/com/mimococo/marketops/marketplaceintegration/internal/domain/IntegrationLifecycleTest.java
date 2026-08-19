package com.mimococo.marketops.marketplaceintegration.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The integration-metadata state machines: recoverable credential disabling,
 * terminal revocation that releases the secret reference's uniqueness scope,
 * and forward-only store-scope withdrawal.
 */
class IntegrationLifecycleTest {

    @Test
    @DisplayName("TC-MI-101 credentials disable recoverably and revoke terminally")
    void credentialMachineIsExact() {
        assertThat(CredentialStatus.ACTIVE.allowedTransitions())
                .containsExactlyInAnyOrder(
                        CredentialStatus.DISABLED, CredentialStatus.REVOKED);
        assertThat(CredentialStatus.DISABLED.allowedTransitions())
                .containsExactlyInAnyOrder(
                        CredentialStatus.ACTIVE, CredentialStatus.REVOKED);
        assertThat(CredentialStatus.REVOKED.allowedTransitions()).isEmpty();
    }

    @Test
    @DisplayName("TC-MI-102 a store-scope row withdraws once; re-adding is a new row")
    void storeScopeMachineIsExact() {
        assertThat(StoreScopeStatus.ACTIVE.allowedTransitions())
                .containsExactly(StoreScopeStatus.WITHDRAWN);
        assertThat(StoreScopeStatus.WITHDRAWN.allowedTransitions()).isEmpty();
    }

    @Test
    @DisplayName("TC-MI-103 the derived scope-usability vocabulary is closed")
    void scopeUsabilityVocabularyIsClosed() {
        assertThat(CredentialScopeUsability.values())
                .extracting(Enum::name)
                .containsExactly("ACCOUNT_WIDE", "STORE_SET", "NO_ACTIVE_STORE_SCOPE");
        assertThat(RotationStanding.values())
                .extracting(Enum::name)
                .containsExactly("STABLE", "BEING_REPLACED");
    }

    @Test
    @DisplayName("TC-MI-104 the persisted registry vocabularies match the schema checks")
    void registryVocabulariesMatchTheSchema() {
        assertThat(VerificationState.values())
                .extracting(Enum::name)
                .containsExactly("UNKNOWN", "UNVERIFIED", "VERIFIED");
        assertThat(Availability.values())
                .extracting(Enum::name)
                .containsExactly("UNKNOWN", "UNAVAILABLE", "AVAILABLE");
        assertThat(TriState.values())
                .extracting(Enum::name)
                .containsExactly("YES", "NO", "UNKNOWN");
        assertThat(ContractTestStatus.values())
                .extracting(Enum::name)
                .containsExactly("NOT_IMPLEMENTED", "FAILING", "PASSING");
        assertThat(PaginationModel.values())
                .extracting(Enum::name)
                .containsExactly("CURSOR", "OFFSET", "PAGE", "DATE_WINDOW", "NONE", "UNKNOWN");
        assertThat(FlagScopeKind.values())
                .extracting(Enum::name)
                .containsExactly(
                        "GLOBAL", "PLATFORM", "MARKETPLACE_ACCOUNT", "STORE", "CAPABILITY");
        assertThat(RequirementKind.values())
                .extracting(Enum::name)
                .containsExactly(
                        "API_ROLE", "OAUTH_SCOPE", "SUBSCRIPTION", "PLAN", "OTHER", "UNKNOWN");
    }
}

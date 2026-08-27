package com.mimococo.marketops.identityaccess.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.internal.config.IdentityProperties;
import com.mimococo.marketops.identityaccess.internal.domain.*;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.*;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TokenIdentityResolverTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final String ISSUER = "https://identity.example.invalid";
    private static final String SUBJECT = "synthetic-subject";
    private static final String SESSION = "synthetic-session";
    private static final UUID PROVIDER = UUID.randomUUID(), USER = UUID.randomUUID(), ORGANIZATION = UUID.randomUUID();
    private final IdentityProviderRepository providers = mock(IdentityProviderRepository.class);
    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final UserAuthorizationRepository authorization = mock(UserAuthorizationRepository.class);
    private final IdentityDecisionJournal journal = mock(IdentityDecisionJournal.class);
    private final TokenIdentityResolver resolver = new TokenIdentityResolver(providers, profiles, authorization,
            journal, new IdentityProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void provisionSyntheticIdentity() {
        when(providers.findByIssuer(ISSUER)).thenReturn(Optional.of(provider("amr", "mfa",
                IdentityProviderStatus.ACTIVE, ProviderVerificationState.VERIFIED)));
        when(profiles.findBySubject(PROVIDER, SUBJECT)).thenReturn(Optional.of(profile(UserAccountStatus.ACTIVE, NOW.minusSeconds(600), null)));
        when(authorization.liveRoles(USER, NOW)).thenReturn(Set.of(BusinessRoleCode.READ_ONLY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABSENT", "RETIRED", "UNVERIFIED", "UNKNOWN", "MISSING_NAME", "MISSING_VALUE",
            "EMPTY_NAME", "BLANK_NAME", "EMPTY_VALUE", "BLANK_VALUE"})
    void unacceptedProvidersCannotReachProfilesOrRoles(String condition) {
        var registration = switch (condition) {
            case "ABSENT" -> Optional.<IdentityProviderRecord>empty();
            case "RETIRED" -> Optional.of(provider("amr", "mfa", IdentityProviderStatus.RETIRED, ProviderVerificationState.VERIFIED));
            case "UNVERIFIED", "UNKNOWN" -> Optional.of(provider("amr", "mfa", IdentityProviderStatus.ACTIVE, ProviderVerificationState.valueOf(condition)));
            case "MISSING_NAME" -> Optional.of(provider(null, "mfa", IdentityProviderStatus.ACTIVE, ProviderVerificationState.VERIFIED));
            case "EMPTY_NAME", "BLANK_NAME" -> Optional.of(provider(condition.equals("EMPTY_NAME") ? "" : " ",
                    "mfa", IdentityProviderStatus.ACTIVE, ProviderVerificationState.VERIFIED));
            case "EMPTY_VALUE", "BLANK_VALUE" -> Optional.of(provider("amr", condition.equals("EMPTY_VALUE") ? "" : " ",
                    IdentityProviderStatus.ACTIVE, ProviderVerificationState.VERIFIED));
            default -> Optional.of(provider("amr", null, IdentityProviderStatus.ACTIVE, ProviderVerificationState.VERIFIED));
        };
        when(providers.findByIssuer(ISSUER)).thenReturn(registration);
        assertThat(resolve(Map.of("amr", List.of("mfa")))).isEqualTo(new TokenResolution.Refused(ErrorCode.IDENTITY_PROVIDER_NOT_ACCEPTED));
        verifyNoInteractions(profiles, authorization);
        verify(journal).recordAuthenticationDenial(eq(ISSUER), eq(registration.isPresent() ? PROVIDER : null),
                eq(subjectDigest()), eq(sessionDigest()), eq("IDENTITY_PROVIDER_NOT_ACCEPTED"), eq(NOW.minusSeconds(60)), eq(false));
    }

    @ParameterizedTest
    @MethodSource("invalidMfaClaims")
    void mfaMustMatchTheExactConfiguredClaimAndHaveARecognizedShape(Map<String, Object> claims) {
        assertThat(resolve(claims)).isEqualTo(new TokenResolution.Refused(ErrorCode.MULTI_FACTOR_REQUIRED));
        verifyNoInteractions(profiles, authorization);
        verify(journal).recordAuthenticationDenial(ISSUER, PROVIDER, subjectDigest(), sessionDigest(),
                "MULTI_FACTOR_REQUIRED", NOW.minusSeconds(60), false);
    }

    static Stream<Arguments> invalidMfaClaims() {
        return Stream.of(Arguments.of(Map.of()), Arguments.of(Map.of("acr", "mfa")),
                Arguments.of(Map.of("amr", "pwd")), Arguments.of(Map.of("amr", "MFA")),
                Arguments.of(Map.of("amr", List.of())), Arguments.of(Map.of("amr", List.of("pwd"))),
                Arguments.of(Map.of("amr", List.of("mfa", 1))), Arguments.of(Map.of("amr", true)),
                Arguments.of(Map.of("amr", Map.of("mfa", true))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"amr", "acr", "custom_assurance"})
    void verifiedProviderVocabularyIsHonoredWithoutImportingTokenRoles(String claim) {
        when(providers.findByIssuer(ISSUER)).thenReturn(Optional.of(provider(claim, "provider-level-2",
                IdentityProviderStatus.ACTIVE, ProviderVerificationState.VERIFIED)));
        for (Object value : List.of("provider-level-2", List.of("pwd", "provider-level-2"))) {
            var actor = accepted(resolve(Map.of(claim, value, "roles", List.of("OWNER"))));
            assertThat(actor.roles()).containsExactly(BusinessRoleCode.READ_ONLY);
            assertThat(actor.subjectDigest()).isEqualTo(subjectDigest());
            assertThat(actor.sessionDigest()).isEqualTo(sessionDigest());
            assertThat(actor.stepUpSatisfiedAt(NOW)).isTrue();
            assertThat(actor.stepUpSatisfiedAt(NOW.plusSeconds(840))).isFalse();
        }
        verify(authorization, times(2)).liveRoles(USER, NOW);
    }

    @Test
    void unknownSubjectIsDeniedAfterMfaAndBeforeRoles() {
        when(profiles.findBySubject(PROVIDER, SUBJECT)).thenReturn(Optional.empty());
        assertThat(resolve(Map.of("amr", "mfa"))).isEqualTo(new TokenResolution.Refused(ErrorCode.USER_NOT_PROVISIONED));
        verifyNoInteractions(authorization);
        verify(journal).recordAuthenticationDenial(ISSUER, PROVIDER, subjectDigest(), sessionDigest(),
                "USER_NOT_PROVISIONED", NOW.minusSeconds(60), true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISABLED", "SUSPENDED", "REVOKED"})
    void disabledSuspendedAndPreRevocationTokensCannotLoadRoles(String reason) {
        when(profiles.findBySubject(PROVIDER, SUBJECT)).thenReturn(Optional.of(profile(
                reason.equals("REVOKED") ? UserAccountStatus.ACTIVE : UserAccountStatus.valueOf(reason),
                reason.equals("REVOKED") ? NOW.minusSeconds(29) : NOW.minusSeconds(600), null)));
        assertThat(resolve(Map.of("amr", "mfa"))).isEqualTo(new TokenResolution.Refused(ErrorCode.USER_INACTIVE));
        verifyNoInteractions(authorization);
        verify(journal).recordAuthenticationDenial(ISSUER, PROVIDER, subjectDigest(), sessionDigest(),
                "USER_INACTIVE", NOW.minusSeconds(60), true);
    }

    @Test
    void exactRevocationBoundaryAndMissingSessionAreHandledWithoutFabricatingStepUp() {
        when(profiles.findBySubject(PROVIDER, SUBJECT)).thenReturn(Optional.of(profile(UserAccountStatus.ACTIVE, NOW, null)));
        var actor = accepted(resolver.resolve(ISSUER, SUBJECT, null, NOW, Instant.EPOCH, Map.of("amr", "mfa")));
        assertThat(actor.sessionDigest()).isNull();
        assertThat(actor.authenticatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(actor.stepUpSatisfiedAt(NOW)).isFalse();
        verify(journal).recordAuthentication(ISSUER, PROVIDER, subjectDigest(), null, USER, Instant.EPOCH, true);
        verify(profiles).touch(USER, NOW);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 299, 300, 301})
    void activityIsThrottledButRolesAreAlwaysLive(long lastSeenAge) {
        when(profiles.findBySubject(PROVIDER, SUBJECT)).thenReturn(Optional.of(
                profile(UserAccountStatus.ACTIVE, NOW.minusSeconds(600), NOW.minusSeconds(lastSeenAge))));
        when(authorization.liveRoles(USER, NOW)).thenReturn(Set.of(BusinessRoleCode.READ_ONLY))
                .thenReturn(Set.of(BusinessRoleCode.OPERATIONS));
        assertThat(accepted(resolve(Map.of("amr", "mfa"))).roles()).containsExactly(BusinessRoleCode.READ_ONLY);
        assertThat(accepted(resolve(Map.of("amr", "mfa"))).roles()).containsExactly(BusinessRoleCode.OPERATIONS);
        if (lastSeenAge < 300) {
            verifyNoInteractions(journal);
            verify(profiles, never()).touch(any(), any());
        } else {
            verify(profiles, times(2)).touch(USER, NOW);
            verify(journal, times(2)).recordAuthentication(ISSUER, PROVIDER, subjectDigest(), sessionDigest(), USER, NOW.minusSeconds(60), true);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidTimesAndIdentity")
    void malformedOrFutureAuthenticationCannotReachProviderOrUserData(String issuer, String subject,
            Instant issued, Instant authenticated, Map<String, Object> claims) {
        assertThat(resolver.resolve(issuer, subject, null, issued, authenticated, claims))
                .isEqualTo(new TokenResolution.Refused(ErrorCode.AUTHENTICATION_REQUIRED));
        verifyNoInteractions(providers, profiles, authorization, journal);
    }

    static Stream<Arguments> invalidTimesAndIdentity() {
        Map<String,Object> claims=Map.of("amr","mfa");
        return Stream.of(Arguments.of(null,SUBJECT,NOW,NOW,claims), Arguments.of(" ",SUBJECT,NOW,NOW,claims),
                Arguments.of(ISSUER,null,NOW,NOW,claims), Arguments.of(ISSUER," ",NOW,NOW,claims),
                Arguments.of(ISSUER,SUBJECT,null,NOW,claims), Arguments.of(ISSUER,SUBJECT,NOW,null,claims),
                Arguments.of(ISSUER,SUBJECT,NOW,NOW,null), Arguments.of(ISSUER,SUBJECT,Instant.EPOCH.minusSeconds(1),Instant.EPOCH,claims),
                Arguments.of(ISSUER,SUBJECT,NOW.plusSeconds(1),NOW,claims),
                Arguments.of(ISSUER,SUBJECT,NOW,Instant.EPOCH.minusSeconds(1),claims),
                Arguments.of(ISSUER,SUBJECT,NOW,NOW.plusSeconds(1),claims));
    }

    private TokenResolution resolve(Map<String,Object> claims) {
        return resolver.resolve(ISSUER,SUBJECT,SESSION,NOW.minusSeconds(30),NOW.minusSeconds(60),claims);
    }
    private static AuthenticatedActor accepted(TokenResolution result) {
        assertThat(result).isInstanceOf(TokenResolution.Accepted.class);
        return ((TokenResolution.Accepted)result).actor();
    }
    private static String subjectDigest() { return Digest.ofComponents(List.of(ISSUER,SUBJECT)); }
    private static String sessionDigest() { return Digest.ofComponents(List.of(ISSUER,SESSION)); }
    private static IdentityProviderRecord provider(String name,String value,IdentityProviderStatus status,ProviderVerificationState verified) {
        return new IdentityProviderRecord(PROVIDER,"synthetic","Synthetic identity",ISSUER,name,value,900,verified,NOW,
                "evidence://synthetic/identity","Synthetic fixture","synthetic-owner",status,NOW,NOW,1);
    }
    private static UserProfile profile(UserAccountStatus status,Instant validFrom,Instant seen) {
        return new UserProfile(USER,ORGANIZATION,PROVIDER,SUBJECT,null,"Synthetic user",null,status,
                status==UserAccountStatus.ACTIVE?null:"synthetic-disabled",validFrom,seen,NOW,NOW,1);
    }
}

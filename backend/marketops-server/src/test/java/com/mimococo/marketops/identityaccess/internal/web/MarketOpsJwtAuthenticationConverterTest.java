package com.mimococo.marketops.identityaccess.internal.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.internal.application.TokenIdentityResolver;
import com.mimococo.marketops.identityaccess.internal.application.TokenResolution;
import com.mimococo.marketops.shared.ErrorCode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;

class MarketOpsJwtAuthenticationConverterTest {
    private static final Instant NOW=Instant.parse("2026-08-28T00:00:00Z");
    private static final String ISSUER="https://identity.example.invalid";
    private final TokenIdentityResolver resolver=mock(TokenIdentityResolver.class);
    private final MarketOpsJwtAuthenticationConverter converter=new MarketOpsJwtAuthenticationConverter(resolver);
    private final AuthenticatedActor actor=new AuthenticatedActor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),ISSUER,
            "Synthetic user","1".repeat(64),null,NOW,NOW.plusSeconds(900),true,Set.of(BusinessRoleCode.READ_ONLY));

    @ParameterizedTest
    @ValueSource(strings={"SID","JTI","NONE"})
    void carriesOnlyTheResolvedActorWithNoTokenBasedAuthorities(String identifier) {
        Jwt.Builder builder=token().claim("auth_time",NOW).claim("amr",List.of("pwd")).claim("acr","mfa")
                .claim("custom_assurance","reviewed-level").claim("roles",List.of("OWNER"));
        if(identifier.equals("SID")) builder.claim("sid","synthetic-session").jti("synthetic-jti");
        if(identifier.equals("JTI")) builder.jti("synthetic-jti");
        Jwt jwt=builder.build();
        String session=identifier.equals("NONE")?null:identifier.equals("SID")?"synthetic-session":"synthetic-jti";
        when(resolver.resolve(ISSUER,"synthetic-subject",session,NOW,NOW,jwt.getClaims()))
                .thenReturn(new TokenResolution.Accepted(actor));
        var authentication=converter.convert(jwt);
        assertThat(authentication.getPrincipal()).isSameAs(actor);
        assertThat(authentication.getAuthorities()).isEmpty();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo(actor.subjectDigest());
        verify(resolver).resolve(ISSUER,"synthetic-subject",session,NOW,NOW,jwt.getClaims());
    }

    @Test
    void absentAuthenticationTimeNeverBecomesTheRenewedTokenIssueTime() {
        Jwt jwt=token().build();
        when(resolver.resolve(ISSUER,"synthetic-subject",null,NOW,Instant.EPOCH,jwt.getClaims()))
                .thenReturn(new TokenResolution.Accepted(actor));
        converter.convert(jwt);
        verify(resolver).resolve(ISSUER,"synthetic-subject",null,NOW,Instant.EPOCH,jwt.getClaims());
    }

    @ParameterizedTest
    @MethodSource("validNumericDates")
    void numericDatesAreReadExactly(Object time) {
        Jwt jwt=token().claim("auth_time",time).build();
        when(resolver.resolve(ISSUER,"synthetic-subject",null,NOW,NOW,jwt.getClaims()))
                .thenReturn(new TokenResolution.Accepted(actor));
        converter.convert(jwt);
        verify(resolver).resolve(ISSUER,"synthetic-subject",null,NOW,NOW,jwt.getClaims());
    }
    static Stream<Arguments> validNumericDates() {
        return Stream.of(Arguments.of(NOW),Arguments.of(NOW.getEpochSecond()),
                Arguments.of(BigInteger.valueOf(NOW.getEpochSecond())),Arguments.of(new BigDecimal(NOW.getEpochSecond()+".000")));
    }

    @ParameterizedTest
    @MethodSource("malformedClaims")
    void malformedClaimsRefuseWithoutReadingBusinessIdentity(String key,Object value) {
        var claims=new java.util.HashMap<>(token().build().getClaims());
        if(value==null) claims.remove(key); else claims.put(key,value);
        // Preserve malformed raw claims; the builder itself rejects malformed
        // iat before the converter can exercise its independent boundary.
        Jwt jwt=new Jwt("synthetic-token",null,NOW.plusSeconds(600),Map.of("alg","RS256"),claims);
        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(IdentityRefusedException.class)
                .hasMessage(ErrorCode.AUTHENTICATION_REQUIRED.safeMessage());
        verifyNoInteractions(resolver);
    }
    static Stream<Arguments> malformedClaims() {
        return Stream.of(Arguments.of("iss",null),Arguments.of("iss",true),Arguments.of("iss"," "),
                Arguments.of("sub",null),Arguments.of("sub",List.of("subject")),Arguments.of("sub",""),
                Arguments.of("iat",null),Arguments.of("iat","2026-08-28"),
                Arguments.of("auth_time",true),Arguments.of("auth_time","1787875200"),
                Arguments.of("auth_time",new BigDecimal("1787875200.1")),
                Arguments.of("auth_time",new BigInteger("9999999999999999999999999999")),
                Arguments.of("auth_time",Long.MAX_VALUE),Arguments.of("auth_time",Double.NaN),
                Arguments.of("sid",true),Arguments.of("sid"," "),Arguments.of("jti",Map.of("id","value")));
    }

    @Test
    void resolverRefusalStaysASafeAuthenticationFailure() {
        when(resolver.resolve(anyString(),anyString(),isNull(),any(),any(),anyMap()))
                .thenReturn(new TokenResolution.Refused(ErrorCode.USER_INACTIVE));
        assertThatThrownBy(() -> converter.convert(token().build()))
                .isInstanceOfSatisfying(IdentityRefusedException.class, failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.USER_INACTIVE))
                .hasMessage(ErrorCode.USER_INACTIVE.safeMessage());
    }

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("synthetic-token").header("alg","RS256").issuer(ISSUER)
                .subject("synthetic-subject").issuedAt(NOW).expiresAt(NOW.plusSeconds(600));
    }
}

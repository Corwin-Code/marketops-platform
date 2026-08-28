package com.mimococo.marketops.identityaccess.internal.web;

import com.mimococo.marketops.identityaccess.internal.config.IdentityProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Ephemeral RSA issuer for browser tests; the production signature/claim validators still run. */
@TestConfiguration(proxyBeanMethods = false)
public class BrowserSigningFixture {
    public static final String ISSUER = "https://id.example.test/browser";
    public static final String AUDIENCE = "marketops";
    private static final RSAKey KEY = key();

    @Bean @Primary
    JwtDecoder browserSignatureDecoder(IdentityProperties properties) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        decoder.setJwtValidator(IdentitySecurityConfig.tokenValidator(properties));
        return decoder;
    }

    /** Mint only the synthetic identity provisioned into the empty browser database. */
    public static String token(String subject) throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("browser-fixture").build(),
                new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(AUDIENCE)
                        .issueTime(Date.from(now.minusSeconds(2))).expirationTime(Date.from(now.plusSeconds(600)))
                        .claim("amr", List.of("pwd", "mfa")).claim("auth_time", now.minusSeconds(5).getEpochSecond())
                        .claim("sid", UUID.randomUUID().toString()).build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID("browser-fixture").generate(); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
}

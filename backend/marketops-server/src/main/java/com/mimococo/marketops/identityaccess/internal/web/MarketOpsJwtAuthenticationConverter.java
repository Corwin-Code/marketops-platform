package com.mimococo.marketops.identityaccess.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.internal.application.TokenIdentityResolver;
import com.mimococo.marketops.identityaccess.internal.application.TokenResolution;
import com.mimococo.marketops.shared.ErrorCode;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a validated bearer token into the request's authenticated person.
 *
 * <p>The converter reads only the claims the identity contract names and hands
 * them to the resolver; it makes no authorization decision of its own and
 * grants no authority. The resulting authentication carries an
 * {@link AuthenticatedActor} as its principal and deliberately no granted
 * authorities, because a role read from a token would be an authorization
 * decision made by the identity provider rather than by this product.
 *
 * <p>Missing {@code auth_time} is represented as EPOCH, so token renewal cannot
 * fabricate a fresh human authentication. Malformed supplied times are refused.
 * The resolver reads the exact provider-configured MFA claim, not a union of
 * similarly named claims.
 */
@Component
public class MarketOpsJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    /** Claim naming the session, preferred over the token identifier. */
    private static final String SESSION_CLAIM = "sid";

    /** Claim naming the instant the subject authenticated. */
    private static final String AUTH_TIME_CLAIM = "auth_time";

    private final TokenIdentityResolver resolver;

    MarketOpsJwtAuthenticationConverter(TokenIdentityResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        String issuer = requiredString(token.getClaim("iss"));
        String subject = requiredString(token.getClaim("sub"));
        Instant issuedAt = numericDate(token.getClaim("iat"), false);
        Instant authenticatedAt = numericDate(token.getClaim(AUTH_TIME_CLAIM), true);

        TokenResolution resolution = resolver.resolve(
                issuer, subject, sessionIdentifier(token), issuedAt, authenticatedAt,
                token.getClaims());

        if (resolution instanceof TokenResolution.Refused refused) {
            throw new IdentityRefusedException(refused.code());
        }
        AuthenticatedActor actor = ((TokenResolution.Accepted) resolution).actor();
        return new AuthenticatedActorAuthentication(actor, token);
    }

    private static String sessionIdentifier(Jwt token) {
        Object session = token.getClaim(SESSION_CLAIM);
        if (session != null) return requiredString(session);
        Object identifier = token.getClaim("jti");
        return identifier == null ? null : requiredString(identifier);
    }

    private static String requiredString(Object claim) {
        if (claim instanceof String value && !value.isBlank()) return value;
        throw new IdentityRefusedException(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static Instant numericDate(Object claim, boolean optional) {
        if (claim == null && optional) return Instant.EPOCH;
        if (claim instanceof Instant instant) {
            return instant;
        }
        if (claim instanceof Number seconds) {
            try {
                return Instant.ofEpochSecond(new BigDecimal(seconds.toString()).longValueExact());
            } catch (ArithmeticException | NumberFormatException | DateTimeException malformed) {
                throw new IdentityRefusedException(ErrorCode.AUTHENTICATION_REQUIRED);
            }
        }
        throw new IdentityRefusedException(ErrorCode.AUTHENTICATION_REQUIRED);
    }
}

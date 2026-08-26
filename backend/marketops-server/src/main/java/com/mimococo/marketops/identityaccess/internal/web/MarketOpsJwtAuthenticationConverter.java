package com.mimococo.marketops.identityaccess.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.internal.application.TokenIdentityResolver;
import com.mimococo.marketops.identityaccess.internal.application.TokenResolution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * <p>{@code auth_time} falls back to the issue time when the provider omits it.
 * That is the conservative reading: a token that does not state when its subject
 * authenticated cannot be treated as older or newer than its own issuance.
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
        String issuer = Objects.toString(token.getIssuer(), "");
        String subject = Objects.toString(token.getSubject(), "");
        Instant issuedAt = token.getIssuedAt() == null ? Instant.EPOCH : token.getIssuedAt();
        Instant authenticatedAt = authenticationTime(token, issuedAt);

        TokenResolution resolution = resolver.resolve(
                issuer, subject, sessionIdentifier(token), issuedAt, authenticatedAt,
                authenticationMethods(token));

        if (resolution instanceof TokenResolution.Refused refused) {
            throw new IdentityRefusedException(refused.code());
        }
        AuthenticatedActor actor = ((TokenResolution.Accepted) resolution).actor();
        return new AuthenticatedActorAuthentication(actor, token);
    }

    private static String sessionIdentifier(Jwt token) {
        String session = token.getClaimAsString(SESSION_CLAIM);
        return session != null ? session : token.getId();
    }

    private static Instant authenticationTime(Jwt token, Instant issuedAt) {
        Object claim = token.getClaim(AUTH_TIME_CLAIM);
        if (claim instanceof Instant instant) {
            return instant;
        }
        if (claim instanceof Number seconds) {
            return Instant.ofEpochSecond(seconds.longValue());
        }
        return issuedAt;
    }

    /**
     * The authentication methods the provider reported, in whichever shape it
     * reported them.
     *
     * <p>Providers publish this as a list or as a single value depending on the
     * claim. Both are read; an unrecognised shape yields an empty list, which
     * fails the mandatory second-factor check rather than passing it.
     */
    private static List<String> authenticationMethods(Jwt token) {
        List<String> values = new ArrayList<>();
        for (String claimName : List.of("amr", "acr")) {
            Object claim = token.getClaim(claimName);
            if (claim instanceof String single) {
                values.add(single);
            } else if (claim instanceof List<?> many) {
                for (Object element : many) {
                    if (element instanceof String value) {
                        values.add(value);
                    }
                }
            }
        }
        return values;
    }
}

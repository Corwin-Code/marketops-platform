package com.mimococo.marketops.identityaccess.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import java.io.Serial;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authentication of one accepted request.
 *
 * <p>It carries no granted authorities on purpose. Authorization in this product
 * is a live database decision made by the business authorization service, and a
 * role baked into an authentication object would be a second, stale copy of that
 * decision that a filter could accidentally trust.
 */
final class AuthenticatedActorAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient AuthenticatedActor actor;
    private final transient Jwt token;

    AuthenticatedActorAuthentication(AuthenticatedActor actor, Jwt token) {
        super(List.of());
        this.actor = actor;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public Object getPrincipal() {
        return actor;
    }

    @Override
    public String getName() {
        return actor.subjectDigest();
    }
}

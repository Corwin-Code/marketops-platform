package com.mimococo.marketops.identityaccess.internal.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Makes the authenticated person available as a controller parameter.
 *
 * <p>Registering the resolver here rather than annotating each controller keeps
 * the identity contract in one place: a console endpoint asks for an actor by
 * declaring one, and cannot receive an unauthenticated stand-in.
 */
@Configuration
class IdentityWebConfig implements WebMvcConfigurer {

    private final AuthenticatedActorArgumentResolver actorResolver;

    IdentityWebConfig(AuthenticatedActorArgumentResolver actorResolver) {
        this.actorResolver = actorResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(actorResolver);
    }
}

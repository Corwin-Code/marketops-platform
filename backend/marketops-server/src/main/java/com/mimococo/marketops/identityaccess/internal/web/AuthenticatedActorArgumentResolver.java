package com.mimococo.marketops.identityaccess.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies the authenticated person to a controller that declares one.
 *
 * <p>Declaring {@code AuthenticatedActor} as a parameter is how a console
 * endpoint states that it operates on somebody's behalf. If no actor is present
 * the request is refused here rather than reaching a handler that would have to
 * remember to check, so an endpoint cannot accidentally serve an anonymous
 * caller by forgetting a guard.
 */
@Component
public class AuthenticatedActorArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedActor.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer container,
                                  NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedActor actor) {
            return actor;
        }
        throw OperationRejectedException.of(ErrorCode.AUTHENTICATION_REQUIRED);
    }
}

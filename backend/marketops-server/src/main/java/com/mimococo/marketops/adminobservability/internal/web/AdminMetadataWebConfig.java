package com.mimococo.marketops.adminobservability.internal.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the maintenance boundary on every metadata maintenance route.
 *
 * <p>The pattern covers the whole maintenance surface, so a new maintenance
 * controller is guarded by construction rather than by remembering to opt in.
 */
@Configuration
class AdminMetadataWebConfig implements WebMvcConfigurer {

    static final String ADMIN_METADATA_PATTERN = "/api/v1/admin/metadata/**";

    private final AdminMetadataGuard guard;

    AdminMetadataWebConfig(AdminMetadataGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns(ADMIN_METADATA_PATTERN);
    }
}

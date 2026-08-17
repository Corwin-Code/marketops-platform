package com.mimococo.marketops;

import org.springframework.boot.SpringApplication;
import org.springframework.modulith.Modulith;

/**
 * Entry point of the MarketOps server.
 *
 * <p>{@code @Modulith} is meta-annotated with {@code @SpringBootApplication}, so
 * adding that annotation as well would declare the same configuration twice.
 *
 * <p>Declaring {@code shared} as a shared module makes it available whenever a
 * single module is bootstrapped in isolation for an integration test. It does not
 * relax encapsulation: the module keeps its private {@code internal} package and
 * remains part of cycle detection.
 */
@Modulith(systemName = "MarketOps Russia", sharedModules = "shared")
public class MarketOpsServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketOpsServerApplication.class, args);
    }
}

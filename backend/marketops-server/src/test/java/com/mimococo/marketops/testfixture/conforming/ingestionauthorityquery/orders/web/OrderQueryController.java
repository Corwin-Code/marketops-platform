package com.mimococo.marketops.testfixture.conforming.ingestionauthorityquery.orders.web;

import com.mimococo.marketops.testfixture.conforming.ingestionauthorityquery.orders.application.OrderQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/** Read-only controller whose dependency graph contains no acquisition authority. */
@Profile("architecture-fixture")
@RestController
public final class OrderQueryController {

    private final OrderQueryService query;

    public OrderQueryController(OrderQueryService query) {
        this.query = query;
    }

    /** Return the service's bounded read result. */
    public String summary() {
        return query.summary();
    }
}

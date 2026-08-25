package com.mimococo.marketops.testfixture.conforming.polymorphicsafe;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

interface QueryUseCase {

    String summary();
}

final class SafeQueryService implements QueryUseCase {

    @Override
    public String summary() {
        return "safe query";
    }
}

@Profile("architecture-fixture")
@RestController
final class SafeQueryController {

    private final QueryUseCase query;

    SafeQueryController(QueryUseCase query) {
        this.query = query;
    }

    String summary() {
        return query.summary();
    }
}

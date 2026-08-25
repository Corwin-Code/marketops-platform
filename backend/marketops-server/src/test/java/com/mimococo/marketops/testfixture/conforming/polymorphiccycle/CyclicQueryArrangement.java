package com.mimococo.marketops.testfixture.conforming.polymorphiccycle;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

interface CyclicQueryUseCase {

    String summary();
}

final class FirstQueryService implements CyclicQueryUseCase {

    private final SecondQueryService second;

    FirstQueryService(SecondQueryService second) {
        this.second = second;
    }

    @Override
    public String summary() {
        return second.summary();
    }
}

final class SecondQueryService implements CyclicQueryUseCase {

    private final FirstQueryService first;

    SecondQueryService(FirstQueryService first) {
        this.first = first;
    }

    @Override
    public String summary() {
        return first.getClass().getSimpleName();
    }
}

@Profile("architecture-fixture")
@RestController
final class CyclicQueryController {

    private final CyclicQueryUseCase query;

    CyclicQueryController(CyclicQueryUseCase query) {
        this.query = query;
    }

    String summary() {
        return query.summary();
    }
}

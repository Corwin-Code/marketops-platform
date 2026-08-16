package com.mimococo.marketops.testfixture.conforming.internalization.beta;

import com.mimococo.marketops.testfixture.conforming.internalization.alpha.internalization.InternalizationService;

/** Another module may use a public package whose name merely starts with internal. */
public final class InternalizationConsumer {

    private final InternalizationService service = new InternalizationService();
}

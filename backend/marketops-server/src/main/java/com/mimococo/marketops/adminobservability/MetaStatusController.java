package com.mimococo.marketops.adminobservability;

import com.mimococo.marketops.adminobservability.internal.MetaStatusAssembler;
import com.mimococo.marketops.adminobservability.internal.MetaStatusResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the application metadata the operations console renders.
 *
 * <p>The resource answers successfully even when the database is unreachable, and
 * reports the database state inside the payload. That lets the console tell an
 * unreachable backend apart from a reachable backend with a degraded data layer —
 * two situations with different operator responses.
 */
@RestController
@RequestMapping("/api/v1/meta")
public class MetaStatusController {

    private final MetaStatusAssembler assembler;

    MetaStatusController(MetaStatusAssembler assembler) {
        this.assembler = assembler;
    }

    /** Return the current application metadata. */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public MetaStatusResponse status() {
        return assembler.assemble();
    }
}

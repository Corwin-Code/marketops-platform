package com.mimococo.marketops.listingconversion.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.listingconversion.internal.application.ListingAllowanceReleaseService;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.AllowanceReleaseRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loopback maintenance for the matured-outcome allowance release.
 *
 * <p>The maintenance boundary admits only a loopback peer, and a run additionally needs the
 * workstation write switch and {@code X-Operator} attribution. A run is exactly one pass of the
 * same database function the timer calls, so it can release only what the timer would release.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata/listing-allowance-occupations")
class ListingAllowanceReleaseAdminController {

    private final ListingAllowanceReleaseService releases;

    ListingAllowanceReleaseAdminController(ListingAllowanceReleaseService releases) {
        this.releases = releases;
    }

    /** Where every description action still holding allowance stands; changes nothing. */
    @GetMapping(value = "/outcome-maturity", produces = MediaType.APPLICATION_JSON_VALUE)
    List<AllowanceReleaseRepository.Maturity> maturity(
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return releases.pending(limit);
    }

    /** Run one bounded release pass now. */
    @PostMapping(value = "/outcome-release-runs", produces = MediaType.APPLICATION_JSON_VALUE)
    AllowanceReleaseRepository.PassResult run(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                                              @RequestBody(required = false) RunRequest request) {
        return releases.runForOperator(operator, request == null || request.limit() == null ? 50 : request.limit());
    }

    record RunRequest(@Min(1) @Max(500) Integer limit) {
    }
}

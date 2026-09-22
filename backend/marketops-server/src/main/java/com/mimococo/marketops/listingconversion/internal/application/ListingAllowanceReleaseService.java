package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.AllowanceReleaseRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases the allowance a description change still holds once its outcome period has matured.
 *
 * <p>Nothing here decides eligibility: the database function checks confirmation, containment,
 * open investigations and {@code RESPONSIBILITY_SLO.outcomeMaturityDays} under the organization's
 * exposure lock, releases idempotently and journals each release as the fixed system component
 * {@code listing-allowance-release}. Promotion occupations are never released here.
 */
@Service
public class ListingAllowanceReleaseService {

    static final int MAX_PASS = 500;
    private static final Logger log = LoggerFactory.getLogger(ListingAllowanceReleaseService.class);

    private final AllowanceReleaseRepository releases;
    private final Clock clock;

    ListingAllowanceReleaseService(AllowanceReleaseRepository releases, Clock clock) {
        this.releases = releases;
        this.clock = clock;
    }

    /** One timer pass; no person is attributed to the trigger. */
    @Transactional
    public AllowanceReleaseRepository.PassResult runScheduled(int limit) {
        return run(limit, "SCHEDULED", null);
    }

    /** One pass requested by a loopback maintenance operator. */
    @Transactional
    public AllowanceReleaseRepository.PassResult runForOperator(String operator, int limit) {
        if (operator == null || !MetadataFieldPolicy.OPERATOR.matcher(operator).matches()) {
            throw OperationRejectedException.of(ErrorCode.OPERATOR_ATTRIBUTION_MISSING);
        }
        return run(limit, "MAINTENANCE", operator);
    }

    @Transactional(readOnly = true)
    public List<AllowanceReleaseRepository.Maturity> pending(int limit) {
        return releases.pending(clock.instant(), bounded(limit));
    }

    private AllowanceReleaseRepository.PassResult run(int limit, String trigger, String operator) {
        var result = releases.releaseMatured(bounded(limit), trigger, operator, CorrelationId.current());
        if (!result.releasedOccupations().isEmpty() || !result.skipped().isEmpty()) {
            log.info("event=lc_allowance_outcome_release_pass trigger={} considered={} releasedActions={} "
                            + "releasedOccupations={} skipped={} limitReached={} correlationId={}",
                    trigger, result.considered(), result.releasedActions().size(),
                    result.releasedOccupations().size(), result.skipped().size(), result.limitReached(),
                    CorrelationId.current());
        }
        return result;
    }

    private static int bounded(int limit) {
        if (limit < 1 || limit > MAX_PASS) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        return limit;
    }
}

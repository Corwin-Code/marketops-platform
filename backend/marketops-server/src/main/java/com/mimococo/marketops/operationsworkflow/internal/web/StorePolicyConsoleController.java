package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.internal.application.CommercialPolicyService;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.PolicyRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A store's own commercial policy: the limits its price changes are checked against, read and
 * published from the price guardrail page (Owner decisions 2026-10-01).
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console")
class StorePolicyConsoleController {

    private final CommercialPolicyService policies;

    StorePolicyConsoleController(CommercialPolicyService policies) {
        this.policies = policies;
    }

    /** The policy in force for the store's listings, the store's own versions and the limit vocabulary. */
    @GetMapping(value = "/stores/{storeId}/commercial-policy", produces = MediaType.APPLICATION_JSON_VALUE)
    PolicyPage view(AuthenticatedActor actor, @PathVariable UUID storeId) {
        CommercialPolicyService.StorePolicyView view = policies.storeView(actor, storeId);
        return new PolicyPage(view.storeId(), view.generatedAt(), view.currencyCode(), view.limitKinds(),
                view.inForce() == null ? null : PolicyView.of(view.inForce(), view.viewerId()),
                view.limits().stream().map(LimitView::of).toList(),
                view.versions().stream().map(version -> PolicyView.of(version, view.viewerId())).toList());
    }

    /** Publish a new version of the store's own policy; needs COMMERCIAL_POLICY_MANAGE and a recent sign-in. */
    @PostMapping(value = "/stores/{storeId}/commercial-policy", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Published publish(AuthenticatedActor actor, @PathVariable UUID storeId,
                      @Valid @RequestBody PublishRequest request) {
        return new Published(policies.publishForStore(actor, storeId, request.lifecycleObjective(),
                request.limits().stream().map(limit -> new CommercialPolicyService.LimitDraft(limit.limitCode(),
                        limit.rateValue(), limit.amountValue(), limit.countValue(), limit.durationSeconds()))
                        .toList(),
                request.reason()));
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /** A version to publish; each limit sets exactly the value its kind names. */
    record PublishRequest(@NotBlank String lifecycleObjective, @NotEmpty List<@Valid LimitRequest> limits,
                          @NotBlank @Size(max = 512) String reason) {
    }

    /** One limit to publish. */
    record LimitRequest(@NotBlank String limitCode, BigDecimal rateValue, BigDecimal amountValue,
                        Integer countValue, Long durationSeconds) {
    }

    /** The published version. */
    record Published(UUID policyId) {
    }

    /**
     * The store's policy page.
     *
     * @param currencyCode the store's currency, the one its policy amounts are in
     * @param inForce the policy the store's listings are checked against now, or {@code null}
     * @param limits the limits of the policy in force, in the vocabulary's order
     * @param versions the store's own versions, newest first
     */
    record PolicyPage(UUID storeId, Instant generatedAt, String currencyCode,
                      List<PolicyRepository.LimitKind> limitKinds, PolicyView inForce, List<LimitView> limits,
                      List<PolicyView> versions) {
    }

    /**
     * One policy version.
     *
     * @param scopeKind STORE for the store's own; ORGANIZATION or PLATFORM when a wider policy applies
     * @param publishedByViewer whether the person asking published it
     */
    record PolicyView(UUID policyId, String policyCode, int policyVersion, String scopeKind,
                      String lifecycleObjective, String currencyCode, Instant effectiveFrom, Instant effectiveTo,
                      String status, String reason, boolean publishedByViewer) {

        static PolicyView of(PolicyRepository.PolicyDetail policy, UUID viewerId) {
            return new PolicyView(policy.id(), policy.policyCode(), policy.policyVersion(), policy.scopeKind(),
                    policy.lifecycleObjective(), policy.currencyCode(), policy.effectiveFrom(),
                    policy.effectiveTo(), policy.status(), policy.reason(),
                    policy.publishedByUserId().equals(viewerId));
        }
    }

    /** One configured limit; decimals as text, exactly one value set. */
    record LimitView(String limitCode, String rateValue, String amountValue, Integer countValue,
                     Long durationSeconds) {

        static LimitView of(PolicyRepository.LimitRow limit) {
            return new LimitView(limit.limitCode(), text(limit.rateValue()), text(limit.amountValue()),
                    limit.countValue(), limit.durationSeconds());
        }
    }
}

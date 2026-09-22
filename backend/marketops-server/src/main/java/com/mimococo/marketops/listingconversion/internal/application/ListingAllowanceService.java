package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.AuthenticatedInvocationIssuer;
import com.mimococo.marketops.identityaccess.AuthorizationVerdict;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.listingconversion.AllowanceMaintenanceView;
import com.mimococo.marketops.listingconversion.AllowanceMaintenanceView.Allowance;
import com.mimococo.marketops.listingconversion.AllowanceMaintenanceView.PublishResult;
import com.mimococo.marketops.listingconversion.AllowanceMaintenanceView.ReservePolicy;
import com.mimococo.marketops.listingconversion.AllowanceMaintenanceView.ReserveWarning;
import com.mimococo.marketops.listingconversion.AllowanceMaintenanceView.StoreOption;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.AllowanceRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Owner's maintenance of listing launch allowances: list every version with
 * what it has occupied, publish a new version, retire one.
 *
 * <p><b>Why LISTING_CALIBRATION_ACCEPT and not a new action.</b> An allowance is
 * the numeric half of the allowance policy whose other half (ALLOWANCE_AXES and
 * ALLOWANCE_RESERVE) the Owner accepts in a calibration package. The seed data
 * grants LISTING_CALIBRATION_ACCEPT to the OWNER role only, and the calibration
 * activation already reuses it for the same reason. A separate action would need
 * its own seed row, role mapping and scope grants, and could be handed to someone
 * who may not accept the policy yet could loosen its numbers. Reusing the grant
 * keeps "who may set how much listing change is open at once" with one person,
 * and the database functions check the same grant again from the invocation proof.
 */
@Service
public class ListingAllowanceService {

    static final List<String> AXES =
            List.of("CONCURRENT_LISTINGS", "AFFECTED_VARIANTS", "REVENUE_EXPOSURE", "CATEGORY_SHARE");
    private static final List<String> SCOPES = List.of("ORGANIZATION", "PLATFORM", "STORE");
    /** numeric(18,4): 14 integer digits. */
    private static final BigDecimal UPPER_BOUND = new BigDecimal("100000000000000");
    /** The database accepts an effective start this far in the past as "now". */
    private static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(5);

    /** A publication as the Owner entered it. Decimals arrive as text so none is rounded. */
    public record PublishRequest(String scopeKind, String platformCode, UUID storeId, String axisCode,
                                 String limitValue, String reserveValue, Instant effectiveFrom,
                                 String evidenceReference, String reason) {
    }

    private final AllowanceRepository allowances;
    private final BusinessAuthorization authorization;
    private final AuthenticatedInvocationIssuer issuer;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final MetadataAuditRecorder audit;
    private final IdGenerator ids;
    private final Clock clock;

    ListingAllowanceService(AllowanceRepository allowances, BusinessAuthorization authorization,
                            AuthenticatedInvocationIssuer issuer, JdbcClient jdbc, ObjectMapper json,
                            MetadataAuditRecorder audit, IdGenerator ids, Clock clock) {
        this.allowances = allowances;
        this.authorization = authorization;
        this.issuer = issuer;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ read

    @Transactional
    public AllowanceMaintenanceView view(AuthenticatedActor actor) {
        UUID org = actor.organizationId();
        boolean organizationVisible = holds(actor, ActionScopeCode.LISTING_CONVERSION_VIEW, ResourceScope.organization(org))
                || holds(actor, ActionScopeCode.LISTING_CALIBRATION_ACCEPT, ResourceScope.organization(org));
        Set<UUID> visibleStores = new LinkedHashSet<>(
                authorization.permittedStoreIds(actor, ActionScopeCode.LISTING_CONVERSION_VIEW));
        visibleStores.addAll(authorization.permittedStoreIds(actor, ActionScopeCode.LISTING_CALIBRATION_ACCEPT));
        if (!organizationVisible && visibleStores.isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        boolean organizationOwner = holds(actor, ActionScopeCode.LISTING_CALIBRATION_ACCEPT, ResourceScope.organization(org));
        Instant now = allowances.databaseNow();

        Map<UUID, Boolean> storeOwner = new LinkedHashMap<>();
        List<StoreOption> stores = new ArrayList<>();
        for (StoreOption store : allowances.stores(org)) {
            if (!organizationVisible && !visibleStores.contains(store.storeId())) {
                continue;
            }
            boolean owner = holds(actor, ActionScopeCode.LISTING_CALIBRATION_ACCEPT, ResourceScope.store(store.storeId()));
            storeOwner.put(store.storeId(), owner);
            stores.add(new StoreOption(store.storeId(), store.code(), store.displayName(), store.platformCode(),
                    store.currencyCode(), owner));
        }

        List<Allowance> rows = new ArrayList<>();
        for (Allowance row : allowances.allowances(org, now)) {
            boolean store = "STORE".equals(row.scopeKind());
            if (store ? !storeOwner.containsKey(row.storeId()) : !organizationVisible) {
                continue;
            }
            boolean manage = store ? storeOwner.getOrDefault(row.storeId(), false) : organizationOwner;
            rows.add(withManage(row, manage));
        }
        List<ReservePolicy> policies = allowances.reservePolicies(org, now);
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION, actor.userId().toString(),
                AuditAction.READ, "lc-exposure-allowance", org, null, Map.of(), "allowances", null));
        return new AllowanceMaintenanceView(now, organizationOwner, stores, allowances.platforms(), policies, rows);
    }

    // ------------------------------------------------------------------ publish

    @Transactional
    public PublishResult publish(AuthenticatedActor actor, PublishRequest request) {
        if (request == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String scopeKind = oneOf(request.scopeKind(), SCOPES);
        String axis = oneOf(request.axisCode(), AXES);
        String platform = null;
        UUID store = null;
        switch (scopeKind) {
            case "PLATFORM" -> {
                platform = MetadataFieldPolicy.requireText("platformCode", request.platformCode());
                if (request.storeId() != null) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            case "STORE" -> {
                if (request.storeId() == null || blank(request.platformCode()) != null) {
                    throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
                }
                store = request.storeId();
            }
            default -> {
                if (blank(request.platformCode()) != null || request.storeId() != null) {
                    throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
                }
            }
        }
        ResourceScope scope = store == null ? ResourceScope.organization(actor.organizationId()) : ResourceScope.store(store);
        authorization.require(actor, ActionScopeCode.LISTING_CALIBRATION_ACCEPT, scope);
        requireStepUp(actor);

        BigDecimal limit = decimal(request.limitValue());
        BigDecimal reserve = decimal(request.reserveValue());
        if (limit.signum() <= 0 || reserve.signum() < 0 || reserve.compareTo(limit) >= 0
                || limit.compareTo(UPPER_BOUND) >= 0) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (("CONCURRENT_LISTINGS".equals(axis) || "AFFECTED_VARIANTS".equals(axis))
                && (!whole(limit) || !whole(reserve))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Instant effectiveFrom = request.effectiveFrom();
        if (effectiveFrom != null && effectiveFrom.isBefore(clock.instant().minus(CLOCK_TOLERANCE))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String evidence = MetadataFieldPolicy.requireText("evidenceReference", request.evidenceReference());
        String reason = MetadataFieldPolicy.requireText("reason", request.reason());

        UUID id = ids.newId();
        String answer = allowances.publish(id, proof("LISTING_ALLOWANCE_PUBLISH", id), scopeKind, platform, store,
                axis, limit, reserve, effectiveFrom, evidence, reason);
        JsonNode node = json.readTree(answer);
        int version = node.path("allowanceVersion").asInt();
        String unit = node.path("unitCode").asText();
        Instant starts = OffsetDateTime.parse(node.path("effectiveFrom").asText()).toInstant();

        Map<String, FieldChange> changes = new LinkedHashMap<>();
        changes.put("axisCode", new FieldChange(null, axis));
        changes.put("scope", new FieldChange(null, scopeKind + ":" + (platform == null ? "" : platform) + ":"
                + (store == null ? "" : store)));
        changes.put("limitValue", new FieldChange(null, AllowanceRepository.plain(limit)));
        changes.put("reserveValue", new FieldChange(null, AllowanceRepository.plain(reserve)));
        changes.put("unitCode", new FieldChange(null, unit));
        changes.put("allowanceVersion", new FieldChange(null, Integer.toString(version)));
        changes.put("effectiveFrom", new FieldChange(null, starts.toString()));
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION, actor.userId().toString(),
                AuditAction.POLICY_CHANGE, "lc-exposure-allowance", id, null, changes, reason, evidence));

        List<ReserveWarning> warnings = reserveWarnings(actor.organizationId(), scopeKind, platform, store, axis, reserve);
        return new PublishResult(id, version, unit, starts, uuids(node.path("endedAllowanceIds")),
                uuids(node.path("retiredAllowanceIds")), warnings);
    }

    // ------------------------------------------------------------------ retire

    @Transactional
    public void retire(AuthenticatedActor actor, UUID allowanceId, String reason) {
        AllowanceRepository.AllowanceScope target = allowances.scope(allowanceId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!actor.organizationId().equals(target.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        ResourceScope scope = "STORE".equals(target.scopeKind())
                ? ResourceScope.store(target.storeId()) : ResourceScope.organization(target.organizationId());
        authorization.require(actor, ActionScopeCode.LISTING_CALIBRATION_ACCEPT, scope);
        requireStepUp(actor);
        String text = MetadataFieldPolicy.requireText("reason", reason);
        allowances.retire(allowanceId, proof("LISTING_ALLOWANCE_RETIRE", allowanceId), text);
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION, actor.userId().toString(),
                AuditAction.POLICY_CHANGE, "lc-exposure-allowance", allowanceId, null,
                Map.of("status", new FieldChange("ACTIVE", "RETIRED")), text, null));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Current packages that govern listings this allowance also covers and accept a
     * larger reserve for the axis: launches under them would be refused with
     * RESERVE_BELOW_ACCEPTED_POLICY. A warning, not a refusal: the Owner may be
     * about to raise the reserve or replace the package.
     */
    private List<ReserveWarning> reserveWarnings(UUID org, String scopeKind, String platform, UUID store, String axis,
                                                 BigDecimal reserve) {
        String storePlatform = store == null ? null : allowances.stores(org).stream()
                .filter(option -> option.storeId().equals(store)).map(StoreOption::platformCode)
                .findFirst().orElse(null);
        List<ReserveWarning> warnings = new ArrayList<>();
        for (ReservePolicy policy : allowances.reservePolicies(org, allowances.databaseNow())) {
            if (!axis.equals(policy.axisCode()) || !overlaps(scopeKind, platform, store, storePlatform, policy)) {
                continue;
            }
            BigDecimal accepted;
            try {
                accepted = new BigDecimal(policy.reserveValue());
            } catch (NumberFormatException | NullPointerException unreadable) {
                continue;
            }
            if (reserve.compareTo(accepted) < 0) {
                warnings.add(new ReserveWarning(policy.packageId(), policy.packageCode(), policy.purposeCode(), axis,
                        AllowanceRepository.plain(accepted), AllowanceRepository.plain(reserve)));
            }
        }
        return warnings;
    }

    /** Whether a package at its scope governs any listing the allowance scope covers. */
    static boolean overlaps(String scopeKind, String platform, UUID store, String storePlatform, ReservePolicy policy) {
        return switch (scopeKind) {
            case "ORGANIZATION" -> true;
            case "PLATFORM" -> switch (policy.scopeKind()) {
                case "ORGANIZATION" -> true;
                case "PLATFORM" -> Objects.equals(platform, policy.platformCode());
                default -> Objects.equals(platform, policy.storePlatformCode());
            };
            default -> switch (policy.scopeKind()) {
                case "ORGANIZATION" -> true;
                case "PLATFORM" -> Objects.equals(storePlatform, policy.platformCode());
                default -> Objects.equals(store, policy.storeId());
            };
        };
    }

    /** Holding the grant, even when a fresh sign-in is still needed before using it. */
    private boolean holds(AuthenticatedActor actor, ActionScopeCode action, ResourceScope scope) {
        AuthorizationVerdict verdict = authorization.evaluate(actor, action, scope);
        return verdict == AuthorizationVerdict.PERMITTED || verdict == AuthorizationVerdict.STEP_UP_REQUIRED;
    }

    private void requireStepUp(AuthenticatedActor actor) {
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
    }

    private String proof(String purpose, UUID target) {
        long[] context = jdbc.sql("SELECT pg_backend_pid(), txid_current()")
                .query((rs, row) -> new long[] {rs.getInt(1), rs.getLong(2)}).single();
        return issuer.issueControl(purpose, target, target, Math.toIntExact(context[0]), context[1]);
    }

    private static Allowance withManage(Allowance row, boolean manage) {
        return new Allowance(row.id(), row.version(), row.axisCode(), row.scopeKind(), row.platformCode(),
                row.storeId(), row.storeName(), row.unitCode(), row.limitValue(), row.reserveValue(),
                row.occupiedValue(), row.headroom(), row.occupancyUnresolved(), row.liveOccupations(),
                row.lifecycle(), row.effectiveFrom(), row.effectiveTo(), row.publishedAt(), row.publishedByUserId(),
                row.publishedByName(), row.evidenceReference(), row.publishReason(), row.supersedesAllowanceId(),
                row.supersededByAllowanceId(), row.retiredAt(), row.retiredByName(), row.retireReason(),
                manage && ("CURRENT".equals(row.lifecycle()) || "SCHEDULED".equals(row.lifecycle())));
    }

    private static String oneOf(String value, List<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** A plain decimal with at most four decimal places, as numeric(18,4) keeps it. */
    private static BigDecimal decimal(String value) {
        if (value == null || !value.strip().matches("[0-9]{1,14}([.][0-9]{1,4})?")) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return new BigDecimal(value.strip());
    }

    private static boolean whole(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static List<UUID> uuids(JsonNode array) {
        List<UUID> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(UUID.fromString(item.asText()));
        }
        return out;
    }
}

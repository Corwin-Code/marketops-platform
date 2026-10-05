package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.FeedWatermarkRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.FeedWatermarkRepository.Attestation;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.FeedWatermarkRepository.Collected;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.FeedWatermarkRepository.StoreScope;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.FeedWatermarkRepository.Watermark;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The freshness watermarks of the price guardrail (Owner decisions 2026-10-01).
 *
 * <p>The guardrail wants a verified watermark within the policy's maximum input age for each of eight
 * feeds. After every settled scheduled pass this records the ones the store's data supports, each
 * citing what it rests on: PRICE and STOCK the newest collected and normalized snapshot, SALES the
 * newest ordered-units window, INTERNAL_COST the newest price collection after which the master-data
 * automation reconciled the unit costs, COMMERCIAL_INPUTS the required profit and safety buffer in
 * force. RETURNS and FINANCE_FEES rest on the newest day the store read of its returns and of its
 * accruals (Owner decision 2026-10-06): once ordered units exist the store has both, and what tells
 * how fresh they are is how far they were read. A feed the store does not collect is covered by the
 * Owner's attestation that the store has no data in it: returns and finance fees as far as the
 * ordered units are known to be zero, and only until an ordered unit appears; advertising until the
 * attestation expires.
 *
 * <p>A watermark is written only when what it rests on moves on, and a reconciled one at most every
 * twelve hours, so a pass every minute leaves the table as it was. A feed that stops being renewed
 * ages out by itself within the policy's maximum input age.
 */
@Service
public class FeedWatermarkKeeper {

    /** Every feed the guardrail reads, in the order it lists them. */
    public static final List<String> FEEDS = List.of("PRICE", "STOCK", "SALES", "RETURNS", "FINANCE_FEES",
            "ADVERTISING", "INTERNAL_COST", "COMMERCIAL_INPUTS");

    /** The feeds an attestation may cover. */
    public static final List<String> ATTESTABLE = List.of("RETURNS", "FINANCE_FEES", "ADVERTISING");

    /**
     * The feeds that rest on a daily collection once the store reads it, each with the dataset read.
     * The accruals are archived without normalization; that they were read is what the feed's
     * freshness says.
     */
    private static final Map<String, String> READ_DAILY = Map.of("RETURNS", "RETURNS", "FINANCE_FEES", "FINANCE");

    /** The finance inputs the guardrail's minimum price rests on. */
    private static final List<String> COMMERCIAL_INPUT_CODES = List.of("REQUIRED_PROFIT_PER_UNIT",
            "SAFETY_BUFFER_PER_UNIT");

    /** How often a reconciled watermark is renewed. */
    private static final Duration RECONCILE_EVERY = Duration.ofHours(12);

    /** How long an attestation stands unless the Owner says otherwise, and at most. */
    public static final int DEFAULT_ATTESTATION_DAYS = 30;
    public static final int MAXIMUM_ATTESTATION_DAYS = 90;

    /**
     * How far back an ordered unit contradicts a statement that the store has no returns or finance
     * fees: one in this span refuses the statement, and one after it ends a standing one.
     */
    static final Duration ORDERS_LOOKBACK = Duration.ofDays(30);

    private static final String ENTITY_TYPE = "feed-absence-attestation";

    private final FeedWatermarkRepository watermarks;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;
    private final IdGenerator idGenerator;
    private final Clock clock;

    FeedWatermarkKeeper(FeedWatermarkRepository watermarks, BusinessAuthorization authorization,
                        MetadataAuditRecorder audit, IdGenerator idGenerator, Clock clock) {
        this.watermarks = watermarks;
        this.authorization = authorization;
        this.audit = audit;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Record the watermarks the store's data supports now. */
    @Transactional
    public void refresh(UUID organizationId, UUID storeId) {
        Instant now = clock.instant();
        Optional<StoreScope> found = watermarks.scope(organizationId, storeId);
        if (found.isEmpty()) {
            return;
        }
        StoreScope scope = found.get();
        watermarks.latestCollected(storeId, "PRICE", false).ifPresent(price ->
                advance(scope, "PRICE", price.collectedAt(), price.collectedAt(), run(price), now));
        watermarks.latestCollected(storeId, "STOCK", false).ifPresent(stock ->
                advance(scope, "STOCK", stock.collectedAt(), stock.collectedAt(), run(stock), now));
        Optional<Collected> sales = readWindow(storeId, "TRAFFIC", now);
        sales.ifPresent(traffic -> advance(scope, "SALES", traffic.windowTo(), traffic.collectedAt(),
                run(traffic) + "; ordered units through " + traffic.windowTo(), now));
        watermarks.latestCollected(storeId, "PRICE", true).ifPresent(costs ->
                advance(scope, "INTERNAL_COST", costs.collectedAt(), costs.collectedAt(),
                        run(costs) + "; master-data automation reconciled unit costs", now));
        Set<String> readDaily = new HashSet<>();
        READ_DAILY.forEach((feed, dataset) -> readWindow(storeId, dataset, now).ifPresent(read -> {
            advance(scope, feed, read.windowTo(), read.collectedAt(),
                    run(read) + "; " + dataset.toLowerCase(Locale.ROOT) + " read through " + read.windowTo(), now);
            readDaily.add(feed);
        }));

        List<FeedWatermarkRepository.FinanceInput> inputs = watermarks.financeInputsInForce(organizationId, storeId,
                COMMERCIAL_INPUT_CODES, now);
        if (inputs.stream().map(FeedWatermarkRepository.FinanceInput::inputCode).collect(Collectors.toSet())
                .containsAll(COMMERCIAL_INPUT_CODES)) {
            Instant newest = inputs.stream().map(FeedWatermarkRepository.FinanceInput::effectiveFrom)
                    .max(Instant::compareTo).orElse(now);
            reconcile(scope, "COMMERCIAL_INPUTS", newest, "finance-inputs:" + inputs.stream()
                    .map(input -> input.id().toString()).collect(Collectors.joining(",")), now);
        }

        for (String feed : ATTESTABLE) {
            Optional<Attestation> standing = watermarks.attestations(organizationId, storeId, 50).stream()
                    .filter(attestation -> attestation.feedCode().equals(feed) && attestation.standsAt(now))
                    .findFirst();
            if (standing.isEmpty()) {
                continue;
            }
            Attestation attestation = standing.get();
            if (attestation.lapsesOnOrders()) {
                if (watermarks.orderedSince(organizationId, storeId, attestation.attestedAt().minus(ORDERS_LOOKBACK))) {
                    watermarks.lapse(attestation.id(), now, "ORDERED_UNITS_APPEARED");
                    continue;
                }
                if (readDaily.contains(feed)) {
                    // The store reads the feed: its watermark rests on what was read, not on the statement.
                    continue;
                }
                // No ordered unit as far as the orders are known: nothing to return or to charge for.
                sales.ifPresent(traffic -> advance(scope, feed, traffic.windowTo(), traffic.collectedAt(),
                        "attestation:" + attestation.id() + "; no ordered units through " + traffic.windowTo(),
                        now));
            } else {
                reconcile(scope, feed, attestation.attestedAt(), "attestation:" + attestation.id(), now);
            }
        }
    }

    /** Record a watermark resting on a source time, when the time has moved on or the evidence changed. */
    private void advance(StoreScope scope, String feed, Instant sourceUpdatedAt, Instant ingestedAt, String evidence,
                         Instant now) {
        Optional<Watermark> latest = watermarks.latest(scope, feed);
        if (latest.isPresent() && latest.get().reconciledAt() == null && latest.get().sourceUpdatedAt() != null
                && !sourceUpdatedAt.isAfter(latest.get().sourceUpdatedAt())
                && evidence.equals(latest.get().evidenceReference())) {
            return;
        }
        if (latest.isPresent() && latest.get().effectiveAt() != null && sourceUpdatedAt.isBefore(latest.get().effectiveAt())) {
            return;
        }
        watermarks.insert(idGenerator.newId(), scope, feed, sourceUpdatedAt, ingestedAt.isAfter(now) ? now : ingestedAt,
                null, bounded(evidence), now);
    }

    /** Record a reconciled watermark at most every twelve hours, or when the evidence changed. */
    private void reconcile(StoreScope scope, String feed, Instant sourceUpdatedAt, String evidence, Instant now) {
        Optional<Watermark> latest = watermarks.latest(scope, feed);
        if (latest.isPresent() && latest.get().reconciledAt() != null
                && latest.get().reconciledAt().isAfter(now.minus(RECONCILE_EVERY))
                && evidence.equals(latest.get().evidenceReference())) {
            return;
        }
        watermarks.insert(idGenerator.newId(), scope, feed, sourceUpdatedAt.isAfter(now) ? now : sourceUpdatedAt,
                now, now, bounded(evidence), now);
    }

    /** The newest window of a dataset the store read, when the window has ended. */
    private Optional<Collected> readWindow(UUID storeId, String datasetKind, Instant now) {
        return watermarks.latestCollected(storeId, datasetKind, false)
                .filter(read -> read.windowTo() != null && !read.windowTo().isAfter(now));
    }

    private static String run(Collected collected) {
        return "ingestion-run:" + collected.runId();
    }

    private static String bounded(String evidence) {
        return evidence.length() <= 512 ? evidence : evidence.substring(0, 512);
    }

    /**
     * Each feed's newest watermark and how old it is, with the store's attestations, newest first.
     */
    @Transactional(readOnly = true)
    public Freshness status(AuthenticatedActor actor, UUID storeId) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        Instant now = clock.instant();
        Optional<StoreScope> scope = watermarks.scope(actor.organizationId(), storeId);
        List<FeedState> feeds = new ArrayList<>();
        for (String feed : FEEDS) {
            Optional<Watermark> latest = scope.flatMap(found -> watermarks.latest(found, feed));
            feeds.add(new FeedState(feed, ATTESTABLE.contains(feed),
                    latest.map(Watermark::effectiveAt).orElse(null), latest.map(Watermark::recordedAt).orElse(null),
                    latest.map(Watermark::evidenceReference).orElse(null),
                    latest.map(Watermark::effectiveAt).map(at -> Math.max(0, Duration.between(at, now).toSeconds()))
                            .orElse(null)));
        }
        return new Freshness(storeId, now, feeds, watermarks.attestations(actor.organizationId(), storeId, 20));
    }

    /**
     * Attest that the store has no data in the feeds named. A statement about returns or finance fees
     * is refused while ordered units of the last thirty days say otherwise.
     *
     * @param validDays how long the statement stands; 30 by default, at most 90
     * @return the attestations recorded, one per feed
     */
    @Transactional
    public List<UUID> attest(AuthenticatedActor actor, UUID storeId, List<String> feedCodes, String statement,
                             Integer validDays) {
        authorization.require(actor, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ResourceScope.organization(actor.organizationId()));
        LinkedHashSet<String> feeds = new LinkedHashSet<>(feedCodes == null ? List.of() : feedCodes);
        String text = statement == null ? "" : statement.strip();
        int days = validDays == null ? DEFAULT_ATTESTATION_DAYS : validDays;
        if (feeds.isEmpty() || !ATTESTABLE.containsAll(feeds) || text.isEmpty() || text.length() > 500
                || days < 1 || days > MAXIMUM_ATTESTATION_DAYS
                || watermarks.scope(actor.organizationId(), storeId).isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Instant now = clock.instant();
        boolean aboutOrders = feeds.stream().anyMatch(feed -> !"ADVERTISING".equals(feed));
        if (aboutOrders && watermarks.orderedSince(actor.organizationId(), storeId, now.minus(ORDERS_LOOKBACK))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        List<UUID> recorded = new ArrayList<>();
        for (String feed : feeds) {
            UUID id = idGenerator.newId();
            watermarks.insertAttestation(new Attestation(id, actor.organizationId(), storeId, feed, text,
                    actor.userId(), now, now.plus(Duration.ofDays(days)), !"ADVERTISING".equals(feed), null, null,
                    null, null));
            audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                    actor.userId().toString(), AuditAction.CREATE, ENTITY_TYPE, id, null,
                    Map.of("feedCode", new FieldChange(null, feed),
                            "storeId", new FieldChange(null, storeId.toString()),
                            "expiresAt", new FieldChange(null, now.plus(Duration.ofDays(days)).toString())),
                    text, null));
            recorded.add(id);
        }
        return recorded;
    }

    /** Revoke a standing attestation. */
    @Transactional
    public void revoke(AuthenticatedActor actor, UUID storeId, UUID attestationId, String reason) {
        authorization.require(actor, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ResourceScope.organization(actor.organizationId()));
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty() || text.length() > 500) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Attestation attestation = watermarks.attestation(actor.organizationId(), storeId, attestationId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!watermarks.revoke(attestation.id(), actor.userId(), clock.instant(), text)) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                actor.userId().toString(), AuditAction.STATUS_CHANGE, ENTITY_TYPE, attestation.id(), null,
                Map.of("revokedAt", new FieldChange(null, clock.instant().toString())), text, null));
    }

    /**
     * The store's feed freshness.
     *
     * @param attestations the store's attestations, newest first
     */
    public record Freshness(UUID storeId, Instant generatedAt, List<FeedState> feeds, List<Attestation> attestations) {

        public Freshness {
            Objects.requireNonNull(storeId, "storeId");
            feeds = List.copyOf(feeds);
            attestations = List.copyOf(attestations);
        }
    }

    /**
     * One feed's newest watermark.
     *
     * @param attestable whether an attestation may cover the feed
     * @param effectiveAt the instant the guardrail measures the feed's age from, or {@code null} without one
     * @param ageSeconds how old the feed is now, or {@code null} without a watermark
     */
    public record FeedState(String feedCode, boolean attestable, Instant effectiveAt, Instant recordedAt,
                            String evidence, Long ageSeconds) {
    }
}

package com.mimococo.marketops.productlisting.internal.application;

import com.mimococo.marketops.productlisting.ObservedPromotion;
import com.mimococo.marketops.productlisting.PromotionObservationSink;
import com.mimococo.marketops.productlisting.internal.infrastructure.jdbc.PlatformPromotionRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The one writer of promotion identity. */
@Service
public class PromotionObservationService implements PromotionObservationSink {

    /** The longest marketplace promotion key kept. */
    private static final int MAXIMUM_KEY_LENGTH = 64;

    private final PlatformPromotionRepository promotions;
    private final IdGenerator idGenerator;
    private final Clock clock;

    PromotionObservationService(PlatformPromotionRepository promotions, IdGenerator idGenerator, Clock clock) {
        this.promotions = promotions;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID record(UUID organizationId, UUID storeId, ObservedPromotion promotion, Instant observedAt) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(observedAt, "observedAt");
        String key = promotion.nativePromotionKey().strip();
        if (key.isEmpty() || key.length() > MAXIMUM_KEY_LENGTH) {
            throw new IllegalArgumentException("a promotion key must hold 1 to 64 characters");
        }
        return promotions.upsert(idGenerator.newId(), organizationId, storeId, key, promotion.endsAt(), observedAt,
                clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> find(UUID storeId, String nativePromotionKey) {
        if (storeId == null || nativePromotionKey == null || nativePromotionKey.isBlank()) {
            return Optional.empty();
        }
        return promotions.find(storeId, nativePromotionKey.strip());
    }
}

package com.mimococo.marketops.shared.internal.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.mimococo.marketops.shared.CorrelationId;
import java.util.List;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/** Supplies the stable application members that extend Spring Boot's ECS record. */
public final class EcsCorrelationIdJsonMembersCustomizer
        implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    private static final String NO_REQUEST = "none";

    @Override
    public void customize(JsonWriter.Members<ILoggingEvent> members) {
        members.add(CorrelationId.LOG_CONTEXT_KEY, this::correlationId);
        members.add().usingPairs(this::safeApplicationPairs);
    }

    private String correlationId(ILoggingEvent event) {
        String established = event.getMDCPropertyMap().get(CorrelationId.LOG_CONTEXT_KEY);
        return established == null || established.isBlank() ? NO_REQUEST : established;
    }

    private void safeApplicationPairs(
            ILoggingEvent event,
            java.util.function.BiConsumer<String, Object> consumer) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null) {
            return;
        }
        for (KeyValuePair pair : pairs) {
            if (!CorrelationId.LOG_CONTEXT_KEY.equals(pair.key)) {
                consumer.accept(pair.key, pair.value);
            }
        }
    }
}

package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.AdvertisingBriefView;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingBriefRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingBriefSourceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingRecalculationRepository;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes the daily brief and the weekly review, and republishes when the
 * facts underneath them change.
 *
 * <p>Everything this service writes is a link. It reads canonical Cases, Tasks,
 * Outcomes, containments, reservations and service-level observations and
 * records which ones a period contained; it raises no Task, records no Approval,
 * computes no Metric and grants no authority. The schema holds no column in
 * which it could, which is what makes "read-only projection" a property rather
 * than an intention.
 *
 * <p>Republishing is the interesting part. A published report is never edited —
 * a person read it and may have acted on it — so when late facts change what the
 * period contained, this writes a further publication that names the one it
 * supersedes, keeps its own fact cut and source cutoff, and records line by line
 * what changed and which late fact caused it. A reader can then see both what
 * was believed on the day and what is believed now, which is the only way a
 * decision taken on the earlier reading can be understood afterwards.
 *
 * <p>What a brief covers is a business decision, not a constant here: the
 * operating days, the cut time and the timezone come from an owner-published
 * reporting calendar. Without one, nothing is published and the absence is
 * recorded as a gap rather than as a quiet success.
 */
@Service
public class AdvertisingBriefService {

    /** The daily topics the Contract names, in the order a reader works through them. */
    static final List<String> DAILY_SECTIONS = List.of(
            "DATA_HEALTH", "IMMEDIATE_PROTECTION_AND_REGRESSION", "DATA_REPAIR",
            "QUALIFIED_OPTIMIZATION", "WATCH", "HUMAN_RESPONSIBILITY",
            "APPROVALS_AND_EXCEPTIONS", "EXECUTION_AND_AGGREGATE_EXPOSURE",
            "UNKNOWN_MISMATCH_AND_MANUAL_VERIFICATION", "RECENT_OUTCOMES");

    /** The weekly topics, likewise. */
    static final List<String> WEEKLY_SECTIONS = List.of(
            "SHADOW_DECISION_REASONS", "GOVERNED_ACTIONS", "CONFIGURATION_VERIFICATION",
            "EARLY_GUARDS", "OPERATIONAL_AND_SETTLED_TRANSITIONS",
            "REGRESSION_QUARANTINE_AND_COMPENSATION", "EXCEPTIONS", "SYSTEM_AND_HUMAN_SLO",
            "AGGREGATE_EXPOSURE", "POLICY_BUNDLE_MATURITY", "GATE_EVIDENCE",
            "DEFERRED_RELEASE_OBLIGATIONS");

    static final String DAILY = "DAILY_ACTION_BRIEF";
    static final String WEEKLY = "WEEKLY_EVIDENCE_REVIEW";

    /**
     * Topics with no canonical source in this database.
     *
     * <p>Gate evidence and deferred release obligations live in the evidence
     * package and in an Owner's decisions, not in a table this product owns. The
     * sections are still emitted, stating that and naming the reason — a report
     * that silently dropped them would let a reader believe they had been
     * checked.
     */
    private static final Map<String,String> SECTIONS_WITHOUT_A_CANONICAL_SOURCE = Map.of(
            "GATE_EVIDENCE", "NO_CANONICAL_GATE_EVIDENCE_SOURCE",
            "DEFERRED_RELEASE_OBLIGATIONS", "NO_CANONICAL_RELEASE_REGISTER_SOURCE");

    private final AdvertisingBriefRepository briefs;
    private final AdvertisingBriefSourceRepository sources;
    private final AdvertisingRecalculationRepository queue;
    private final IdGenerator ids;

    AdvertisingBriefService(AdvertisingBriefRepository briefs,
                            AdvertisingBriefSourceRepository sources,
                            AdvertisingRecalculationRepository queue,
                            IdGenerator ids) {
        this.briefs = briefs;
        this.sources = sources;
        this.queue = queue;
        this.ids = ids;
    }

    /** What one publication attempt did, so a caller can report it without asking again. */
    public record Published(UUID publicationId, String briefKind, String periodKey,
                            int revisionNo, String revisionKind, boolean unchanged) {
    }

    /**
     * Publish one period's brief, or republish it when the facts have moved.
     *
     * <p>Idempotent on content rather than on existence. Running twice over an
     * unchanged period writes nothing the second time, because a revision that
     * restated nothing would be noise in a lineage whose whole purpose is to make
     * a real change visible.
     */
    @Transactional
    public Optional<Published> publish(UUID organizationId, String briefKind, Instant asOf,
                                       String lateFactReference) {
        Optional<AdvertisingBriefRepository.CalendarRow> calendar =
                briefs.activeCalendar(organizationId, asOf);
        if (calendar.isEmpty()) {
            // No calendar means nobody has said which days are operating days.
            // Publishing on a day this organization does not recognise would be
            // inventing the schedule the calendar exists to state.
            return Optional.empty();
        }
        AdvertisingBriefRepository.CalendarRow settings = calendar.get();
        ZoneId zone = ZoneId.of(settings.reportingTimezone());
        LocalDate day = asOf.atZone(zone).toLocalDate();
        if (DAILY.equals(briefKind)
                && !settings.operatingDays().contains(day.getDayOfWeek().getValue())) {
            return Optional.empty();
        }

        Period period = periodOf(briefKind, day, zone, settings);
        List<String> sectionCodes = DAILY.equals(briefKind) ? DAILY_SECTIONS : WEEKLY_SECTIONS;
        Map<String,List<AdvertisingBriefSourceRepository.Link>> content = new LinkedHashMap<>();
        for (String section : sectionCodes) {
            content.put(section, SECTIONS_WITHOUT_A_CANONICAL_SOURCE.containsKey(section)
                    ? List.of()
                    : sources.linksFor(organizationId, section, period.startsAt(),
                            period.endsAt()));
        }

        String digest = contentDigest(sectionCodes, content);
        int previousRevision = briefs.highestRevision(organizationId, briefKind, period.key());
        Optional<AdvertisingBriefView> previous = previousRevision == 0
                ? Optional.empty()
                : briefs.latest(organizationId, briefKind, period.key());
        if (previous.isPresent() && previous.get().contentDigest().equals(digest)) {
            return Optional.of(new Published(previous.get().id(), briefKind, period.key(),
                    previous.get().revisionNo(), previous.get().revisionKind(), true));
        }

        boolean revision = previous.isPresent();
        // A restatement has to say what caused it. Republishing without naming
        // the late fact would leave a reader unable to judge whether the change
        // is new evidence or a defect.
        String cause = revision
                ? (lateFactReference == null ? "late facts restated the period" : lateFactReference)
                : null;

        AdvertisingRecalculationRepository.CursorPosition cursor = queue.cursorPosition();
        UUID publicationId = ids.newId();
        List<String> gaps = new ArrayList<>(SECTIONS_WITHOUT_A_CANONICAL_SOURCE.values());

        briefs.insertPublication(new AdvertisingBriefRepository.PublicationRow(
                publicationId, organizationId, briefKind, period.key(), period.startsAt(),
                period.endsAt(), asOf, settings.id(), settings.policyVersion(),
                "ADVERTISING_ACCEPTED_FACT", cursor.positionAt(),
                // A feed that has scanned nothing has no item key, and the
                // schema will not take an empty one. Naming the state is better
                // than a blank a reader would have to interpret.
                cursor.itemKey() == null || cursor.itemKey().isBlank()
                        ? "NO_ITEM_SCANNED" : cursor.itemKey(),
                null, null, sources.policyVersionDigest(organizationId),
                sources.bundleVersionSnapshot(organizationId), gaps,
                previousRevision + 1, previous.map(AdvertisingBriefView::id).orElse(null),
                revision ? "REVISION" : "ORIGINAL", revision ? "the facts underneath the period "
                        + "were restated after publication" : null,
                cause, digest, asOf, "ad-brief:" + briefKind + ':' + period.key()));

        Map<String,Map<String,UUID>> writtenItems = new LinkedHashMap<>();
        int ordinal = 0;
        for (String section : sectionCodes) {
            ordinal++;
            List<AdvertisingBriefSourceRepository.Link> links = content.get(section);
            String blocker = SECTIONS_WITHOUT_A_CANONICAL_SOURCE.get(section);
            briefs.insertSection(new AdvertisingBriefRepository.SectionRow(
                    ids.newId(), publicationId, organizationId, section, ordinal, links.size(),
                    blocker == null ? "COMPLETE" : "NOT_AVAILABLE",
                    blocker == null ? List.of() : List.of(blocker),
                    blocker == null ? null
                            : "this product holds no canonical source for this topic"));
            Map<String,UUID> byKey = new LinkedHashMap<>();
            int itemOrdinal = 0;
            for (AdvertisingBriefSourceRepository.Link link : links) {
                itemOrdinal++;
                UUID itemId = ids.newId();
                byKey.put(link.subjectKind() + ':' + link.referenceId(), itemId);
                briefs.insertItem(itemRow(itemId, publicationId, organizationId, section,
                        itemOrdinal, link));
            }
            writtenItems.put(section, byKey);
        }

        if (revision) {
            recordDeltas(publicationId, organizationId, previous.get(), sectionCodes,
                    writtenItems, content, cause);
        }
        return Optional.of(new Published(publicationId, briefKind, period.key(),
                previousRevision + 1, revision ? "REVISION" : "ORIGINAL", false));
    }

    /** The newest reading of one period. */
    public Optional<AdvertisingBriefView> latest(UUID organizationId, String briefKind,
                                                 String periodKey) {
        return briefs.latest(organizationId, briefKind, periodKey);
    }

    /**
     * The newest published reading of any period of one kind.
     *
     * <p>The console asks for this rather than computing today's period key
     * itself. Which day a period covers depends on the owner's reporting
     * timezone and cut minute, and a browser deciding that from its own clock
     * would be inventing the calendar.
     */
    public Optional<AdvertisingBriefView> mostRecent(UUID organizationId, String briefKind) {
        return briefs.mostRecentPeriodKey(organizationId, briefKind)
                .flatMap(periodKey -> briefs.latest(organizationId, briefKind, periodKey));
    }

    /** Every reading of one period, oldest first, so a restatement is visible as one. */
    public List<AdvertisingBriefView> history(UUID organizationId, String briefKind,
                                              String periodKey) {
        return briefs.history(organizationId, briefKind, periodKey);
    }

    /**
     * What changed between the previous reading and this one.
     *
     * <p>Written rather than left to be diffed. A reader comparing two published
     * bodies line by line would be reconstructing an answer the producer already
     * had, and would have to guess which late fact caused which change.
     */
    private void recordDeltas(UUID publicationId, UUID organizationId,
                              AdvertisingBriefView previous, List<String> sectionCodes,
                              Map<String,Map<String,UUID>> writtenItems,
                              Map<String,List<AdvertisingBriefSourceRepository.Link>> content,
                              String lateFactReference) {
        Map<String,Map<String,AdvertisingBriefView.Item>> before = new LinkedHashMap<>();
        for (AdvertisingBriefView.Section section : previous.sections()) {
            Map<String,AdvertisingBriefView.Item> byKey = new LinkedHashMap<>();
            for (AdvertisingBriefView.Item item : section.items()) {
                byKey.put(item.subjectKind() + ':' + item.referenceId(), item);
            }
            before.put(section.sectionCode(), byKey);
        }
        for (String section : sectionCodes) {
            Map<String,AdvertisingBriefView.Item> previousItems =
                    before.getOrDefault(section, Map.of());
            Map<String,UUID> currentItems = writtenItems.getOrDefault(section, Map.of());
            for (AdvertisingBriefSourceRepository.Link link : content.get(section)) {
                String key = link.subjectKind() + ':' + link.referenceId();
                AdvertisingBriefView.Item was = previousItems.get(key);
                if (was == null) {
                    briefs.insertDelta(delta(publicationId, organizationId, previous, section,
                            "ADDED", null, currentItems.get(key), null, null,
                            link.valueState(), link.numericValue(), lateFactReference,
                            "this line was not in the previous reading"));
                } else if (!java.util.Objects.equals(was.valueState(), link.valueState())
                        || compare(was.numericValue(), link.numericValue()) != 0) {
                    briefs.insertDelta(delta(publicationId, organizationId, previous, section,
                            "RESTATED", null, currentItems.get(key), was.valueState(),
                            was.numericValue(), link.valueState(), link.numericValue(),
                            lateFactReference, "the figure behind this line was restated"));
                }
            }
            for (Map.Entry<String,AdvertisingBriefView.Item> gone : previousItems.entrySet()) {
                if (!currentItems.containsKey(gone.getKey())) {
                    briefs.insertDelta(delta(publicationId, organizationId, previous, section,
                            "REMOVED", null, null, gone.getValue().valueState(),
                            gone.getValue().numericValue(), null, null, lateFactReference,
                            "this line no longer applies to the period"));
                }
            }
        }
    }

    private AdvertisingBriefRepository.DeltaRow delta(
            UUID publicationId, UUID organizationId, AdvertisingBriefView previous,
            String section, String changeKind, UUID previousItemId, UUID currentItemId,
            String previousValueState, java.math.BigDecimal previousValue,
            String currentValueState, java.math.BigDecimal currentValue,
            String lateFactReference, String reason) {
        return new AdvertisingBriefRepository.DeltaRow(ids.newId(), publicationId,
                organizationId, "REVISION", previous.id(), section, changeKind, previousItemId,
                currentItemId, previousValueState, previousValue, currentValueState,
                currentValue, lateFactReference, reason);
    }

    private static int compare(java.math.BigDecimal left, java.math.BigDecimal right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null || right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static AdvertisingBriefRepository.ItemRow itemRow(
            UUID id, UUID publicationId, UUID organizationId, String section, int ordinal,
            AdvertisingBriefSourceRepository.Link link) {
        return new AdvertisingBriefRepository.ItemRow(id, publicationId, organizationId, section,
                ordinal, link.subjectKind(),
                "AD_CASE".equals(link.subjectKind()) ? link.referenceId() : null,
                "WORK_TASK".equals(link.subjectKind()) ? link.referenceId() : null,
                "RECOMMENDATION".equals(link.subjectKind()) ? link.referenceId() : null,
                "OUTCOME_OBSERVATION".equals(link.subjectKind()) ? link.referenceId() : null,
                "SLO_OBSERVATION".equals(link.subjectKind()) ? link.referenceId() : null,
                "CONTAINMENT".equals(link.subjectKind()) ? link.referenceId() : null,
                "RESERVATION".equals(link.subjectKind()) ? link.referenceId() : null,
                "BID_COMMAND".equals(link.subjectKind()) ? link.referenceId() : null,
                "MANUAL_PACKET".equals(link.subjectKind()) ? link.referenceId() : null,
                "DECISION_BUNDLE".equals(link.subjectKind()) ? link.referenceId() : null,
                "METRIC_VALUE".equals(link.subjectKind()) ? link.referenceId() : null,
                link.storeId(), link.lane(), link.protectionTier(), link.causeCode(),
                link.valueState(), link.numericValue(), link.currencyCode(),
                link.evidenceState(), link.confidenceState(), link.blockerCodes(),
                link.observedAt());
    }

    /**
     * The digest of exactly what this reading says.
     *
     * <p>Over the ordered sections and their ordered links, so a republication
     * that found the same things produces the same digest and writes nothing. It
     * covers the value states as well as the identities, because a line whose
     * figure was restated is a change even though it points at the same row.
     */
    private static String contentDigest(
            List<String> sectionCodes,
            Map<String,List<AdvertisingBriefSourceRepository.Link>> content) {
        List<String> components = new ArrayList<>();
        for (String section : sectionCodes) {
            components.add(section);
            for (AdvertisingBriefSourceRepository.Link link : content.get(section)) {
                components.add(link.subjectKind() + '|' + link.referenceId() + '|'
                        + link.valueState() + '|'
                        + (link.numericValue() == null ? "-" : link.numericValue().toPlainString())
                        + '|' + (link.blockerCodes() == null ? "" : link.blockerCodes()));
            }
        }
        return Digest.ofComponents(components);
    }

    /** One period, named the way a person would name it. */
    private record Period(String key, Instant startsAt, Instant endsAt) {
    }

    private static Period periodOf(String briefKind, LocalDate day, ZoneId zone,
                                   AdvertisingBriefRepository.CalendarRow settings) {
        if (DAILY.equals(briefKind)) {
            Instant starts = day.atStartOfDay(zone).plusMinutes(settings.dailyCutMinute())
                    .minusDays(1).toInstant();
            Instant ends = day.atStartOfDay(zone).plusMinutes(settings.dailyCutMinute())
                    .toInstant();
            return new Period(day.toString(), starts, ends);
        }
        WeekFields weeks = WeekFields.ISO;
        LocalDate cut = day.with(weeks.dayOfWeek(),
                DayOfWeek.of(settings.weeklyCutWeekday()).getValue());
        if (cut.isAfter(day)) {
            cut = cut.minusWeeks(1);
        }
        Instant ends = cut.atStartOfDay(zone).plusMinutes(settings.weeklyCutMinute()).toInstant();
        Instant starts = cut.minusWeeks(1).atStartOfDay(zone)
                .plusMinutes(settings.weeklyCutMinute()).toInstant();
        String key = String.format(Locale.ROOT, "%d-W%02d", cut.get(weeks.weekBasedYear()),
                cut.get(weeks.weekOfWeekBasedYear()));
        return new Period(key, starts, ends);
    }
}

package com.mimococo.marketops.analyticsdecision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published read access to deterministic diagnosis findings.
 *
 * <p>Findings are the deterministic layer beneath every explanation. A model may
 * describe them and a person may act on them, but neither produces them, and a
 * recommendation that cites a finding cites a row that exists.
 */
public interface DiagnosisQuery {

    /** The current findings for one subject and window, in rule order. */
    List<DiagnosisFindingView> currentFindings(SubjectKind subjectKind,
                                               UUID subjectId,
                                               MetricWindow window);

    /** The latest finding for each rule no later than one captured decision instant. */
    List<DiagnosisFindingView> currentFindingsAt(SubjectKind subjectKind,
                                                 UUID subjectId,
                                                 MetricWindow window,
                                                 Instant at);

    /**
     * The subjects a store should look at first.
     *
     * <p>Ordering is by the severity of what the rules found and then by the
     * money at stake, so the queue is a work list rather than an alphabet.
     */
    List<PrioritySubjectView> priorityQueue(UUID storeId, MetricWindow window, int limit);
}

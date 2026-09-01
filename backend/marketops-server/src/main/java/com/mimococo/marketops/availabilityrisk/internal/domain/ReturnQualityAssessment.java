package com.mimococo.marketops.availabilityrisk.internal.domain;

/** Result of the accepted return/retention/QC guardrail. */
public record ReturnQualityAssessment(State state, String blockerCode) {
    public enum State { CLEAR, REVIEW, DATA_BLOCKED, POLICY_BLOCKED }

    public static ReturnQualityAssessment clear() {
        return new ReturnQualityAssessment(State.CLEAR, null);
    }

    public static ReturnQualityAssessment review(String code) {
        return new ReturnQualityAssessment(State.REVIEW, code);
    }

    public static ReturnQualityAssessment blocked(String code, boolean policy) {
        return new ReturnQualityAssessment(policy ? State.POLICY_BLOCKED : State.DATA_BLOCKED,
                code);
    }
}

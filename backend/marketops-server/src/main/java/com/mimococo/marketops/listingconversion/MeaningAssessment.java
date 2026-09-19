package com.mimococo.marketops.listingconversion;

import java.util.List;

/** Independent answers to every accepted meaning condition for the exact frozen action. */
public record MeaningAssessment(String model,String basisDigest,boolean complete,String evidenceReference,List<Answer> answers) {
    public record Answer(String code,String state,String reason) { }
    public MeaningAssessment { answers=answers==null?List.of():List.copyOf(answers); }
}

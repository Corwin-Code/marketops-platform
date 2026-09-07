import com.mimococo.marketops.advertisingefficiency.*;
import com.mimococo.marketops.advertisingefficiency.internal.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Controller-only diagnostic: compiles unmodified CI-authenticated production sources.
 * No mocks, database, framework replacement, Provider or credentials are used.
 * This probes the cause-specific qualification function, not full command execution.
 */
public final class CauseBoundProbe {
  static final UUID ORG=UUID.fromString("00000000-0000-0000-0000-000000000001");
  static final UUID OBJ=UUID.fromString("00000000-0000-0000-0000-000000000002");
  static final Instant NOW=Instant.parse("2026-09-05T12:00:00Z");
  static AdMeasure fact(String v) {
    return AdMeasure.available(new BigDecimal(v),AdEvidenceState.CANONICAL_CONFIRMED);
  }
  public static void main(String[] args) {
    var members=AffectedSet.complete(List.of(ORG),List.of(OBJ));
    var evidence=new ArrayList<AdCaseCalculation.PurposeEvidence>();
    for(String kind:List.of("OFFICIAL_AD_SPEND","OFFICIAL_AD_TRAFFIC","AD_LINKED_SALE_EVENT",
        "COST_AND_FEE","AD_OBJECT_CONFIGURATION","AFFECTED_SET","SELLABILITY","AVAILABILITY")) {
      // The missing conversion is the only intended gap. Other purpose evidence
      // is complete; extra evidence rows cannot explain refusal for loss.
      if(!kind.equals("OFFICIAL_AD_TRAFFIC")) evidence.add(new AdCaseCalculation.PurposeEvidence(
        "PROTECTION_BID_WRITE",kind,ORG,NOW.minusSeconds(20),NOW.minusSeconds(10),
        NOW.plusSeconds(300),true,List.of()));
    }
    for(var cause:List.of(AdvertisingCause.PROMOTED_VARIANT_UNAVAILABLE,
                         AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE,
                         AdvertisingCause.PROVEN_ADVERTISING_LOSS)) {
      var tier=cause==AdvertisingCause.PROVEN_ADVERTISING_LOSS?ProtectionTier.P2:ProtectionTier.P1;
      var lane=new AdLaneResolver.Decision(AdvertisingLane.PROTECTION,tier,cause,
        AdEvidenceState.CANONICAL_CONFIRMED,AdConfidence.HIGH,List.of());
      var scored=new AdCaseCalculation.ScoredCase(new AdCaseIdentity(ORG,OBJ,1,cause),lane,
        AdPriorityPolicy.unranked(AdvertisingLane.PROTECTION,tier),fact("-12000"),fact("-0.6"),
        fact("20000"),AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE),null,
        MaxCpc.absent(MaxCpc.Absence.CONVERSION_NOT_WRITE_GRADE,AdEvidenceState.INCOMPLETE),
        null,fact("28"),null,"RUB",List.of());
      var calculation=new AdCaseCalculation(ORG,OBJ,ORG,"FIXTURE_ADS",ORG,1,NOW,
        new AdPolicySet(ORG,1,ORG,1,ORG,1,ORG,1,ORG,1,ORG,1,ORG,1,ORG,1,ORG,1,ORG,1),members,OBJ,List.of(scored),List.of(),evidence,false);
      System.out.println(cause+": lane="+scored.decision().lane()+", maxCpcWriteGrade="
        +scored.maxCpc().writeGrade()+", ordinaryDirection="+BidDirectionForCause.of(cause)+", causeBoundProtectionQualified="
        +calculation.causeBoundProtectionQualified(scored));
    }
  }
}

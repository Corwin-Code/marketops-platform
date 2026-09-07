import com.mimococo.marketops.advertisingefficiency.*;
import com.mimococo.marketops.advertisingefficiency.internal.domain.*;
import java.util.*; import java.math.*; import java.time.*;
/** Diagnostic replay of the exact production spend-grading expression only.
 * This is NOT a full application/SQL rerun. The record declaration and expression
 * below are copied unmodified from hash-bound CI source; no new checks are inserted.
 */
public final class OutcomeSpendAgePredicateProbe {
    public record ObjectFactAggregate(
            BigDecimal spendAmount, String currencyCode, Long impressions, Long views,
            Long clicks, Long providerAttributedOrders, BigDecimal providerAttributedRevenue,
            boolean everyWindowComplete, boolean anyCorrectionWindowOpen,
            Instant earliestSourceTime, Instant latestSourceTime, int factCount,
            UUID latestFactId, BigDecimal coverageRatio, Instant acceptedAt,
            Instant coveredFrom, Instant coveredTo) {
        public ObjectFactAggregate(BigDecimal spendAmount, String currencyCode, Long impressions,
                Long views, Long clicks, Long providerAttributedOrders, BigDecimal providerAttributedRevenue,
                boolean everyWindowComplete, boolean anyCorrectionWindowOpen, Instant earliestSourceTime,
                Instant latestSourceTime, int factCount, UUID latestFactId) {
            this(spendAmount, currencyCode, impressions, views, clicks, providerAttributedOrders,
                    providerAttributedRevenue, everyWindowComplete, anyCorrectionWindowOpen,
                    earliestSourceTime, latestSourceTime, factCount, latestFactId, null, null, null, null);
        }
    }

  public static void main(String[] args) {
    var readAt=Instant.parse("2026-09-05T12:00:00Z");
    long configuredMaxAgeSeconds=900;
    for(long ageSeconds:new long[]{1,3540}) {
      var source=readAt.minusSeconds(ageSeconds);
      var row=new ObjectFactAggregate(new BigDecimal("1000"),"RUB",1000L,500L,100L,
          10L,new BigDecimal("10000"),true,false,source,source,1,
          UUID.fromString("00000000-0000-0000-0000-000000000001"),BigDecimal.ONE,
          source,readAt.minusSeconds(86400),readAt.minusSeconds(3600));
      var facts=Optional.of(row);
        AdMeasure spend = facts.filter(value -> value.spendAmount() != null && value.everyWindowComplete() && !value.anyCorrectionWindowOpen())
                .map(value -> AdMeasure.available(value.spendAmount(), AdEvidenceState.CANONICAL_CONFIRMED))
                .orElseGet(() -> AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE));
      boolean withinPublishedAge=ageSeconds<=configuredMaxAgeSeconds;
      System.out.println("source/accepted age="+ageSeconds+"s, published max="+configuredMaxAgeSeconds
          +"s, withinPublishedAge="+withinPublishedAge+", productionSpendGrade="+spend.evidenceState()
          +", sufficientForWrite="+spend.sufficientForWrite());
    }
  }
}

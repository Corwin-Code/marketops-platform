package com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc;

import com.mimococo.marketops.shared.Digest;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ListingFeedbackRepository {
    private final JdbcClient jdbc;
    ListingFeedbackRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public record Item(UUID id,UUID listingId,String sourceIdentity,UUID rawObservationId,String originalPointer,
                       String originalDigest,Instant observedAt,Instant acquiredAt) { }
    public record Label(UUID id,UUID itemId,int revision,String themeCode,String qualificationState,
                        String classifierVersion,String reason,UUID classifiedBy,Instant classifiedAt) { }
    public record Theme(String themeCode,String qualificationState,long mentionCount) { }
    public record CurrentClassification(UUID id,UUID itemId,int revision,String themeCode,String qualificationState) { }
    public record CurrentClassificationSet(List<CurrentClassification> classifications) {
        public CurrentClassificationSet { classifications=List.copyOf(classifications); }
        public boolean present() { return !classifications.isEmpty(); }
        public String evidenceReference() {
            if (!present()) return "mart.lc_feedback_classification:none";
            return "mart.lc_feedback_classification:current:"+Digest.ofComponents(classifications.stream()
                    .map(value->value.itemId()+":"+value.id()+":"+value.revision()+":"+value.themeCode()+":"
                            +value.qualificationState()).toList());
        }
    }

    public void lockListing(UUID listing) {
        jdbc.sql("SELECT id FROM core.platform_listing WHERE id=:id FOR UPDATE").param("id",listing).query(UUID.class).single();
    }
    public Optional<Item> item(UUID id) { return jdbc.sql("SELECT * FROM core.lc_feedback_item WHERE id=:id")
            .param("id",id).query(this::mapItem).optional(); }
    public List<Item> items(UUID listing,Instant from,Instant to,Instant at,int limit) {
        return jdbc.sql("""
                SELECT * FROM core.lc_feedback_item WHERE platform_listing_id=:listing
                    AND observed_at>=:from AND observed_at<:to AND linked_at<=:at
                ORDER BY observed_at DESC,id LIMIT :limit
                """).param("listing",listing).param("from",Timestamp.from(from)).param("to",Timestamp.from(to))
                .param("at",Timestamp.from(at)).param("limit",limit).query(this::mapItem).list();
    }
    public Optional<Item> itemForSource(UUID listing,String identity) {
        return jdbc.sql("SELECT * FROM core.lc_feedback_item WHERE platform_listing_id=:listing AND source_identity=:identity")
                .param("listing",listing).param("identity",identity).query(this::mapItem).optional();
    }
    private Item mapItem(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new Item(rs.getObject("id",UUID.class),rs.getObject("platform_listing_id",UUID.class),rs.getString("source_identity"),
                rs.getObject("raw_observation_id",UUID.class),rs.getString("original_pointer"),rs.getString("original_digest"),
                ListingFactRepository.instant(rs,"observed_at"),ListingFactRepository.instant(rs,"acquired_at"));
    }
    public void insertItem(Item item,UUID organization,UUID actor,Instant now) {
        jdbc.sql("""
                INSERT INTO core.lc_feedback_item(id,organization_id,platform_listing_id,source_identity,raw_observation_id,
                    original_pointer,original_digest,observed_at,acquired_at,linked_by,linked_at)
                VALUES(:id,:org,:listing,:identity,:raw,:pointer,:digest,:observed,:acquired,:actor,:now)
                """).param("id",item.id()).param("org",organization).param("listing",item.listingId()).param("identity",item.sourceIdentity())
                .param("raw",item.rawObservationId()).param("pointer",item.originalPointer()).param("digest",item.originalDigest())
                .param("observed",Timestamp.from(item.observedAt())).param("acquired",Timestamp.from(item.acquiredAt()))
                .param("actor",actor).param("now",Timestamp.from(now)).update();
    }
    public List<Label> labels(UUID itemId) {
        return jdbc.sql("SELECT * FROM mart.lc_feedback_classification WHERE feedback_item_id=:id ORDER BY revision_no")
                .param("id",itemId).query((rs,n)->new Label(rs.getObject("id",UUID.class),itemId,rs.getInt("revision_no"),
                        rs.getString("theme_code"),rs.getString("qualification_state"),rs.getString("classifier_version"),
                        rs.getString("reason"),rs.getObject("classified_by",UUID.class),ListingFactRepository.instant(rs,"classified_at"))).list();
    }
    public void insertLabel(Label label,UUID organization) {
        jdbc.sql("""
                INSERT INTO mart.lc_feedback_classification(id,organization_id,feedback_item_id,revision_no,theme_code,
                    qualification_state,classifier_version,reason,classified_by,classified_at)
                VALUES(:id,:org,:item,:revision,:theme,:state,:version,:reason,:actor,:at)
                """).param("id",label.id()).param("org",organization).param("item",label.itemId()).param("revision",label.revision())
                .param("theme",label.themeCode()).param("state",label.qualificationState()).param("version",label.classifierVersion())
                .param("reason",label.reason()).param("actor",label.classifiedBy()).param("at",Timestamp.from(label.classifiedAt())).update();
    }
    public List<Theme> themes(UUID listing,Instant from,Instant to,Instant at) {
        return jdbc.sql("""
                SELECT c.theme_code,c.qualification_state,count(*) AS mentions FROM core.lc_feedback_item i
                JOIN LATERAL(SELECT theme_code,qualification_state FROM mart.lc_feedback_classification c
                    WHERE c.feedback_item_id=i.id AND c.classified_at<=:at ORDER BY revision_no DESC LIMIT 1) c ON true
                WHERE i.platform_listing_id=:listing AND i.observed_at>=:from AND i.observed_at<:to AND i.linked_at<=:at
                GROUP BY c.theme_code,c.qualification_state ORDER BY c.theme_code,c.qualification_state
                """).param("listing",listing).param("from",Timestamp.from(from)).param("to",Timestamp.from(to)).param("at",Timestamp.from(at))
                .query((rs,n)->new Theme(rs.getString("theme_code"),rs.getString("qualification_state"),rs.getLong("mentions"))).list();
    }

    /** Current human revision for each deduplicated source item at the health calculation instant. */
    public CurrentClassificationSet currentClassifications(UUID listing,Instant at) {
        return new CurrentClassificationSet(jdbc.sql("""
                SELECT c.id,c.feedback_item_id,c.revision_no,c.theme_code,c.qualification_state
                  FROM core.lc_feedback_item i
                  JOIN LATERAL(SELECT * FROM mart.lc_feedback_classification c
                    WHERE c.feedback_item_id=i.id AND c.classified_at<=:at
                    ORDER BY c.revision_no DESC LIMIT 1) c ON true
                 WHERE i.platform_listing_id=:listing AND i.linked_at<=:at
                 ORDER BY i.id
                """).param("listing",listing).param("at",Timestamp.from(at))
                .query((rs,n)->new CurrentClassification(rs.getObject("id",UUID.class),
                        rs.getObject("feedback_item_id",UUID.class),rs.getInt("revision_no"),
                        rs.getString("theme_code"),rs.getString("qualification_state"))).list());
    }
}

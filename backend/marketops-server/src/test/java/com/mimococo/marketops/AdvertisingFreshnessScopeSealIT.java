package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exact app seal/create path over immutable synthetic frozen baselines; no Provider execution. */
class AdvertisingFreshnessScopeSealIT {
    AdvertisingSealedAuthorityIT fixture;
    @BeforeAll static void database() { AdvertisingSealedAuthorityIT.database(); }
    @BeforeEach void fixture() throws Exception {
        fixture=new AdvertisingSealedAuthorityIT();fixture.fixture();
    }
    @ParameterizedTest @ValueSource(strings={"ANOTHER_STORE","ANOTHER_PLATFORM"})
    void unrelatedSemanticCompanyProfilePermitsSealWhileAggregateAdmissionRemainsIndependent(String scope) throws Exception {
        assertThat(canonical()).isTrue();String original=frozenBytes();
        UUID store=scope.equals("ANOTHER_STORE")?anotherStore():null;
        companyProfile("SEMANTIC_PROFILE",scope.equals("ANOTHER_PLATFORM")?"WILDBERRIES":fixture.graph.platform(),store,"ACTIVE");
        try(var app=fixture.transaction()) {
            assertThat(AdvertisingR1Fixture.seal(app,fixture.graph,fixture.proof(app,fixture.graph.id("ownerUser")))).isNotNull();
            app.commit();
        }
        assertThat(AdvertisingSealedAuthorityIT.seed.sql("SELECT count(*) FROM ops.ad_action_authorization WHERE organization_id=:org")
                .param("org",fixture.graph.id("organization")).query(Integer.class).single()).isEqualTo(1);
        try(var app=fixture.transaction()) {
            if(scope.equals("ANOTHER_STORE")) {
                // The newly active store has no retained-sales coverage. A legitimate
                // baseline and seal must not bypass this independent aggregate gate.
                assertThatThrownBy(()->AdvertisingR1Fixture.createCommand(app,fixture.graph))
                        .isInstanceOfSatisfying(org.postgresql.util.PSQLException.class,error->{
                            assertThat(error.getSQLState()).isEqualTo("MO097");
                            assertThat(error.getServerErrorMessage().getMessage()).isEqualTo(
                                    "exposure admission refused: AGGREGATE_ENVELOPE_BLOCKED,RETAINED_SALES_SHARE_UNRESOLVED");
                        });
                app.rollback();
            } else {
                assertThat(AdvertisingR1Fixture.createCommand(app,fixture.graph)).isNotNull();app.commit();
            }
        }
        assertThat(AdvertisingSealedAuthorityIT.seed.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org")
                .param("org",fixture.graph.id("organization")).query(Integer.class).single())
                .isEqualTo(scope.equals("ANOTHER_STORE")?0:1);
        assertThat(canonical()).isTrue();
        assertThat(frozenBytes()).isEqualTo(original);
        assertThat(AdvertisingSealedAuthorityIT.seed.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",fixture.graph.id("gate")).query(Boolean.class).single()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"CURRENT_STORE_NARROW","SAME_RANK_CONFLICT"})
    void applicableNarrowOrConflictedCompanyProfileStillRefusesSealWithoutRewritingFrozenHistory(String scope) throws Exception {
        assertThat(canonical()).isTrue();String original=frozenBytes();
        if(scope.equals("CURRENT_STORE_NARROW")) companyProfile("SEMANTIC_PROFILE",fixture.graph.platform(),fixture.graph.id("store"),"ACTIVE");
        else companyProfile("ORGANIZATION",null,null,"RETIRED");
        assertThat(canonical()).isFalse();
        try(var app=fixture.transaction()) {
            assertThatThrownBy(()->AdvertisingR1Fixture.seal(app,fixture.graph,fixture.proof(app,fixture.graph.id("ownerUser"))))
                    .isInstanceOfSatisfying(SQLException.class,error->{
                        assertThat(error.getSQLState()).isEqualTo("MO099");
                        assertThat(error.getMessage()).contains("exact canonical approved frozen Outcome baseline required");
                    });
            app.rollback();
        }
        assertThat(frozenBytes()).isEqualTo(original);
        assertThat(AdvertisingSealedAuthorityIT.seed.sql("SELECT count(*) FROM ops.ad_action_authorization WHERE organization_id=:org")
                .param("org",fixture.graph.id("organization")).query(Integer.class).single()).isZero();
    }
    private UUID anotherStore() {
        UUID id=UUID.randomUUID();
        AdvertisingSealedAuthorityIT.seed.sql("""
                INSERT INTO core.store(id,organization_id,marketplace_account_id,code,display_name,status,created_at,updated_at)
                VALUES(:id,:org,:account,:code,'Synthetic other freshness store','ACTIVE',clock_timestamp(),clock_timestamp())
                """).param("id",id).param("org",fixture.graph.id("organization")).param("account",fixture.graph.id("account"))
                .param("code","freshness-"+id).update();
        return id;
    }
    private void companyProfile(String scope,String platform,UUID store,String status) {
        // A separate legitimate scoped input. The pre-action attested payload is never edited.
        AdvertisingSealedAuthorityIT.seed.sql("""
                INSERT INTO core.ad_freshness_profile SELECT (jsonb_populate_record(NULL::core.ad_freshness_profile,
                  to_jsonb(original)||jsonb_build_object('id',gen_random_uuid(),'profile_version',2,
                    'scope_kind',CAST(:scope AS text),'platform_code',CAST(:platform AS text),
                    'store_ref_id',CAST(:store AS uuid),'semantic_profile_id',CAST(:semantic AS uuid),
                    'status',CAST(:status AS text),'reason','Synthetic exact freshness scope regression'))).*
                FROM core.ad_freshness_profile original WHERE organization_id=:org
                  AND evidence_kind='COMPANY_COMPLETED_SALE' AND decision_purpose='EARLY_COMPLETED_SALES_OUTCOME'
                  AND profile_version=1
                """).param("scope",scope).param("platform",platform).param("store",store)
                .param("semantic",scope.equals("SEMANTIC_PROFILE")?fixture.graph.id("profile"):null)
                .param("status",status).param("org",fixture.graph.id("organization")).update();
    }
    private boolean canonical() {
        return org.springframework.jdbc.core.simple.JdbcClient.create(AdvertisingSealedAuthorityIT.application)
                .sql("SELECT ops.ad_outcome_baseline_is_canonical(:id,clock_timestamp())")
                .param("id",fixture.graph.id("baseline")).query(Boolean.class).single();
    }
    private String frozenBytes() {
        return AdvertisingSealedAuthorityIT.seed.sql("""
                SELECT jsonb_build_object('baseline',to_jsonb(b),
                  'stages',(SELECT jsonb_agg(to_jsonb(s) ORDER BY stage) FROM ops.ad_outcome_stage_baseline s WHERE s.outcome_baseline_id=b.id),
                  'attestation',(SELECT to_jsonb(a) FROM ops.ad_outcome_baseline_attestation a WHERE a.outcome_baseline_id=b.id))::text
                FROM ops.ad_outcome_baseline b WHERE b.id=:id
                """).param("id",fixture.graph.id("baseline")).query(String.class).single();
    }
}

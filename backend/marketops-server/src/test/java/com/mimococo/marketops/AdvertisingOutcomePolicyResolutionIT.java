package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Real application-role resolver reads over isolated synthetic Owner policy inputs. */
class AdvertisingOutcomePolicyResolutionIT {
    static final String RESOLVED="RESOLVED";
    static final String UNRESOLVED="OUTCOME_POLICY_UNRESOLVED";
    static final String CONFLICTED="OUTCOME_POLICY_CONFLICTED";
    static final String CAUSE="PROVEN_ADVERTISING_LOSS";
    AdvertisingSealedAuthorityIT f;
    JdbcClient app;
    Instant at;
    int version;
    record Resolution(String state,UUID policyId,Integer policyVersion) { }

    @BeforeAll static void database() { AdvertisingSealedAuthorityIT.database(); }
    @BeforeEach void fixture() throws Exception {
        f=new AdvertisingSealedAuthorityIT();f.fixture();
        app=JdbcClient.create(AdvertisingSealedAuthorityIT.application);
        at=app.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
        version=1;
        assertResolved(resolve(at),f.graph.id("outcome"),1);
    }

    @Test void geographicAndCauseSpecificitySelectTheUniqueDominatingAuthority() {
        UUID platform=policy("PLATFORM",f.graph.platform(),null,null,at.minusSeconds(30),null,"ACTIVE",false,false);
        assertResolved(resolve(at),platform,2);
        UUID store=policy("STORE",f.graph.platform(),f.graph.id("store"),null,at.minusSeconds(30),null,"ACTIVE",false,false);
        assertResolved(resolve(at),store,3);
        UUID exact=policy("STORE",f.graph.platform(),f.graph.id("store"),CAUSE,at.minusSeconds(30),null,"ACTIVE",false,false);
        assertResolved(resolve(at),exact,4);
        assertThat(legacy(at)).isEqualTo(exact);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void crossingGeographicAndCauseScopesHaveNoInventedTotalOrder(boolean causeFirst) {
        UUID geographic;
        UUID cause;
        if(causeFirst) {
            cause=policy("ORGANIZATION",null,null,CAUSE,at.minusSeconds(60),null,"ACTIVE",false,false);
            geographic=policy("STORE",f.graph.platform(),f.graph.id("store"),null,at.minusSeconds(30),null,"ACTIVE",false,false);
        } else {
            geographic=policy("STORE",f.graph.platform(),f.graph.id("store"),null,at.minusSeconds(60),null,"ACTIVE",false,false);
            cause=policy("ORGANIZATION",null,null,CAUSE,at.minusSeconds(30),null,"ACTIVE",false,false);
        }
        assertThat(geographic).isNotEqualTo(cause);
        assertAbsent(resolve(at),CONFLICTED);assertThat(legacy(at)).isNull();
        UUID joint=policy("STORE",f.graph.platform(),f.graph.id("store"),CAUSE,at.minusSeconds(10),null,"ACTIVE",false,false);
        assertResolved(resolve(at),joint,4);
    }

    @ParameterizedTest @ValueSource(strings={"SAME_INSTANT","FIRST_NEWER","SECOND_NEWER","RETIRED_OVERLAP"})
    void equalPriorityOverlapNeverUsesInsertionDateOrStatusAsAnArbitraryWinner(String variant) {
        Instant first=at.minusSeconds(60),second=first;
        if(variant.equals("FIRST_NEWER"))first=at.minusSeconds(10);
        if(variant.equals("SECOND_NEWER"))second=at.minusSeconds(10);
        policy("STORE",f.graph.platform(),f.graph.id("store"),CAUSE,first,null,"ACTIVE",false,false);
        policy("STORE",f.graph.platform(),f.graph.id("store"),CAUSE,second,null,
                variant.equals("RETIRED_OVERLAP")?"RETIRED":"ACTIVE",false,false);
        assertAbsent(resolve(at),CONFLICTED);assertThat(legacy(at)).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"WRONG_PLATFORM","STORE_WRONG_PLATFORM","WRONG_STORE","WRONG_CAUSE"})
    void irrelevantNarrowPoliciesCannotSelectOrSuppressThisOrganizationsAuthority(String variant) {
        String scope=variant.equals("WRONG_PLATFORM")?"PLATFORM":"STORE";
        String platform=variant.contains("PLATFORM")?"WILDBERRIES":f.graph.platform();
        UUID store=scope.equals("PLATFORM")?null:variant.equals("WRONG_STORE")?anotherStore():f.graph.id("store");
        String cause=variant.equals("WRONG_CAUSE")?"UNSELLABLE_AD_SPEND":CAUSE;
        policy(scope,platform,store,cause,at.minusSeconds(30),null,"ACTIVE",false,false);
        assertResolved(resolve(at),f.graph.id("outcome"),1);
        assertThat(legacy(at)).isEqualTo(f.graph.id("outcome"));
    }

    @Test void eachStoreCanResolveItsOwnUniqueScopedPolicy() {
        UUID other=anotherStore();
        UUID first=policy("STORE",f.graph.platform(),f.graph.id("store"),CAUSE,at.minusSeconds(30),null,"ACTIVE",false,false);
        UUID second=policy("STORE",f.graph.platform(),other,CAUSE,at.minusSeconds(30),null,"ACTIVE",false,false);
        assertResolved(resolve(at),first,2);
        assertResolved(resolve(f.graph.platform(),other,"PROTECTION_DECREASE",CAUSE,at),second,3);
    }

    @ParameterizedTest @ValueSource(strings={"CRITICAL_DEFINITION","NULL_BOUND"})
    void incompleteMostSpecificAuthorityStaysUnresolvedWithoutBroaderFallback(String missing) {
        policy("STORE",f.graph.platform(),f.graph.id("store"),CAUSE,at.minusSeconds(30),null,"ACTIVE",
                missing.equals("CRITICAL_DEFINITION"),missing.equals("NULL_BOUND"));
        assertAbsent(resolve(at),UNRESOLVED);assertThat(legacy(at)).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"material_profit_delta","material_profit_per_rub_delta","sales_preservation_tolerance_ratio",
            "non_worsening_profit_band","non_worsening_per_rub_band","minimum_ad_spend_denominator","comparison_scale",
            "comparison_rounding_mode","material_boundary_inclusive","negative_profit_terminal"})
    void eachIndependentMissingThresholdFieldRefusesTheNarrowPolicy(String field) {
        policy("STORE",f.graph.platform(),f.graph.id("store"),CAUSE,at.minusSeconds(30),null,"ACTIVE",false,false,field);
        assertAbsent(resolve(at),UNRESOLVED);assertThat(legacy(at)).isNull();
    }

    @Test void missingPolicyReturnsAnExplicitStateAndNeverAnImplicitThreshold() {
        assertAbsent(resolve(f.graph.platform(),f.graph.id("store"),"OPTIMIZATION_INCREASE",CAUSE,at),UNRESOLVED);
        var otherOrganization=UUID.randomUUID();
        var rows=app.sql("SELECT * FROM core.ad_outcome_policy_resolution(:org,:platform,:store,'PROTECTION_DECREASE',:cause,:at)")
                .param("org",otherOrganization).param("platform",f.graph.platform()).param("store",f.graph.id("store"))
                .param("cause",CAUSE).param("at",Timestamp.from(at)).query((rs,n)->new Resolution(rs.getString("state"),rs.getObject("policy_id",UUID.class),rs.getObject("policy_version",Integer.class))).list();
        assertThat(rows).containsExactly(new Resolution(UNRESOLVED,null,null));
    }

    @Test void effectivePeriodIsHalfOpenAndRetiredHistoricalAuthorityRemainsLegible() {
        UUID narrow=policy("STORE",f.graph.platform(),f.graph.id("store"),CAUSE,at,at.plusSeconds(60),"RETIRED",false,false);
        assertResolved(resolve(at.minusNanos(1000)),f.graph.id("outcome"),1);
        assertResolved(resolve(at),narrow,2);
        assertResolved(resolve(at.plusSeconds(60).minusNanos(1000)),narrow,2);
        assertResolved(resolve(at.plusSeconds(60)),f.graph.id("outcome"),1);
    }

    @Test void oneIncompleteAndOneCompleteMaximalPolicyRemainConflictedInsteadOfDiscardingTheBadOne() {
        policy("STORE",f.graph.platform(),f.graph.id("store"),CAUSE,at.minusSeconds(30),null,"ACTIVE",false,true);
        policy("STORE",f.graph.platform(),f.graph.id("store"),CAUSE,at.minusSeconds(20),null,"ACTIVE",false,false);
        assertAbsent(resolve(at),CONFLICTED);assertThat(legacy(at)).isNull();
    }

    @Test void candidateBundleBindingCannotSilentlyAdoptANewMoreSpecificPolicy() {
        String history=frozenBytes();
        assertResolved(candidate(),f.graph.id("outcome"),1);
        UUID preferred=policy("STORE",f.graph.platform(),f.graph.id("store"),null,at.minusSeconds(30),null,"ACTIVE",false,false);
        assertResolved(resolve(at),preferred,2);
        assertAbsent(candidate(),UNRESOLVED);
        assertThat(AdvertisingSealedAuthorityIT.seed.sql("SELECT outcome_policy_id FROM ops.ad_decision_policy_bundle WHERE id=:id")
                .param("id",f.graph.id("bundle")).query(UUID.class).single()).isEqualTo(f.graph.id("outcome"));
        assertThat(frozenBytes()).isEqualTo(history);
        assertThat(AdvertisingSealedAuthorityIT.seed.sql("SELECT count(*) FROM ops.ad_action_authorization WHERE organization_id=:org")
                .param("org",f.graph.id("organization")).query(Integer.class).single()).isZero();
    }

    @Test void originalCompatibilityResolverKeepsItsApplicationOnlyReadPrivilege() {
        for(String signature:List.of(
                "core.resolve_ad_outcome_policy(uuid,text,uuid,text,text,timestamp with time zone)",
                "core.ad_outcome_policy_resolution(uuid,text,uuid,text,text,timestamp with time zone)")) {
            assertThat(app.sql("SELECT has_function_privilege(current_user,CAST(:signature AS regprocedure),'EXECUTE')")
                    .param("signature",signature).query(Boolean.class).single()).isTrue();
            assertThat(AdvertisingSealedAuthorityIT.seed.sql("SELECT EXISTS(SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) x WHERE p.oid=CAST(:signature AS regprocedure) AND x.grantee=0 AND x.privilege_type='EXECUTE')")
                    .param("signature",signature).query(Boolean.class).single()).isFalse();
        }
        assertThat(AdvertisingSealedAuthorityIT.seed.sql("SELECT prosecdef FROM pg_proc WHERE oid='core.resolve_ad_outcome_policy(uuid,text,uuid,text,text,timestamp with time zone)'::regprocedure")
                .query(Boolean.class).single()).isFalse();
        assertThat(AdvertisingSealedAuthorityIT.seed.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",f.graph.id("gate")).query(Boolean.class).single()).isFalse();
    }

    private Resolution resolve(Instant time) { return resolve(f.graph.platform(),f.graph.id("store"),"PROTECTION_DECREASE",CAUSE,time); }
    private Resolution resolve(String platform,UUID store,String direction,String cause,Instant time) {
        return app.sql("SELECT * FROM core.ad_outcome_policy_resolution(:org,:platform,:store,:direction,:cause,:at)")
                .param("org",f.graph.id("organization")).param("platform",platform).param("store",store)
                .param("direction",direction).param("cause",cause).param("at",Timestamp.from(time))
                .query((rs,n)->new Resolution(rs.getString("state"),rs.getObject("policy_id",UUID.class),rs.getObject("policy_version",Integer.class))).single();
    }
    private Resolution candidate() {
        return app.sql("SELECT * FROM ops.ad_outcome_candidate_policy_resolution(:id,:at)")
                .param("id",f.graph.id("candidate")).param("at",Timestamp.from(at))
                .query((rs,n)->new Resolution(rs.getString("state"),rs.getObject("policy_id",UUID.class),rs.getObject("policy_version",Integer.class))).single();
    }
    private UUID legacy(Instant time) {
        return app.sql("SELECT (core.resolve_ad_outcome_policy(:org,:platform,:store,'PROTECTION_DECREASE',:cause,:at)).id")
                .param("org",f.graph.id("organization")).param("platform",f.graph.platform()).param("store",f.graph.id("store"))
                .param("cause",CAUSE).param("at",Timestamp.from(time)).query(UUID.class).optional().orElse(null);
    }
    private UUID policy(String scope,String platform,UUID store,String cause,Instant from,Instant to,String status,boolean missingCritical,boolean missingBound) {
        return policy(scope,platform,store,cause,from,to,status,missingCritical,missingBound,null);
    }
    private UUID policy(String scope,String platform,UUID store,String cause,Instant from,Instant to,String status,boolean missingCritical,boolean missingBound,String missingField) {
        UUID id=UUID.randomUUID();
        AdvertisingSealedAuthorityIT.seed.sql("""
                INSERT INTO core.ad_outcome_policy
                SELECT (jsonb_populate_record(NULL::core.ad_outcome_policy,to_jsonb(p)||jsonb_build_object(
                  'id',CAST(:id AS uuid),'policy_version',CAST(:version AS integer),'scope_kind',CAST(:scope AS text),
                  'platform_code',CAST(:platform AS text),'store_ref_id',CAST(:store AS uuid),'cause_code',CAST(:cause AS text),
                  'effective_from',CAST(:from AS timestamptz),'effective_to',CAST(:to AS timestamptz),'status',CAST(:status AS text),
                  'created_at',CAST(:created AS timestamptz),'critical_unit_definition_complete',NOT :missingCritical,
                  'material_profit_delta',CASE WHEN :missingBound THEN NULL ELSE p.material_profit_delta END)
                  || CASE WHEN CAST(:missingField AS text) IS NULL THEN '{}'::jsonb ELSE jsonb_build_object(CAST(:missingField AS text),NULL) END)).*
                FROM core.ad_outcome_policy p WHERE p.id=:base
                """).param("id",id).param("version",++version).param("scope",scope).param("platform",platform).param("store",store)
                .param("cause",cause).param("from",Timestamp.from(from)).param("to",to==null?null:Timestamp.from(to)).param("status",status)
                .param("missingField",missingField).param("created",Timestamp.from(at.minusSeconds(120))).param("missingCritical",missingCritical).param("missingBound",missingBound)
                .param("base",f.graph.id("outcome")).update();
        return id;
    }
    private UUID anotherStore() {
        UUID id=UUID.randomUUID();
        AdvertisingSealedAuthorityIT.seed.sql("""
                INSERT INTO core.store(id,organization_id,marketplace_account_id,code,display_name,status,created_at,updated_at)
                VALUES(:id,:org,:account,:code,'Synthetic Outcome policy store','ACTIVE',:at,:at)
                """).param("id",id).param("org",f.graph.id("organization")).param("account",f.graph.id("account"))
                .param("code","outcome-"+id).param("at",Timestamp.from(at.minusSeconds(120))).update();
        return id;
    }
    private String frozenBytes() {
        return AdvertisingSealedAuthorityIT.seed.sql("""
                SELECT jsonb_build_object('baseline',to_jsonb(b),
                  'stages',(SELECT jsonb_agg(to_jsonb(s) ORDER BY stage) FROM ops.ad_outcome_stage_baseline s WHERE s.outcome_baseline_id=b.id),
                  'attestation',(SELECT to_jsonb(a) FROM ops.ad_outcome_baseline_attestation a WHERE a.outcome_baseline_id=b.id))::text
                FROM ops.ad_outcome_baseline b WHERE b.id=:id
                """).param("id",f.graph.id("baseline")).query(String.class).single();
    }
    private static void assertResolved(Resolution result,UUID id,int version) {
        assertThat(result).isEqualTo(new Resolution(RESOLVED,id,version));
    }
    private static void assertAbsent(Resolution result,String state) {
        assertThat(result).isEqualTo(new Resolution(state,null,null));
    }
}

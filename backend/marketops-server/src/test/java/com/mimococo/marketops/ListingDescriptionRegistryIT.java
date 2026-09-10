package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Normal application-role registry workflow over fictional data, never provider qualification. */
class ListingDescriptionRegistryIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration,application,admin;
    private static final String REFERENCE="evidence://synthetic/description-protocol";
    private static final String REQUEST_GUARD="""
            {"schema":"DESCRIPTION_REQUEST_V1","evidenceRef":"evidence://synthetic/description-protocol",
             "mutationSemantics":"PARTIAL_ATTRIBUTE","markingPolicy":"REQUIRED","body":{
               "offer_id":{"$bind":"LISTING_KEY","$type":"string"},
               "attributes":[{"id":{"$bind":"ATTRIBUTE_KEY","$type":"integer"},
                  "values":[{"value":{"$bind":"DESCRIPTION_TEXT","$type":"string"}}]}],
               "kizMarked":{"$bind":"KIZ_MARKED","$type":"boolean"}}}
            """;

    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }

    @Test void normalDraftAndIndependentReviewPreserveDescriptionFieldsAndDoNotEnableWrites() throws Exception {
        var f=fixture(); UUID header=configure(f,"CONTENT_WRITE"); UUID evidence=submit(f,header);
        assertThatThrownBy(() -> review(f,evidence,f.id("ownerUser"))).hasMessageContaining("independent verification");
        review(f,evidence,f.id("verifierUser"));
        assertThat(f.app.sql("SELECT state FROM platform.registry_verification_case WHERE id=:id")
                .param("id",evidence).query(String.class).single()).isEqualTo("APPROVED");
        assertThat(f.app.sql("""
                SELECT count(*) FROM platform.capability_operation WHERE capability_id=:id AND verification_state='VERIFIED'
                    AND description_response_binding->>'evidenceRef'=:ref AND description_attribute_key='4191'
                """).param("id",f.id("capability")).param("ref",REFERENCE).query(Integer.class).single()).isEqualTo(3);
        assertThat(f.app.sql("SELECT platform.capability_evidence_current(:account,:capability,:endpoint)")
                .param("account",f.id("account")).param("capability",f.id("capability")).param("endpoint",f.id("endpointReadback"))
                .query(Boolean.class).single()).isTrue();
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_description_command WHERE organization_id=:org")
                .param("org",f.id("organization")).query(Integer.class).single()).isZero();
        assertThat(f.app.sql("SELECT bool_or(production_write_enabled) FROM ops.lc_gate_authority WHERE organization_id=:org")
                .param("org",f.id("organization")).query(Boolean.class).single()).isFalse();
        assertThatThrownBy(() -> configureOperation(f,"READBACK",false)).hasMessageContaining("verified revision not opened");
    }

    @Test void missingResponseDescriptorCannotBeApproved() throws Exception {
        var f=fixture(); UUID header=configure(f,"CONTENT_WRITE");
        configureOperation(f,"READBACK",true);
        UUID evidence=submit(f,header);
        assertThatThrownBy(() -> review(f,evidence,f.id("verifierUser"))).hasMessageContaining("Description response or request semantics incomplete");
        assertThat(f.app.sql("SELECT state FROM platform.registry_verification_case WHERE id=:id")
                .param("id",evidence).query(String.class).single()).isEqualTo("SUBMITTED");
    }

    @Test void renewedAccountEvidenceRetainsTheVerifiedProtocolDefinition() throws Exception {
        var f=fixture(); UUID header=configure(f,"CONTENT_WRITE");
        review(f,submit(f,header),f.id("verifierUser"));
        String before=f.app.sql("SELECT platform.registry_configuration_snapshot(:id)::text")
                .param("id",f.id("capability")).query(String.class).single();
        UUID renewed=submit(f,header,REFERENCE+"-renewal");
        review(f,renewed,f.id("verifierUser"));
        assertThat(f.app.sql("SELECT platform.registry_configuration_snapshot(:id)::text")
                .param("id",f.id("capability")).query(String.class).single()).isEqualTo(before);
        assertThat(f.app.sql("SELECT state FROM platform.registry_verification_case WHERE id=:id")
                .param("id",renewed).query(String.class).single()).isEqualTo("APPROVED");
    }

    @Test void priceCredentialPurposeCannotCertifyDescriptionProtocol() throws Exception {
        var f=fixture(); UUID header=configure(f,"PRICE_WRITE"); UUID evidence=submit(f,header);
        assertThatThrownBy(() -> review(f,evidence,f.id("verifierUser"))).hasMessageContaining("complete protocol semantics missing");
    }

    @Test void absentOrUnprovedWholeCardRequestSchemaCannotBeApproved() throws Exception {
        for (String guard:new String[] {null,REQUEST_GUARD.replace("PARTIAL_ATTRIBUTE","WHOLE_CARD_REPLACEMENT")}) {
            var f=fixture(); UUID header=configure(f,"CONTENT_WRITE");
            configureOperation(f,"APPLY",false,guard);
            UUID evidence=submit(f,header);
            assertThatThrownBy(() -> review(f,evidence,f.id("verifierUser")))
                    .hasMessageContaining("Description exact request schema is incomplete or unsupported");
        }
    }

    @Test void draftCannotUseAnUnscopedOperatorOrUnknownDefinitionField() throws Exception {
        var f=fixture();
        assertThatThrownBy(() -> f.app.sql("SELECT platform.configure_registry_draft(:account,:capability,:actor,'OPERATION',NULL,-1,'{}','synthetic-draft')")
                .param("account",f.id("account")).param("capability",f.id("capability")).param("actor",f.id("executorUser"))
                .query(UUID.class).single()).hasMessageContaining("configuration account authority denied");
        assertThatThrownBy(() -> f.app.sql("SELECT platform.configure_registry_draft(:account,:capability,:actor,'OPERATION',NULL,-1,'{\"production_write_enabled\":true}','synthetic-draft')")
                .param("account",f.id("account")).param("capability",f.id("capability")).param("actor",f.id("ownerUser"))
                .query(UUID.class).single()).hasMessageContaining("unknown or unbounded draft fields");
    }

    private ListingConversionFixture fixture() throws Exception {
        var f=new ListingConversionFixture(migration,application,admin);
        f.seed.sql("""
                INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,status,effective_from,reason,created_at,updated_at)
                VALUES(gen_random_uuid(),:org,:user,'OWNER','ACTIVE',now()-interval '1 day','synthetic independent registry reviewer',now(),now())
                """).param("org",f.id("organization")).param("user",f.id("verifierUser")).update();
        for (String actor:java.util.List.of("ownerUser","verifierUser")) {
            f.seed.sql("""
                    INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,status,effective_from,reason,created_at,updated_at)
                    VALUES(gen_random_uuid(),:org,:user,'KILL_SWITCH_OPERATE',:org,'ACTIVE',now()-interval '1 day','synthetic registry scope',now(),now())
                    """).param("org",f.id("organization")).param("user",f.id(actor)).update();
        }
        return f;
    }
    private UUID configure(ListingConversionFixture f,String headerPurpose) {
        f.app.sql("SELECT platform.begin_registry_revision(:account,:capability,:actor,encode(sha256(convert_to(platform.registry_configuration_snapshot(:capability)::text,'UTF8')),'hex'),'synthetic-revision')")
                .param("account",f.id("account")).param("capability",f.id("capability")).param("actor",f.id("ownerUser"))
                .query(Object.class).optional();
        f.app.sql("""
                SELECT platform.configure_registry_draft(:account,:capability,:actor,'PROFILE',NULL,-1,
                    '{"base_url":"https://example.invalid","request_timeout_ms":5000,"max_response_bytes":8192,"owner_label":"synthetic"}',
                    'synthetic-profile')
                """).param("account",f.id("account")).param("capability",f.id("capability")).param("actor",f.id("ownerUser"))
                .query(UUID.class).single();
        UUID header=f.app.sql("""
                SELECT platform.configure_registry_draft(:account,:capability,:actor,'HEADER',NULL,-1,
                    jsonb_build_object('header_name','Authorization','value_source','RESOLVED_SECRET','value_template','Bearer {value}',
                        'credential_purpose',CAST(:purpose AS text),'ordinal',1,'owner_label','synthetic'), 'synthetic-header')
                """).param("account",f.id("account")).param("capability",f.id("capability")).param("actor",f.id("ownerUser"))
                .param("purpose",headerPurpose).query(UUID.class).single();
        for (String name:java.util.List.of("endpointApply","endpointReadback","endpointRestore")) {
            f.app.sql("""
                    SELECT platform.configure_registry_draft(:account,:capability,:actor,'ENDPOINT',e.id,e.version,
                        (SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(e)) WHERE key=ANY(ARRAY[
                            'http_method','path_template','operation_function','query_template','body_template','response_content_type',
                            'continuation_pointer','pagination_model','rate_limit_per_minute']))||
                        '{"rate_limit_per_minute":60,"response_content_type":"application/json"}', 'synthetic-endpoint')
                      FROM platform.platform_endpoint e WHERE id=:id
                    """).param("account",f.id("account")).param("capability",f.id("capability")).param("actor",f.id("ownerUser"))
                    .param("id",f.id(name)).query(UUID.class).single();
        }
        for (String operation:java.util.List.of("APPLY","READBACK","RESTORE")) configureOperation(f,operation,false);
        return header;
    }
    private void configureOperation(ListingConversionFixture f,String operation,boolean missing) {
        configureOperation(f,operation,missing,REQUEST_GUARD);
    }
    private void configureOperation(ListingConversionFixture f,String operation,boolean missing,String requestGuard) {
        f.app.sql("""
                SELECT platform.configure_registry_draft(:account,:capability,:actor,'OPERATION',o.id,o.version,
                    (SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(o)) WHERE key=ANY(ARRAY[
                        'operation','endpoint_id','request_template','accepted_pointer','accepted_value','task_key_pointer',
                        'task_status_pointer','task_success_value','task_failure_value','task_pending_values','observed_price_pointer',
                        'observed_currency_pointer','conditional_write_header','version_token_header','owner_label',
                        'description_observed_text_pointer','description_kiz_marked_pointer','description_attribute_key']))||
                    jsonb_build_object('conditional_write_header',CASE WHEN o.operation='RESTORE' THEN 'If-Match' END,
                        'version_token_header',CASE WHEN o.operation='READBACK' THEN 'etag' END,
                        'request_template',CASE WHEN o.operation IN ('APPLY','RESTORE') THEN
                            '{"offer_id":"{nativeListingKey}","attributes":[{"id":{descriptionAttributeKey},"values":[{"value":"{descriptionText}"}]}],"kizMarked":{kizMarkedDeclared}}'
                            ELSE o.request_template END,
                        'description_request_guard',CASE WHEN o.operation IN ('APPLY','RESTORE') THEN CAST(:requestGuard AS jsonb) END,
                        'description_response_binding',CASE WHEN :missing THEN NULL ELSE jsonb_build_object(
                            'schema','DESCRIPTION_RESPONSE_IDENTITY_V1','evidenceRef',CAST(:ref AS text),'mode','EXACT_OBJECT',
                            'selection','OBJECT','payloadPointer','','listingKeyPointer','/offer_id','listingKeyType','string') END),
                    'synthetic-operation') FROM platform.capability_operation o WHERE capability_id=:capability AND operation=:operation
                """).param("account",f.id("account")).param("capability",f.id("capability")).param("actor",f.id("ownerUser"))
                .param("operation",operation).param("missing",missing).param("ref",REFERENCE)
                .param("requestGuard",requestGuard).query(UUID.class).single();
    }
    private UUID submit(ListingConversionFixture f,UUID header) {
        return submit(f,header,REFERENCE);
    }
    private UUID submit(ListingConversionFixture f,UUID header,String reference) {
        return f.app.sql("""
                SELECT platform.submit_registry_verification(:account,:capability,:actor,
                    (SELECT array_agg(id ORDER BY id) FROM platform.platform_endpoint WHERE capability_id=:capability),
                    ARRAY[CAST(:header AS uuid)],jsonb_build_object('officialSourceUrl','https://example.invalid/synthetic',
                        'officialSourceSha256',repeat('a',64),'accountEvidenceRef',CAST(:ref AS text),'accountEvidenceSha256',repeat('b',64),
                        'evidenceClass','REAL_ACCOUNT','testedAt',now()-interval '1 minute','validUntil',now()+interval '1 day'),
                    encode(sha256(convert_to(platform.registry_configuration_snapshot(:capability)::text,'UTF8')),'hex'),'synthetic-submit')
                """).param("account",f.id("account")).param("capability",f.id("capability")).param("actor",f.id("ownerUser"))
                .param("header",header).param("ref",reference).query(UUID.class).single();
    }
    private void review(ListingConversionFixture f,UUID evidence,UUID actor) {
        f.app.sql("SELECT platform.review_registry_verification(:id,:actor,0,true,'synthetic-independent-review')")
                .param("id",evidence).param("actor",actor).query(Object.class).optional();
    }
}

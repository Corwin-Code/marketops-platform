package com.mimococo.marketops.advertisingefficiency.internal.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdBundleControlRepository;
import com.mimococo.marketops.identityaccess.AuthenticatedInvocationIssuer;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AdBundleControlService {
    private final AdBundleControlRepository bundles;
    private final AuthenticatedInvocationIssuer issuer;
    private final ObjectMapper json;
    public AdBundleControlService(AdBundleControlRepository bundles,AuthenticatedInvocationIssuer issuer,ObjectMapper json) {
        this.bundles=bundles;this.issuer=issuer;this.json=json;
    }
    public UUID draft(UUID bundleId,UUID gateId,Map<String,Object> references) {
        Map<String,Object> content=new HashMap<>(references);
        content.put("id",bundleId);content.put("gate_scope_reference",gateId.toString());
        content.put("reason",MetadataFieldPolicy.requireText("reason",content.get("reason") instanceof String reason ? reason : null));
        content.put("evidence_reference",MetadataFieldPolicy.requireText("evidenceReference",content.get("evidence_reference") instanceof String evidence ? evidence : null));
        try { return bundles.draft(json.writeValueAsString(content),proof("BUNDLE_DRAFT",bundleId,gateId)); }
        catch(JacksonException invalid) { throw new IllegalArgumentException("Bundle references must be structured values"); }
    }
    public void endorse(UUID bundleId,UUID gateId) { bundles.endorse(bundleId,gateId,proof("BUNDLE_ENDORSE",bundleId,gateId)); }
    public void activate(UUID bundleId,UUID gateId) { bundles.activate(bundleId,gateId,proof("BUNDLE_APPROVE",bundleId,gateId)); }
    private String proof(String purpose,UUID bundleId,UUID gateId) {
        long[] context=bundles.transactionContext();
        return issuer.issueControl(purpose,bundleId,gateId,Math.toIntExact(context[0]),context[1]);
    }
}

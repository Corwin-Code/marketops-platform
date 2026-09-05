package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdContainmentControlRepository;
import com.mimococo.marketops.identityaccess.AuthenticatedInvocationIssuer;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AdContainmentControlService {
    private final AdContainmentControlRepository controls;
    private final AuthenticatedInvocationIssuer issuer;
    public AdContainmentControlService(AdContainmentControlRepository controls, AuthenticatedInvocationIssuer issuer) {
        this.controls=controls; this.issuer=issuer;
    }
    public UUID stop(UUID object, String scope, String kind, String cause, UUID reviewOwner,
            String reason, String evidence) {
        UUID id=UUID.randomUUID();
        return controls.activate(id,object,scope,kind,cause,reviewOwner,
                MetadataFieldPolicy.requireText("reason",reason),
                MetadataFieldPolicy.requireText("evidenceReference",evidence),
                proof("CONTAINMENT_STOP",object,id));
    }
    public void attest(UUID id,String condition,String evidence) {
        controls.attest(id,condition,MetadataFieldPolicy.requireText("evidenceReference",evidence),
                proof("OPERATIONS_ENDORSEMENT".equals(condition)?"CONTAINMENT_ENDORSE":"CONTAINMENT_ATTEST",id,id));
    }
    public boolean reenable(UUID id,UUID newBundle) {
        return controls.reenable(id,newBundle,proof("CONTAINMENT_REENABLE",id,newBundle));
    }
    private String proof(String purpose,UUID target,UUID version) {
        long[] context=controls.transactionContext();
        return issuer.issueControl(purpose,target,version,Math.toIntExact(context[0]),context[1]);
    }
}

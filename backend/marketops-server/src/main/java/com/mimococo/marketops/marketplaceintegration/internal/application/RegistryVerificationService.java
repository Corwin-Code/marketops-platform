package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.identityaccess.*;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.RegistryVerificationRepository;
import com.mimococo.marketops.shared.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authenticated maintenance of recorded facts; it never contacts a provider. */
@Service
public class RegistryVerificationService {
    private final RegistryVerificationRepository repository;
    private final BusinessAuthorization authorization;
    private final MaintenanceWriteGate maintenance;

    RegistryVerificationService(RegistryVerificationRepository repository,BusinessAuthorization authorization,MaintenanceWriteGate maintenance) {
        this.repository=repository; this.authorization=authorization; this.maintenance=maintenance;
    }

    @Transactional(readOnly=true)
    public RegistryVerificationRepository.Configuration configuration(AuthenticatedActor actor,UUID account,UUID capability) {
        require(actor,account,false);
        return repository.configuration(account,capability).orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED));
    }

    @Transactional
    public UUID configure(AuthenticatedActor actor,UUID account,UUID capability,String kind,UUID id,long version,Map<String,Object> definition) {
        require(actor,account,true);
        validate(definition,0);
        return repository.configure(account,capability,actor.userId(),kind,id,version,definition,CorrelationId.current());
    }

    @Transactional
    public UUID submit(AuthenticatedActor actor,UUID account,UUID capability,List<UUID> endpoints,List<UUID> headers,
                       Map<String,Object> evidence,String digest) {
        require(actor,account,true);
        if (endpoints==null || endpoints.isEmpty() || endpoints.size()>32 || endpoints.stream().anyMatch(java.util.Objects::isNull)
                || headers==null || headers.isEmpty() || headers.size()>16 || headers.stream().anyMatch(java.util.Objects::isNull)
                || digest==null || !digest.matches("[0-9a-f]{64}")) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        validate(evidence,0);
        return repository.submit(account,capability,actor.userId(),endpoints,headers,evidence,digest,CorrelationId.current());
    }

    @Transactional(readOnly=true)
    public RegistryVerificationRepository.CaseView find(AuthenticatedActor actor,UUID id) {
        var found=repository.find(id).orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED));
        require(actor,found.marketplaceAccountId(),false);
        return found;
    }

    @Transactional
    public void review(AuthenticatedActor actor,UUID id,long version,boolean approve) {
        var found=find(actor,id); require(actor,found.marketplaceAccountId(),true);
        repository.review(id,actor.userId(),version,approve,CorrelationId.current());
    }

    @Transactional
    public void revoke(AuthenticatedActor actor,UUID id,long version) {
        var found=find(actor,id); require(actor,found.marketplaceAccountId(),true);
        repository.revoke(id,actor.userId(),version,CorrelationId.current());
    }

    @Transactional
    public void beginRevision(AuthenticatedActor actor,UUID account,UUID capability,String digest) {
        require(actor,account,true);
        repository.beginRevision(account,capability,actor.userId(),digest,CorrelationId.current());
    }

    private void require(AuthenticatedActor actor,UUID account,boolean write) {
        authorization.require(actor,ActionScopeCode.KILL_SWITCH_OPERATE,new ResourceScope(ResourceScopeType.MARKETPLACE_ACCOUNT,account));
        if (write && !maintenance.writeEnabled()) throw OperationRejectedException.of(ErrorCode.MAINTENANCE_WRITE_DISABLED);
    }

    private static void validate(Object value,int depth) {
        if (depth>8) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        if (value instanceof String text) {
            if (text.length()>4096) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            SecretMaterialGuard.requireNonSecret("registryDefinition",text);
        }
        else if (value instanceof Map<?,?> map) {
            if (map.size()>32) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            map.forEach((key,entry) -> {
                if (!(key instanceof String)) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
                if (depth==0 && java.util.Set.of("officialSourceSha256","accountEvidenceSha256").contains(key)) {
                    if (!(entry instanceof String hash) || !hash.matches("[0-9a-f]{64}")) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
                } else validate(entry,depth+1);
            });
        } else if (value instanceof List<?> list) {
            if (list.size()>32) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            list.forEach(entry -> validate(entry,depth+1));
        } else if (value!=null && !(value instanceof Number) && !(value instanceof Boolean)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }
}

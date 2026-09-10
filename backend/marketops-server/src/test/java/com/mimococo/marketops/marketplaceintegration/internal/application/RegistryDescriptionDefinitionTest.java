package com.mimococo.marketops.marketplaceintegration.internal.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.RegistryVerificationRepository;
import com.mimococo.marketops.shared.MaintenanceWriteGate;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exercises the authenticated service boundary; SQL separately qualifies the exact schema. */
class RegistryDescriptionDefinitionTest {
    private final RegistryVerificationRepository repository=mock(RegistryVerificationRepository.class);
    private final MaintenanceWriteGate gate=mock(MaintenanceWriteGate.class);
    private final RegistryVerificationService service=new RegistryVerificationService(repository,mock(BusinessAuthorization.class),gate);
    private final AuthenticatedActor actor=mock(AuthenticatedActor.class);
    private final UUID account=UUID.randomUUID(), capability=UUID.randomUUID();

    @Test void nestedNativeOperationShapeReachesTheControlledWriter() {
        when(gate.writeEnabled()).thenReturn(true);
        service.configure(actor,account,capability,"OPERATION",null,0,Map.of("description_request_guard",nested(14)));
        verify(repository).configure(eq(account),eq(capability),any(),eq("OPERATION"),isNull(),eq(0L),anyMap(),any());
    }

    @Test void ordinaryMetadataAndEvidenceKeepTheirOriginalDepthBound() {
        when(gate.writeEnabled()).thenReturn(true);
        for (String kind:List.of("PROFILE","HEADER","ENDPOINT")) {
            assertThatThrownBy(() -> service.configure(actor,account,capability,kind,null,0,
                    Map.of("description_request_guard",nested(14)))).isInstanceOf(OperationRejectedException.class);
        }
        assertThatThrownBy(() -> service.configure(actor,account,capability,"OPERATION",null,0,
                Map.of("other",nested(14)))).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> service.submit(actor,account,capability,List.of(UUID.randomUUID()),List.of(UUID.randomUUID()),
                Map.of("description_request_guard",nested(14)),"a".repeat(64))).isInstanceOf(OperationRejectedException.class);
        verifyNoInteractions(repository);
    }

    @Test void nativeShapeStillHasFiniteDepthCollectionAndStringBounds() {
        when(gate.writeEnabled()).thenReturn(true);
        for (Object invalid:List.of(nested(34),java.util.Collections.nCopies(33,true),"text ".repeat(820))) {
            assertThatThrownBy(() -> service.configure(actor,account,capability,"OPERATION",null,0,
                    Map.of("description_request_guard",invalid))).isInstanceOf(OperationRejectedException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test void defaultOffMaintenanceStillBlocksTheExpandedSchema() {
        assertThatThrownBy(() -> service.configure(actor,account,capability,"OPERATION",null,0,
                Map.of("description_request_guard",nested(14)))).isInstanceOf(OperationRejectedException.class);
        verifyNoInteractions(repository);
    }

    private static Object nested(int depth) {
        Object value=true;
        for (int i=0;i<depth;i++) value=Map.of("body",value);
        return value;
    }
}

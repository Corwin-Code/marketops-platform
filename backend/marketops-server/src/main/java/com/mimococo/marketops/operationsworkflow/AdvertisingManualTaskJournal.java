package com.mimococo.marketops.operationsworkflow;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import java.util.UUID;

/** Records a verified canonical Manual action in the existing attributable Task journal. */
public interface AdvertisingManualTaskJournal {
    void recordManualAction(AuthenticatedActor actor,UUID packetId,UUID evidenceRecordId,
                            String actionKind,String reason);
}

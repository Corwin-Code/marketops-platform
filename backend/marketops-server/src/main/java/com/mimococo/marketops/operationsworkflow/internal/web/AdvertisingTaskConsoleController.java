package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.internal.application.WorkTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** View, acknowledgement, attributable action and outcome remain distinct events. */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/advertising/tasks/{taskId}")
class AdvertisingTaskConsoleController {
    private final WorkTaskService tasks;
    private final com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingTaskGovernance governance;
    private final com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy disclosure;
    AdvertisingTaskConsoleController(WorkTaskService tasks,
            com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingTaskGovernance governance,
            com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy disclosure) {
        this.tasks=tasks;this.governance=governance;this.disclosure=disclosure;
    }

    @PostMapping("/view") @ResponseStatus(HttpStatus.NO_CONTENT)
    void view(AuthenticatedActor actor,@PathVariable UUID taskId) { tasks.recordView(actor,taskId); }

    @PostMapping("/acknowledgement") @ResponseStatus(HttpStatus.NO_CONTENT)
    void acknowledge(AuthenticatedActor actor,@PathVariable UUID taskId) { tasks.acknowledge(actor,taskId); }

    @PostMapping("/action") @ResponseStatus(HttpStatus.NO_CONTENT)
    void action(AuthenticatedActor actor,@PathVariable UUID taskId,@Valid @RequestBody Action request) {
        tasks.recordAction(actor,taskId,request.actionKind(),request.evidenceReference(),request.reason());
    }

    @PostMapping("/reopen") @ResponseStatus(HttpStatus.NO_CONTENT)
    void reopen(AuthenticatedActor actor,@PathVariable UUID taskId,@Valid @RequestBody Reopen request) {
        tasks.reopen(actor,taskId,request.escalated(),request.reason());
    }

    @GetMapping("/journal")
    tools.jackson.databind.node.ArrayNode journal(AuthenticatedActor actor,@PathVariable UUID taskId) {
        tasks.requireTaskAction(actor,taskId,true);
        var context=governance.context(taskId).orElseThrow();
        return disclosure.discloseTaskEvents(actor,context.object(),context.affectedSetDigest(),tasks.journal(taskId));
    }

    record Action(@NotBlank String actionKind,@NotBlank String evidenceReference,@NotBlank String reason) { }
    record Reopen(boolean escalated,@NotBlank String reason) { }
}

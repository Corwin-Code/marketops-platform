package com.mimococo.marketops.marketplaceintegration.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.marketplaceintegration.internal.application.RegistryVerificationService;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.RegistryVerificationRepository;
import com.mimococo.marketops.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Loopback-only, authenticated maintenance; attribution headers confer no authority. */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/registry-verification")
class RegistryVerificationController {
    private final RegistryVerificationService service;
    private final ObjectMapper mapper;
    RegistryVerificationController(RegistryVerificationService service,ObjectMapper mapper) { this.service=service; this.mapper=mapper; }

    @GetMapping("/accounts/{account}/capabilities/{capability}")
    RegistryVerificationRepository.Configuration configuration(AuthenticatedActor actor,@PathVariable UUID account,
            @PathVariable UUID capability,HttpServletRequest request) {
        local(request); return service.configuration(actor,account,capability);
    }

    @PostMapping("/accounts/{account}/capabilities/{capability}/draft")
    @ResponseStatus(HttpStatus.CREATED)
    Created configure(AuthenticatedActor actor,@PathVariable UUID account,@PathVariable UUID capability,
            @RequestBody byte[] raw,HttpServletRequest request) {
        local(request);
        Map<String,Object> input=read(raw,Set.of("kind","id","expectedVersion","definition"));
        return new Created(service.configure(actor,account,capability,string(input,"kind"),
                input.get("id")==null?null:uuid(string(input,"id")),version(input),object(input,"definition")));
    }

    @PostMapping("/accounts/{account}/capabilities/{capability}/revision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revision(AuthenticatedActor actor,@PathVariable UUID account,@PathVariable UUID capability,
            @RequestBody byte[] raw,HttpServletRequest request) {
        local(request); var input=read(raw,Set.of("expectedDigest"));
        service.beginRevision(actor,account,capability,string(input,"expectedDigest"));
    }

    @PostMapping("/accounts/{account}/capabilities/{capability}/cases")
    @ResponseStatus(HttpStatus.CREATED)
    Created submit(AuthenticatedActor actor,@PathVariable UUID account,@PathVariable UUID capability,
            @RequestBody byte[] raw,HttpServletRequest request) {
        local(request); var input=read(raw,Set.of("endpointIds","authHeaderIds","evidence","expectedDigest"));
        return new Created(service.submit(actor,account,capability,ids(input,"endpointIds"),ids(input,"authHeaderIds"),
                object(input,"evidence"),string(input,"expectedDigest")));
    }

    @GetMapping("/cases/{id}")
    RegistryVerificationRepository.CaseView get(AuthenticatedActor actor,@PathVariable UUID id,HttpServletRequest request) {
        local(request); return service.find(actor,id);
    }

    @PostMapping("/cases/{id}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void review(AuthenticatedActor actor,@PathVariable UUID id,@RequestBody byte[] raw,HttpServletRequest request) {
        local(request); var input=read(raw,Set.of("expectedVersion","approve"));
        if (!(input.get("approve") instanceof Boolean approve)) throw invalid();
        service.review(actor,id,version(input),approve);
    }

    @PostMapping("/cases/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(AuthenticatedActor actor,@PathVariable UUID id,@RequestBody byte[] raw,HttpServletRequest request) {
        local(request); service.revoke(actor,id,version(read(raw,Set.of("expectedVersion"))));
    }

    private Map<String,Object> read(byte[] raw,Set<String> fields) {
        if (raw==null || raw.length>32768) throw invalid();
        Map<String,Object> parsed;
        try { parsed=JsonValues.object(JsonValues.read(mapper,raw)); }
        catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) { throw invalid(); }
        if (!fields.containsAll(parsed.keySet())) throw invalid();
        return parsed;
    }

    private static String string(Map<String,Object> input,String name) {
        if (!(input.get(name) instanceof String value) || value.isBlank()) throw invalid();
        return value;
    }
    private static long version(Map<String,Object> input) {
        Object value=input.get("expectedVersion");
        if (value instanceof Long number) return number;
        if (value instanceof Integer number) return number.longValue();
        throw invalid();
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Map<String,Object> input,String name) {
        if (!(input.get(name) instanceof Map<?,?> value)) throw invalid();
        return (Map<String,Object>)value;
    }
    private static List<UUID> ids(Map<String,Object> input,String name) {
        if (!(input.get(name) instanceof List<?> values) || values.size()>32) throw invalid();
        return values.stream().map(value -> { if (!(value instanceof String id)) throw invalid(); return uuid(id); }).toList();
    }
    private static UUID uuid(String value) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { throw invalid(); }
    }
    private static void local(HttpServletRequest request) {
        if (!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
    }
    private static OperationRejectedException invalid() { return OperationRejectedException.of(ErrorCode.VALIDATION_FAILED); }
    record Created(UUID id) { }
}

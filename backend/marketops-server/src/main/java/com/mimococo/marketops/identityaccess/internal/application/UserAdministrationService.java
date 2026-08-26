package com.mimococo.marketops.identityaccess.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.internal.domain.GrantStatus;
import com.mimococo.marketops.identityaccess.internal.domain.RoleAssignment;
import com.mimococo.marketops.identityaccess.internal.domain.UserAccountStatus;
import com.mimococo.marketops.identityaccess.internal.domain.UserProfile;
import com.mimococo.marketops.identityaccess.internal.domain.UserScopeGrantRecord;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.UserAuthorizationRepository;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.UserGrantRepository;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.UserProfileRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of MarketOps profiles, their roles and their scope grants.
 *
 * <p>Provisioning binds an external subject to a profile. It never creates a
 * credential, because there is none to create: the person authenticates at the
 * provider, and this record only says who they are here and what they may do.
 *
 * <p>Disabling does three things in one transaction: it marks the profile, it
 * moves the credential boundary forward so tokens already in circulation stop
 * working, and it revokes every live role and grant. Doing fewer of the three
 * would leave a disabled person able to act until a token expired, or leave
 * grants that reactivate silently if the profile is ever re-enabled.
 */
@Service
public class UserAdministrationService {

    static final String PROFILE_ENTITY_TYPE = "user-account";
    static final String ROLE_ENTITY_TYPE = "user-role-assignment";
    static final String GRANT_ENTITY_TYPE = "user-scope-grant";

    /** The opaque subject shape an identity provider publishes. */
    private static final Pattern SUBJECT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@|-]{0,254}$");

    /** A bounded business contact address; never used to authenticate. */
    private static final Pattern CONTACT_EMAIL =
            Pattern.compile("^[^@\\s]{1,64}@[a-z0-9][a-z0-9.-]{0,252}$");

    private final UserProfileRepository profiles;
    private final UserGrantRepository grants;
    private final UserAuthorizationRepository authorization;
    private final IdentityProviderService providerService;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    UserAdministrationService(UserProfileRepository profiles,
                              UserGrantRepository grants,
                              UserAuthorizationRepository authorization,
                              IdentityProviderService providerService,
                              MetadataAuditRecorder auditRecorder,
                              IdGenerator idGenerator,
                              Clock clock) {
        this.profiles = profiles;
        this.grants = grants;
        this.authorization = authorization;
        this.providerService = providerService;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Bind an external subject to a new MarketOps profile. */
    @Transactional
    public UserProfile provision(String operator,
                                 UUID organizationId,
                                 UUID identityProviderId,
                                 String externalSubject,
                                 String loginHint,
                                 String displayName,
                                 String contactEmail) {
        providerService.require(identityProviderId);
        String validSubject = requireSubject(externalSubject);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validHint = loginHint == null
                ? null : MetadataFieldPolicy.requireText("loginHint", loginHint);
        String validEmail = requireContactEmail(contactEmail);

        profiles.findBySubject(identityProviderId, validSubject).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                    PROFILE_ENTITY_TYPE, null, existing.id());
        });

        Instant now = clock.instant();
        UserProfile profile = new UserProfile(
                idGenerator.newId(), organizationId, identityProviderId, validSubject,
                validHint, validName, validEmail, UserAccountStatus.ACTIVE, null,
                now, null, now, now, 0L);
        profiles.insert(profile);

        Map<String, FieldChange> changes = new LinkedHashMap<>();
        changes.put("organizationId", new FieldChange(null, organizationId.toString()));
        changes.put("identityProviderId", new FieldChange(null, identityProviderId.toString()));
        changes.put("displayName", new FieldChange(null, validName));
        changes.put("status", new FieldChange(null, UserAccountStatus.ACTIVE.name()));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.CREATE,
                PROFILE_ENTITY_TYPE, profile.id(), null, changes, null, null));
        return profile;
    }

    /** Change a profile's business attributes. */
    @Transactional
    public UserProfile update(String operator,
                              UUID id,
                              String displayName,
                              String loginHint,
                              String contactEmail,
                              long expectedVersion) {
        UserProfile current = require(id);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validHint = loginHint == null
                ? null : MetadataFieldPolicy.requireText("loginHint", loginHint);
        String validEmail = requireContactEmail(contactEmail);

        UserProfile updated = new UserProfile(
                current.id(), current.organizationId(), current.identityProviderId(),
                current.externalSubject(), validHint, validName, validEmail,
                current.status(), current.disabledReason(), current.credentialsValidFrom(),
                current.lastSeenAt(), current.createdAt(), clock.instant(), expectedVersion + 1);
        applyVersioned(profiles.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.UPDATE,
                PROFILE_ENTITY_TYPE, current.id(), null,
                Map.of("displayName", new FieldChange(current.displayName(), validName)),
                null, null));
        return updated;
    }

    /**
     * Disable a profile, invalidate its existing tokens and revoke its grants.
     *
     * <p>All three happen in one transaction. A disabled profile whose grants
     * survived would reactivate with its old authority the moment somebody
     * re-enabled it, and a disabled profile whose tokens survived would keep
     * acting until the provider's expiry caught up.
     */
    @Transactional
    public UserProfile disable(String operator, UUID id, String reason, long expectedVersion) {
        UserProfile current = require(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        Instant now = clock.instant();

        UserProfile disabled = new UserProfile(
                current.id(), current.organizationId(), current.identityProviderId(),
                current.externalSubject(), current.loginHint(), current.displayName(),
                current.contactEmail(), UserAccountStatus.DISABLED, validReason,
                now, current.lastSeenAt(), current.createdAt(), now, expectedVersion + 1);
        applyVersioned(profiles.update(disabled, expectedVersion));

        for (RoleAssignment assignment : grants.listRoles(id)) {
            if (assignment.status() == GrantStatus.ACTIVE) {
                grants.revokeRole(assignment.id(), validReason, now, assignment.version());
            }
        }
        for (UserScopeGrantRecord grantRecord : grants.listGrants(id)) {
            if (grantRecord.status() == GrantStatus.ACTIVE) {
                grants.revokeGrant(grantRecord.id(), validReason, now, grantRecord.version());
            }
        }

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.STATUS_CHANGE,
                PROFILE_ENTITY_TYPE, current.id(), null,
                Map.of(
                        "status", new FieldChange(current.status().name(),
                                UserAccountStatus.DISABLED.name()),
                        "credentialsValidFrom", new FieldChange(
                                current.credentialsValidFrom().toString(), now.toString())),
                validReason, null));
        return disabled;
    }

    /** Assign a business role from an instant onward. */
    @Transactional
    public RoleAssignment assignRole(String operator,
                                     UUID userId,
                                     BusinessRoleCode role,
                                     Instant effectiveFrom) {
        UserProfile profile = requireActive(userId);
        Instant now = clock.instant();
        Instant from = effectiveFrom == null ? now : effectiveFrom;

        RoleAssignment assignment = new RoleAssignment(
                idGenerator.newId(), profile.organizationId(), userId, role, from, null,
                GrantStatus.ACTIVE, null, now, now, 0L);
        grants.insertRole(assignment);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.GRANT,
                ROLE_ENTITY_TYPE, assignment.id(), role.name(),
                Map.of(
                        "userId", new FieldChange(null, userId.toString()),
                        "role", new FieldChange(null, role.name()),
                        "effectiveFrom", new FieldChange(null, from.toString())),
                null, null));
        return assignment;
    }

    /** Withdraw a role assignment. */
    @Transactional
    public void revokeRole(String operator, UUID assignmentId, String reason,
                           long expectedVersion) {
        RoleAssignment current = grants.findRole(assignmentId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        applyVersioned(grants.revokeRole(
                assignmentId, validReason, clock.instant(), expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.REVOKE,
                ROLE_ENTITY_TYPE, assignmentId, current.role().name(),
                Map.of("status", new FieldChange(GrantStatus.ACTIVE.name(),
                        GrantStatus.REVOKED.name())),
                validReason, null));
    }

    /** Grant one action on one resource from an instant onward. */
    @Transactional
    public UserScopeGrantRecord grantScope(String operator,
                                           UUID userId,
                                           ActionScopeCode action,
                                           ResourceScopeType resourceType,
                                           UUID resourceId,
                                           Instant effectiveFrom) {
        UserProfile profile = requireActive(userId);
        // The relational layer forbids a grant that leaves the organization, but
        // refusing here turns a constraint violation into the stable code an
        // operator can act on.
        authorization.resolveChain(resourceType, resourceId)
                .filter(chain -> chain.organizationId().equals(profile.organizationId()))
                .orElseThrow(() -> OperationRejectedException.of(
                        ErrorCode.CROSS_ORGANIZATION_REJECTED));

        Instant now = clock.instant();
        Instant from = effectiveFrom == null ? now : effectiveFrom;
        UserScopeGrantRecord grantRecord = new UserScopeGrantRecord(
                idGenerator.newId(), profile.organizationId(), userId, action, resourceType,
                resourceId, from, null, GrantStatus.ACTIVE, null, now, now, 0L);
        grants.insertGrant(grantRecord);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.GRANT,
                GRANT_ENTITY_TYPE, grantRecord.id(), action.name(),
                Map.of(
                        "userId", new FieldChange(null, userId.toString()),
                        "action", new FieldChange(null, action.name()),
                        "resourceType", new FieldChange(null, resourceType.name()),
                        "resourceId", new FieldChange(null, resourceId.toString())),
                null, null));
        return grantRecord;
    }

    /** Withdraw a scope grant. */
    @Transactional
    public void revokeScope(String operator, UUID grantId, String reason, long expectedVersion) {
        UserScopeGrantRecord current = grants.findGrant(grantId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        applyVersioned(grants.revokeGrant(
                grantId, validReason, clock.instant(), expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.REVOKE,
                GRANT_ENTITY_TYPE, grantId, current.action().name(),
                Map.of("status", new FieldChange(GrantStatus.ACTIVE.name(),
                        GrantStatus.REVOKED.name())),
                validReason, null));
    }

    /** Load one profile, refusing when it does not exist. */
    @Transactional(readOnly = true)
    public UserProfile require(UUID id) {
        return profiles.findById(id)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** List an organization's profiles. */
    @Transactional(readOnly = true)
    public List<UserProfile> list(UUID organizationId, int limit) {
        return profiles.list(organizationId, Math.clamp(limit, 1, 200));
    }

    /** List a profile's role assignments. */
    @Transactional(readOnly = true)
    public List<RoleAssignment> listRoles(UUID userId) {
        return grants.listRoles(userId);
    }

    /** List a profile's scope grants. */
    @Transactional(readOnly = true)
    public List<UserScopeGrantRecord> listGrants(UUID userId) {
        return grants.listGrants(userId);
    }

    private UserProfile requireActive(UUID id) {
        UserProfile profile = require(id);
        if (!profile.isActive()) {
            throw OperationRejectedException.of(ErrorCode.USER_INACTIVE);
        }
        return profile;
    }

    private static String requireSubject(String externalSubject) {
        if (externalSubject == null || !SUBJECT.matcher(externalSubject).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return externalSubject;
    }

    private static String requireContactEmail(String contactEmail) {
        if (contactEmail == null) {
            return null;
        }
        String value = MetadataFieldPolicy.requireText("contactEmail", contactEmail);
        if (!CONTACT_EMAIL.matcher(value).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private static void applyVersioned(boolean applied) {
        if (!applied) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
    }
}

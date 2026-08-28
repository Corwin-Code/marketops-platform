# Identity and mounted secrets

This describes candidate PR #20 code, not deployment or provider acceptance.
Real OIDC/MFA, credential provisioning, Gate EV, Gate E and production enablement
remain outside the rework authority.

## Human authentication and immediate revocation

The console sends an access token in the `Authorization: Bearer` header. The
servlet boundary is stateless: cookies, query parameters, form parameters and
an existing servlet session cannot supply authentication. No authentication
cookie is issued. CORS remains a separate origin check; it does not confer a
business role or resource grant.

The decoder checks the signature, configured issuer, configured audience,
expiry and not-before time. An expiry is mandatory. A provider record must also
be active and verified. Only that record's exact MFA claim name and value can
satisfy the second-factor check; a matching value in a different claim cannot.
Production configuration requires the issuer and audience. Unconfigured local
identity refuses console requests.

The signed `auth_time` is the original human authentication time. Missing
`auth_time` permits no step-up action, even if the access token was just renewed.
Malformed, negative, future or temporally inconsistent authentication/issuance
times are refused. A fresh token's `iat` never substitutes for `auth_time`.
Expiry/not-before validation permits the configured 60-second clock skew;
future issuance/authentication evidence is not used to extend step-up authority.

User status, credential invalidation boundary, roles and action/resource grants
are read from PostgreSQL for each request. Token role/scope claims do not grant
business authority. The five-minute activity-journal throttle does not cache
authorization. Disable and revoke through the audited administration service;
do not wait for token expiration or create another role cache.

An expired/malformed bearer receives 401. An inactive profile or missing live
business grant receives 403 with a stable refusal code. A step-up refusal uses
`STEP_UP_REQUIRED`. Inspect only the correlation identifier, stable code and
digest-based decision journal; never copy a bearer token into logs or evidence.

## Mounted secret contract

The platform supplies a read-only Linux mount containing fresh regular files.
Only the trusted platform may create, replace or rotate those files. The
application does not accept a secret value in business metadata: an opaque
`secret-ref://namespace/name` names a relative path in the configured mount.

Every component, including mount ancestors and the final filename, is traversed
through directory descriptors with `NOFOLLOW_LINKS`. A symlink is refused even
when it points elsewhere inside the mount. There is no lexical-path fallback.
This requires filesystem support for Java `SecureDirectoryStream`. Unsupported
filesystems refuse resolution. The tested macOS JDK does not supply it; run the
secret-resolution runtime contract in the supplied Linux container. Do not
weaken this boundary to make host-side secret delivery work.

Values are strict UTF-8, at most 16,384 bytes. Empty and whitespace-only values
are refused. Other whitespace is preserved exactly; no newline or spaces are
silently removed. The reader is bounded even when a file grows after its size
check. Temporary byte/character buffers are cleared on success and failure;
the adapter owns and clears the returned `char[]`. Failure logs contain fixed
events and correlation identifiers, never the value, reference, resolved path
or exception details.

The descriptor API's guarantees require OS support, as described in the
[Java 21 SecureDirectoryStream contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/SecureDirectoryStream.html).
This boundary does not protect against a trusted host administrator deliberately
replacing mounted content or provisioning an incorrect value. Platform mount
ownership/read-only delivery and actual rotation still require runtime evidence.

## Local verification

Run `make backend-check` from the repository root. This includes:

- `SignedBearerIdentityIT`: locally generated RSA signatures, production
  validators/converter/filter chain, MockMvc and real migrated PostgreSQL.
  It does not contact an issuer/JWKS service and is not a browser login test.
- `MountedSecretResolverTest`: reference grammar, exact decoding, byte bound,
  growing/failed reads, buffer clearing and diagnostics.
- `MountedSecretFilesystemIT`: 19 filesystem scenarios in the pinned Linux JRE
  image with network disabled; synthetic files only and no host secret mount.

After a full backend run, `bash scripts/verify_coverage_thresholds.sh backend`
proves that the same execution data fails at 100% coverage. It must preserve the
JAR, execution data and XML report bytes. This deliberate negative test is not
an alternative to the complete 80%/70% verification.

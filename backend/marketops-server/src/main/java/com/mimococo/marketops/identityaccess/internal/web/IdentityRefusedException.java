package com.mimococo.marketops.identityaccess.internal.web;

import com.mimococo.marketops.shared.ErrorCode;
import java.io.Serial;
import org.springframework.security.core.AuthenticationException;

/**
 * A structurally valid token that this deployment does not accept.
 *
 * <p>The exception carries only the stable error code. Nothing about the token,
 * the subject or the provider reaches the message, so the refusal can be
 * rendered and logged without a redaction step.
 */
public final class IdentityRefusedException extends AuthenticationException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;

    IdentityRefusedException(ErrorCode errorCode) {
        super(errorCode.safeMessage());
        this.errorCode = errorCode;
    }

    /** The stable code explaining the refusal. */
    public ErrorCode errorCode() {
        return errorCode;
    }
}

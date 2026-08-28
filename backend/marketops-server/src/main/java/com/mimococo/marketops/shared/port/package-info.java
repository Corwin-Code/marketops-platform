/**
 * Published contracts every module may implement against.
 *
 * <p>These are capabilities of the deployment rather than of any one business
 * area: the secret store an adapter reads a credential from is the same store
 * whether the call goes to a marketplace or to a model provider. Keeping the
 * contract here means a second adapter that needs one does not have to depend
 * on the module that happened to need it first.
 *
 * <p>Implementations live in {@code shared.internal} and are selected by
 * configuration. Nothing in this package depends on an implementation.
 */
@org.springframework.modulith.NamedInterface("port")
package com.mimococo.marketops.shared.port;

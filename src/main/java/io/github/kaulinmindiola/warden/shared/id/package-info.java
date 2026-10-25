/**
 * Identifier generation. Every identifier exposed by the API is a UUID (REQ-INT-007); new ones
 * are UUIDv7, assigned by the domain when an object is created.
 *
 * <p>Exposed to the other modules as the {@code id} named interface.
 */
@NamedInterface("id")
package io.github.kaulinmindiola.warden.shared.id;

import org.springframework.modulith.NamedInterface;

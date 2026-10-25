/**
 * Time source of the application. Business time always comes from the {@link java.time.Clock}
 * bean, never from {@code Instant.now()}, so tests can control it.
 *
 * <p>Exposed to the other modules as the {@code time} named interface.
 */
@NamedInterface("time")
package io.github.kaulinmindiola.warden.shared.time;

import org.springframework.modulith.NamedInterface;

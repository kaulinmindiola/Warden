/**
 * Security kernel shared by every module: the stateless filter chain, the rendering of 401 and
 * 403 responses, the current user, the minimum response time of enumeration-sensitive endpoints,
 * workspace roles and the workspace authorization interceptor with its membership SPI.
 *
 * <p>Exposed to the other modules as the {@code security} named interface.
 */
@NamedInterface("security")
package io.github.kaulinmindiola.warden.shared.security;

import org.springframework.modulith.NamedInterface;

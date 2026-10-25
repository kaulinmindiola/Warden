/**
 * Security kernel shared by every module: the stateless filter chain, the rendering of 401 and
 * 403 responses and, as the modules grow, the current user and workspace authorization.
 *
 * <p>Exposed to the other modules as the {@code security} named interface.
 */
@NamedInterface("security")
package io.github.kaulinmindiola.warden.shared.security;

import org.springframework.modulith.NamedInterface;

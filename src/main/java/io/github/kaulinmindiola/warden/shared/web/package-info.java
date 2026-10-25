/**
 * HTTP error contract shared by every module: the problem-type catalogue, {@link
 * io.github.kaulinmindiola.warden.shared.web.ProblemException} and the RFC 9457 rendering of
 * errors.
 *
 * <p>Exposed to the other modules as the {@code web} named interface.
 */
@NamedInterface("web")
package io.github.kaulinmindiola.warden.shared.web;

import org.springframework.modulith.NamedInterface;

/**
 * Technical kernel shared by all modules.
 *
 * <p>Responsible for:
 * <ul>
 *   <li>the HTTP error contract (RFC 9457 problem details), API conventions and pagination;
 *   <li>the current user, the tenant context, tenant transactions and the workspace
 *       authorization interceptor;
 *   <li>the transactional outbox (write side and dispatch);
 *   <li>rate limiting, the append-only audit log, cryptographic helpers, the clock and
 *       telemetry conventions.
 * </ul>
 *
 * <p>Not responsible for any business rule of the domain modules.
 *
 * <p>Admission rule: a type belongs here only if at least two modules need it and it contains
 * no domain rule. This module depends on no domain module.
 */
package io.github.kaulinmindiola.warden.shared;

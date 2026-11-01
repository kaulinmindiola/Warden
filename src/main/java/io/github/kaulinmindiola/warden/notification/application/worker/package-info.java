/**
 * Background workers of the notification module.
 *
 * <p>The only package allowed to open a system-context (cross-tenant) transaction through {@code
 * TenantTransactions.system} (SDD V04 R5, enforced by FF-03 in {@code ArchitectureTest}).
 */
package io.github.kaulinmindiola.warden.notification.application.worker;

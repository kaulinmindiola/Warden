/**
 * Reliable e-mail notifications.
 *
 * <p>Responsible for:
 * <ul>
 *   <li>turning outbox events into deliveries, one per recipient;
 *   <li>recipient settings and notification preferences;
 *   <li>templates and their rendering;
 *   <li>provider adapters, failover, retries and the dead-letter queue;
 *   <li>delivery queries and reprocessing.
 * </ul>
 *
 * <p>Not responsible for knowing users or workspaces beyond what the event payloads carry.
 * Information reaches this module only through outbox events; no other module calls it.
 *
 * <p>Public API: none besides its REST endpoints and its {@code OutboxEventHandler}
 * implementation. Allowed dependencies: {@code shared} only.
 */
package io.github.kaulinmindiola.warden.notification;

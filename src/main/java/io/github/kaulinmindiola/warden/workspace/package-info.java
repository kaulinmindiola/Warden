/**
 * Workspaces and their membership.
 *
 * <p>Responsible for:
 * <ul>
 *   <li>creating, listing and deleting workspaces;
 *   <li>memberships and roles, including the rule that a workspace never loses its last OWNER;
 *   <li>invitations: creation, replacement, revocation and acceptance;
 *   <li>resolving a user's membership for authorization, by implementing the
 *       {@code MembershipLookup} port defined in {@code shared}.
 * </ul>
 *
 * <p>Not responsible for authentication, notes or notifications.
 *
 * <p>Public API: domain events in this root package. Allowed dependencies: {@code iam} (only
 * through {@code UserDirectory}) and {@code shared}.
 */
package io.github.kaulinmindiola.warden.workspace;

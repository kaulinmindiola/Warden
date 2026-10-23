/**
 * Notes: the reference workspace-scoped resource.
 *
 * <p>Responsible for creating, reading, updating and deleting notes inside a workspace, with
 * optimistic versioning to prevent lost updates.
 *
 * <p>Not responsible for membership or role checks; they are enforced by the workspace
 * authorization in {@code shared} before any request reaches this module.
 *
 * <p>Public API: none besides its REST endpoints. Allowed dependencies: {@code shared}.
 */
package io.github.kaulinmindiola.warden.notes;

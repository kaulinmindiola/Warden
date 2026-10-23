/**
 * Identity and access management.
 *
 * <p>Responsible for:
 * <ul>
 *   <li>accounts, e-mail verification, the password policy and password hashing;
 *   <li>login, sessions, access tokens and refresh tokens, with rotation and reuse detection;
 *   <li>session revocation and password reset;
 *   <li>the authentication filter chain that populates the current user.
 * </ul>
 *
 * <p>Not responsible for workspace authorization, sending e-mail or rendering templates.
 *
 * <p>Public API: {@code UserDirectory} (read-only user facts) and domain events, both in this
 * root package. Allowed dependencies: {@code shared}.
 */
package io.github.kaulinmindiola.warden.iam;

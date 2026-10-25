/**
 * Key material and cryptographic primitives shared by every module (SDD V18).
 *
 * <p>Keys come only from the environment ({@code WARDEN_JWT_SIGNING_KEY}, {@code
 * WARDEN_JWT_KEY_ID}, {@code WARDEN_SECRET_KEYS}, {@code WARDEN_HMAC_KEY}). When the property
 * {@code warden.keys.allow-ephemeral} is true, which only the local profile and the test
 * classpath set, missing keys are generated at startup and live only in memory. The
 * {@code prod-like} profile never allows ephemeral keys (REQ-INST-002).
 *
 * <p>No key value is ever logged or included in an error message.
 *
 * <p>Exposed to the other modules as the {@code crypto} named interface.
 */
@NamedInterface("crypto")
package io.github.kaulinmindiola.warden.shared.crypto;

import org.springframework.modulith.NamedInterface;

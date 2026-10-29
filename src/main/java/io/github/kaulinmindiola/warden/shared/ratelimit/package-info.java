/**
 * Rate limiting shared by every module (SDD V09, V14-A2; ADR-014).
 *
 * <p>The port is {@link io.github.kaulinmindiola.warden.shared.ratelimit.RateLimiter}: {@code
 * check} answers whether one more request fits a policy without spending anything, and {@code
 * consume} spends one request. Each {@link io.github.kaulinmindiola.warden.shared.ratelimit.RateLimitPolicy}
 * is a token bucket whose capacity and window come from {@code warden.ratelimit.*}; the defaults
 * in {@code application.yaml} are the SRS values (DEC-005, DEC-028, DEC-031).
 *
 * <p>Buckets live in Redis, under {@code warden:rl:{policy}:{hmac(subject)}} with an expiry, so
 * every instance shares them. E-mail addresses, IPs and ids never reach Redis: the subject is
 * hashed with the HMAC key first (SDD V06, V18). Rejections are counted in the metric {@code
 * warden_ratelimit_rejections_total{policy}}.
 *
 * <p>A rejected request is answered with the {@code rate-limited} problem (429) and a {@code
 * Retry-After} header.
 *
 * <p>Exposed to the other modules as the {@code ratelimit} named interface.
 */
@NamedInterface("ratelimit")
package io.github.kaulinmindiola.warden.shared.ratelimit;

import org.springframework.modulith.NamedInterface;

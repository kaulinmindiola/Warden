/**
 * Append-only audit log of security-relevant actions (REQ-SEC-015, REQ-SEC-016).
 *
 * <p>Successes are recorded inside the caller's transaction, so they disappear if the business
 * change rolls back; failures are recorded in their own transaction, so they survive it. The
 * application database role cannot update or delete records.
 *
 * <p>Exposed to the other modules as the {@code audit} named interface.
 */
@NamedInterface("audit")
package io.github.kaulinmindiola.warden.shared.audit;

import org.springframework.modulith.NamedInterface;

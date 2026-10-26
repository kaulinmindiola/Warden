package io.github.kaulinmindiola.warden.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Minimum workspace role required by a handler method. The method's path must contain the
 * variable {@code {workspaceId}}; {@link WorkspaceAuthorizationInterceptor} resolves the caller's
 * membership in that workspace before the handler runs (SDD V07 layer L1).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresWorkspaceRole {

    /** The least privileged role allowed. */
    Role value();
}

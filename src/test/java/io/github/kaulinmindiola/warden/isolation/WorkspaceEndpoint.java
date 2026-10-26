package io.github.kaulinmindiola.warden.isolation;

import io.github.kaulinmindiola.warden.shared.security.Role;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A workspace-scoped endpoint as the isolation suite and the role matrix see it.
 *
 * @param method HTTP method
 * @param pattern path pattern exactly as the handler mapping reports it, prefix included
 * @param minimumRole the role the handler declares with {@code @RequiresWorkspaceRole}
 * @param successStatus status of a successful call by an authorized member
 * @param request builds a valid request against a target; the caller adds the token
 */
public record WorkspaceEndpoint(
        HttpMethod method, String pattern, Role minimumRole, int successStatus, RequestFactory request) {

    /**
     * What a request is aimed at. For a workspace that exists, {@link #newNote()} and {@link
     * #newMember()} create a fresh resource in it; for a random workspace id they return random ids.
     */
    public interface Target {

        UUID workspaceId();

        /** A new note in the workspace. */
        UUID newNote();

        /** A new user who is a MEMBER of the workspace. */
        UUID newMember();
    }

    /** Builds a request against a target. */
    @FunctionalInterface
    public interface RequestFactory {
        MockHttpServletRequestBuilder build(Target target);
    }

    public WorkspaceEndpoint {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(minimumRole, "minimumRole");
        Objects.requireNonNull(request, "request");
    }

    /** {@code METHOD pattern}, the identity used to match handlers. */
    public String key() {
        return method.name() + " " + pattern;
    }

    public MockHttpServletRequestBuilder requestFor(Target target) {
        return request.build(target);
    }

    /** Names parameterized test cases. */
    @Override
    public String toString() {
        return key();
    }
}

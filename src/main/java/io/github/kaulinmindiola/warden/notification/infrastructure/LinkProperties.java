package io.github.kaulinmindiola.warden.notification.infrastructure;

import io.github.kaulinmindiola.warden.notification.domain.LinkType;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The base URL of each single-use link (REQ-INT-009, DEC-053): pages of the API consumer, which
 * read the token from the fragment and post it to Warden. The defaults are the local profile
 * values (D-05, resolves TBD-09).
 *
 * <p>A base URL must be an absolute http or https URL without query and without fragment: the
 * builder appends the fragment, and the token must never reach a query string.
 *
 * @param verifyEmail the page that confirms an e-mail address
 * @param resetPassword the page that sets a new password
 * @param acceptInvitation the page that accepts a workspace invitation
 */
@ConfigurationProperties("warden.notification.links")
public record LinkProperties(
        @DefaultValue("http://localhost:3001/verify-email") URI verifyEmail,

        @DefaultValue("http://localhost:3001/reset-password")
        URI resetPassword,

        @DefaultValue("http://localhost:3001/accept-invitation")
        URI acceptInvitation) {

    public LinkProperties {
        requireBase("verify-email", verifyEmail);
        requireBase("reset-password", resetPassword);
        requireBase("accept-invitation", acceptInvitation);
    }

    /** The base URL of a link type. */
    public URI baseFor(LinkType type) {
        return switch (type) {
            case EMAIL_VERIFICATION -> verifyEmail;
            case PASSWORD_RESET -> resetPassword;
            case INVITATION -> acceptInvitation;
        };
    }

    private static void requireBase(String name, URI base) {
        String property = "warden.notification.links." + name;
        if (base == null || !base.isAbsolute() || base.getHost() == null) {
            throw new IllegalArgumentException(property + " must be an absolute URL");
        }
        if (!"http".equals(base.getScheme()) && !"https".equals(base.getScheme())) {
            throw new IllegalArgumentException(property + " must be an http or https URL");
        }
        if (base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException(property + " must have no query and no fragment");
        }
    }
}

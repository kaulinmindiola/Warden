package io.github.kaulinmindiola.warden.notification.infrastructure;

import io.github.kaulinmindiola.warden.notification.domain.LinkType;
import io.github.kaulinmindiola.warden.notification.domain.TemplateKey;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds single-use links as {@code <base URL>#token=<token>} (REQ-INT-009, DEC-053). The token
 * goes only in the fragment, which browsers do not send to servers, so it reaches neither the
 * consumer's access logs nor any proxy; never in the path or the query.
 *
 * <p>Tokens are Base64url (SDD V09) and need no encoding; anything else is rejected, and the
 * rejected value is never part of the message.
 */
public class LinkBuilder {

    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

    private final LinkProperties properties;

    public LinkBuilder(LinkProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * The link of {@code type} carrying {@code token}.
     *
     * @throws IllegalArgumentException if the token is empty or not Base64url
     */
    public String build(LinkType type, String token) {
        Objects.requireNonNull(type, "type");
        if (token == null || !BASE64URL.matcher(token).matches()) {
            throw new IllegalArgumentException("A link token must be a non-empty Base64url string");
        }
        return properties.baseFor(type) + "#token=" + token;
    }

    /**
     * The link variable of a template of {@code key}: its name and the link with {@code token}, or
     * nothing for a template without link.
     *
     * @throws IllegalArgumentException if the template carries a link and the token is missing or
     *     invalid
     */
    public Map<String, String> linkVariable(TemplateKey key, String token) {
        return LinkType.of(Objects.requireNonNull(key, "key"))
                .map(type -> Map.of(type.variable(), build(type, token)))
                .orElse(Map.of());
    }
}

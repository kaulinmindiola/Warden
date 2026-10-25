package io.github.kaulinmindiola.warden.shared.security;

import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a validated access token into a {@link CurrentUserAuthentication}. The decoder has already
 * checked that {@code sub} and {@code sid} are UUIDs.
 */
public class CurrentUserAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        CurrentUser user = new CurrentUser(
                UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString(CurrentUser.SESSION_CLAIM)));
        return new CurrentUserAuthentication(user);
    }
}

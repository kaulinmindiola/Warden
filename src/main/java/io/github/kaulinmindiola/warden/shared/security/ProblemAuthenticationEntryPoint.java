package io.github.kaulinmindiola.warden.shared.security;

import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Answers unauthenticated requests with 401 {@code unauthenticated} problem details and the
 * {@code WWW-Authenticate: Bearer} challenge of RFC 6750.
 */
@Component
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver exceptionResolver;

    ProblemAuthenticationEntryPoint(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        exceptionResolver.resolveException(request, response, null, new ProblemException(ProblemType.UNAUTHENTICATED));
    }
}

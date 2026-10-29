package io.github.kaulinmindiola.warden.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** The filter that turns a database outage escaping the filter chain into 503 (REQ-REL-007). */
class DatabaseUnavailableFilterTest {

    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final DatabaseUnavailableFilter filter = new DatabaseUnavailableFilter(resolver);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/sessions");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void aDatabaseOutageInTheChainIsAnsweredWithServiceUnavailable() throws Exception {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException(
                    "token check failed", new SQLTransientConnectionException("Connection is not available"));
        };

        filter.doFilter(request, response, failing);

        verify(resolver)
                .resolveException(
                        eq(request),
                        eq(response),
                        isNull(),
                        argThat(problem -> problem instanceof ProblemException p
                                && p.type() == ProblemType.SERVICE_UNAVAILABLE
                                && p.retryAfterSeconds().orElse(0) == 5));
    }

    @Test
    void otherFailuresAreRethrownUntouched() {
        IllegalStateException bug = new IllegalStateException("bug");
        FilterChain failing = (req, res) -> {
            throw bug;
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing)).isSameAs(bug);
        verifyNoInteractions(resolver);
    }

    @Test
    void aCommittedResponseIsNeverRewritten() throws Exception {
        IllegalStateException outage =
                new IllegalStateException("late", new SQLTransientConnectionException("Connection is not available"));
        FilterChain failing = (req, res) -> {
            res.flushBuffer();
            throw outage;
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing)).isSameAs(outage);
        verifyNoInteractions(resolver);
        assertThat(response.isCommitted()).isTrue();
    }
}

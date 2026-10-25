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
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

class RequestSizeLimitFilterTest {

    private static final int LIMIT = (int) RequestSizeLimitFilter.MAX_BODY_BYTES;

    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(resolver);

    @Test
    void aDeclaredLengthAboveTheLimitIsRejectedWithoutReadingTheBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/anything");
        request.setContent(new byte[LIMIT + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(resolver)
                .resolveException(
                        eq(request),
                        eq(response),
                        isNull(),
                        argThat(ex -> ex instanceof ProblemException problem
                                && problem.type() == ProblemType.PAYLOAD_TOO_LARGE));
        verifyNoInteractions(chain);
    }

    @Test
    void aBodyOfUnknownLengthFailsOnceItExceedsTheLimit() {
        MockHttpServletRequest request = new UnknownLengthRequest(new byte[LIMIT + 1]);
        FilterChain chain = (req, res) -> req.getInputStream().readAllBytes();

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), chain))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void aBodyOfUnknownLengthAtTheLimitIsReadCompletely() throws Exception {
        MockHttpServletRequest request = new UnknownLengthRequest(new byte[LIMIT]);
        int[] bytesRead = new int[1];
        FilterChain chain = (req, res) -> bytesRead[0] = req.getInputStream().readAllBytes().length;

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(bytesRead[0]).isEqualTo(LIMIT);
    }

    /** A request without Content-Length, as with chunked transfer encoding. */
    private static final class UnknownLengthRequest extends MockHttpServletRequest {

        UnknownLengthRequest(byte[] content) {
            super("POST", "/api/v1/anything");
            setContent(content);
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }
    }
}

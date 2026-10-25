package io.github.kaulinmindiola.warden.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Rejects request bodies larger than 64 KiB with {@code payload-too-large} (REQ-SEC-018).
 *
 * <p>A declared {@code Content-Length} above the limit is rejected before the body is read. A body
 * of unknown length (chunked) is counted while it is read and fails as soon as it exceeds the
 * limit. The error is rendered by the Spring MVC exception resolvers, so it has the same problem
 * format as every other error. Runs after the observation filter and before the security chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** Maximum request body size in bytes (64 KiB). */
    public static final long MAX_BODY_BYTES = 64L * 1024;

    private final HandlerExceptionResolver exceptionResolver;

    public RequestSizeLimitFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) {
            exceptionResolver.resolveException(
                    request, response, null, new ProblemException(ProblemType.PAYLOAD_TOO_LARGE));
            return;
        }
        HttpServletRequest effective = declaredLength < 0 ? new BoundedRequest(request, MAX_BODY_BYTES) : request;
        chain.doFilter(effective, response);
    }

    /** Request whose body fails with {@link RequestBodyTooLargeException} past the limit. */
    static final class BoundedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        BoundedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new BoundedInputStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /** Counts the bytes read from the delegate and fails once they exceed the limit. */
    static final class BoundedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        BoundedInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        private void count(long bytes) throws RequestBodyTooLargeException {
            count += bytes;
            if (count > limit) {
                throw new RequestBodyTooLargeException(limit);
            }
        }
    }
}

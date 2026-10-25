package io.github.kaulinmindiola.warden.shared.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pagination parameters of every list endpoint (REQ-INT-006): zero-based {@code page} and {@code
 * size} between 1 and 100, defaulting to 0 and 20. Bound from the query string with {@code @Valid
 * PageQuery query}; out-of-range values produce a {@code validation-error}.
 */
public record PageQuery(
        @Min(0) Integer page, @Min(1) @Max(MAX_SIZE) Integer size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (page == null) {
            page = 0;
        }
        if (size == null) {
            size = DEFAULT_SIZE;
        }
    }

    /** Only valid after the query passed validation. */
    public Pageable toPageable() {
        return PageRequest.of(page, size);
    }

    /** Only valid after the query passed validation. */
    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }
}

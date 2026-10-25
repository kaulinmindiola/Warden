package io.github.kaulinmindiola.warden.shared.web;

import java.io.IOException;

/** Raised while reading a request body of unknown length that exceeds the allowed size. */
class RequestBodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    RequestBodyTooLargeException(long limit) {
        super("Request body exceeds " + limit + " bytes");
    }
}

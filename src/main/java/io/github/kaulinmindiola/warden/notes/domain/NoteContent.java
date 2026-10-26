package io.github.kaulinmindiola.warden.notes.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Title and body of a note (REQ-FUNC-044): a title of 1 to 200 characters after removing leading
 * and trailing white space, and a body of up to 10 000 characters, kept as written. Lengths are
 * counted in Unicode code points, like PostgreSQL's {@code char_length}.
 *
 * @param title the stripped title
 * @param body the body
 */
public record NoteContent(String title, String body) {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_BODY_LENGTH = 10_000;

    public static final String REQUIRED = "is required";
    public static final String TITLE_LENGTH = "must be between 1 and " + MAX_TITLE_LENGTH + " characters";
    public static final String BODY_LENGTH = "must be at most " + MAX_BODY_LENGTH + " characters";

    /** @throws InvalidNoteException naming every invalid field */
    public NoteContent {
        Map<String, String> violations = violations(title, body);
        if (!violations.isEmpty()) {
            throw new InvalidNoteException(violations);
        }
    }

    /**
     * The content as submitted, with the title stripped.
     *
     * @throws InvalidNoteException naming every invalid field
     */
    public static NoteContent of(String rawTitle, String rawBody) {
        return new NoteContent(rawTitle == null ? null : rawTitle.strip(), rawBody);
    }

    /** Field name to reason for every invalid field, in request order; empty if all are valid. */
    public static Map<String, String> violations(String rawTitle, String body) {
        Map<String, String> violations = new LinkedHashMap<>();
        if (rawTitle == null) {
            violations.put("title", REQUIRED);
        } else {
            int length = codePoints(rawTitle.strip());
            if (length < 1 || length > MAX_TITLE_LENGTH) {
                violations.put("title", TITLE_LENGTH);
            }
        }
        if (body == null) {
            violations.put("body", REQUIRED);
        } else if (codePoints(body) > MAX_BODY_LENGTH) {
            violations.put("body", BODY_LENGTH);
        }
        return Collections.unmodifiableMap(violations);
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}

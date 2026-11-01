package io.github.kaulinmindiola.warden.shared.logging;

import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import java.util.regex.Pattern;

/**
 * Defensive masking of secrets and e-mail addresses in log text (REQ-OBS-005, SDD V19). Tokens are
 * never passed to loggers on purpose; this is the safety net for a mistake, such as an exception
 * message that quotes a value.
 *
 * <ul>
 *   <li>refresh and single-use tokens ({@code wrt_…}, {@code wot_…}) keep only their prefix;
 *   <li>JSON Web Tokens ({@code eyJ…}, the encoded {@code {"}} that starts every JWT header);
 *   <li>e-mail addresses.
 * </ul>
 */
public final class LogRedactor {

    public static final String TOKEN_MASK = "[REDACTED]";
    public static final String JWT_MASK = "[REDACTED-JWT]";
    public static final String EMAIL_MASK = "[REDACTED-EMAIL]";

    private static final Pattern OPAQUE_TOKEN = Pattern.compile("\\b("
            + Pattern.quote(SecureTokenGenerator.REFRESH_TOKEN_PREFIX)
            + "|"
            + Pattern.quote(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX)
            + ")[A-Za-z0-9_-]+");

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}(?:\\.[A-Za-z0-9_-]*){0,2}");

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    private LogRedactor() {}

    /** The text with every token and e-mail address masked; {@code null} stays {@code null}. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String redacted = OPAQUE_TOKEN.matcher(text).replaceAll("$1" + TOKEN_MASK);
        redacted = JWT.matcher(redacted).replaceAll(JWT_MASK);
        return EMAIL.matcher(redacted).replaceAll(EMAIL_MASK);
    }
}

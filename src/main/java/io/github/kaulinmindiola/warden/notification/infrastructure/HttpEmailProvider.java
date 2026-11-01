package io.github.kaulinmindiola.warden.notification.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.kaulinmindiola.warden.notification.domain.EmailMessage;
import io.github.kaulinmindiola.warden.notification.domain.EmailProvider;
import io.github.kaulinmindiola.warden.notification.domain.ProviderResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * A simulated e-mail provider reached over HTTP (REQ-INT-008, SDD V09):
 * {@code POST {baseUrl}/v1/messages} with {@code {idempotencyKey, to, subject, text}}.
 *
 * <table>
 *   <caption>Classification</caption>
 *   <tr><th>Answer</th><th>Result</th></tr>
 *   <tr><td>202</td><td>ACCEPTED</td></tr>
 *   <tr><td>422 with {@code {"code": "invalid_recipient"}}</td><td>PERMANENT_REJECTION</td></tr>
 *   <tr><td>429, 5xx, any other status, connection error, no answer in time</td>
 *       <td>TRANSIENT_FAILURE</td></tr>
 * </table>
 *
 * <p>The connection must open within the connect timeout (2 s) and the answer arrive within the
 * request timeout (5 s, REQ-REL-005). Logs name the provider and the kind of failure only, never
 * the recipient, the content or the provider's response body.
 */
public final class HttpEmailProvider implements EmailProvider, AutoCloseable {

    static final String MESSAGES_PATH = "/v1/messages";
    static final String INVALID_RECIPIENT = "invalid_recipient";

    private static final Logger log = LoggerFactory.getLogger(HttpEmailProvider.class);

    private final String name;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final JsonMapper jsonMapper;
    private final HttpClient client;

    public HttpEmailProvider(
            String name, URI baseUrl, Duration connectTimeout, Duration requestTimeout, JsonMapper jsonMapper) {
        this.name = Objects.requireNonNull(name, "name");
        String base = Objects.requireNonNull(baseUrl, "baseUrl").toString();
        this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + MESSAGES_PATH);
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ProviderResult send(EmailMessage message) {
        String body = jsonMapper.writeValueAsString(Map.of(
                "idempotencyKey", message.idempotencyKey(),
                "to", message.to(),
                "subject", message.subject(),
                "text", message.text()));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ProviderResult result = classify(response.statusCode(), response.body());
            if (result != ProviderResult.ACCEPTED) {
                log.warn("Provider {} answered {}: {}", name, response.statusCode(), result);
            }
            return result;
        } catch (IOException failure) {
            log.warn("Provider {} failed: {}", name, failure.getClass().getSimpleName());
            return ProviderResult.TRANSIENT_FAILURE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ProviderResult.TRANSIENT_FAILURE;
        }
    }

    /** The result of an HTTP answer, by the contract of SDD V09. */
    ProviderResult classify(int status, String body) {
        if (status == 202) {
            return ProviderResult.ACCEPTED;
        }
        if (status == 422 && isInvalidRecipient(body)) {
            return ProviderResult.PERMANENT_REJECTION;
        }
        return ProviderResult.TRANSIENT_FAILURE;
    }

    private boolean isInvalidRecipient(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            return INVALID_RECIPIENT.equals(
                    jsonMapper.readValue(body, ErrorBody.class).code());
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Closes the HTTP client and its connections when the application stops. */
    @Override
    public void close() {
        client.close();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String code) {}
}

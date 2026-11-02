package io.github.kaulinmindiola.warden.shared.tools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Health check of the container (REQ-AVAIL-001): exits 0 when a health endpoint answers 200 within
 * {@link #TIMEOUT}, 1 otherwise. The runtime image has no curl or wget, and the management port is
 * never published, so Compose runs this probe inside the container:
 *
 * <pre>
 * java -cp /app/BOOT-INF/classes io.github.kaulinmindiola.warden.shared.tools.HealthProbe \
 *     http://localhost:8081/actuator/health/readiness
 * </pre>
 */
public final class HealthProbe {

    /** The longest a probe waits for the connection and the answer. */
    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private HealthProbe() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: HealthProbe <health URL>");
            System.exit(2);
        }
        System.exit(isHealthy(URI.create(args[0]), TIMEOUT) ? 0 : 1);
    }

    /** Whether {@code url} answers 200 within {@code timeout}. */
    static boolean isHealthy(URI url, Duration timeout) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build()) {
            HttpRequest request =
                    HttpRequest.newBuilder(url).timeout(timeout).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException unreachable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

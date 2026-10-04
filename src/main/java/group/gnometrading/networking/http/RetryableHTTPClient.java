package group.gnometrading.networking.http;

import group.gnometrading.strings.GnomeString;
import org.agrona.concurrent.EpochClock;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.function.Supplier;

/**
 * Wraps {@link HTTPClient} with retry-on-failure and proactive idle reconnect.
 *
 * <p>Retries never risk applying a request twice: GET, PUT and DELETE retry on a dropped connection, a missing
 * response, a 429 or a 5xx; a POST retries only when it could not connect (nothing was sent) or got a 429. Other
 * 4xx responses are returned as-is. Not meant for multithreading use. Not suitable for hot-path order submission.
 */
public final class RetryableHTTPClient implements AutoCloseable {

    private static final int STATUS_TOO_MANY_REQUESTS = 429;
    private static final int STATUS_SERVER_ERROR = 500;

    private final int maxRetries;
    private final long idleThresholdMs;
    private final EpochClock clock;
    private final Supplier<HTTPClient> clientFactory;
    private final long backoffMs;

    private HTTPClient httpClient;
    private long lastRequestTimeMs;

    public RetryableHTTPClient() {
        this(builder());
    }

    RetryableHTTPClient(final RetryableHTTPClientBuilder settings) {
        this.maxRetries = settings.maxRetries;
        this.idleThresholdMs = settings.idleReconnectThresholdMs;
        this.backoffMs = settings.backoffMs;
        this.clock = settings.clock;
        this.clientFactory = settings.clientFactory;
        this.httpClient = this.clientFactory.get();
        this.lastRequestTimeMs = this.clock.time();
    }

    public static RetryableHTTPClientBuilder builder() {
        return new RetryableHTTPClientBuilder();
    }

    private void maybeReconnect(final HTTPProtocol protocol, final String host) {
        if (clock.time() - lastRequestTimeMs > idleThresholdMs) {
            try {
                httpClient.reconnect(protocol, host);
            } catch (Exception ignored) {
            }
        }
    }

    private void resetClient() {
        try {
            this.httpClient.close();
        } catch (Exception ignored) {
        }
        this.httpClient = clientFactory.get();
    }

    /**
     * Whether a failed response may be retried. With no response at all (status 0 or below) or a 5xx, the server
     * may already have acted on the request, so only idempotent methods retry; a 429 means it refused the request
     * outright, so anything may retry. Other 4xx responses would only fail again.
     */
    private static boolean shouldRetry(final int status, final boolean idempotent) {
        if (status == STATUS_TOO_MANY_REQUESTS) {
            return true;
        }
        return idempotent && (status <= 0 || status >= STATUS_SERVER_ERROR);
    }

    private void backoff(final int attempt) throws IOException {
        try {
            Thread.sleep(this.backoffMs * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while backing off before a retry");
        }
    }

    private HTTPResponse retryRequest(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final HTTPMethod method,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        maybeReconnect(protocol, host);
        final boolean idempotent = method != HTTPMethod.POST;
        IOException lastException = null;
        HTTPResponse lastResponse = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                backoff(attempt);
            }
            if (!idempotent) {
                // A POST may only be retried while nothing has been sent, so connecting is done as its own step.
                try {
                    httpClient.prepareConnection(protocol, host);
                } catch (IOException e) {
                    lastException = e;
                    resetClient();
                    continue;
                }
            }
            final HTTPResponse response;
            try {
                response = switch (method) {
                    case GET -> httpClient.get(protocol, host, path, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case DELETE -> httpClient.delete(protocol, host, path, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case POST -> httpClient.post(protocol, host, path, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case PUT -> httpClient.put(protocol, host, path, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    default -> throw new IllegalStateException("Unsupported method: " + method);
                };
            } catch (IOException e) {
                lastRequestTimeMs = clock.time();
                resetClient();
                if (!idempotent) {
                    throw e;
                }
                lastException = e;
                continue;
            }
            lastRequestTimeMs = clock.time();
            if (response.isSuccess() || !shouldRetry(response.getStatusCode(), idempotent)) {
                return response;
            }
            lastResponse = response;
            lastException = null;
        }
        if (lastException != null) {
            throw lastException;
        }
        return lastResponse;
    }

    private HTTPResponse retryRequest(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final HTTPMethod method,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        maybeReconnect(protocol, host);
        final boolean idempotent = method != HTTPMethod.POST;
        IOException lastException = null;
        HTTPResponse lastResponse = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                backoff(attempt);
            }
            if (!idempotent) {
                // A POST may only be retried while nothing has been sent, so connecting is done as its own step.
                try {
                    httpClient.prepareConnection(protocol, host);
                } catch (IOException e) {
                    lastException = e;
                    resetClient();
                    continue;
                }
            }
            final HTTPResponse response;
            try {
                response = switch (method) {
                    case GET -> httpClient.get(protocol, host, path, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case DELETE -> httpClient.delete(protocol, host, path, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case POST -> httpClient.post(protocol, host, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case PUT -> httpClient.put(protocol, host, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    default -> throw new IllegalStateException("Unsupported method: " + method);
                };
            } catch (IOException e) {
                lastRequestTimeMs = clock.time();
                resetClient();
                if (!idempotent) {
                    throw e;
                }
                lastException = e;
                continue;
            }
            lastRequestTimeMs = clock.time();
            if (response.isSuccess() || !shouldRetry(response.getStatusCode(), idempotent)) {
                return response;
            }
            lastResponse = response;
            lastException = null;
        }
        if (lastException != null) {
            throw lastException;
        }
        return lastResponse;
    }

    public void connect(final HTTPProtocol protocol, final String host) throws IOException {
        httpClient.connect(protocol, host);
    }

    // GET — String path

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // GET — GnomeString path

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, 0, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // POST — String path

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // POST — GnomeString path

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // DELETE — String path

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // DELETE — GnomeString path

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, 0, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // PUT — String path

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // PUT — GnomeString path

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return retryRequest(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    @Override
    public void close() throws Exception {
        httpClient.close();
    }
}

package group.gnometrading.networking.http;

import group.gnometrading.strings.GnomeString;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.SystemEpochClock;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Wraps {@link HTTPClient} with retry-on-failure and proactive idle reconnect.
 * Not meant for multithreading use. Not suitable for hot-path order submission.
 */
public final class RetryableHTTPClient implements AutoCloseable {

    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final long DEFAULT_IDLE_THRESHOLD_MS = 25_000;

    private final int maxRetries;
    private final long idleThresholdMs;
    private final EpochClock clock;
    private final Supplier<HTTPClient> clientFactory;

    private HTTPClient httpClient;
    private long lastRequestTimeMs;

    public RetryableHTTPClient() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_IDLE_THRESHOLD_MS, SystemEpochClock.INSTANCE);
    }

    public RetryableHTTPClient(final int maxRetries, final long idleThresholdMs, final EpochClock clock) {
        this(maxRetries, idleThresholdMs, clock, HTTPClient::new);
    }

    RetryableHTTPClient(final int maxRetries, final long idleThresholdMs, final EpochClock clock, final Supplier<HTTPClient> clientFactory) {
        this.maxRetries = maxRetries;
        this.idleThresholdMs = idleThresholdMs;
        this.clock = clock;
        this.clientFactory = clientFactory;
        this.httpClient = clientFactory.get();
        this.lastRequestTimeMs = clock.time();
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
        IOException lastException = null;
        HTTPResponse lastResponse = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                lastResponse = switch (method) {
                    case GET -> httpClient.get(protocol, host, path, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case DELETE -> httpClient.delete(protocol, host, path, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case POST -> httpClient.post(protocol, host, path, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case PUT -> httpClient.put(protocol, host, path, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    default -> throw new IllegalStateException("Unsupported method: " + method);
                };
                lastRequestTimeMs = clock.time();
                if (lastResponse.isSuccess()) {
                    return lastResponse;
                }
            } catch (IOException e) {
                lastException = e;
                lastRequestTimeMs = clock.time();
                resetClient();
            }
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
        IOException lastException = null;
        HTTPResponse lastResponse = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                lastResponse = switch (method) {
                    case GET -> httpClient.get(protocol, host, path, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case DELETE -> httpClient.delete(protocol, host, path, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case POST -> httpClient.post(protocol, host, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    case PUT -> httpClient.put(protocol, host, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
                    default -> throw new IllegalStateException("Unsupported method: " + method);
                };
                lastRequestTimeMs = clock.time();
                if (lastResponse.isSuccess()) {
                    return lastResponse;
                }
            } catch (IOException e) {
                lastException = e;
                lastRequestTimeMs = clock.time();
                resetClient();
            }
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

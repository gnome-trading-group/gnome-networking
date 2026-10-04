package group.gnometrading.networking.http;

import group.gnometrading.networking.sockets.NativeSocket;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;

public final class HTTPClientBuilder {

    boolean keepAlive = true;
    int keepAliveIdleSeconds;
    int keepAliveIntervalSeconds;
    int keepAliveProbes;
    int port;
    int readTimeoutMs = HTTPClient.DEFAULT_READ_TIMEOUT_MS;
    int responseTimeoutMs;
    int connectTimeoutMs = NativeSocket.DEFAULT_CONNECT_TIMEOUT_MS;
    int readBufferSize = HTTPSocketMessageClient.DEFAULT_HTTP_READ_BUFFER_SIZE;
    int writeBufferSize = HTTPSocketMessageClient.DEFAULT_HTTP_WRITE_BUFFER_SIZE;
    int connectionsPerHost = HTTPClient.DEFAULT_CONNECTIONS_PER_HOST;
    GnomeSocketFactory socketFactory;

    HTTPClientBuilder() {}

    /** TCP keep-alive with the kernel's default timings, which take over two hours to notice a dead peer. */
    public HTTPClientBuilder withKeepAlive(final boolean keepAlive) {
        this.keepAlive = keepAlive;
        this.keepAliveIdleSeconds = 0;
        return this;
    }

    /**
     * TCP keep-alive that probes an idle connection after {@code idleSeconds}, then every {@code intervalSeconds},
     * giving up on the peer after {@code count} unanswered probes. Catches a pooled connection that died without
     * a FIN or RST, which the liveness check before each request cannot see.
     */
    public HTTPClientBuilder withKeepAlive(final int idleSeconds, final int intervalSeconds, final int count) {
        if (idleSeconds <= 0 || intervalSeconds <= 0 || count <= 0) {
            throw new IllegalArgumentException("Keep-alive idle, interval and count must all be positive");
        }
        this.keepAlive = true;
        this.keepAliveIdleSeconds = idleSeconds;
        this.keepAliveIntervalSeconds = intervalSeconds;
        this.keepAliveProbes = count;
        return this;
    }

    /** Connects to this port instead of the protocol's default (80 or 443). */
    public HTTPClientBuilder withPort(final int port) {
        this.port = port;
        return this;
    }

    /** How long one blocking read may wait for bytes. Keep it well under the response timeout. */
    public HTTPClientBuilder withReadTimeout(final int millis) {
        this.readTimeoutMs = millis;
        return this;
    }

    /**
     * How long to wait for a whole response before giving up on the request and closing its connection. Checked
     * between reads, so it can overrun by up to the read timeout. Defaults to three read timeouts.
     */
    public HTTPClientBuilder withResponseTimeout(final int millis) {
        this.responseTimeoutMs = millis;
        return this;
    }

    /** Creates each connection's socket, e.g. a TLS factory trusting a private CA. Defaults by protocol. */
    public HTTPClientBuilder withSocketFactory(final GnomeSocketFactory factory) {
        this.socketFactory = factory;
        return this;
    }

    public HTTPClientBuilder withConnectTimeout(final int millis) {
        this.connectTimeoutMs = millis;
        return this;
    }

    /** The largest response (headers and body) a connection can hold. */
    public HTTPClientBuilder withReadBufferSize(final int bytes) {
        this.readBufferSize = bytes;
        return this;
    }

    /** The largest request (headers and body) a connection can send. */
    public HTTPClientBuilder withWriteBufferSize(final int bytes) {
        this.writeBufferSize = bytes;
        return this;
    }

    /** How many requests to one host may be in flight at once through {@link HTTPClient#send}. */
    public HTTPClientBuilder withConnectionsPerHost(final int connections) {
        this.connectionsPerHost = connections;
        return this;
    }

    public HTTPClient build() {
        return new HTTPClient(this);
    }
}

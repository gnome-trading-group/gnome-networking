package group.gnometrading.networking.websockets;

import group.gnometrading.networking.client.SocketClient;
import group.gnometrading.networking.sockets.NativeSocket;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.drafts.Draft;
import group.gnometrading.networking.websockets.drafts.RFC6455;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a {@link WebSocketClient}. Every socket setting here is kept by the client and applied again on each
 * connect, so it survives reconnects.
 */
public class WebSocketClientBuilder {

    // Shared so that every wss connection uses the same TLS context.
    private static final GnomeSocketFactory SSL_SOCKET_FACTORY = new NativeSSLSocketFactory();
    private static final GnomeSocketFactory PLAIN_SOCKET_FACTORY = new NativeSocketFactory();

    private URI uri = null;
    private Draft draft = new RFC6455();
    private GnomeSocketFactory socketFactory = null;
    private int readBufferSize = SocketClient.DEFAULT_READ_BUFFER_SIZE;
    private int writeBufferSize = SocketClient.DEFAULT_WRITE_BUFFER_SIZE;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private int connectTimeoutMs = NativeSocket.DEFAULT_CONNECT_TIMEOUT_MS;
    private int handshakeTimeoutMs = HandshakeHandler.DEFAULT_TIMEOUT_MS;
    private int readTimeoutMs = 0;
    private boolean blocking = true;
    private boolean tcpNoDelay = false;
    private boolean keepAlive = false;
    private int keepAliveIdleSeconds;
    private int keepAliveIntervalSeconds;
    private int keepAliveProbes;
    private int busyPollMicros = 0;

    public WebSocketClientBuilder withURI(URI uri) {
        this.uri = uri;
        return this;
    }

    public WebSocketClientBuilder withDraft(Draft draft) {
        this.draft = draft;
        return this;
    }

    /** Overrides the socket factory, which otherwise follows the URI: TLS for {@code wss}, plain for {@code ws}. */
    public WebSocketClientBuilder withSocketFactory(GnomeSocketFactory factory) {
        this.socketFactory = factory;
        return this;
    }

    public WebSocketClientBuilder withReadBufferSize(int readBufferSize) {
        this.readBufferSize = readBufferSize;
        return this;
    }

    public WebSocketClientBuilder withWriteBufferSize(int writeBufferSize) {
        this.writeBufferSize = writeBufferSize;
        return this;
    }

    /** Bounds each connect's TCP connect and TLS handshake. */
    public WebSocketClientBuilder withConnectTimeout(int millis) {
        this.connectTimeoutMs = millis;
        return this;
    }

    /** Bounds how long each connect waits for the server to accept the WebSocket upgrade. */
    public WebSocketClientBuilder withHandshakeTimeout(int millis) {
        if (millis <= 0) {
            throw new IllegalArgumentException("Handshake timeout must be positive, or a silent server blocks forever");
        }
        this.handshakeTimeoutMs = millis;
        return this;
    }

    /** Bounds each blocking read, so a silently dead connection cannot block a reader forever. 0 means no limit. */
    public WebSocketClientBuilder withReadTimeout(int millis) {
        this.readTimeoutMs = millis;
        return this;
    }

    /** Whether reads block (the default) or return immediately when nothing has arrived. */
    public WebSocketClientBuilder withBlocking(boolean blocking) {
        this.blocking = blocking;
        return this;
    }

    public WebSocketClientBuilder withTcpNoDelay(boolean on) {
        this.tcpNoDelay = on;
        return this;
    }

    /** TCP keep-alive with the kernel's default timings, which take over two hours to notice a dead peer. */
    public WebSocketClientBuilder withKeepAlive(boolean on) {
        this.keepAlive = on;
        this.keepAliveIdleSeconds = 0;
        return this;
    }

    /**
     * TCP keep-alive that probes after {@code idleSeconds} of silence, then every {@code intervalSeconds}, giving
     * up on the peer after {@code count} unanswered probes.
     */
    public WebSocketClientBuilder withKeepAlive(int idleSeconds, int intervalSeconds, int count) {
        if (idleSeconds <= 0 || intervalSeconds <= 0 || count <= 0) {
            throw new IllegalArgumentException("Keep-alive idle, interval and count must all be positive");
        }
        this.keepAlive = true;
        this.keepAliveIdleSeconds = idleSeconds;
        this.keepAliveIntervalSeconds = intervalSeconds;
        this.keepAliveProbes = count;
        return this;
    }

    /** Lets the kernel busy-poll for up to {@code micros} on a blocking read (Linux only). 0 turns it off. */
    public WebSocketClientBuilder withBusyPoll(int micros) {
        this.busyPollMicros = micros;
        return this;
    }

    public WebSocketClientBuilder withHeader(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    public WebSocketClient build() throws IOException {
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }

        WebSocketClient client = new WebSocketClient(uri, draft, socketFactory(), readBufferSize, writeBufferSize);
        this.headers.forEach(client::setHeader);
        client.setConnectTimeout(connectTimeoutMs);
        client.setHandshakeTimeout(handshakeTimeoutMs);
        client.setReadTimeout(readTimeoutMs);
        client.configureBlocking(blocking);
        client.setTcpNoDelay(tcpNoDelay);
        if (keepAliveIdleSeconds > 0) {
            client.setKeepAlive(keepAliveIdleSeconds, keepAliveIntervalSeconds, keepAliveProbes);
        } else {
            client.setKeepAlive(keepAlive);
        }
        client.setBusyPoll(busyPollMicros);
        return client;
    }

    GnomeSocketFactory socketFactory() {
        if (this.socketFactory != null) {
            return this.socketFactory;
        }
        return "wss".equalsIgnoreCase(this.uri.getScheme()) ? SSL_SOCKET_FACTORY : PLAIN_SOCKET_FACTORY;
    }
}

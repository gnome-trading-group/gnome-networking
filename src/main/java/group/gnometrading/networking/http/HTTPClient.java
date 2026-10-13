package group.gnometrading.networking.http;

import group.gnometrading.collections.GnomeMap;
import group.gnometrading.collections.PooledHashMap;
import group.gnometrading.networking.sockets.NativeSocket;
import group.gnometrading.networking.sockets.SocketProbe;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.strings.GnomeString;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Blocking HTTP/1.1 client over persistent connections, with an optional non-blocking {@link #send} / {@link #poll}
 * API for keeping several requests to one host in flight at once.
 *
 * <p>Each host gets up to {@code connectionsPerHost} keep-alive connections. A connection only ever carries one
 * request at a time, and is dropped whenever a response does not arrive whole, so a late or partial response can
 * never be read as the reply to a later request. Creating connections produces garbage; requests do not.
 * Not meant for multithreaded use.
 */
public class HTTPClient implements AutoCloseable {

    static final int DEFAULT_READ_TIMEOUT_MS = 10_000;
    /** Without an explicit response timeout, a response may take this many read timeouts. */
    private static final int DEFAULT_READ_TIMEOUTS_PER_RESPONSE = 3;
    public static final int DEFAULT_CONNECTIONS_PER_HOST = 1;
    /** Returned by {@link #send} when the client's {@link RequestGate} refused the request: nothing was sent. */
    public static final int SEND_REFUSED = -2;

    private final GnomeMap<String, HostConnections> hosts;
    private final HTTPResponse httpResponse;
    private final ByteBuffer body;
    private final boolean keepAlive;
    private final int keepAliveIdleSeconds;
    private final int keepAliveIntervalSeconds;
    private final int keepAliveProbes;
    private final int port;
    private final int readTimeoutMs;
    // Far enough away to never pass, yet small enough that adding it to System.nanoTime() cannot wrap around.
    private static final long NO_RESPONSE_DEADLINE_NANOS = Long.MAX_VALUE / 2;

    private final long responseTimeoutNanos;
    private final int connectTimeoutMs;
    private final GnomeSocketFactory socketFactory;
    private final int readBufferSize;
    private final int writeBufferSize;
    private final int connectionsPerHost;
    private final RequestGate requestGate;
    private final byte[][] capturedHeaders;

    public HTTPClient() {
        this(builder());
    }

    public HTTPClient(final boolean keepAlive) {
        this(builder().withKeepAlive(keepAlive));
    }

    HTTPClient(final boolean keepAlive, final int port) {
        this(builder().withKeepAlive(keepAlive).withPort(port));
    }

    HTTPClient(final boolean keepAlive, final int port, final int readTimeoutMs) {
        this(builder().withKeepAlive(keepAlive).withPort(port).withReadTimeout(readTimeoutMs));
    }

    HTTPClient(final HTTPClientBuilder settings) {
        if (settings.connectionsPerHost < 1) {
            throw new IllegalArgumentException("connectionsPerHost must be at least 1");
        }
        this.hosts = new PooledHashMap<>();
        this.capturedHeaders = settings.capturedHeaders;
        this.requestGate = settings.requestGate;
        this.httpResponse = new HTTPResponse(settings.capturedHeaders.length);
        this.body = ByteBuffer.allocate(settings.readBufferSize);
        this.keepAlive = settings.keepAlive;
        this.keepAliveIdleSeconds = settings.keepAliveIdleSeconds;
        this.keepAliveIntervalSeconds = settings.keepAliveIntervalSeconds;
        this.keepAliveProbes = settings.keepAliveProbes;
        this.port = settings.port;
        this.readTimeoutMs = settings.readTimeoutMs;
        if (settings.responseTimeoutMs > 0) {
            this.responseTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(settings.responseTimeoutMs);
        } else if (settings.readTimeoutMs > 0) {
            this.responseTimeoutNanos =
                    TimeUnit.MILLISECONDS.toNanos((long) DEFAULT_READ_TIMEOUTS_PER_RESPONSE * settings.readTimeoutMs);
        } else {
            // Reads that never time out leave nothing to derive a deadline from; three times zero would fail
            // every response that needs a second read.
            this.responseTimeoutNanos = NO_RESPONSE_DEADLINE_NANOS;
        }
        this.connectTimeoutMs = settings.connectTimeoutMs;
        this.socketFactory = settings.socketFactory;
        this.readBufferSize = settings.readBufferSize;
        this.writeBufferSize = settings.writeBufferSize;
        this.connectionsPerHost = settings.connectionsPerHost;
    }

    public static HTTPClientBuilder builder() {
        return new HTTPClientBuilder();
    }

    private HTTPResponse request(
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
            final String headerValue5
    ) throws IOException {
        final int tag = this.requestGate == null ? 0 : this.requestGate.beforeRequest(method, path);
        if (tag == RequestGate.REFUSE) {
            return this.httpResponse.refuse();
        }
        final HostConnections connections = hostConnections(host);
        final int index = idleConnection(connections, host);
        HTTPSocketMessageClient client = readyConnection(connections, index, protocol, host);
        final int bodyLength = body == null ? 0 : body.length;
        int bytesWritten;
        try {
            bytesWritten = client.request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
        } catch (IOException e) {
            bytesWritten = -1;
        }

        if (bytesWritten <= 0) {
            client = recreateConnection(connections, index, protocol, host);
            try {
                bytesWritten = client.request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
            } catch (IOException | RuntimeException e) {
                // Part of the request may be on the wire; the connection cannot carry another one.
                discardConnection(connections, index);
                throw e;
            }
        }

        return answered(tag, awaitResponse(connections, index, client, bytesWritten));
    }

    private HTTPResponse request(
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
        final int tag = this.requestGate == null ? 0 : this.requestGate.beforeRequest(method, path);
        if (tag == RequestGate.REFUSE) {
            return this.httpResponse.refuse();
        }
        final HostConnections connections = hostConnections(host);
        final int index = idleConnection(connections, host);
        HTTPSocketMessageClient client = readyConnection(connections, index, protocol, host);
        int bytesWritten;
        try {
            bytesWritten = client.request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
        } catch (IOException e) {
            bytesWritten = -1;
        }

        if (bytesWritten <= 0) {
            client = recreateConnection(connections, index, protocol, host);
            try {
                bytesWritten = client.request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
            } catch (IOException | RuntimeException e) {
                // Part of the request may be on the wire; the connection cannot carry another one.
                discardConnection(connections, index);
                throw e;
            }
        }

        return answered(tag, awaitResponse(connections, index, client, bytesWritten));
    }

    private HTTPResponse answered(final int tag, final HTTPResponse response) {
        if (this.requestGate != null) {
            this.requestGate.afterResponse(tag, response);
        }
        return response;
    }

    private HTTPResponse awaitResponse(
            final HostConnections connections,
            final int index,
            final HTTPSocketMessageClient client,
            final int bytesWritten) throws IOException {
        if (bytesWritten <= 0) {
            // Nothing was sent, so no response is coming.
            discardConnection(connections, index);
            return this.httpResponse.update(false, -1, null);
        }

        final long deadline = System.nanoTime() + this.responseTimeoutNanos;
        while (true) {
            final int message;
            try {
                message = client.readMessage();
            } catch (IOException | RuntimeException e) {
                // The read buffer may still hold the bad response, so the connection must not be reused.
                discardConnection(connections, index);
                throw e;
            }
            if (message > 0) {
                return completeResponse(connections, index, client);
            }
            if (message < 0) {
                discardConnection(connections, index);
                return this.httpResponse.update(false, message, null);
            }
            if (System.nanoTime() - deadline >= 0) {
                discardConnection(connections, index);
                return this.httpResponse.update(false, 0, null);
            }
        }
    }

    private HTTPResponse completeResponse(
            final HostConnections connections, final int index, final HTTPSocketMessageClient client) {
        final HTTPDecoder decoder = client.getHTTPDecoder();
        this.body.clear();
        if (decoder.contentLength() > 0) {
            decoder.copyBody(this.body);
        }
        // Flipped even when empty: a cleared buffer would otherwise expose the previous response's bytes.
        this.body.flip();

        connections.lastUsedNanos[index] = System.nanoTime();
        final int status = decoder.getStatus();
        this.httpResponse.update(status >= 200 && status < 300, status, this.body);
        // Before the connection can be closed: the values are read from its buffer.
        this.httpResponse.captureHeaders(decoder);

        // Bytes beyond the response mean the server sent something unasked for; whatever it is would be read as
        // the reply to the next request.
        if (decoder.shouldClose() || client.getReadBuffer().hasRemaining()) {
            discardConnection(connections, index);
        }
        return this.httpResponse;
    }

    /**
     * Opens every connection to {@code host} ahead of time, so the first requests do not pay for TCP and TLS
     * handshakes.
     */
    public void connect(final HTTPProtocol protocol, final String host) throws IOException {
        final HostConnections connections = hostConnections(host);
        for (int i = 0; i < this.connectionsPerHost; i++) {
            if (!connections.inFlight[i]) {
                readyConnection(connections, i, protocol, host);
            }
        }
    }

    /** Makes sure the connection the next request to {@code host} will use is open, connecting it if needed. */
    void prepareConnection(final HTTPProtocol protocol, final String host) throws IOException {
        final HostConnections connections = hostConnections(host);
        readyConnection(connections, idleConnection(connections, host), protocol, host);
    }

    public void reconnect(final HTTPProtocol protocol, final String host) throws IOException {
        final HostConnections connections = hostConnections(host);
        for (int i = 0; i < this.connectionsPerHost; i++) {
            if (!connections.inFlight[i]) {
                recreateConnection(connections, i, protocol, host);
            }
        }
    }

    /**
     * Replaces any idle connection to {@code host} the server has closed (or left unread bytes on), and opens
     * any that are missing. Servers drop idle keep-alive connections; calling this on a schedule moves the
     * reconnect off the request path. Costs one non-blocking syscall per healthy connection.
     */
    public void keepWarm(final HTTPProtocol protocol, final String host) throws IOException {
        connect(protocol, host);
    }

    /**
     * As {@link #keepWarm(HTTPProtocol, String)}, and also replaces any idle connection that has carried nothing for
     * longer than {@code maxIdleNanos}. A connection can be dropped silently along the way (a NAT, a proxy's idle
     * timeout) with nothing to tell this end; once it has been idle that long, only replacing it makes sure the next
     * request isn't written into a dead connection. A connection is used when a response arrives on it, or when it
     * connects. Connections with a request in flight are left alone.
     *
     * @throws IOException if a connection could not be replaced; it is left closed, to be connected by the next
     *     request
     */
    public void keepWarm(final HTTPProtocol protocol, final String host, final long maxIdleNanos) throws IOException {
        final HostConnections connections = hostConnections(host);
        final long now = System.nanoTime();
        for (int i = 0; i < this.connectionsPerHost; i++) {
            if (connections.inFlight[i]) {
                continue;
            }
            if (connections.clients[i] != null && now - connections.lastUsedNanos[i] > maxIdleNanos) {
                recreateConnection(connections, i, protocol, host);
            } else {
                readyConnection(connections, i, protocol, host);
            }
        }
    }

    /**
     * Writes a request on an idle connection to {@code host} and returns without waiting for the response.
     *
     * @return the connection carrying the request, to pass to {@link #poll}; -1 if every connection is busy;
     *     {@link #SEND_REFUSED} if the client's {@link RequestGate} refused it
     */
    public int send(
            final HTTPProtocol protocol,
            final String host,
            final HTTPMethod method,
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
        final HostConnections connections = hostConnections(host);
        final int index = connections.idleIndex();
        if (index < 0) {
            return -1;
        }
        final int tag = this.requestGate == null ? 0 : this.requestGate.beforeRequest(method, path);
        if (tag == RequestGate.REFUSE) {
            return SEND_REFUSED;
        }
        HTTPSocketMessageClient client = readyConnection(connections, index, protocol, host);
        int bytesWritten;
        try {
            bytesWritten = client.request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
        } catch (IOException e) {
            bytesWritten = -1;
        }
        if (bytesWritten <= 0) {
            client = recreateConnection(connections, index, protocol, host);
            try {
                bytesWritten = client.request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
            } catch (IOException | RuntimeException e) {
                discardConnection(connections, index);
                throw e;
            }
            if (bytesWritten <= 0) {
                discardConnection(connections, index);
                throw new IOException("Unable to write request to " + host);
            }
        }
        connections.inFlight[index] = true;
        connections.tags[index] = tag;
        return index;
    }

    /**
     * Checks, without blocking, for the response to a request from {@link #send}.
     *
     * @return the response once it has fully arrived, otherwise null. A failed response (connection closed)
     *     is returned with status -1. The returned object is reused by the next response.
     */
    public HTTPResponse poll(final String host, final int connection) throws IOException {
        final HostConnections connections = this.hosts.get(host);
        if (connections == null || !connections.inFlight[connection]) {
            throw new IllegalStateException("No request in flight on connection " + connection + " to " + host);
        }
        final HTTPSocketMessageClient client = connections.clients[connection];
        final int message;
        try {
            message = client.pollMessage();
        } catch (IOException | RuntimeException e) {
            connections.inFlight[connection] = false;
            discardConnection(connections, connection);
            throw e;
        }
        if (message == 0) {
            return null;
        }
        connections.inFlight[connection] = false;
        if (message < 0) {
            discardConnection(connections, connection);
            return answered(connections.tags[connection], this.httpResponse.update(false, -1, null));
        }
        return answered(connections.tags[connection], completeResponse(connections, connection, client));
    }

    /** Gives up on a request from {@link #send}; its connection is closed so the late response is never read. */
    public void abandon(final String host, final int connection) {
        final HostConnections connections = this.hosts.get(host);
        if (connections != null && connections.inFlight[connection]) {
            connections.inFlight[connection] = false;
            discardConnection(connections, connection);
        }
    }

    private HostConnections hostConnections(final String host) {
        HostConnections connections = this.hosts.get(host);
        if (connections == null) {
            connections = new HostConnections(this.connectionsPerHost);
            this.hosts.put(host, connections);
        }
        return connections;
    }

    private static int idleConnection(final HostConnections connections, final String host) {
        final int index = connections.idleIndex();
        if (index < 0) {
            throw new IllegalStateException("Every connection to " + host + " has a request in flight");
        }
        return index;
    }

    /** The connection at {@code index}, replaced first if the server has closed it or left bytes on it. */
    private HTTPSocketMessageClient readyConnection(
            final HostConnections connections, final int index, final HTTPProtocol protocol, final String host)
            throws IOException {
        final HTTPSocketMessageClient client = connections.clients[index];
        if (client != null
                && client.isConnected()
                && !client.getReadBuffer().hasRemaining()
                && client.probe() == SocketProbe.IDLE) {
            return client;
        }
        return recreateConnection(connections, index, protocol, host);
    }

    /**
     * Reconnects the connection at {@code index}. The client object and its buffers are kept across reconnects:
     * building them again would allocate megabytes of direct memory on the request path, freed only by a GC.
     */
    private HTTPSocketMessageClient recreateConnection(
            final HostConnections connections, final int index, final HTTPProtocol protocol, final String host)
            throws IOException {
        discardConnection(connections, index);

        HTTPSocketMessageClient client = connections.clients[index];
        if (client == null || client.protocol() != protocol) {
            final int connectPort = this.port > 0 ? this.port : HTTPSocketMessageClient.parsePort(protocol);
            client = new HTTPSocketMessageClient(
                    protocol,
                    host,
                    connectPort,
                    this.readBufferSize,
                    this.writeBufferSize,
                    this.socketFactory,
                    this.capturedHeaders);
            connections.clients[index] = client;
        }
        client.setConnectTimeout(this.connectTimeoutMs);
        try {
            client.connect();
            if (this.keepAliveIdleSeconds > 0) {
                client.setKeepAlive(this.keepAliveIdleSeconds, this.keepAliveIntervalSeconds, this.keepAliveProbes);
            } else if (this.keepAlive) {
                client.setKeepAlive(true);
            }
            client.setTcpNoDelay(true);
            client.setReadTimeout(this.readTimeoutMs);
            connections.lastUsedNanos[index] = System.nanoTime();
        } catch (IOException | RuntimeException e) {
            // A connection whose settings failed is closed rather than left open and unused.
            discardConnection(connections, index);
            throw e;
        }
        return client;
    }

    /** Closes the connection at {@code index}; the client stays in its slot to be reconnected. */
    private static void discardConnection(final HostConnections connections, final int index) {
        final HTTPSocketMessageClient client = connections.clients[index];
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
                // The connection is being thrown away; a failure to close it changes nothing.
            }
        }
    }

    private static final class HostConnections {
        final HTTPSocketMessageClient[] clients;
        final boolean[] inFlight;
        // The request gate's tag for each in-flight request from send, handed back when its response arrives.
        final int[] tags;
        // When each connection last connected or had a response arrive, for keepWarm to tell how long it's been idle.
        final long[] lastUsedNanos;

        HostConnections(final int size) {
            this.clients = new HTTPSocketMessageClient[size];
            this.inFlight = new boolean[size];
            this.tags = new int[size];
            this.lastUsedNanos = new long[size];
        }

        int idleIndex() {
            for (int i = 0; i < this.inFlight.length; i++) {
                if (!this.inFlight[i]) {
                    return i;
                }
            }
            return -1;
        }
    }

    // GET — String path

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
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
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
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
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
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
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // GET — GnomeString path

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
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
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
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
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
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
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // POST — String path

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
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
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
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
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
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
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // POST — GnomeString path

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
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
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
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
        return request(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
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
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
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
        return request(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
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
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
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
        return request(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
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
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
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
        return request(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // DELETE — String path

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
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
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
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
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
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
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // DELETE — GnomeString path

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
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
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
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
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
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
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    public HTTPResponse delete(
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
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // PUT — String path

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
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
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
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
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
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
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // PUT — GnomeString path

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
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
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
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
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
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
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
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
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
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
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
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
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
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
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    @Override
    public void close() throws Exception {
        for (final String host : this.hosts.keys()) {
            final HostConnections connections = this.hosts.get(host);
            for (int i = 0; i < connections.clients.length; i++) {
                connections.inFlight[i] = false;
                discardConnection(connections, i);
            }
        }
    }
}

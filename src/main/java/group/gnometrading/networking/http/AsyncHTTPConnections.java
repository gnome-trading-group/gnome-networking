package group.gnometrading.networking.http;

import group.gnometrading.networking.sockets.SocketProbe;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.LongSupplier;

/**
 * Keep-alive HTTP/1.1 connections to one host for a thread that must never block on the network: requests are
 * written to connections that are already open and responses are polled, so many requests are in flight at once,
 * one per connection. Opening, reopening and replacing connections is done by an {@link HTTPConnector} on another
 * thread; this class never connects.
 *
 * <p>Each connection is owned by one thread at a time, handed over through its state: the connector owns it while
 * CLOSED, CONNECTING or DEAD, the requesting thread while READY or IN_FLIGHT. The requesting thread marks a
 * connection DEAD to give it back (failed, closed by the server, or idle too long to trust) and the connector
 * reopens it. Nothing is allocated per request.
 */
public final class AsyncHTTPConnections implements AutoCloseable {

    /** Returned by {@link #acquire} when no connection is open and idle. */
    public static final int NONE = -1;

    /** The request was written whole and its completion will come from {@link #poll}. */
    public static final int SENT = 0;
    /** Nothing was written; the request can go on another connection. */
    public static final int NOT_SENT = 1;
    /** The write failed part way or with an error: the server may or may not have the request. No completion follows. */
    public static final int SEND_FAILED = 2;

    static final int CLOSED = 0;
    static final int CONNECTING = 1;
    static final int READY = 2;
    static final int IN_FLIGHT = 3;
    static final int DEAD = 4;

    private final HTTPProtocol protocol;
    private final String host;
    private final byte[] hostBytes;
    private final HTTPSocketMessageClient[] clients;
    private final AtomicIntegerArray states;
    // Written by whichever thread owns the connection, before handing it over through its state.
    private final long[] lastUsedNanos;
    private final long[] tags;
    private final long[] deadlines;
    private final HTTPRequestWriter writer = new HTTPRequestWriter();
    private final HTTPCompletion completion;
    private final ByteBuffer body;
    private final LongSupplier nanoClock;
    private final long maxIdleNanos;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int keepAliveIdleSeconds;
    private final int keepAliveIntervalSeconds;
    private final int keepAliveProbes;
    private int nextAcquire;
    private int nextPoll;
    private int inFlight;

    private AsyncHTTPConnections(final Builder settings) throws IOException {
        if (settings.connections < 1) {
            throw new IllegalArgumentException("At least one connection is needed");
        }
        this.protocol = settings.protocol;
        this.host = settings.host;
        this.hostBytes = settings.host.getBytes(StandardCharsets.US_ASCII);
        this.clients = new HTTPSocketMessageClient[settings.connections];
        final int port = settings.port > 0 ? settings.port : HTTPSocketMessageClient.parsePort(settings.protocol);
        for (int i = 0; i < settings.connections; i++) {
            this.clients[i] = new HTTPSocketMessageClient(
                    settings.protocol,
                    settings.host,
                    port,
                    settings.readBufferSize,
                    settings.writeBufferSize,
                    settings.socketFactory,
                    settings.capturedHeaders);
        }
        this.states = new AtomicIntegerArray(settings.connections);
        this.lastUsedNanos = new long[settings.connections];
        this.tags = new long[settings.connections];
        this.deadlines = new long[settings.connections];
        this.completion = new HTTPCompletion(settings.capturedHeaders.length);
        this.body = ByteBuffer.allocate(settings.readBufferSize);
        this.nanoClock = settings.nanoClock;
        this.maxIdleNanos = settings.maxIdleNanos;
        this.connectTimeoutMs = settings.connectTimeoutMs;
        this.readTimeoutMs = settings.readTimeoutMs;
        this.keepAliveIdleSeconds = settings.keepAliveIdleSeconds;
        this.keepAliveIntervalSeconds = settings.keepAliveIntervalSeconds;
        this.keepAliveProbes = settings.keepAliveProbes;
    }

    public static Builder builder(final HTTPProtocol protocol, final String host) {
        return new Builder(protocol, host);
    }

    public String host() {
        return this.host;
    }

    public int size() {
        return this.clients.length;
    }

    /** Requests written and not yet completed. */
    public int inFlight() {
        return this.inFlight;
    }

    /**
     * An open, idle connection to write the next request on, or {@link #NONE}. A connection the server has closed,
     * that holds unread bytes, or that has been idle longer than the configured limit is handed back to the
     * connector instead: a request written into it could be lost without an answer.
     */
    public int acquire() {
        final long now = this.nanoClock.getAsLong();
        final int size = this.clients.length;
        for (int k = 0; k < size; k++) {
            final int index = (this.nextAcquire + k) % size;
            if (this.states.get(index) != READY) {
                continue;
            }
            final HTTPSocketMessageClient client = this.clients[index];
            if (now - this.lastUsedNanos[index] > this.maxIdleNanos
                    || client.getReadBuffer().hasRemaining()
                    || client.probe() != SocketProbe.IDLE) {
                this.states.set(index, DEAD);
                continue;
            }
            this.nextAcquire = index + 1;
            return index;
        }
        return NONE;
    }

    /**
     * Hands connections back to the connector for replacing before a request needs them: any open one the server has
     * closed or that holds unread bytes, and the one idle longest once it has been idle {@code olderThanNanos} (less than
     * the max idle, so it is replaced before {@link #acquire} would refuse it). One found stale only at acquire costs
     * that request a new connect, longer than a request that must go now can wait, and on a quiet account every
     * connection goes stale between requests. Only one idle connection goes per call, so the rest stay ready while it is
     * replaced: connections opened together would otherwise all be replaced together, leaving none for a while. Only
     * the requesting thread calls this; it allocates nothing.
     */
    public void retireIdle(final long nowNanos, final long olderThanNanos) {
        int oldest = NONE;
        long oldestUsedNanos = 0;
        for (int index = 0; index < this.clients.length; index++) {
            if (this.states.get(index) != READY) {
                continue;
            }
            final HTTPSocketMessageClient client = this.clients[index];
            if (client.getReadBuffer().hasRemaining() || client.probe() != SocketProbe.IDLE) {
                this.states.set(index, DEAD);
                continue;
            }
            final long usedNanos = this.lastUsedNanos[index];
            if (nowNanos - usedNanos > olderThanNanos && (oldest == NONE || usedNanos - oldestUsedNanos < 0)) {
                oldest = index;
                oldestUsedNanos = usedNanos;
            }
        }
        if (oldest != NONE) {
            this.states.set(oldest, DEAD);
        }
    }

    /** How long a connection may sit unused before {@link #acquire} refuses it. */
    public long maxIdleNanos() {
        return this.maxIdleNanos;
    }

    /** Starts the request to write on {@code connection}, which {@link #acquire} returned. */
    public HTTPRequestWriter request(final int connection, final HTTPMethod method) {
        requireState(connection, READY);
        return this.writer.start(this.clients[connection].getWriteBuffer(), this.hostBytes, method);
    }

    /**
     * Sends the request written on {@code connection}.
     *
     * @param tag handed back with the request's completion
     * @param deadlineNanos when, on this object's clock, to give up waiting for the response
     * @return {@link #SENT}, {@link #NOT_SENT} or {@link #SEND_FAILED}
     */
    public int send(final int connection, final long tag, final long deadlineNanos) {
        requireState(connection, READY);
        final int written;
        try {
            written = this.clients[connection].sendEncoded();
        } catch (IOException | RuntimeException e) {
            this.states.set(connection, DEAD);
            return SEND_FAILED;
        }
        if (written <= 0) {
            this.states.set(connection, DEAD);
            return NOT_SENT;
        }
        this.tags[connection] = tag;
        this.deadlines[connection] = deadlineNanos;
        this.states.set(connection, IN_FLIGHT);
        this.inFlight++;
        return SENT;
    }

    /**
     * Checks the connections with a request in flight, without blocking, and completes at most one.
     *
     * @return true if a request completed; read it from {@link #completion()} before the next poll
     */
    public boolean poll() {
        if (this.inFlight == 0) {
            return false;
        }
        final long now = this.nanoClock.getAsLong();
        final int size = this.clients.length;
        for (int k = 0; k < size; k++) {
            final int index = (this.nextPoll + k) % size;
            if (this.states.get(index) != IN_FLIGHT) {
                continue;
            }
            final HTTPSocketMessageClient client = this.clients[index];
            int message;
            try {
                message = client.pollMessage();
            } catch (IOException | RuntimeException e) {
                message = -1;
            }
            if (message > 0) {
                this.nextPoll = index + 1;
                completeResponse(index, client, now);
                return true;
            }
            if (message < 0) {
                this.nextPoll = index + 1;
                finish(index, HTTPCompletion.FAILED);
                return true;
            }
            if (now - this.deadlines[index] >= 0) {
                this.nextPoll = index + 1;
                finish(index, HTTPCompletion.TIMED_OUT);
                return true;
            }
        }
        return false;
    }

    public HTTPCompletion completion() {
        return this.completion;
    }

    private void completeResponse(final int index, final HTTPSocketMessageClient client, final long now) {
        final HTTPDecoder decoder = client.getHTTPDecoder();
        this.body.clear();
        if (decoder.contentLength() > 0) {
            decoder.copyBody(this.body);
        }
        // Flipped even when empty: a cleared buffer would otherwise expose the previous response's bytes.
        this.body.flip();
        final int status = decoder.getStatus();
        final HTTPResponse response = this.completion.set(HTTPCompletion.RESPONSE, this.tags[index], index)
                .mutableResponse()
                .update(status >= 200 && status < 300, status, this.body);
        response.captureHeaders(decoder);
        this.lastUsedNanos[index] = now;
        this.inFlight--;
        // Bytes beyond the response would be read as the reply to the next request.
        final boolean reusable = !decoder.shouldClose() && !client.getReadBuffer().hasRemaining();
        this.states.set(index, reusable ? READY : DEAD);
    }

    private void finish(final int index, final int outcome) {
        this.completion.set(outcome, this.tags[index], index);
        this.inFlight--;
        this.states.set(index, DEAD);
    }

    private void requireState(final int connection, final int state) {
        if (this.states.get(connection) != state) {
            throw new IllegalStateException(
                    "Connection " + connection + " to " + this.host + " is in state " + this.states.get(connection));
        }
    }

    int state(final int connection) {
        return this.states.get(connection);
    }

    /** Whether the connector should (re)open {@code connection}. */
    boolean needsOpening(final int connection) {
        final int state = this.states.get(connection);
        return state == CLOSED || state == DEAD;
    }

    /** Opens {@code connection}, closing what was there. Only the connector calls this, and only when it owns it. */
    void open(final int connection) throws IOException {
        this.states.set(connection, CONNECTING);
        final HTTPSocketMessageClient client = this.clients[connection];
        closeQuietly(client);
        try {
            client.setConnectTimeout(this.connectTimeoutMs);
            client.connect();
            if (this.keepAliveIdleSeconds > 0) {
                client.setKeepAlive(this.keepAliveIdleSeconds, this.keepAliveIntervalSeconds, this.keepAliveProbes);
            } else {
                client.setKeepAlive(true);
            }
            client.setTcpNoDelay(true);
            client.setReadTimeout(this.readTimeoutMs);
        } catch (IOException | RuntimeException e) {
            closeQuietly(client);
            this.states.set(connection, CLOSED);
            throw e;
        }
        this.lastUsedNanos[connection] = this.nanoClock.getAsLong();
        this.states.set(connection, READY);
    }

    private static void closeQuietly(final HTTPSocketMessageClient client) {
        try {
            client.close();
        } catch (Exception ignored) {
            // The connection is being replaced; a failure to close it changes nothing.
        }
    }

    /** Closes every connection. Call only once the connector has stopped. */
    @Override
    public void close() {
        for (int i = 0; i < this.clients.length; i++) {
            closeQuietly(this.clients[i]);
            this.states.set(i, CLOSED);
        }
        this.inFlight = 0;
    }

    public static final class Builder {
        private final HTTPProtocol protocol;
        private final String host;
        private int port;
        private int connections = 1;
        private int readBufferSize = HTTPSocketMessageClient.DEFAULT_HTTP_READ_BUFFER_SIZE;
        private int writeBufferSize = HTTPSocketMessageClient.DEFAULT_HTTP_WRITE_BUFFER_SIZE;
        private GnomeSocketFactory socketFactory;
        private int connectTimeoutMs = 10_000;
        private int readTimeoutMs = HTTPClient.DEFAULT_READ_TIMEOUT_MS;
        private int keepAliveIdleSeconds;
        private int keepAliveIntervalSeconds;
        private int keepAliveProbes;
        private long maxIdleNanos = TimeUnit.SECONDS.toNanos(30);
        private byte[][] capturedHeaders = new byte[0][];
        private LongSupplier nanoClock = System::nanoTime;

        private Builder(final HTTPProtocol protocol, final String host) {
            this.protocol = protocol;
            this.host = host;
        }

        public Builder withPort(final int port) {
            this.port = port;
            return this;
        }

        public Builder withConnections(final int connections) {
            this.connections = connections;
            return this;
        }

        public Builder withReadBufferSize(final int bytes) {
            this.readBufferSize = bytes;
            return this;
        }

        /** The largest request (headers and body) a connection can send. */
        public Builder withWriteBufferSize(final int bytes) {
            this.writeBufferSize = bytes;
            return this;
        }

        public Builder withSocketFactory(final GnomeSocketFactory socketFactory) {
            this.socketFactory = socketFactory;
            return this;
        }

        public Builder withConnectTimeout(final int millis) {
            this.connectTimeoutMs = millis;
            return this;
        }

        /** Bounds one read of a response that is already arriving; deadlines per request are given to send. */
        public Builder withReadTimeout(final int millis) {
            this.readTimeoutMs = millis;
            return this;
        }

        public Builder withKeepAlive(final int idleSeconds, final int intervalSeconds, final int probes) {
            this.keepAliveIdleSeconds = idleSeconds;
            this.keepAliveIntervalSeconds = intervalSeconds;
            this.keepAliveProbes = probes;
            return this;
        }

        /**
         * A connection idle longer than this is replaced before use: it can be dropped silently along the way (a
         * NAT, a proxy's idle timeout) with nothing to tell this end.
         */
        public Builder withMaxIdle(final long nanos) {
            this.maxIdleNanos = nanos;
            return this;
        }

        /** Response headers to capture, as {@link HTTPClientBuilder#captureHeaders}. */
        public Builder captureHeaders(final String... names) {
            this.capturedHeaders = HTTPClientBuilder.lowercaseNames(names);
            return this;
        }

        public Builder withNanoClock(final LongSupplier nanoClock) {
            this.nanoClock = nanoClock;
            return this;
        }

        public AsyncHTTPConnections build() throws IOException {
            return new AsyncHTTPConnections(this);
        }
    }
}

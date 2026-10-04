package group.gnometrading.networking.websockets;

import group.gnometrading.networking.sockets.NativeSocket;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.websockets.drafts.Draft;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.networking.websockets.frames.DataFrame;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;

public class WebSocketClient {

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final WebSocketMessageClient messageClient;
    private final WebSocketResponse response;
    private final ByteBuffer body;

    // Socket settings belong to one connection and are lost when it closes, so they are kept here and applied
    // again on every connect.
    private int connectTimeoutMs = NativeSocket.DEFAULT_CONNECT_TIMEOUT_MS;
    private int handshakeTimeoutMs = HandshakeHandler.DEFAULT_TIMEOUT_MS;
    private int readTimeoutMs;
    private boolean blocking = true;
    private boolean tcpNoDelay;
    private boolean keepAlive;
    private int keepAliveIdleSeconds;
    private int keepAliveIntervalSeconds;
    private int keepAliveProbes;
    private int busyPollMicros;

    // A payload handed out as a view of the read buffer: the buffer's real position and limit, restored before
    // the next read.
    private ByteBuffer viewBuffer;
    private int viewFrameEnd;
    private int viewLimit;

    protected WebSocketClient(
            URI uri,
            Draft draft,
            GnomeSocketFactory socketFactory,
            int readBufferSize,
            int writeBufferSize
    ) throws IOException {
        this.messageClient = new WebSocketMessageClient(uri, draft, socketFactory, readBufferSize, writeBufferSize);
        this.response = new WebSocketResponse();
        this.body = ByteBuffer.allocate(readBufferSize);
    }

    public void connect() throws IOException {
        this.viewBuffer = null;
        this.messageClient.setConnectTimeout(this.connectTimeoutMs);
        this.messageClient.connect();
        try {
            // A server that accepts the connection but never answers the upgrade would otherwise block the
            // handshake's first read forever.
            this.messageClient.setReadTimeout(this.handshakeTimeoutMs);
            this.messageClient.sendHandshake(this.handshakeTimeoutMs);
            applySocketSettings();
        } catch (IOException | RuntimeException e) {
            // Left open, a connection that never finished upgrading would look connected to its callers.
            closeQuietly(e);
            throw e;
        }
    }

    private void closeQuietly(final Throwable failure) {
        try {
            this.messageClient.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private void applySocketSettings() throws IOException {
        this.messageClient.setReadTimeout(this.readTimeoutMs);
        this.messageClient.setTcpNoDelay(this.tcpNoDelay);
        if (this.keepAliveIdleSeconds > 0) {
            this.messageClient.setKeepAlive(this.keepAliveIdleSeconds, this.keepAliveIntervalSeconds, this.keepAliveProbes);
        } else {
            this.messageClient.setKeepAlive(this.keepAlive);
        }
        if (this.busyPollMicros > 0) {
            this.messageClient.setBusyPoll(this.busyPollMicros);
        }
        // Last, because the handshake above needs a blocking socket.
        this.messageClient.configureBlocking(this.blocking);
    }

    public void close() throws Exception {
        this.messageClient.close();
    }

    public boolean isConnected() {
        return this.messageClient.isConnected();
    }

    public void reconnect() throws Exception {
        this.close();
        this.connect();
    }

    /** Applies now if connected, and after every later connect. */
    public void configureBlocking(final boolean blocking) throws IOException {
        this.blocking = blocking;
        if (this.messageClient.isConnected()) {
            this.messageClient.configureBlocking(blocking);
        }
    }

    public void wrapPingMessage(final ByteBuffer output) {
        this.messageClient.encoder().wrap(output).encode(Opcode.PING, EMPTY);
    }

    public boolean writePingMessage() throws IOException {
        return send(Opcode.PING, EMPTY);
    }

    public void wrapPongMessage(final ByteBuffer output) {
        this.messageClient.encoder().wrap(output).encode(Opcode.PONG, EMPTY);
    }

    public boolean writePongMessage() throws IOException {
        return send(Opcode.PONG, EMPTY);
    }

    public void wrapMessage(final ByteBuffer output, final Opcode opcode, final ByteBuffer payload) {
        this.messageClient.encoder().wrap(output).encode(opcode, payload);
    }

    public boolean writeMessage(final Opcode opcode, final ByteBuffer payload) throws IOException {
        return send(opcode, payload);
    }

    public void wrapMessage(final ByteBuffer output, final Opcode opcode) {
        this.messageClient.encoder().wrap(output).encode(opcode, EMPTY);
    }

    public boolean writeMessage(final Opcode opcode) throws IOException {
        return send(opcode, EMPTY);
    }

    public boolean writeBuffer(final ByteBuffer buffer) throws IOException {
        return this.messageClient.writeBuffer(buffer) > 0;
    }

    private boolean send(final Opcode opcode, final ByteBuffer buffer) throws IOException {
        return this.messageClient.writeWebSocketMessage(opcode, buffer) > 0;
    }

    public WebSocketResponse read() throws IOException {
        return read(this.messageClient.getReadBuffer());
    }

    /**
     * Reads the next frame. A TEXT or BINARY payload is a view straight into {@code buffer} rather than a copy, so it
     * is only valid until the next call to {@code read}.
     */
    public WebSocketResponse read(final ByteBuffer buffer) throws IOException {
        restoreView();
        int message = this.messageClient.readMessage(buffer);
        if (message <= 0) {
            return this.response.update(false, null, null, message < 0);
        }

        final DataFrame frame = this.messageClient.readFrame;
        if (frame.isFragment()) {
            throw new IllegalStateException("Sorry, I haven't implemented fragments yet.");
        }

        final Opcode opcode = frame.getOpcode();
        if (opcode == Opcode.CLOSING) {
            this.body.clear().flip();
            return this.response.update(true, opcode, this.body, true);
        }
        return this.response.update(true, opcode, payload(buffer, frame, opcode), false);
    }

    private ByteBuffer payload(final ByteBuffer buffer, final DataFrame frame, final Opcode opcode) {
        // Control frames (at most 125 bytes, and rare) are copied so callers may treat them as a buffer of their own,
        // e.g. rewinding a ping payload to echo it. Servers never mask, but a masked payload needs unmasking anyway.
        if (frame.isMasked() || (opcode != Opcode.TEXT && opcode != Opcode.BINARY)) {
            this.body.clear();
            frame.copyPayloadData(this.body);
            this.body.flip();
            return this.body;
        }

        this.viewBuffer = buffer;
        this.viewFrameEnd = buffer.position();
        this.viewLimit = buffer.limit();
        final int payloadStart = frame.payloadOffset();
        buffer.limit(payloadStart + frame.payloadLength());
        buffer.position(payloadStart);
        return buffer;
    }

    private void restoreView() {
        if (this.viewBuffer != null) {
            this.viewBuffer.limit(this.viewLimit);
            this.viewBuffer.position(this.viewFrameEnd);
            this.viewBuffer = null;
        }
    }

    public void reset() {
        this.viewBuffer = null;
        this.messageClient.clearBuffers();
    }

    public void setHeader(String key, String value) {
        this.messageClient.setHeader(key, value);
    }

    /** Applies now if connected, and after every later connect. */
    public void setKeepAlive(boolean on) throws IOException {
        this.keepAlive = on;
        this.keepAliveIdleSeconds = 0;
        if (this.messageClient.isConnected()) {
            this.messageClient.setKeepAlive(on);
        }
    }

    /**
     * Turns on TCP keep-alive and tunes how fast a dead peer is noticed: after {@code idleSeconds} of silence,
     * then every {@code intervalSeconds}, giving up after {@code count} unanswered probes. Applies now if
     * connected, and after every later connect.
     */
    public void setKeepAlive(int idleSeconds, int intervalSeconds, int count) throws IOException {
        if (idleSeconds <= 0 || intervalSeconds <= 0 || count <= 0) {
            throw new IllegalArgumentException("Keep-alive idle, interval and count must all be positive");
        }
        this.keepAlive = true;
        this.keepAliveIdleSeconds = idleSeconds;
        this.keepAliveIntervalSeconds = intervalSeconds;
        this.keepAliveProbes = count;
        if (this.messageClient.isConnected()) {
            this.messageClient.setKeepAlive(idleSeconds, intervalSeconds, count);
        }
    }

    /** Applies now if connected, and after every later connect. */
    public void setTcpNoDelay(boolean on) throws IOException {
        this.tcpNoDelay = on;
        if (this.messageClient.isConnected()) {
            this.messageClient.setTcpNoDelay(on);
        }
    }

    /**
     * Bounds every blocking read, so a read on a silently dead connection returns no message instead of blocking
     * until the kernel notices. 0 means no limit. Applies now if connected, and after every later connect.
     */
    public void setReadTimeout(int millis) throws IOException {
        this.readTimeoutMs = millis;
        if (this.messageClient.isConnected()) {
            this.messageClient.setReadTimeout(millis);
        }
    }

    /** Bounds the TCP connect and TLS handshake of later connects. */
    public void setConnectTimeout(int millis) {
        this.connectTimeoutMs = millis;
    }

    /** Bounds how long later connects wait for the server to accept the WebSocket upgrade. Must be positive. */
    public void setHandshakeTimeout(int millis) {
        if (millis <= 0) {
            throw new IllegalArgumentException("Handshake timeout must be positive, or a silent server blocks forever");
        }
        this.handshakeTimeoutMs = millis;
    }

    /**
     * Lets the kernel busy-poll for up to {@code micros} on a blocking read (Linux only; elsewhere connecting
     * fails). 0 turns it off. Applies now if connected, and after every later connect.
     */
    public void setBusyPoll(int micros) throws IOException {
        final boolean wasOn = this.busyPollMicros > 0;
        this.busyPollMicros = micros;
        // Setting 0 on a socket that never had busy polling is skipped, as platforms without it reject any value.
        if (this.messageClient.isConnected() && (micros > 0 || wasOn)) {
            this.messageClient.setBusyPoll(micros);
        }
    }
}

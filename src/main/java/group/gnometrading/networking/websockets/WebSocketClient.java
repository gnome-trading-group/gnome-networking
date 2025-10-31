package group.gnometrading.networking.websockets;

import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.websockets.drafts.Draft;
import group.gnometrading.networking.websockets.enums.Opcode;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;

public class WebSocketClient {

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final WebSocketMessageClient messageClient;
    private final WebSocketResponse response;
    private final ByteBuffer body;

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
        this.messageClient.connect();
        this.messageClient.sendHandshake();
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

    public void configureBlocking(final boolean blocking) throws IOException {
        this.messageClient.configureBlocking(blocking);
    }

    public void wrapPingMessage(final ByteBuffer output) {
        this.messageClient.writeFrame.wrap(output).encode(Opcode.PING, EMPTY);
    }

    public boolean writePingMessage() throws IOException {
        return send(Opcode.PING, EMPTY);
    }

    public void wrapPongMessage(final ByteBuffer output) {
        this.messageClient.writeFrame.wrap(output).encode(Opcode.PONG, EMPTY);
    }

    public boolean writePongMessage() throws IOException {
        return send(Opcode.PONG, EMPTY);
    }

    public void wrapMessage(final ByteBuffer output, final Opcode opcode, final ByteBuffer payload) {
        this.messageClient.writeFrame.wrap(output).encode(opcode, payload);
    }

    public boolean writeMessage(final Opcode opcode, final ByteBuffer payload) throws IOException {
        return send(opcode, payload);
    }

    public void wrapMessage(final ByteBuffer output, final Opcode opcode) {
        this.messageClient.writeFrame.wrap(output).encode(opcode, EMPTY);
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

    public WebSocketResponse read(final ByteBuffer buffer) throws IOException {
        int message = this.messageClient.readMessage(buffer);
        if (message <= 0) {
            return this.response.update(false, null, null, message < 0);
        }

        final Opcode opcode = this.messageClient.readFrame.getOpcode();
        this.body.clear();
        if (opcode == Opcode.BINARY || opcode == Opcode.TEXT) {
            this.messageClient.readFrame.copyPayloadData(this.body);
            this.body.flip();
        } else if (opcode == Opcode.CLOSING) {
            return this.response.update(true, opcode, this.body, true);
        }

        if (this.messageClient.readFrame.isFragment()) {
            throw new IllegalStateException("Sorry, I haven't implemented fragments yet.");
        }

        return this.response.update(true, opcode, this.body, false);
    }

    public void reset() {
        this.messageClient.clearBuffers();
    }

    public void setKeepAlive(boolean on) throws IOException {
        this.messageClient.setKeepAlive(on);
    }

    public void setTcpNoDelay(boolean on) throws IOException {
        this.messageClient.setTcpNoDelay(on);
    }

}

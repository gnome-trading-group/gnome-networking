package group.gnometrading.networking.websockets;

import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.websockets.drafts.Draft;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import group.gnometrading.networking.websockets.enums.Opcode;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;

public class WebSocketClient {

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final WebSocketMessageClient messageClient;
    private final WebSocketResponse response;
    private final ByteBuffer body;

    public WebSocketClient(final URI uri) throws IOException {
        this(uri, new RFC6455());
    }

    public WebSocketClient(final URI uri, final Draft draft) throws IOException {
        this(uri, draft, GnomeSocketFactory.getDefault());
    }

    public WebSocketClient(final URI uri, final GnomeSocketFactory socketFactory) throws IOException {
        this(uri, new RFC6455(), socketFactory);
    }

    public WebSocketClient(final URI uri, final Draft draft, final GnomeSocketFactory socketFactory) throws IOException {
        this.messageClient = new WebSocketMessageClient(uri, draft, socketFactory);
        this.response = new WebSocketResponse();
        this.body = ByteBuffer.allocate(WebSocketMessageClient.DEFAULT_READ_BUFFER_SIZE);
    }

    public void connect() throws IOException {
        this.messageClient.connect();
        this.messageClient.configureBlocking(false);
    }

    private boolean pong() throws IOException {
        return send(Opcode.PONG);
    }

    public boolean ping() throws IOException {
        return send(Opcode.PING);
    }

    public boolean send(final ByteBuffer buffer) throws IOException {
        return send(Opcode.BINARY, buffer);
    }

    public boolean send(final Opcode opcode) throws IOException {
        return send(opcode, EMPTY);
    }

    public boolean send(final Opcode opcode, final ByteBuffer buffer) throws IOException {
        return this.messageClient.writeMessage(opcode, buffer) > 0;
    }

    public WebSocketResponse read() throws IOException {
        int message = this.messageClient.readMessage();
        if (message <= 0) {
            return this.response.update(false, null, null, message < 0);
        }

        final Opcode opcode = this.messageClient.frame.getOpcode();
        this.body.clear();
        if (opcode == Opcode.BINARY || opcode == Opcode.TEXT) {
            this.messageClient.frame.copyPayloadData(this.body);
            this.body.flip();
        }

        if (opcode == Opcode.PING) {
            pong();
        }

        if (this.messageClient.frame.isFragment()) {
            throw new IllegalStateException("Sorry, I haven't implemented fragments yet.");
        }

        return this.response.update(true, opcode, this.body, false);
    }

}

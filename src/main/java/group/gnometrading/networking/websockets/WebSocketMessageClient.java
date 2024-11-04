package group.gnometrading.networking.websockets;

import group.gnometrading.networking.client.AbstractSocketMessageClient;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.websockets.drafts.Draft;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.networking.websockets.frames.DataFrame;
import sun.nio.ch.IOStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;

class WebSocketMessageClient extends AbstractSocketMessageClient {

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_WSS_PORT = 443;

    private final URI uri;
    private final Draft draft;
    public final DataFrame frame;

    public WebSocketMessageClient(
            URI uri,
            Draft draft
    ) throws IOException {
        super(parseURI(uri), GnomeSocketFactory.getDefault(), DEFAULT_READ_BUFFER_SIZE, DEFAULT_WRITE_BUFFER_SIZE, false, false);
        this.draft = draft;
        this.uri = uri;
        this.frame = this.draft.getDataFrame();
    }

    private static InetSocketAddress parseURI(URI uri) {
        int port = uri.getPort() == -1 ? (uri.getScheme().equals("wss") ? DEFAULT_WSS_PORT : DEFAULT_PORT) : uri.getPort();
        return new InetSocketAddress(uri.getHost(), port);
    }

    @Override
    public void connect() throws IOException {
        super.connect();

        HandshakeInput input = new HandshakeInput(this.uri);
        HandshakeHandler.attemptHandshake(this, this.draft, input);
    }

    public int writeMessage(Opcode opcode, ByteBuffer buffer) throws IOException {
        this.writeBuffer.clear();
        this.frame.wrap(this.writeBuffer).encode(opcode, buffer);
        this.writeBuffer.flip();

        return IOStatus.normalize(this.socket.write(this.writeBuffer));
    }

    @Override
    public boolean isCompleteMessage() {
        this.frame.wrap(this.readBuffer);
        final boolean complete = !this.frame.isIncomplete();
        if (complete) {
            // Drafts do not consume the bytes in the buffer. Maybe fix that in the future?
            this.readBuffer.position(this.readBuffer.position() + this.frame.length());
        }
        return complete;
    }
}

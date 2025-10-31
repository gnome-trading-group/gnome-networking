package group.gnometrading.networking.websockets;

import group.gnometrading.networking.client.AbstractSocketMessageClient;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.websockets.drafts.Draft;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.networking.websockets.frames.DataFrame;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;

class WebSocketMessageClient extends AbstractSocketMessageClient {

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_WSS_PORT = 443;

    private final URI uri;
    private final Draft draft;
    public final DataFrame readFrame, writeFrame;

    WebSocketMessageClient(
            URI uri,
            Draft draft,
            GnomeSocketFactory socketFactory,
            int readBufferSize,
            int writeBufferSize
    ) throws IOException {
        super(parseURI(uri), socketFactory, readBufferSize, writeBufferSize);
        this.draft = draft;
        this.uri = uri;
        this.readFrame = this.draft.createDataFrame();
        this.writeFrame = this.draft.createDataFrame();
    }

    private static InetSocketAddress parseURI(URI uri) {
        int port = uri.getPort() == -1 ? (uri.getScheme().equals("wss") ? DEFAULT_WSS_PORT : DEFAULT_PORT) : uri.getPort();
        return new InetSocketAddress(uri.getHost(), port);
    }
    
    protected void sendHandshake() {
        HandshakeInput input = new HandshakeInput(this.uri);
        HandshakeHandler.attemptHandshake(this, this.draft, input);
    }

    public int writeBuffer(ByteBuffer buffer) throws IOException {
        return this.socket.write(buffer);
    }

    public int writeWebSocketMessage(Opcode opcode, ByteBuffer buffer) throws IOException {
        this.writeBuffer.clear();
        this.writeFrame.wrap(this.writeBuffer).encode(opcode, buffer);
        this.writeBuffer.flip();

        return this.socket.write(this.writeBuffer);
    }

    @Override
    public boolean isCompleteMessage(final ByteBuffer byteBuffer) {
        this.readFrame.wrap(byteBuffer);
        final boolean complete = !this.readFrame.isIncomplete();
        if (complete) {
            byteBuffer.position(byteBuffer.position() + this.readFrame.length());
        } else if (this.readFrame.hasCompleteHeader() && byteBuffer.capacity() < this.readFrame.length()) {
            throw new RuntimeException("Read buffer overflowed. Capacity bytes " + byteBuffer.capacity() +
                    " and needed " + this.readFrame.length() + " bytes");
        }
        return complete;
    }
}

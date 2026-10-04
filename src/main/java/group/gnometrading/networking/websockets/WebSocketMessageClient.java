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
import java.util.HashMap;
import java.util.Map;

class WebSocketMessageClient extends AbstractSocketMessageClient {

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_WSS_PORT = 443;

    private final URI uri;
    private final Draft draft;
    public final DataFrame readFrame;
    // The reader thread (pongs) and others (pings, subscriptions) encode frames at the same time, and a frame
    // object holds the buffer it writes into, so each thread gets its own.
    private final ThreadLocal<DataFrame> encoders;
    private final DataFrame writeFrame;
    private Map<String, String> headers = null;

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
        this.encoders = ThreadLocal.withInitial(draft::createDataFrame);
    }

    private static InetSocketAddress parseURI(URI uri) {
        int port = uri.getPort() == -1 ? ("wss".equalsIgnoreCase(uri.getScheme()) ? DEFAULT_WSS_PORT : DEFAULT_PORT) : uri.getPort();
        return new InetSocketAddress(uri.getHost(), port);
    }
    
    void setHeader(String key, String value) {
        if (this.headers == null) {
            this.headers = new HashMap<>();
        }
        this.headers.put(key, value);
    }

    protected void sendHandshake(int timeoutMillis) {
        HandshakeInput input = new HandshakeInput(this.uri, this.headers);
        HandshakeHandler.attemptHandshake(this, this.draft, input, timeoutMillis);
    }

    /** Synchronized with {@link #writeWebSocketMessage}, so frames sent from two threads never interleave. */
    public synchronized int writeBuffer(ByteBuffer buffer) throws IOException {
        return this.socket.write(buffer);
    }

    /** A frame encoder owned by the calling thread. */
    DataFrame encoder() {
        return this.encoders.get();
    }

    /** Synchronized because the write buffer and its encoder are shared by every thread that sends directly. */
    public synchronized int writeWebSocketMessage(Opcode opcode, ByteBuffer buffer) throws IOException {
        this.writeBuffer.clear();
        this.writeFrame.wrap(this.writeBuffer).encode(opcode, buffer);
        this.writeBuffer.flip();

        return this.socket.write(this.writeBuffer);
    }

    @Override
    public boolean isCompleteMessage(final ByteBuffer byteBuffer) throws IOException {
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

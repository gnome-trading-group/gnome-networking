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
import java.nio.charset.StandardCharsets;

public class WebSocketMessageClient extends AbstractSocketMessageClient {

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_WSS_PORT = 443;

    private final URI uri;
    private final Draft draft;
    public final DataFrame frame;

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
        this.frame = this.draft.getDataFrame();
    }

    private static InetSocketAddress parseURI(URI uri) {
        int port = uri.getPort() == -1 ? (uri.getScheme().equals("wss") ? DEFAULT_WSS_PORT : DEFAULT_PORT) : uri.getPort();
        return new InetSocketAddress(uri.getHost(), port);
    }

    @Override
    public void connect() throws IOException {
        if (!this.socket.isConnected()) { // Some sockets auto-connect (NetSockets)
            super.connect();
        }

        HandshakeInput input = new HandshakeInput(this.uri);
        HandshakeHandler.attemptHandshake(this, this.draft, input);
    }

    public int writeMessage(Opcode opcode, ByteBuffer buffer) throws IOException {
        this.writeBuffer.clear();
        this.frame.wrap(this.writeBuffer).encode(opcode, buffer);
        this.writeBuffer.flip();

        return IOStatus.normalize(this.socket.write(this.writeBuffer));
    }

    public void print() {
        System.out.println(this.readBuffer);
        if (this.frame.length() > 10_000) {
            int originalPos = this.readBuffer.position();
            System.out.println(StandardCharsets.UTF_8.decode(this.readBuffer));
            this.readBuffer.position(originalPos);
            System.out.println(toHex(this.readBuffer));
        }
    }

    public static String toHex(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }

        StringBuilder hexString = new StringBuilder();
        int position = buffer.position();

        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            hexString.append(String.format("%02x", b));
            if (buffer.hasRemaining()) {
                hexString.append(" ");
            }
        }
        buffer.position(position);
        return hexString.toString();
    }

    @Override
    public boolean isCompleteMessage() {
        this.frame.wrap(this.readBuffer);
        final boolean complete = !this.frame.isIncomplete();
        if (complete) {
            this.print();
            // Drafts do not consume the bytes in the buffer. Maybe fix that in the future?
            this.readBuffer.position(this.readBuffer.position() + this.frame.length());
        } else if (this.frame.hasCompleteHeader() && this.readBuffer.capacity() < this.frame.length()) {
            throw new RuntimeException("Read buffer overflowed. Capacity bytes " + this.readBuffer.capacity() +
                    " and needed " + this.frame.length() + " bytes");
        }
        return complete;
    }
}

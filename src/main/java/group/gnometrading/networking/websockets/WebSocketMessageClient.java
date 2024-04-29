package group.gnometrading.networking.websockets;

import group.gnometrading.networking.client.AbstractSocketMessageClient;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.websockets.drafts.Draft;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.networking.websockets.frames.DataFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.IOStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;

public class WebSocketMessageClient extends AbstractSocketMessageClient {

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_WSS_PORT = 443;

    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageClient.class);

    private final URI uri;
    private final Draft draft;
    private final DataFrame frame;
    private final ByteBuffer payloadBuffer;

    public WebSocketMessageClient(
            URI uri,
            Draft draft
    ) throws IOException {
        super(parseURI(uri), GnomeSocketFactory.getDefault(), DEFAULT_READ_BUFFER_SIZE, DEFAULT_WRITE_BUFFER_SIZE, false, false);
        this.draft = draft;
        this.uri = uri;
        this.frame = this.draft.getDataFrame();
        this.payloadBuffer = ByteBuffer.allocate(1 << 13);
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

    private int writeMessage(Opcode opcode, ByteBuffer buffer) throws IOException {
        this.writeBuffer.clear();
        DataFrame encoder = draft.getDataFrame().wrap(this.writeBuffer);
        encoder.encode(opcode, buffer);
        this.writeBuffer.flip();

        return IOStatus.normalize(this.socket.write(this.writeBuffer));
    }

    @Override
    public ByteBuffer readMessage() throws IOException {
        ByteBuffer message = super.readMessage();
        if (message.remaining() == 0) {
            return message;
        }

        this.frame.wrap(message);

        if (this.frame.isFragment()) {
            throw new IllegalStateException("Sorry, I haven't implemented fragments yet.");
        }

        this.readBuffer.position(this.readBuffer.position() + this.frame.length());
        this.payloadBuffer.clear();
        return switch (this.frame.getOpcode()) {
            case TEXT, BINARY -> {
                this.frame.copyPayloadData(this.payloadBuffer);
                yield this.payloadBuffer.flip();
            }
            case CLOSING -> {
                logger.trace("Close received from server");
                yield EMPTY_BUFFER;
            }
            case PING -> {
                pong();
                yield readMessage();
            }
            case PONG -> {
                logger.trace("Pong received from server");
                yield readMessage();
            }
            default -> throw new IllegalStateException("Unhandled opcode: " + this.frame.getOpcode());
        };
    }

    @Override
    public int writeMessage(ByteBuffer message) throws IOException {
        return writeMessage(Opcode.BINARY, message);
    }

    public void ping() throws IOException {
        writeMessage(Opcode.PING, EMPTY_BUFFER);
    }

    private void pong() throws IOException {
        writeMessage(Opcode.PONG, EMPTY_BUFFER);
    }

    @Override
    public boolean isCompleteMessage(ByteBuffer directBuffer) {
        this.frame.wrap(directBuffer);
        return !this.frame.isIncomplete();
    }
}

package group.gnometrading.networking.websockets.drafts;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.networking.websockets.HandshakeInput;
import group.gnometrading.networking.websockets.enums.HandshakeState;
import group.gnometrading.networking.websockets.frames.DataFrame6455;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.random.RandomGenerator;

public class RFC6455 extends Draft {

    private static final String TEMPLATE;
    private static final String PROTOCOL = "HTTP/1.1 101";
    private static final String DEFAULT_PATH = "/";
    private static final ThreadLocal<ByteBuffer> ENCODING = ThreadLocal.withInitial(() -> ByteBuffer.allocate(16));

    static {
        StringBuilder builder = new StringBuilder();

        builder.append("GET %s HTTP/1.1\r\n");
        builder.append("Host: %s\r\n");
        builder.append("Upgrade: websocket\r\n");
        builder.append("Connection: Upgrade\r\n");
        builder.append("Sec-WebSocket-Key: %s\r\n");
        builder.append("Sec-WebSocket-Version: 13\r\n");
        builder.append("\r\n");

        TEMPLATE = builder.toString();
    }

    public RFC6455() {
        super(new DataFrame6455());
    }

    @VisibleForTesting
    public RFC6455(RandomGenerator randomGenerator) {
        super(new DataFrame6455(), randomGenerator);
    }

    @Override
    public byte[] createHandshake(HandshakeInput input) {
        secureRandom.nextBytes(ENCODING.get().array());

        String websocketKey = Base64.getEncoder().encodeToString(ENCODING.get().array());
        String path = DEFAULT_PATH;
        if (!input.uri.getPath().isEmpty()) {
            path = input.uri.getPath();
        }
        return String.format(TEMPLATE, path, input.uri.getHost(), websocketKey).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public HandshakeState parseHandshake(ByteBuffer buffer) {
        int index = 0;

        // Checking for protocol first
        while (index < Math.min(buffer.remaining(), PROTOCOL.length())) {
            if (PROTOCOL.charAt(index) != buffer.get(index)) {
                return HandshakeState.INVALID_PROTOCOL;
            }

            index++;
        }

        if (index < PROTOCOL.length()) {
            return HandshakeState.INCOMPLETE;
        }

        while (index < buffer.remaining() && buffer.get(index++) != '\n');

        int headers = 0; // header bitmap, should be 0b111
        StringBuilder builder = new StringBuilder();

        while (index < buffer.remaining()) {
            if (buffer.get(index) != '\n') {
                builder.append(Character.toLowerCase((char) buffer.get(index)));
                index++;
                continue;
            }

            index++;
            // We are at a \r, check if there's a header
            if (builder.indexOf("upgrade: websocket") != -1) {
                headers |= 0b1;
            } else if (builder.indexOf("connection: upgrade") != -1) {
                headers |= 0b10;
            } else if (builder.indexOf("sec-websocket-accept") != -1) { // Hmm.. should we verify this? Nah
                headers |= 0b100;
            } else if (builder.length() == 1 && builder.charAt(0) == '\r') {
                break; // We've reached the end
            }
            builder.setLength(0);
        }

        if (headers != 0b111) {
            return HandshakeState.INCOMPLETE;
        }

        buffer.position(buffer.position() + index); // Consume the bytes used
        return HandshakeState.MATCHED;
    }
}

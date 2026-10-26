package group.gnometrading.networking.websockets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HandshakeHandlerTest {

    private static ByteBuffer response(String text) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
        buffer.put(text.getBytes(StandardCharsets.ISO_8859_1)).flip();
        return buffer;
    }

    @Test
    void aRejectionReportsTheStatusLineAndBody() {
        String rejection = HandshakeHandler.rejection(response(
                "HTTP/1.1 401 Unauthorized\r\nContent-Length: 27\r\n\r\n{\"error\":\"invalid api key\"}"));

        assertEquals("HTTP/1.1 401 Unauthorized body: {\"error\":\"invalid api key\"}", rejection);
    }

    @Test
    void withoutAllTheHeadersOnlyTheStatusLineIsReported() {
        assertEquals("HTTP/1.1 451", HandshakeHandler.rejection(response("HTTP/1.1 451\r\nContent-Ty")));
    }

    @Test
    void theBodyIsCutAndMadePrintable() {
        String body = "\u0001x".repeat(400);
        String rejection = HandshakeHandler.rejection(response("HTTP/1.1 403 Forbidden\r\n\r\n" + body));

        String reported = rejection.substring("HTTP/1.1 403 Forbidden body: ".length());
        assertEquals(HandshakeHandler.MAX_BODY_BYTES, reported.length());
        assertTrue(reported.startsWith(".x.x"));
    }
}

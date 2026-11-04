package group.gnometrading.networking.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.strings.ViewString;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HTTPRequestWriterTest {

    private static final byte[] HOST = ascii("api.example.com");

    private final ByteBuffer buffer = ByteBuffer.allocateDirect(2048);
    private final HTTPRequestWriter writer = new HTTPRequestWriter();

    @Test
    void aPostCarriesItsHeadersInOrderThenTheBody() {
        final byte[] body = ascii("xx{\"a\":1}yy");
        writer.start(buffer, HOST, HTTPMethod.POST)
                .path(ascii("/v1/orders"))
                .header(ascii("Content-Type"), ascii("application/json"))
                .header(ascii("X-Timestamp"), 1730700000123L)
                .header(ascii("X-Signature"), ascii("..sig=.."), 2, 4)
                .body(body, 2, 7);

        assertEquals(
                "POST /v1/orders HTTP/1.1\r\n"
                        + "Host: api.example.com\r\n"
                        + "Connection: keep-alive\r\n"
                        + "Content-Type: application/json\r\n"
                        + "X-Timestamp: 1730700000123\r\n"
                        + "X-Signature: sig=\r\n"
                        + "Content-Length: 7\r\n"
                        + "\r\n"
                        + "{\"a\":1}",
                written());
    }

    @Test
    void aPathCanBeWrittenInPiecesAndAGetHasNoBody() {
        writer.start(buffer, HOST, HTTPMethod.DELETE)
                .path(ascii("/orders/"))
                .path(new ViewString("abc-123"))
                .path(ascii("?market=x"))
                .noBody();

        assertEquals(
                "DELETE /orders/abc-123?market=x HTTP/1.1\r\nHost: api.example.com\r\nConnection: keep-alive\r\n\r\n",
                written());
    }

    @Test
    void manyHeadersFit() {
        final HTTPRequestWriter request = writer.start(buffer, HOST, HTTPMethod.GET).path(ascii("/"));
        for (int i = 0; i < 8; i++) {
            request.header(ascii("H" + i), i);
        }
        request.noBody();
        final String written = written();
        for (int i = 0; i < 8; i++) {
            assertTrue(written.contains("\r\nH" + i + ": " + i + "\r\n"), written);
        }
        assertTrue(written.endsWith("\r\nH7: 7\r\n\r\n"), written);
    }

    @Test
    void aStartedRequestReplacesWhatWasInTheBuffer() {
        writer.start(buffer, HOST, HTTPMethod.GET).path(ascii("/first/long/path")).noBody();
        writer.start(buffer, HOST, HTTPMethod.GET).path(ascii("/b")).noBody();
        assertEquals("GET /b HTTP/1.1\r\nHost: api.example.com\r\nConnection: keep-alive\r\n\r\n", written());
    }

    @Test
    void thePathCantFollowAHeader() {
        final HTTPRequestWriter request =
                writer.start(buffer, HOST, HTTPMethod.GET).path(ascii("/")).header(ascii("A"), 1);
        assertThrows(IllegalStateException.class, () -> request.path(ascii("/more")));
    }

    private String written() {
        final ByteBuffer view = buffer.duplicate().flip();
        final byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}

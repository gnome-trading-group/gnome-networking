package group.gnometrading.networking.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HTTPDecoderTest {

    private static Stream<Arguments> testBasicResponsesArguments() {
        return Stream.of(
                Arguments.of("", false, -1, -1, -1),
                Arguments.of("HTTP/1.1 200", false, -1, -1, -1),
                Arguments.of("HTTP/1.1 200 Success", false, -1, -1, -1),
                Arguments.of(lines("HTTP/1.1 200 Success"), true, 200, 0, 24),
                Arguments.of(lines("HTTP/1.1 200 Success", "Content-Length: 0"), true, 200, 0, 43),
                Arguments.of(lines("HTTP/1.1 200 Success", "Content-Length: 0", "Accept: gzip"), true, 200, 0, 57),
                Arguments.of(lines("HTTP/1.1 201 Success", "Content-Length: 0", "Accept: gzip"), true, 201, 0, 57),
                Arguments.of(linesWithBody(new byte[] {'h', 'i', 'm'}, "HTTP/1.1 201 Success", "Content-Length: 3", "Accept: gzip"), true, 201, 3, 60)
        );
    }

    @ParameterizedTest
    @MethodSource("testBasicResponsesArguments")
    void testBasicResponses(String http, boolean isCompleted, int status, int contentLength, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(http.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertEquals(isCompleted, decoder.isComplete());
        if (isCompleted) {
            assertEquals(status, decoder.getStatus());
            assertEquals(contentLength, decoder.contentLength());
            if (contentLength >= 0) {
                assertEquals(length, decoder.length());
            }
        }
    }

    private static Stream<Arguments> testBodyArguments() {
        return Stream.of(
                Arguments.of(linesWithBody(new byte[0], "HTTP/1.1 201 Success", "Content-Length: 0", "Accept: gzip"), new byte[0]),
                Arguments.of(linesWithBody(new byte[] {'h', 'i', 'm'}, "HTTP/1.1 201 Success", "Content-Length: 3", "Accept: gzip"), new byte[] {'h', 'i', 'm'}),
                Arguments.of(linesWithBody("whats up dog".getBytes(), "HTTP/1.1 201 Success", "Content-Length: 12", "Accept: gzip"), "whats up dog".getBytes())
        );
    }

    @ParameterizedTest
    @MethodSource("testBodyArguments")
    void testBody(String http, byte[] body) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(http.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertTrue(decoder.isComplete());

        ByteBuffer destination = ByteBuffer.allocate(body.length);
        decoder.copyBody(destination);
        assertEquals(body.length, destination.array().length);
        for (int i = 0; i < body.length; i++) {
            assertEquals(body[i], destination.get(i));
        }
    }

    @Test
    void testNoContentLengthHeaderCompletesSuccessfully() throws IOException {
        // 204 No Content pattern — no Content-Length header
        String response = lines("HTTP/1.1 204 No Content");
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertTrue(decoder.isComplete());
        assertEquals(204, decoder.getStatus());
        assertEquals(0, decoder.contentLength());
    }

    @Test
    void testStatusOnlyResponseCompletesSuccessfully() throws IOException {
        // Response with only status and blank line (no headers at all)
        String response = lines("HTTP/1.1 200 OK");
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertTrue(decoder.isComplete());
        assertEquals(200, decoder.getStatus());
        assertEquals(0, decoder.contentLength());
    }

    @Test
    void testMalformedStatusLineThrows() {
        // Does not start with HTTP
        String response = "XTTP/1.1 200 OK\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        assertThrows(IOException.class, () -> decoder.wrap(buffer));
    }

    @Test
    void testDecoderResetBetweenResponses() throws IOException {
        HTTPDecoder decoder = new HTTPDecoder();

        String first = lines("HTTP/1.1 200 OK", "Content-Length: 0");
        decoder.wrap(ByteBuffer.wrap(first.getBytes()));
        assertTrue(decoder.isComplete());
        assertEquals(200, decoder.getStatus());

        String second = lines("HTTP/1.1 201 Created", "Content-Length: 0");
        decoder.wrap(ByteBuffer.wrap(second.getBytes()));
        assertTrue(decoder.isComplete());
        assertEquals(201, decoder.getStatus());
    }

    @Test
    void testPartialHeadersNotComplete() throws IOException {
        // Response cut off mid-header — should not be complete
        String partial = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(partial.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertFalse(decoder.isComplete());
    }

    @Test
    void test2xxStatusCodesParseCorrectly() throws IOException {
        int[] statuses = {200, 201, 202, 204};
        for (int status : statuses) {
            String response = lines("HTTP/1.1 " + status + " OK");
            ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());
            HTTPDecoder decoder = new HTTPDecoder();
            decoder.wrap(buffer);
            assertTrue(decoder.isComplete(), "Expected complete for status " + status);
            assertEquals(status, decoder.getStatus());
        }
    }

    @Test
    void test500ResponseParsesCorrectly() throws IOException {
        String response = lines("HTTP/1.1 500 Internal Server Error", "Content-Length: 0");
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertTrue(decoder.isComplete());
        assertEquals(500, decoder.getStatus());
        assertEquals(0, decoder.contentLength());
    }

    @Test
    void testContentLengthLargerThanActualBody() throws IOException {
        // Headers claim 100 bytes but only 10 are present — decoder must stay incomplete
        String headers = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n";
        String partial = headers + "0123456789";
        ByteBuffer buffer = ByteBuffer.wrap(partial.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertFalse(decoder.isComplete(), "Decoder should not be complete when body is shorter than Content-Length");
        assertEquals(100, decoder.contentLength());
    }

    @Test
    void testZeroContentLengthWithTrailingBytes() throws IOException {
        // Content-Length: 0 — any bytes after the blank line are not part of the response
        String response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nextra";
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertTrue(decoder.isComplete());
        assertEquals(0, decoder.contentLength());
    }

    @Test
    void testChunkedBodyIsReassembled() throws IOException {
        String response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n"
                + "1;ext=1\r\n \r\n"
                + "5\r\nworld\r\n"
                + "0\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertTrue(decoder.isComplete());
        assertEquals("hello world", body(decoder));
        assertEquals(response.length(), buffer.position());
    }

    @Test
    void testChunkedBodyWithTrailersAndFollowingResponse() throws IOException {
        String first = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "a\r\n0123456789\r\n"
                + "0\r\nX-Trailer: yes\r\n\r\n";
        String second = "HTTP/1.1 204 No Content\r\n\r\n";
        ByteBuffer buffer = ByteBuffer.wrap((first + second).getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);
        assertTrue(decoder.isComplete());
        assertEquals("0123456789", body(decoder));
        assertEquals(first.length(), buffer.position());

        decoder.wrap(buffer);
        assertTrue(decoder.isComplete());
        assertEquals(204, decoder.getStatus());
    }

    @Test
    void testChunkedBodyIsIncompleteUntilTheLastChunk() throws IOException {
        String full = "HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n"
                + "0\r\n\r\n";
        HTTPDecoder decoder = new HTTPDecoder();
        for (int cut = 0; cut < full.length(); cut++) {
            ByteBuffer partial = ByteBuffer.wrap(full.substring(0, cut).getBytes());
            decoder.wrap(partial);
            assertFalse(decoder.isComplete(), "complete after only " + cut + " bytes");
            assertEquals(0, partial.position(), "partial response consumed after " + cut + " bytes");
        }
        ByteBuffer complete = ByteBuffer.wrap(full.getBytes());
        decoder.wrap(complete);
        assertTrue(decoder.isComplete());
        assertEquals("hello", body(decoder));
    }

    @Test
    void testHeaderNamesAreCaseInsensitive() throws IOException {
        String response = "HTTP/1.1 200 OK\r\ncontent-length: 3\r\nCONNECTION: Close\r\n\r\nabc";
        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(ByteBuffer.wrap(response.getBytes()));

        assertTrue(decoder.isComplete());
        assertEquals("abc", body(decoder));
        assertTrue(decoder.shouldClose());
    }

    @Test
    void testStatusLineWithoutReasonPhrase() throws IOException {
        String response = "HTTP/1.1 200\r\nContent-Length: 2\r\n\r\nok";
        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(ByteBuffer.wrap(response.getBytes()));

        assertTrue(decoder.isComplete());
        assertEquals(200, decoder.getStatus());
        assertEquals("ok", body(decoder));
        assertFalse(decoder.shouldClose());
    }

    @Test
    void testBodyWithoutLengthClosesTheConnection() throws IOException {
        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(ByteBuffer.wrap(lines("HTTP/1.1 200 OK").getBytes()));
        assertTrue(decoder.shouldClose());

        decoder.wrap(ByteBuffer.wrap(lines("HTTP/1.1 204 No Content").getBytes()));
        assertFalse(decoder.shouldClose());
    }

    @Test
    void testInterimResponsesAreSkipped() throws IOException {
        String response = "HTTP/1.1 100 Continue\r\n\r\n"
                + "HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\n"
                + "HTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\nok";
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertTrue(decoder.isComplete());
        assertEquals(201, decoder.getStatus());
        assertEquals("ok", body(decoder));
        assertEquals(response.length(), buffer.position());
    }

    @Test
    void testInterimResponseAloneIsIncomplete() throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap("HTTP/1.1 103 Early Hints\r\n\r\n".getBytes());
        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertFalse(decoder.isComplete());
        assertEquals(0, buffer.position());
    }

    @Test
    void testOversizedChunkIsRejected() {
        String response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nfffffffff\r\n";
        HTTPDecoder decoder = new HTTPDecoder();
        assertThrows(IOException.class, () -> decoder.wrap(ByteBuffer.wrap(response.getBytes())));
    }

    private static String body(HTTPDecoder decoder) {
        ByteBuffer destination = ByteBuffer.allocate(decoder.contentLength());
        decoder.copyBody(destination);
        return new String(destination.array(), StandardCharsets.US_ASCII);
    }

    @Test
    void testContentLengthAllowsSurroundingWhitespace() throws IOException {
        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(ByteBuffer.wrap(linesWithBody("ab".getBytes(), "HTTP/1.1 200 OK", "Content-Length: \t2 \t").getBytes()));

        assertTrue(decoder.isComplete());
        assertEquals(2, decoder.contentLength());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"1 2", "-1", "+2", "2a", "", "2147483648"})
    void testMalformedContentLengthThrows(String value) {
        String response = lines("HTTP/1.1 200 OK", "Content-Length: " + value);
        assertThrows(IOException.class, () -> new HTTPDecoder().wrap(ByteBuffer.wrap(response.getBytes())));
    }

    private static String lines(String... lines) {
        StringBuilder res = new StringBuilder();
        for (String l : lines) {
            res.append(l).append("\r\n");
        }
        res.append("\r\n");
        return res.toString();
    }

    private static String linesWithBody(byte[] body, String... lines) {
        StringBuilder res = new StringBuilder(lines(lines));
        for (byte b : body) {
            res.append((char) b);
        }
        return res.toString();
    }

}

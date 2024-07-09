package group.gnometrading.networking.http;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HTTPDecoderTest {

    private static Stream<Arguments> testBasicResponsesArguments() {
        return Stream.of(
                Arguments.of("", false, -1, -1, -1),
                Arguments.of("HTTP/1.1 200", false, -1, -1, -1),
                Arguments.of("HTTP/1.1 200 Success", false, -1, -1, -1),
                Arguments.of(lines("HTTP/1.1 200 Success"), false, -1, -1, -1),
                Arguments.of(lines("HTTP/1.1 200 Success", "Content-Length: 0"), true, 200, 0, 43),
                Arguments.of(lines("HTTP/1.1 200 Success", "Content-Length: 0", "Accept: gzip"), true, 200, 0, 57),
                Arguments.of(lines("HTTP/1.1 201 Success", "Content-Length: 0", "Accept: gzip"), true, 201, 0, 57),
                Arguments.of(linesWithBody(new byte[] {'h', 'i', 'm'}, "HTTP/1.1 201 Success", "Content-Length: 3", "Accept: gzip"), true, 201, 3, 60)
        );
    }

    @ParameterizedTest
    @MethodSource("testBasicResponsesArguments")
    void testBasicResponses(String http, boolean isCompleted, int status, int contentLength, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(http.getBytes());

        HTTPDecoder decoder = new HTTPDecoder();
        decoder.wrap(buffer);

        assertEquals(isCompleted, decoder.isComplete());
        if (isCompleted) {
            assertEquals(status, decoder.getStatus());
            assertEquals(contentLength, decoder.contentLength());
            assertEquals(length, decoder.length());
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
    void testBody(String http, byte[] body) {
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
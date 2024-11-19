package group.gnometrading.networking.http;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.MalformedURLException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HTTPEncoderTest {

    private static Stream<Arguments> testEncodingArguments() throws MalformedURLException {
        return Stream.of(
                Arguments.of(
                        "HTTP/1.1", HTTPMethod.GET, "/hello", "google.com", null, null, null, null, null, lines(
                                "GET /hello HTTP/1.1",
                                "Host: google.com",
                                "Connection: keep-alive"
                        )
                ),
                Arguments.of(
                        "HTTPS/1.1", HTTPMethod.GET, "/hello123", "google.com", null, null, null, null, null, lines(
                                "GET /hello123 HTTPS/1.1",
                                "Host: google.com",
                                "Connection: keep-alive"
                        )
                ),
                Arguments.of(
                        "HTTPS/1.1", HTTPMethod.GET, "/hello123", "google.com", new byte[0], null, null, null, null, linesWithBody(
                                new byte[0],
                                "GET /hello123 HTTPS/1.1",
                                "Host: google.com",
                                "Connection: keep-alive",
                                "Content-Length: 0"
                        )
                ),
                Arguments.of(
                        "HTTPS/1.1", HTTPMethod.GET, "/hello123", "google.com", "hi".getBytes(StandardCharsets.UTF_8), null, null, null, null, linesWithBody(
                                "hi".getBytes(StandardCharsets.UTF_8),
                                "GET /hello123 HTTPS/1.1",
                                "Host: google.com",
                                "Connection: keep-alive",
                                "Content-Length: 2"
                        )
                ),
                Arguments.of(
                        "HTTP/1.1", HTTPMethod.GET, "/hello", "google.com", null, "Header1", "Value1", null, null, lines(
                                "GET /hello HTTP/1.1",
                                "Host: google.com",
                                "Connection: keep-alive",
                                "Header1: Value1"
                        )
                ),
                Arguments.of(
                        "HTTP/1.1", HTTPMethod.GET, "/hello", "google.com", null, "Header1", "Value1", "Header2", "Value2", lines(
                                "GET /hello HTTP/1.1",
                                "Host: google.com",
                                "Connection: keep-alive",
                                "Header1: Value1",
                                "Header2: Value2"
                        )
                )
        );
    }

    @ParameterizedTest
    @MethodSource("testEncodingArguments")
    void testEncoding(
            String protocol,
            HTTPMethod httpMethod,
            String path,
            String host,
            byte[] body,
            String headerKey1,
            String headerValue1,
            String headerKey2,
            String headerValue2,
            String expected
    ) {
        HTTPEncoder httpEncoder = new HTTPEncoder(protocol);

        ByteBuffer out = ByteBuffer.allocate(10000);
        httpEncoder.wrap(out);

        httpEncoder.encode(httpMethod, path, host, body, headerKey1, headerValue1, headerKey2, headerValue2);
        out.flip();
        assertEquals(expected, String.valueOf(StandardCharsets.UTF_8.decode(out)));
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
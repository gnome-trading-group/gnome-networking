package group.gnometrading.networking.http;

import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.Test;
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

    @Test
    void testEncodeWithBodyLengthSendsOnlySpecifiedBytes() {
        HTTPEncoder encoder = new HTTPEncoder("HTTP/1.1");
        ByteBuffer out = ByteBuffer.allocate(10000);
        encoder.wrap(out);

        byte[] oversizedBuffer = "hello world".getBytes(StandardCharsets.UTF_8);
        encoder.encode(HTTPMethod.POST, "/api", "example.com", oversizedBuffer, 5, null, null, null, null);
        out.flip();

        String result = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(result.contains("Content-Length: 5"));
        assertTrue(result.endsWith("hello"));
        assertFalse(result.contains("world"));
    }

    @Test
    void testEncodeGnomeStringPathWithBodyLength() {
        HTTPEncoder encoder = new HTTPEncoder("HTTP/1.1");
        ByteBuffer out = ByteBuffer.allocate(10000);
        encoder.wrap(out);

        byte[] oversizedBuffer = "[{\"key\":\"value\"}]EXTRA GARBAGE".getBytes(StandardCharsets.UTF_8);
        int bodyLength = "[{\"key\":\"value\"}]".length();
        encoder.encode(HTTPMethod.POST, new ViewString("/api/pnl/snapshots"), "example.com", oversizedBuffer, bodyLength, "x-api-key", "secret", null, null);
        out.flip();

        String result = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(result.contains("Content-Length: " + bodyLength));
        assertTrue(result.contains("x-api-key: secret"));
        assertTrue(result.endsWith("[{\"key\":\"value\"}]"));
        assertFalse(result.contains("EXTRA GARBAGE"));
    }

    @Test
    void testEncodeWithoutLengthUsesFullArray() {
        HTTPEncoder encoder = new HTTPEncoder("HTTP/1.1");
        ByteBuffer out = ByteBuffer.allocate(10000);
        encoder.wrap(out);

        byte[] body = "full body".getBytes(StandardCharsets.UTF_8);
        encoder.encode(HTTPMethod.POST, new ViewString("/path"), "host.com", body, null, null, null, null);
        out.flip();

        String result = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(result.contains("Content-Length: 9"));
        assertTrue(result.endsWith("full body"));
    }

    @Test
    void testEncodeDeleteMethod() {
        HTTPEncoder encoder = new HTTPEncoder("HTTP/1.1");
        ByteBuffer out = ByteBuffer.allocate(10000);
        encoder.wrap(out);
        encoder.encode(HTTPMethod.DELETE, "/orders/123", "exchange.com", null, "x-api-key", "secret", null, null);
        out.flip();
        String result = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(result.startsWith("DELETE /orders/123 HTTP/1.1\r\n"));
        assertTrue(result.contains("x-api-key: secret"));
        assertFalse(result.contains("Content-Length"));
    }

    @Test
    void testEncodePutMethodWithBody() {
        HTTPEncoder encoder = new HTTPEncoder("HTTP/1.1");
        ByteBuffer out = ByteBuffer.allocate(10000);
        encoder.wrap(out);
        byte[] body = "{\"qty\":1}".getBytes(StandardCharsets.UTF_8);
        encoder.encode(HTTPMethod.PUT, "/orders/123", "exchange.com", body, "Content-Type", "application/json", null, null);
        out.flip();
        String result = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(result.startsWith("PUT /orders/123 HTTP/1.1\r\n"));
        assertTrue(result.contains("Content-Length: " + body.length));
        assertTrue(result.endsWith("{\"qty\":1}"));
    }

    @Test
    void testEncodeThreeHeaders() {
        HTTPEncoder encoder = new HTTPEncoder("HTTP/1.1");
        ByteBuffer out = ByteBuffer.allocate(10000);
        encoder.wrap(out);
        encoder.encode(HTTPMethod.DELETE, "/orders/123", "exchange.com", null, 0,
                "x-api-key", "mykey", "x-timestamp", "1234567890", "x-signature", "abc123");
        out.flip();
        String result = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(result.contains("x-api-key: mykey"));
        assertTrue(result.contains("x-timestamp: 1234567890"));
        assertTrue(result.contains("x-signature: abc123"));
    }

    @Test
    void testEncodeFourHeaders() {
        HTTPEncoder encoder = new HTTPEncoder("HTTP/1.1");
        ByteBuffer out = ByteBuffer.allocate(10000);
        encoder.wrap(out);
        encoder.encode(HTTPMethod.GET, "/orders", "exchange.com", null, 0,
                "x-api-key", "mykey", "x-timestamp", "1234567890", "x-signature", "abc123", "x-nonce", "999");
        out.flip();
        String result = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(result.contains("x-api-key: mykey"));
        assertTrue(result.contains("x-timestamp: 1234567890"));
        assertTrue(result.contains("x-signature: abc123"));
        assertTrue(result.contains("x-nonce: 999"));
    }

    @Test
    void testEncodeFiveHeaders() {
        HTTPEncoder encoder = new HTTPEncoder("HTTP/1.1");
        ByteBuffer out = ByteBuffer.allocate(10000);
        encoder.wrap(out);
        encoder.encode(HTTPMethod.GET, "/orders", "exchange.com", null, 0,
                "x-api-key", "mykey", "x-timestamp", "1234567890", "x-signature", "abc123", "x-nonce", "999", "Accept", "application/json");
        out.flip();
        String result = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(result.contains("x-api-key: mykey"));
        assertTrue(result.contains("x-timestamp: 1234567890"));
        assertTrue(result.contains("x-signature: abc123"));
        assertTrue(result.contains("x-nonce: 999"));
        assertTrue(result.contains("Accept: application/json"));
    }

    @Test
    void testEncodeDeleteWithBodyAndFiveHeaders() {
        HTTPEncoder encoder = new HTTPEncoder("HTTP/1.1");
        ByteBuffer out = ByteBuffer.allocate(10000);
        encoder.wrap(out);

        byte[] oversizedBuffer = "{\"orderID\":\"0xabc\"}TRAILING".getBytes(StandardCharsets.UTF_8);
        int bodyLength = "{\"orderID\":\"0xabc\"}".length();
        encoder.encode(HTTPMethod.DELETE, new ViewString("/order"), "exchange.com", oversizedBuffer, bodyLength,
                "k1", "v1", "k2", "v2", "k3", "v3", "k4", "v4", "k5", "v5");
        out.flip();

        String result = StandardCharsets.UTF_8.decode(out).toString();
        assertTrue(result.startsWith("DELETE /order HTTP/1.1\r\n"));
        assertTrue(result.contains("k5: v5"));
        assertTrue(result.contains("Content-Length: " + bodyLength));
        assertTrue(result.endsWith("\r\n\r\n{\"orderID\":\"0xabc\"}"));
    }

    @Test
    void testEncodeNullBodyProducesNoContentLength() {
        HTTPEncoder encoder = new HTTPEncoder("HTTP/1.1");
        ByteBuffer out = ByteBuffer.allocate(10000);
        encoder.wrap(out);

        encoder.encode(HTTPMethod.GET, new ViewString("/path"), "host.com", null, null, null, null, null);
        out.flip();

        String result = StandardCharsets.UTF_8.decode(out).toString();
        assertFalse(result.contains("Content-Length"));
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
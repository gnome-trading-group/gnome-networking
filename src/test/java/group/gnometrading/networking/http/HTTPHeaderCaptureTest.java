package group.gnometrading.networking.http;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HTTPHeaderCaptureTest {

    private static final int RETRY_AFTER = 0;
    private static final int REMAINING = 1;
    private static final int RESET = 2;

    @Test
    void capturesHeadersIgnoringCaseAndSpacing() throws IOException {
        final HTTPResponse response = decode(
                "HTTP/1.1 429 Too Many Requests",
                "RETRY-AFTER:   2  ",
                "poly-ratelimit-remaining:\t0\t",
                "Poly-RateLimit-Reset: 1700000000123",
                "Content-Length: 0");

        assertTrue(response.hasHeader(RETRY_AFTER));
        assertEquals("2", response.header(RETRY_AFTER).toString());
        assertEquals(2, response.headerAsLong(RETRY_AFTER, -1));
        assertEquals(0, response.headerAsLong(REMAINING, -1));
        assertEquals(1_700_000_000_123L, response.headerAsLong(RESET, -1));
    }

    @Test
    void missingHeaderIsAbsent() throws IOException {
        final HTTPResponse response = decode("HTTP/1.1 200 OK", "Retry-After: 1", "Content-Length: 0");

        assertTrue(response.hasHeader(RETRY_AFTER));
        assertFalse(response.hasHeader(REMAINING));
        assertNull(response.header(REMAINING));
        assertEquals(-7, response.headerAsLong(REMAINING, -7));
    }

    @Test
    void nameMustMatchWholly() throws IOException {
        final HTTPResponse response = decode("HTTP/1.1 200 OK", "Retry-After-Ms: 5", "X-Retry-After: 6");

        assertFalse(response.hasHeader(RETRY_AFTER));
    }

    @Test
    void nonNumericValueIsAbsentAsLongButReadable() throws IOException {
        final HTTPResponse response =
                decode("HTTP/1.1 503 Service Unavailable", "Retry-After: Wed, 21 Oct 2026 07:28:00 GMT");

        assertEquals(-1, response.headerAsLong(RETRY_AFTER, -1));
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", response.header(RETRY_AFTER).toString());
    }

    @Test
    void emptyValueIsPresentButNotANumber() throws IOException {
        final HTTPResponse response = decode("HTTP/1.1 200 OK", "Retry-After:");

        assertTrue(response.hasHeader(RETRY_AFTER));
        assertEquals(0, response.header(RETRY_AFTER).length());
        assertEquals(-1, response.headerAsLong(RETRY_AFTER, -1));
    }

    @Test
    void longValueIsCutToTheSlot() throws IOException {
        final String value = "9".repeat(HTTPResponse.MAX_HEADER_VALUE_LENGTH + 10);
        final HTTPResponse response = decode("HTTP/1.1 200 OK", "Retry-After: " + value);

        assertEquals(HTTPResponse.MAX_HEADER_VALUE_LENGTH, response.header(RETRY_AFTER).length());
        assertEquals(-1, response.headerAsLong(RETRY_AFTER, -1));
    }

    @Test
    void interimResponseHeadersAreNotTheRealResponses() throws IOException {
        final String http = lines("HTTP/1.1 103 Early Hints", "Retry-After: 9")
                + lines("HTTP/1.1 200 OK", "Poly-RateLimit-Remaining: 3", "Content-Length: 0");
        final HTTPResponse response = decodeRaw(http);

        assertFalse(response.hasHeader(RETRY_AFTER));
        assertEquals(3, response.headerAsLong(REMAINING, -1));
    }

    @Test
    void chunkedResponseKeepsItsHeaders() throws IOException {
        final String http = lines("HTTP/1.1 200 OK", "Transfer-Encoding: chunked", "Retry-After: 4")
                + "3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n";
        final HTTPResponse response = decodeRaw(http);

        assertEquals(4, response.headerAsLong(RETRY_AFTER, -1));
    }

    @Test
    void nextResponseClearsTheLast() throws IOException {
        final HTTPDecoder decoder = new HTTPDecoder(names());
        final HTTPResponse response = new HTTPResponse(3);
        fill(decoder, response, lines("HTTP/1.1 429 Too Many Requests", "Retry-After: 1", "Content-Length: 0"));
        fill(decoder, response, lines("HTTP/1.1 200 OK", "Content-Length: 0"));

        assertFalse(response.hasHeader(RETRY_AFTER));
    }

    @Test
    void refusedResponseLooksLikeA429WithoutHeaders() throws IOException {
        final HTTPResponse response = decode("HTTP/1.1 200 OK", "Retry-After: 1");
        response.refuse();

        assertTrue(response.isRefusedLocally());
        assertFalse(response.isSuccess());
        assertEquals(HTTPResponse.STATUS_TOO_MANY_REQUESTS, response.getStatusCode());
        assertNull(response.getBody());
        assertFalse(response.hasHeader(RETRY_AFTER));

        response.update(true, 200, null);
        assertFalse(response.isRefusedLocally());
    }

    @Test
    void builderLimitsAndValidatesNames() {
        assertThrows(IllegalArgumentException.class, () -> HTTPClient.builder().captureHeaders(""));
        assertThrows(
                IllegalArgumentException.class,
                () -> HTTPClient.builder().captureHeaders("a", "b", "c", "d", "e", "f", "g", "h", "i"));
    }

    @Test
    void decodingAndReadingCapturedHeadersDoesNotAllocate() throws IOException {
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long threadId = Thread.currentThread().getId();
        final HTTPDecoder decoder = new HTTPDecoder(names());
        final HTTPResponse response = new HTTPResponse(3);
        final ByteBuffer buffer = ByteBuffer.wrap(lines(
                        "HTTP/1.1 429 Too Many Requests",
                        "retry-after: 2",
                        "Poly-RateLimit-Remaining: 0",
                        "POLY-RATELIMIT-RESET: 1700000000123",
                        "Content-Length: 2")
                .concat("{}")
                .getBytes(StandardCharsets.US_ASCII));

        long sink = decodeMany(decoder, response, buffer, 20_000);
        final long before = threads.getThreadAllocatedBytes(threadId);
        sink += decodeMany(decoder, response, buffer, 10_000);
        final long allocated = threads.getThreadAllocatedBytes(threadId) - before;

        assertEquals(30_000L * (2 + 0 + 1_700_000_000_123L), sink);
        // Measuring can itself allocate a little; anything per decode would be hundreds of kilobytes here.
        assertTrue(allocated < 1024, "allocated " + allocated + " bytes over 10,000 decodes");
    }

    private static long decodeMany(
            final HTTPDecoder decoder, final HTTPResponse response, final ByteBuffer buffer, final int count)
            throws IOException {
        long sink = 0;
        for (int i = 0; i < count; i++) {
            buffer.position(0);
            decoder.wrap(buffer);
            response.update(false, decoder.getStatus(), null);
            response.captureHeaders(decoder);
            sink += response.headerAsLong(RETRY_AFTER, 0)
                    + response.headerAsLong(REMAINING, 0)
                    + response.headerAsLong(RESET, 0);
            if (response.header(RETRY_AFTER).length() != 1) {
                throw new AssertionError();
            }
        }
        return sink;
    }

    private static byte[][] names() {
        return new byte[][] {
            "retry-after".getBytes(StandardCharsets.US_ASCII),
            "poly-ratelimit-remaining".getBytes(StandardCharsets.US_ASCII),
            "poly-ratelimit-reset".getBytes(StandardCharsets.US_ASCII)
        };
    }

    private static HTTPResponse decode(final String... lines) throws IOException {
        return decodeRaw(lines(lines));
    }

    private static HTTPResponse decodeRaw(final String http) throws IOException {
        final HTTPResponse response = new HTTPResponse(3);
        fill(new HTTPDecoder(names()), response, http);
        return response;
    }

    private static void fill(final HTTPDecoder decoder, final HTTPResponse response, final String http)
            throws IOException {
        decoder.wrap(ByteBuffer.wrap(http.getBytes(StandardCharsets.US_ASCII)));
        assertTrue(decoder.isComplete());
        response.update(false, decoder.getStatus(), null);
        response.captureHeaders(decoder);
    }

    private static String lines(final String... lines) {
        final StringBuilder res = new StringBuilder();
        for (final String l : lines) {
            res.append(l).append("\r\n");
        }
        return res.append("\r\n").toString();
    }
}

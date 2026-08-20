package group.gnometrading.networking.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HTTPMethodTest {

    @Test
    void encodedMatchesNameBytes() {
        for (HTTPMethod method : HTTPMethod.values()) {
            byte[] expected = method.name().getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals(expected, method.encoded(),
                    "encoded() mismatch for " + method.name());
        }
    }

    @Test
    void encodedIsPrecomputed() {
        assertSame(HTTPMethod.GET.encoded(), HTTPMethod.GET.encoded());
        assertSame(HTTPMethod.DELETE.encoded(), HTTPMethod.DELETE.encoded());
    }
}

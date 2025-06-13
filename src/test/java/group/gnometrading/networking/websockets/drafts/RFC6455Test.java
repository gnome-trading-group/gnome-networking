package group.gnometrading.networking.websockets.drafts;

import group.gnometrading.networking.websockets.HandshakeInput;
import group.gnometrading.networking.websockets.enums.HandshakeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RFC6455Test {

    private RFC6455 rfc6455;
    private RandomGenerator mockRandom;

    @BeforeEach
    void setUp() {
        mockRandom = mock(RandomGenerator.class);
        rfc6455 = new RFC6455(mockRandom);
    }

    @ParameterizedTest
    @MethodSource("provideHandshakeCases")
    void testCreateHandshake(String host, int port, String path, byte[] randomBytes) {
        // Mock random bytes for the key
        doAnswer(invocation -> {
            byte[] bytes = invocation.getArgument(0);
            System.arraycopy(randomBytes, 0, bytes, 0, bytes.length);
            return null;
        }).when(mockRandom).nextBytes(any(byte[].class));

        // Create handshake input
        HandshakeInput input = new HandshakeInput(URI.create("ws://" + host + ":" + port + path));

        // Create handshake
        byte[] handshake = rfc6455.createHandshake(input);
        String request = new String(handshake);

        String expectedKey = Base64.getEncoder().encodeToString(randomBytes);

        // Verify the handshake request
        assertTrue(request.startsWith("GET " + path + " HTTP/1.1\r\n"));
        assertTrue(request.contains("Host: " + host + "\r\n"));
        assertTrue(request.contains("Upgrade: websocket\r\n"));
        assertTrue(request.contains("Connection: Upgrade\r\n"));
        assertTrue(request.contains("Sec-WebSocket-Key: " + expectedKey + "\r\n"));
        assertTrue(request.contains("Sec-WebSocket-Version: 13\r\n"));
        assertTrue(request.endsWith("\r\n\r\n"));
    }

    @ParameterizedTest
    @MethodSource("provideHandshakeCases")
    void testParseHandshake(String host, int port, String path, byte[] key) {
        // Create a handshake response
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + new String(key) + "\r\n" +
                "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());

        // Parse the handshake
        assertEquals(HandshakeState.MATCHED, rfc6455.parseHandshake(buffer));

        // Verify the response was consumed
        assertEquals(0, buffer.remaining());
    }

    @ParameterizedTest
    @MethodSource("provideInvalidHandshakeCases")
    void testParseInvalidHandshake(String response, HandshakeState expectedState) {
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());
        assertEquals(expectedState, rfc6455.parseHandshake(buffer));
    }

    private static Stream<Arguments> provideHandshakeCases() {
        return Stream.of(
            // Basic handshake
            Arguments.of(
                "localhost",
                8080,
                "/",
                new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10 }
            ),
            // Custom path
            Arguments.of(
                "example.com",
                443,
                "/ws/chat",
                new byte[] { 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20 }
            ),
            // Different port
            Arguments.of(
                "test.com",
                9000,
                "/",
                new byte[] { 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30 }
            ),
            // Subdomain
            Arguments.of(
                "sub.example.com",
                8080,
                "/ws",
                new byte[] { 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40 }
            )
        );
    } 

    private static Stream<Arguments> provideInvalidHandshakeCases() {
        return Stream.of(
            // Invalid status code
            Arguments.of(
                "HTTP/1.1 200 OK\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n" +
                "\r\n",
                HandshakeState.INVALID_PROTOCOL
            ),

            // Missing Upgrade header
            Arguments.of(
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n" +
                "\r\n",
                HandshakeState.INCOMPLETE
            ),

            // Missing Connection header
            Arguments.of(
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n" +
                "\r\n",
                HandshakeState.INCOMPLETE
            ),

            // Missing Sec-WebSocket-Accept header
            Arguments.of(
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "\r\n",
                HandshakeState.INCOMPLETE
            ),

            // Empty response
            Arguments.of(
                "",
                HandshakeState.INCOMPLETE
            ),

            // Incomplete response
            Arguments.of(
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n",
                HandshakeState.INCOMPLETE
            ),

            // Valid response with extra headers
            Arguments.of(
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n" +
                "X-Custom-Header: value\r\n" +
                "\r\n",
                HandshakeState.MATCHED
            ),

            // Valid response with different line endings
            Arguments.of(
                "HTTP/1.1 101 Switching Protocols\n" +
                "Upgrade: websocket\n" +
                "Connection: Upgrade\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\n" +
                "\n",
                HandshakeState.MATCHED
            )
        );
    }
} 
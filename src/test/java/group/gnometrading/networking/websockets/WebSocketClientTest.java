package group.gnometrading.networking.websockets;

import group.gnometrading.networking.sockets.GnomeSocket;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.networking.websockets.exceptions.InvalidHandshakeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class WebSocketClientTest {

    private GnomeSocket mockSocket;
    private WebSocketClient client;
    private static final int READ_BUFFER_SIZE = 1024;
    private static final int WRITE_BUFFER_SIZE = 512;

    @BeforeEach
    void setUp() throws IOException, URISyntaxException {
        mockSocket = mock(GnomeSocket.class);
        GnomeSocketFactory mockFactory = mock(GnomeSocketFactory.class);
        when(mockFactory.createSocket(any(InetSocketAddress.class))).thenReturn(mockSocket);
        doNothing().when(mockSocket).connect();
        
        client = new WebSocketClientBuilder()
            .withURI(new URI("ws://localhost:8080"))
            .withDraft(new RFC6455())
            .withSocketFactory(mockFactory)
            .withReadBufferSize(READ_BUFFER_SIZE)
            .withWriteBufferSize(WRITE_BUFFER_SIZE)
            .build();
    }

    private void setupSuccessfulHandshake() throws IOException {
        when(mockSocket.read(any(ByteBuffer.class), anyInt())).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n";
            buffer.put(response.getBytes());
            return response.length();
        });
        when(mockSocket.write(any(ByteBuffer.class), anyInt())).thenReturn(WRITE_BUFFER_SIZE);
    }

    @Test
    void testSuccessfulHandshake() throws IOException, URISyntaxException {
        setupSuccessfulHandshake();
        client.connect();
        verify(mockSocket).connect();
        verify(mockSocket, atLeastOnce()).write(any(ByteBuffer.class), anyInt());
    }

    @Test
    void testBinaryMessage() throws IOException, URISyntaxException {
        setupSuccessfulHandshake();
        client.connect();

        // Test sending binary message
        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);

        when(mockSocket.write(any(ByteBuffer.class))).thenReturn(WRITE_BUFFER_SIZE);
        boolean result = client.send(buffer);
        assertTrue(result);
        verify(mockSocket).write(any(ByteBuffer.class));
    }

    @Test
    void testSendPing() throws IOException, URISyntaxException {
        setupSuccessfulHandshake();
        client.connect();

        // Test sending PING
        when(mockSocket.write(any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            // Verify PING frame format:
            // - First byte: FIN (1) + PING opcode (9)
            assertEquals((byte) 0x89, buffer.get(0));
            // - Second byte: MASK (1) + payload length (0)
            assertEquals((byte) 0x80, buffer.get(1));
            // - Next 4 bytes: masking key
            assertTrue(buffer.get(2) != 0 || buffer.get(3) != 0 || buffer.get(4) != 0 || buffer.get(5) != 0);
            return WRITE_BUFFER_SIZE;
        });
        boolean result = client.ping();
        assertTrue(result);
        verify(mockSocket).write(any(ByteBuffer.class));

        // Test PONG response
        when(mockSocket.read(any(ByteBuffer.class), anyInt())).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            // Simulate PONG frame
            buffer.put((byte) 0x8A); // FIN + PONG opcode
            buffer.put((byte) 0x00); // No mask, empty payload
            return 2;
        });

        WebSocketResponse response = client.read();
        assertTrue(response.isSuccess());
        assertEquals(Opcode.PONG, response.getOpcode());
    }

    @Test
    void testReceivePing() throws IOException, URISyntaxException {
        setupSuccessfulHandshake();
        client.connect();

        // Test receiving PING
        when(mockSocket.read(any(ByteBuffer.class), anyInt())).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            // Simulate PING frame
            buffer.put((byte) 0x89); // FIN + PING opcode
            buffer.put((byte) 0x00); // No mask, empty payload
            return 2;
        });

        // Verify that a PONG is sent in response to PING
        WebSocketResponse response = client.read();
        assertTrue(response.isSuccess());
        assertEquals(Opcode.PING, response.getOpcode());

        // Verify PONG frame was sent
        verify(mockSocket).write(argThat(buffer -> {
            // Verify PONG frame format:
            // - First byte: FIN (1) + PONG opcode (10)
            return buffer.get(0) == (byte) 0x8A &&
                   // - Second byte: MASK (1) + payload length (0)
                   buffer.get(1) == (byte) 0x80 &&
                   // - Next 4 bytes: masking key
                   (buffer.get(2) != 0 || buffer.get(3) != 0 || buffer.get(4) != 0 || buffer.get(5) != 0);
        }));
    }

    @Test
    void testBinaryMessageResponse() throws IOException, URISyntaxException {
        setupSuccessfulHandshake();
        client.connect();

        // Create test binary data
        byte[] testData = new byte[100];
        for (int i = 0; i < testData.length; i++) {
            testData[i] = (byte) i;
        }

        // Mock receiving a binary message
        when(mockSocket.read(any(ByteBuffer.class), anyInt())).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            // Write binary frame header
            // First byte: 1000 0010
            // - 1: FIN bit (this is a complete frame)
            // - 000: RSV1-3 bits (must be 0)
            // - 0010: Opcode (2 = binary frame)
            buffer.put((byte) 0b10000010);
            
            // Second byte: 1110 0100
            // - 0: MASK bit (payload is masked)
            // - 1100100: Payload length (100 bytes)
            buffer.put((byte) 0b11100100);
            
            // Write masking key
            int mask = 0x12345678;
            buffer.putInt(mask);
            
            // Write masked payload
            // According to RFC 6455, masking is applied by XORing the payload with the masking key
            // The masking key is cycled through for each byte of the payload
            for (int i = 0; i < testData.length; i++) {
                byte maskByte = (byte) ((mask >> (8 * (3 - (i % 4)))) & 0xFF);
                buffer.put((byte) (testData[i] ^ maskByte));
            }
            return 2 + 4 + testData.length; // header + mask + payload
        });

        // Read the message
        WebSocketResponse response = client.read();
        assertTrue(response.isSuccess());
        assertEquals(Opcode.BINARY, response.getOpcode());

        // Verify the body contains the correct data
        ByteBuffer body = response.getBody();
        assertNotNull(body);
        assertEquals(testData.length, body.remaining());
        byte[] receivedData = new byte[testData.length];
        body.get(receivedData);
        assertArrayEquals(testData, receivedData);
    }

    @Test
    void testInvalidHandshakeResponse() throws IOException, URISyntaxException {
        when(mockSocket.read(any(ByteBuffer.class), anyInt())).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            // Write a regular HTTP response instead of WebSocket handshake
            String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: 13\r\n" +
                            "\r\n" +
                            "Hello, World!";
            buffer.put(response.getBytes());
            return response.length();
        });
        when(mockSocket.write(any(ByteBuffer.class), anyInt())).thenReturn(WRITE_BUFFER_SIZE);

        assertThrows(InvalidHandshakeException.class, () -> {
            client.connect();
        });
    }

    @Test
    void testConnectionClosed() throws IOException, URISyntaxException {
        setupSuccessfulHandshake();
        client.connect();

        // Mock read returning -1 to indicate connection closed
        when(mockSocket.read(any(ByteBuffer.class), anyInt())).thenReturn(-1);

        WebSocketResponse response = client.read();
        assertFalse(response.isSuccess());
        assertTrue(response.isClosed());
    }
}
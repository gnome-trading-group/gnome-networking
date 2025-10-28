package group.gnometrading.networking.websockets;

import group.gnometrading.networking.websockets.enums.Opcode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class BasicWebSocketIntegrationTest extends WebSocketIntegrationTest {

    @Test
    @Timeout(value = 20)
    void testConnectionAndHandshake() throws Exception {
        client.connect();
        Thread.sleep(1_000);
        assertTrue(client.isConnected());
    }

    @Test
    @Timeout(value = 20)
    void testBinaryMessageExchange() throws IOException {
        client.connect();
        sendRandomBinary();
    }

    @Test
    @Timeout(value = 20)
    void testPingPong() throws IOException {
        client.connect();

        assertTrue(client.ping());

        WebSocketResponse response = client.read();
        assertTrue(response.isSuccess());
        assertEquals(Opcode.PONG, response.getOpcode());
    }

    @Test
    @Timeout(value = 20)
    void testTextMessageExchange() throws IOException {
        client.connect();

        String testMessage = "Hello, WebSocket!";
        ByteBuffer buffer = ByteBuffer.wrap(testMessage.getBytes(StandardCharsets.UTF_8));
        assertTrue(client.send(Opcode.TEXT, buffer));

        WebSocketResponse response = client.read();
        assertTrue(response.isSuccess());
        assertEquals(Opcode.TEXT, response.getOpcode());

        String receivedMessage = StandardCharsets.UTF_8.decode(response.getBody()).toString();
        assertEquals(testMessage, receivedMessage);
    }

    @Test
    @Timeout(value = 20)
    void testMultipleMessages() throws IOException {
        client.connect();

        // Send multiple messages
        String[] messages = {"First", "Second", "Third"};
        for (String message : messages) {
            ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
            assertTrue(client.send(Opcode.TEXT, buffer));

            WebSocketResponse response = client.read();
            assertTrue(response.isSuccess());
            assertEquals(Opcode.TEXT, response.getOpcode());

            String receivedMessage = StandardCharsets.UTF_8.decode(response.getBody()).toString();
            assertEquals(message, receivedMessage);
        }
    }

    @Test
    @Timeout(value = 20)
    void testDisconnectReturnValue() throws Exception {
        client.connect();
        assertTrue(client.isConnected());

        String message = "This is an easter egg. Send it to Mason and he'll buy you a beer!";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
        assertTrue(client.send(Opcode.TEXT, buffer));

        WebSocketResponse response = client.read();
        assertTrue(response.isSuccess());
        String receivedMessage = StandardCharsets.UTF_8.decode(response.getBody()).toString();
        assertEquals(message, receivedMessage);

        message = "disconnect";
        buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
        assertTrue(client.send(Opcode.TEXT, buffer));
        assertTrue(client.isConnected());

        response = client.read();
        assertFalse(response.isSuccess());
        assertTrue(response.isClosed());
    }

    @Test
    @Timeout(value = 20)
    void testDisconnectAndReconnect() throws Exception {
        client.connect();
        assertTrue(client.isConnected());

        String message = "disconnect";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
        assertTrue(client.send(Opcode.TEXT, buffer));

        WebSocketResponse response = client.read();
        assertFalse(response.isSuccess());
        assertTrue(response.isClosed());

        client.reconnect();
        assertTrue(client.isConnected());
        message = "hello";
        buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
        assertTrue(client.send(Opcode.TEXT, buffer));

        response = client.read();
        assertTrue(response.isSuccess());
        String receivedMessage = StandardCharsets.UTF_8.decode(response.getBody()).toString();
        assertEquals(message, receivedMessage);
    }

    @Test
    @Timeout(value = 20)
    void testClientReconnection() throws Exception {
        // First connection
        client.connect();
        assertTrue(client.isConnected());

        // Send a message to verify connection
        String testMessage = "Before disconnect";
        ByteBuffer buffer = ByteBuffer.wrap(testMessage.getBytes(StandardCharsets.UTF_8));
        assertTrue(client.send(Opcode.TEXT, buffer));

        WebSocketResponse response = client.read();
        assertTrue(response.isSuccess());
        assertEquals(Opcode.TEXT, response.getOpcode());

        // Disconnect
        client.close();
        assertFalse(client.isConnected());

        // Reconnect
        client.connect();
        assertTrue(client.isConnected());

        // Send another message to verify reconnection
        String reconnectMessage = "After reconnect";
        buffer = ByteBuffer.wrap(reconnectMessage.getBytes(StandardCharsets.UTF_8));
        assertTrue(client.send(Opcode.TEXT, buffer));

        response = client.read();
        assertTrue(response.isSuccess());
        assertEquals(Opcode.TEXT, response.getOpcode());

        String receivedMessage = StandardCharsets.UTF_8.decode(response.getBody()).toString();
        assertEquals(reconnectMessage, receivedMessage);

        client.reconnect();
        assertTrue(client.isConnected());
        reconnectMessage = "After reconnect 2";
        buffer = ByteBuffer.wrap(reconnectMessage.getBytes(StandardCharsets.UTF_8));
        assertTrue(client.send(Opcode.TEXT, buffer));

        response = client.read();
        assertTrue(response.isSuccess());
        assertEquals(Opcode.TEXT, response.getOpcode());

        receivedMessage = StandardCharsets.UTF_8.decode(response.getBody()).toString();
        assertEquals(reconnectMessage, receivedMessage);
    }

    @Test
    @Timeout(value = 20)
    void testClientReconnectionWithUnknownDisconnect() throws Exception {
        // First connection
        client.connect();
        assertTrue(client.isConnected());

        // Send a message to verify connection
        String testMessage = "Before disconnect";
        ByteBuffer buffer = ByteBuffer.wrap(testMessage.getBytes(StandardCharsets.UTF_8));
        assertTrue(client.send(Opcode.TEXT, buffer));

        WebSocketResponse response = client.read();
        assertTrue(response.isSuccess());
        assertEquals(Opcode.TEXT, response.getOpcode());

        String receivedMessage = StandardCharsets.UTF_8.decode(response.getBody()).toString();
        assertEquals(testMessage, receivedMessage);

        for (int i = 0; i < 10; i++) {
            testMessage = "Message " + i;
            buffer = ByteBuffer.wrap(testMessage.getBytes(StandardCharsets.UTF_8));
            assertTrue(client.send(Opcode.TEXT, buffer));

            response = client.read();
            assertTrue(response.isSuccess());
            assertEquals(Opcode.TEXT, response.getOpcode());

            receivedMessage = StandardCharsets.UTF_8.decode(response.getBody()).toString();
            assertEquals(testMessage, receivedMessage);

            testMessage = "disconnect";
            buffer = ByteBuffer.wrap(testMessage.getBytes(StandardCharsets.UTF_8));
            assertTrue(client.send(Opcode.TEXT, buffer));

            response = client.read();
            assertFalse(response.isSuccess());
            assertTrue(response.isClosed());

            client.connect();
            assertTrue(client.isConnected());

            testMessage = "After reconnect";
            buffer = ByteBuffer.wrap(testMessage.getBytes(StandardCharsets.UTF_8));
            assertTrue(client.send(Opcode.TEXT, buffer));
            response = client.read();
            assertTrue(response.isSuccess());
            assertEquals(Opcode.TEXT, response.getOpcode());
        }
    }

    private void sendRandomBinary() throws IOException {
        byte[] testData = new byte[100];
        new Random().nextBytes(testData);
        ByteBuffer buffer = ByteBuffer.wrap(testData);
        assertTrue(client.send(buffer));

        // Read the echo response
        WebSocketResponse response = client.read();
        assertTrue(response.isSuccess());
        assertEquals(Opcode.BINARY, response.getOpcode());

        // Verify the echoed data matches what we sent
        ByteBuffer receivedData = response.getBody();
        assertNotNull(receivedData);
        assertEquals(testData.length, receivedData.remaining());
        byte[] receivedBytes = new byte[testData.length];
        receivedData.get(receivedBytes);
        assertArrayEquals(testData, receivedBytes);
    }
} 
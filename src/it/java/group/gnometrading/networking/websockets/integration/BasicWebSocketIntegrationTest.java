package group.gnometrading.networking.websockets.integration;

import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.networking.websockets.WebSocketResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class BasicWebSocketIntegrationTest extends WebSocketIntegrationTest {

    @Test
    @Timeout(value = 20)
    void testConnectionAndHandshake() throws Exception {
        // Give the server time to start
        Thread.sleep(1000);
        try {
            client.connect();
            assertTrue(true);
        } catch (Exception e) {
            System.err.println("Connection failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    @Timeout(value = 20)
    void testBinaryMessageExchange() throws IOException {
        // Give the server time to start
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            client.connect();

            byte[] testData = new byte[100];
            for (int i = 0; i < testData.length; i++) {
                testData[i] = (byte) i;
            }
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
        } catch (Exception e) {
            System.err.println("Binary message test failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    @Timeout(value = 20)
    void testPingPong() throws IOException {
        // Give the server time to start
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            client.connect();

            assertTrue(client.ping());

            WebSocketResponse response = client.read();
            assertTrue(response.isSuccess());
            assertEquals(Opcode.PONG, response.getOpcode());
        } catch (Exception e) {
            System.err.println("PingPong test failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
} 
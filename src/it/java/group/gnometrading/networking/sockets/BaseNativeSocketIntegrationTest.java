package group.gnometrading.networking.sockets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public abstract class BaseNativeSocketIntegrationTest extends SocketIntegrationTest {

    @Test
    @Timeout(value = 20)
    void testBasicConnection() throws IOException, InterruptedException {
        clientSocket = createClient();
        clientSocket.connect();
        Thread.sleep(1_000);
        assertTrue(clientSocket.isConnected());
    }

    @Test
    @Timeout(value = 20)
    void testDataExchange() throws IOException {
        clientSocket = createClient();
        clientSocket.connect();

        byte[] testMessage = "Hello, Socket!".getBytes(StandardCharsets.UTF_8);
        ByteBuffer sendBuffer = ByteBuffer.allocateDirect(testMessage.length);
        sendBuffer.put(testMessage);
        sendBuffer.flip();
        clientSocket.write(sendBuffer);

        ByteBuffer receiveBuffer = ByteBuffer.allocateDirect(1 << 16);
        int bytesRead = clientSocket.read(receiveBuffer);
        assertTrue(bytesRead > 0);

        receiveBuffer.flip();
        byte[] receiveMessage = new byte[receiveBuffer.remaining()];
        receiveBuffer.get(receiveMessage);
        assertArrayEquals(testMessage, receiveMessage);
    }

    @Test
    @Timeout(value = 20)
    void testConnectionClosure() throws IOException {
        clientSocket = createClient();
        clientSocket.connect();
        assertTrue(clientSocket.isConnected());

        clientSocket.close();
        assertFalse(clientSocket.isConnected());
    }

    @Test
    void testLargeDataExchange() throws Exception {
        clientSocket = createClient();
        clientSocket.connect();

        Thread.sleep(1_000);

        // Create a large message (1MB)
        byte[] largeData = new byte[1024 * 1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }
        ByteBuffer sendBuffer = ByteBuffer.allocateDirect(1 << 21);
        sendBuffer.put(largeData);
        sendBuffer.flip();
        clientSocket.write(sendBuffer);

        ByteBuffer receiveBuffer = ByteBuffer.allocateDirect(1 << 21);
        int totalBytesRead = 0;
        while (totalBytesRead < largeData.length) {
            int bytesRead = clientSocket.read(receiveBuffer);
            if (bytesRead == -1) break;
            totalBytesRead += bytesRead;
        }

        assertEquals(largeData.length, totalBytesRead);
        receiveBuffer.flip();
        byte[] receivedData = new byte[largeData.length];
        receiveBuffer.get(receivedData);
        assertArrayEquals(largeData, receivedData);
    }
}
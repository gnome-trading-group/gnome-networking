package group.gnometrading.networking.sockets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.ArrayList;
import java.net.SocketTimeoutException;
import java.net.SocketException;
import javax.net.ssl.SSLSocket;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public abstract class BaseNativeSocketIntegrationTest extends SocketIntegrationTest {

    @Test
    @Timeout(value = 20)
    void testBasicConnection() throws IOException {
        clientSocket = createClient(server.getPort());
        clientSocket.connect();
        assertTrue(clientSocket.isConnected());
    }

    @Test
    @Timeout(value = 20)
    void testDataExchange() throws IOException {
        clientSocket = createClient(server.getPort());
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
        clientSocket = createClient(server.getPort());
        clientSocket.connect();
        assertTrue(clientSocket.isConnected());

        clientSocket.close();
        assertFalse(clientSocket.isConnected());
    }

    @Test
    @Timeout(value = 20)
    void testCloseIsIdempotent() throws IOException {
        clientSocket = createClient(server.getPort());
        clientSocket.close();
        clientSocket.connect();
        clientSocket.close();
        clientSocket.close();
        assertFalse(clientSocket.isConnected());
    }

    @Test
    @Timeout(value = 20)
    void testCloseAfterRefusedConnect() throws Exception {
        int port = server.getPort();
        server.stop();
        Thread.sleep(100);
        clientSocket = createClient(port);

        assertThrows(IOException.class, clientSocket::connect);
        clientSocket.close();
        assertThrows(IOException.class, clientSocket::connect);
        clientSocket.close();
        assertFalse(clientSocket.isConnected());
    }

    @Test
    @Timeout(value = 20)
    void testReconnectOnSameSocket() throws IOException {
        clientSocket = createClient(server.getPort());
        clientSocket.connect();
        clientSocket.connect();
        assertTrue(clientSocket.isConnected());
        assertTrue(clientSocket.isAlive());
    }

    @Test
    @Timeout(value = 20)
    void testKeepAliveTuning() throws IOException {
        clientSocket = createClient(server.getPort());
        clientSocket.connect();
        clientSocket.setKeepAlive(10, 5, 3);
        assertTrue(clientSocket.isAlive());
    }

    @Test
    @Timeout(value = 20)
    void testFailedReconnectLeavesTheSocketDisconnected() throws Exception {
        clientSocket = createClient(server.getPort());
        clientSocket.connect();
        server.stop();
        Thread.sleep(100);

        // Reconnecting to the stopped server fails; the socket must then refuse calls rather than reach a freed
        // native handle.
        assertThrows(IOException.class, clientSocket::connect);
        assertFalse(clientSocket.isConnected());
        assertEquals(SocketProbe.DEAD, clientSocket.probe());
        assertThrows(SocketException.class, () -> clientSocket.configureBlocking(true));
        assertThrows(SocketException.class, () -> clientSocket.read(ByteBuffer.allocateDirect(16)));
    }

    @Test
    @Timeout(value = 30)
    void testFailedWriteBreaksTheSocketUntilReconnected() throws Exception {
        try (ServerSocket silent = createClosingServer()) {
            final CountDownLatch finished = new CountDownLatch(1);
            Thread acceptor = new Thread(() -> {
                final List<Socket> held = new ArrayList<>();
                try {
                    // Accepts and holds connections without ever reading, so the client's send buffer fills.
                    for (int i = 0; i < 2; i++) {
                        Socket accepted = silent.accept();
                        if (accepted instanceof SSLSocket ssl) {
                            ssl.startHandshake();
                        }
                        // Sent before the client's writes stall: data already on its way, like an execution report.
                        accepted.getOutputStream().write("hello".getBytes(StandardCharsets.US_ASCII));
                        accepted.getOutputStream().flush();
                        held.add(accepted);
                    }
                    finished.await();
                } catch (Exception ignored) {
                } finally {
                    held.forEach(socket -> {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                        }
                    });
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            try {
                clientSocket = createClient(silent.getLocalPort());
                clientSocket.connect();
                clientSocket.setWriteTimeout(200);
                clientSocket.setReadTimeout(2_000);

                final ByteBuffer chunk = ByteBuffer.allocateDirect(1 << 20);
                IOException failure = null;
                for (int i = 0; i < 500 && failure == null; i++) {
                    chunk.clear();
                    try {
                        clientSocket.write(chunk, chunk.remaining());
                    } catch (IOException e) {
                        failure = e;
                    }
                }
                assertInstanceOf(SocketTimeoutException.class, failure);

                // Part of the last write may have gone out; anything sent now would follow a cut-off message.
                assertFalse(clientSocket.isConnected());
                assertEquals(SocketProbe.DEAD, clientSocket.probe());
                final ByteBuffer small = ByteBuffer.allocateDirect(16);
                SocketException next = assertThrows(SocketException.class, () -> clientSocket.write(small, 16));
                assertTrue(next.getMessage().contains("broken"), next.getMessage());

                // What already arrived is still handed over, then end of stream so the reader reconnects.
                final ByteBuffer received = ByteBuffer.allocateDirect(64);
                int n;
                while ((n = clientSocket.read(received)) > 0) {
                    assertTrue(received.position() <= 5);
                }
                assertEquals(-1, n);
                received.flip();
                assertEquals("hello", StandardCharsets.US_ASCII.decode(received).toString());

                clientSocket.connect();
                assertTrue(clientSocket.isConnected());
                small.clear();
                assertEquals(16, clientSocket.write(small, 16));
            } finally {
                finished.countDown();
            }
        }
    }

    /** A server socket that accepts one connection and then closes it from the server side. */
    protected abstract ServerSocket createClosingServer() throws Exception;

    @Test
    @Timeout(value = 20)
    void testIsAliveDetectsPeerClose() throws Exception {
        try (ServerSocket closing = createClosingServer()) {
            Thread acceptor = new Thread(() -> {
                try (Socket accepted = closing.accept()) {
                    if (accepted instanceof SSLSocket ssl) {
                        ssl.startHandshake();
                    }
                    Thread.sleep(200);
                } catch (Exception ignored) {
                }
            });
            acceptor.start();

            clientSocket = createClient(closing.getLocalPort());
            clientSocket.connect();
            assertTrue(clientSocket.isAlive());

            acceptor.join();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (clientSocket.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(clientSocket.isAlive());
        }
    }

    @Test
    @Timeout(value = 20)
    void testLargeDataExchange() throws Exception {
        clientSocket = createClient(server.getPort());
        clientSocket.connect();

        // Create a large message (100 kb)
        byte[] largeData = new byte[1024 * 100];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }
        ByteBuffer sendBuffer = ByteBuffer.allocateDirect(1 << 21);
        sendBuffer.put(largeData);
        sendBuffer.flip();
        clientSocket.write(sendBuffer);

        ByteBuffer receiveBuffer = ByteBuffer.allocateDirect(1 << 21);
        int totalBytesRead = 0;
        int consecutiveZeroReads = 0;
        while (totalBytesRead < largeData.length) {
            int bytesRead = clientSocket.read(receiveBuffer);
            if (bytesRead == -1) break;
            if (bytesRead == 0) {
                consecutiveZeroReads++;
                if (consecutiveZeroReads > 100) {
                    Thread.sleep(10); // Brief pause for SSL processing
                    consecutiveZeroReads = 0;
                }
                continue;
            }
            consecutiveZeroReads = 0;
            totalBytesRead += bytesRead;
        }

        assertEquals(largeData.length, totalBytesRead);
        receiveBuffer.flip();
        byte[] receivedData = new byte[largeData.length];
        receiveBuffer.get(receivedData);
        assertArrayEquals(largeData, receivedData);
    }
}
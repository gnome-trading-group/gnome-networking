package group.gnometrading.networking.websockets;

import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.enums.HandshakeState;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.networking.websockets.exceptions.InvalidHandshakeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class WebSocketResilienceIT {

    private static final String UPGRADE = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"
            + "\r\n";

    @Test
    void serverClosingDuringHandshakeFailsInsteadOfSpinning() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> readRequest(socket.getInputStream()))) {
            final WebSocketClient client = client(server, 5_000);

            final long start = System.nanoTime();
            final InvalidHandshakeException e = assertThrows(InvalidHandshakeException.class, client::connect);
            assertEquals(HandshakeState.INVALID_READ, e.getHandshakeState());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
        }
    }

    @Test
    void silentServerTimesOutTheHandshake() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            Thread.sleep(5_000);
        })) {
            final WebSocketClient client = client(server, 300);

            final long start = System.nanoTime();
            final InvalidHandshakeException e = assertThrows(InvalidHandshakeException.class, client::connect);
            assertEquals(HandshakeState.TIMEOUT, e.getHandshakeState());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
        }
    }

    @Test
    void upgradeMissingAHeaderIsRejected() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n".getBytes());
            socket.getOutputStream().flush();
            Thread.sleep(2_000);
        })) {
            final WebSocketClient client = client(server, 5_000);

            final InvalidHandshakeException e = assertThrows(InvalidHandshakeException.class, client::connect);
            assertEquals(HandshakeState.INVALID_HEADERS, e.getHandshakeState());
        }
    }

    @Test
    void readTimeoutReturnsNoMessageOnASilentConnection() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            socket.getOutputStream().write(UPGRADE.getBytes());
            socket.getOutputStream().flush();
            Thread.sleep(5_000);
        })) {
            final WebSocketClient client = client(server, 5_000);
            client.setReadTimeout(100);
            client.connect();

            final long start = System.nanoTime();
            final WebSocketResponse response = client.read();
            assertFalse(response.isSuccess());
            assertFalse(response.isClosed());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        }
    }

    @Test
    void framesArrivingTogetherAreEachReturnedIntact() throws Exception {
        final String[] payloads = {"first", "second message", "x".repeat(300)};
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(UPGRADE.getBytes());
            for (String payload : payloads) {
                bytes.write(frame(payload));
            }
            socket.getOutputStream().write(bytes.toByteArray());
            socket.getOutputStream().flush();
            Thread.sleep(2_000);
        })) {
            final WebSocketClient client = client(server, 5_000);
            client.connect();

            for (String expected : payloads) {
                final WebSocketResponse response = client.read();
                assertTrue(response.isSuccess());
                assertEquals(Opcode.TEXT, response.getOpcode());
                // Draining the payload view must not disturb the frames still waiting in the buffer.
                assertEquals(expected, StandardCharsets.US_ASCII.decode(response.getBody()).toString());
            }
        }
    }

    @Test
    void settingsSurviveAReconnect() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            socket.getOutputStream().write(UPGRADE.getBytes());
            socket.getOutputStream().flush();
            Thread.sleep(5_000);
        })) {
            final WebSocketClient client = new WebSocketClientBuilder()
                    .withURI(URI.create("ws://127.0.0.1:" + server.port() + "/"))
                    .withReadTimeout(150)
                    .withTcpNoDelay(true)
                    .withKeepAlive(10, 5, 3)
                    .build();
            client.connect();
            client.reconnect();

            // A fresh socket would block this read forever; the read timeout must have been applied to it too.
            final long start = System.nanoTime();
            final WebSocketResponse response = client.read();
            assertFalse(response.isSuccess());
            assertFalse(response.isClosed());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        }
    }

    @Test
    void nonBlockingModeSurvivesAReconnect() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            socket.getOutputStream().write(UPGRADE.getBytes());
            socket.getOutputStream().flush();
            Thread.sleep(5_000);
        })) {
            final WebSocketClient client = new WebSocketClientBuilder()
                    .withURI(URI.create("ws://127.0.0.1:" + server.port() + "/"))
                    .withBlocking(false)
                    .build();
            client.connect();
            client.reconnect();

            final long start = System.nanoTime();
            assertFalse(client.read().isSuccess());
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500), "a non-blocking read returns at once");
        }
    }

    @Test
    void handshakeTimeoutIsSeparateFromTheConnectTimeout() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            Thread.sleep(5_000);
        })) {
            final WebSocketClient client = new WebSocketClientBuilder()
                    .withURI(URI.create("ws://127.0.0.1:" + server.port() + "/"))
                    .withConnectTimeout(5_000)
                    .withHandshakeTimeout(300)
                    .build();

            final long start = System.nanoTime();
            final InvalidHandshakeException e = assertThrows(InvalidHandshakeException.class, client::connect);
            assertEquals(HandshakeState.TIMEOUT, e.getHandshakeState());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        }
    }

    private static WebSocketClient client(ScriptedServer server, int timeoutMs) throws IOException {
        return new WebSocketClientBuilder()
                .withURI(URI.create("ws://127.0.0.1:" + server.port() + "/"))
                .withSocketFactory(new NativeSocketFactory())
                .withConnectTimeout(timeoutMs)
                .withHandshakeTimeout(timeoutMs)
                .build();
    }

    private static byte[] frame(String payload) {
        final byte[] data = payload.getBytes(StandardCharsets.US_ASCII);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x81);
        if (data.length <= 125) {
            out.write(data.length);
        } else {
            out.write(126);
            out.write(data.length >>> 8);
            out.write(data.length & 0xFF);
        }
        out.writeBytes(data);
        return out.toByteArray();
    }

    private static void readRequest(InputStream in) throws IOException {
        int matched = 0;
        while (matched < 4) {
            final int b = in.read();
            if (b < 0) {
                return;
            }
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
                    ? matched + 1
                    : 0;
        }
    }

    @FunctionalInterface
    interface Handler {
        void handle(Socket socket) throws Exception;
    }

    /** Serves each connection with the handler on its own thread, closing the socket when the handler returns. */
    static final class ScriptedServer implements AutoCloseable {
        private final ServerSocket serverSocket;

        ScriptedServer(Handler handler) throws IOException {
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            final Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        final Socket socket = serverSocket.accept();
                        final Thread worker = new Thread(() -> {
                            try (socket; OutputStream ignored = socket.getOutputStream()) {
                                handler.handle(socket);
                            } catch (Exception ignored) {
                                // The client hanging up mid-script is expected in several tests.
                            }
                        });
                        worker.setDaemon(true);
                        worker.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}

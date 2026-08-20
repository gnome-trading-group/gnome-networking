package group.gnometrading.networking.http;

import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.SystemEpochClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class RetryableHTTPClientIT {

    private static final String TEST_PATH = "/api/test";

    private static final byte[] HTTP_200 = (
            "HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 2\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n[]"
    ).getBytes();

    private static final byte[] HTTP_500 = (
            "HTTP/1.1 500 Internal Server Error\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n"
    ).getBytes();

    @Test
    void happyPath() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final int port = server.getPort();
            final RetryableHTTPClient client = new RetryableHTTPClient(
                    2, 25_000, SystemEpochClock.INSTANCE, () -> new HTTPClient(true, port));

            final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", TEST_PATH);
            assertTrue(response.isSuccess());
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    void retriesOnServerError() throws Exception {
        // Server returns 500 twice, then 200 on the third request
        try (ScriptedHttpServer server = new ScriptedHttpServer(HTTP_500, HTTP_500, HTTP_200)) {
            final int port = server.getPort();
            final RetryableHTTPClient client = new RetryableHTTPClient(
                    2, 25_000, SystemEpochClock.INSTANCE, () -> new HTTPClient(true, port));

            final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", TEST_PATH);
            assertTrue(response.isSuccess());
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    void exhaustsRetries() throws Exception {
        // Server always returns 500 — client should exhaust retries and return the 500
        try (ScriptedHttpServer server = new ScriptedHttpServer(HTTP_500, HTTP_500, HTTP_500)) {
            final int port = server.getPort();
            final RetryableHTTPClient client = new RetryableHTTPClient(
                    2, 25_000, SystemEpochClock.INSTANCE, () -> new HTTPClient(true, port));

            final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", TEST_PATH);
            assertFalse(response.isSuccess());
            assertEquals(500, response.getStatusCode());
        }
    }

    @Test
    void deleteRequestSucceeds() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final int port = server.getPort();
            final RetryableHTTPClient client = new RetryableHTTPClient(
                    2, 25_000, SystemEpochClock.INSTANCE, () -> new HTTPClient(true, port));

            final HTTPResponse response = client.delete(HTTPProtocol.HTTP, "localhost", TEST_PATH);
            assertTrue(response.isSuccess());
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    void putRequestSucceeds() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final int port = server.getPort();
            final RetryableHTTPClient client = new RetryableHTTPClient(
                    2, 25_000, SystemEpochClock.INSTANCE, () -> new HTTPClient(true, port));

            byte[] body = "{\"qty\":5}".getBytes();
            final HTTPResponse response = client.put(HTTPProtocol.HTTP, "localhost", TEST_PATH, body);
            assertTrue(response.isSuccess());
            assertEquals(200, response.getStatusCode());
        }
    }

    private static final byte[] HTTP_201 = (
            "HTTP/1.1 201 Created\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n"
    ).getBytes();

    private static final byte[] HTTP_204 = (
            "HTTP/1.1 204 No Content\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n"
    ).getBytes();

    @Test
    void status201IsNotRetried() throws Exception {
        // Server only has one response queued — if client retries it will get nothing and fail
        try (ScriptedHttpServer server = new ScriptedHttpServer(HTTP_201)) {
            final int port = server.getPort();
            final RetryableHTTPClient client = new RetryableHTTPClient(
                    2, 25_000, SystemEpochClock.INSTANCE, () -> new HTTPClient(true, port));

            final HTTPResponse response = client.post(HTTPProtocol.HTTP, "localhost", TEST_PATH, new byte[0]);
            assertTrue(response.isSuccess(), "201 should be treated as success, not retried");
            assertEquals(201, response.getStatusCode());
        }
    }

    @Test
    void status204DeleteIsNotRetried() throws Exception {
        try (ScriptedHttpServer server = new ScriptedHttpServer(HTTP_204)) {
            final int port = server.getPort();
            final RetryableHTTPClient client = new RetryableHTTPClient(
                    2, 25_000, SystemEpochClock.INSTANCE, () -> new HTTPClient(true, port));

            final HTTPResponse response = client.delete(HTTPProtocol.HTTP, "localhost", TEST_PATH);
            assertTrue(response.isSuccess(), "204 should be treated as success");
            assertEquals(204, response.getStatusCode());
        }
    }

    @Test
    void idleReconnect() throws Exception {
        // Controllable clock: start at 0, jump past threshold to trigger idle reconnect
        final long[] currentTime = {0L};
        final EpochClock clock = () -> currentTime[0];

        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final int port = server.getPort();
            final RetryableHTTPClient client = new RetryableHTTPClient(
                    2, 25_000, clock, () -> new HTTPClient(true, port));

            // First request
            HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", TEST_PATH);
            assertTrue(response.isSuccess());

            // Advance clock past idle threshold
            currentTime[0] = 30_000L;

            // Second request — triggers idle reconnect, still succeeds
            response = client.get(HTTPProtocol.HTTP, "localhost", TEST_PATH);
            assertTrue(response.isSuccess());
        }
    }

    /**
     * Serves scripted responses in sequence. Each request (on any connection) pops the next
     * response from the queue. If the queue is exhausted, closes the connection.
     */
    static final class ScriptedHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final BlockingQueue<byte[]> responses;
        private final AtomicBoolean running = new AtomicBoolean(true);

        ScriptedHttpServer(final byte[]... responses) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.responses = new LinkedBlockingQueue<>();
            for (byte[] r : responses) {
                this.responses.add(r);
            }
            final Thread acceptThread = new Thread(this::acceptLoop, "scripted-http-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    final Socket client = serverSocket.accept();
                    final Thread handler = new Thread(() -> handleConnection(client), "scripted-http-handler");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    if (running.get()) {
                        e.printStackTrace();
                    }
                }
            }
        }

        private void handleConnection(final Socket socket) {
            try {
                socket.setSoTimeout(5000);
                final InputStream in = socket.getInputStream();
                final OutputStream out = socket.getOutputStream();
                final byte[] buf = new byte[8192];

                while (!socket.isClosed()) {
                    int pos = 0;
                    boolean headersComplete = false;

                    while (pos < buf.length - 3) {
                        final int b = in.read();
                        if (b == -1) {
                            return;
                        }
                        buf[pos++] = (byte) b;
                        if (pos >= 4
                                && buf[pos - 4] == '\r' && buf[pos - 3] == '\n'
                                && buf[pos - 2] == '\r' && buf[pos - 1] == '\n') {
                            headersComplete = true;
                            break;
                        }
                    }

                    if (!headersComplete) {
                        return;
                    }

                    final String headerStr = new String(buf, 0, pos);

                    if (!headerStr.startsWith("GET ") && !headerStr.startsWith("POST ")
                            && !headerStr.startsWith("DELETE ") && !headerStr.startsWith("PUT ")) {
                        return;
                    }

                    int contentLength = 0;
                    final int clIdx = headerStr.indexOf("Content-Length: ");
                    if (clIdx >= 0) {
                        final int clEnd = headerStr.indexOf("\r\n", clIdx + 16);
                        try {
                            contentLength = Integer.parseInt(headerStr.substring(clIdx + 16, clEnd).trim());
                        } catch (NumberFormatException e) {
                            return;
                        }
                    }

                    int bodyRead = 0;
                    while (bodyRead < contentLength) {
                        final int n = in.read(buf, 0, Math.min(contentLength - bodyRead, buf.length));
                        if (n <= 0) {
                            return;
                        }
                        bodyRead += n;
                    }

                    final byte[] nextResponse = responses.poll();
                    if (nextResponse == null) {
                        return;
                    }
                    out.write(nextResponse);
                    out.flush();
                }
            } catch (Exception e) {
                // Timeout or closed connection
            } finally {
                try {
                    socket.close();
                } catch (IOException ignore) {
                }
            }
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            serverSocket.close();
        }
    }
}

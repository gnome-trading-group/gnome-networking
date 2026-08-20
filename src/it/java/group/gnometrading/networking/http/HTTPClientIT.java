package group.gnometrading.networking.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class HTTPClientIT {

    private static final byte[] HTTP_200 = (
            "HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 2\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n[]"
    ).getBytes();

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

    private static final byte[] HTTP_400 = (
            "HTTP/1.1 400 Bad Request\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n"
    ).getBytes();

    private static final byte[] HTTP_404 = (
            "HTTP/1.1 404 Not Found\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n"
    ).getBytes();

    private static final byte[] HTTP_429 = (
            "HTTP/1.1 429 Too Many Requests\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n"
    ).getBytes();

    private static final byte[] HTTP_500 = (
            "HTTP/1.1 500 Internal Server Error\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n"
    ).getBytes();

    private static final byte[] HTTP_200_WITH_BODY = (
            "HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 17\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n"
            + "{\"orderId\":\"123\"}"
    ).getBytes();

    // -------------------------------------------------------------------------
    // Happy-path tests
    // -------------------------------------------------------------------------

    @Test
    void deleteRequest() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            final HTTPResponse response = client.delete(HTTPProtocol.HTTP, "localhost", "/orders/123");
            assertTrue(response.isSuccess());
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    void deleteRequestWithHeaders() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            final HTTPResponse response = client.delete(
                    HTTPProtocol.HTTP, "localhost", "/orders/123",
                    "x-api-key", "secret", "x-timestamp", "9999");
            assertTrue(response.isSuccess());
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    void putRequestWithBody() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            byte[] body = "{\"qty\":5}".getBytes(StandardCharsets.UTF_8);
            final HTTPResponse response = client.put(HTTPProtocol.HTTP, "localhost", "/orders/123", body);
            assertTrue(response.isSuccess());
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    void response204NoContentHandledCorrectly() throws Exception {
        try (RetryableHTTPClientIT.ScriptedHttpServer server = new RetryableHTTPClientIT.ScriptedHttpServer(HTTP_204)) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            final HTTPResponse response = client.delete(HTTPProtocol.HTTP, "localhost", "/orders/123");
            assertTrue(response.isSuccess(), "204 should be treated as success");
            assertEquals(204, response.getStatusCode());
        }
    }

    @Test
    void response201CreatedHandledCorrectly() throws Exception {
        try (RetryableHTTPClientIT.ScriptedHttpServer server = new RetryableHTTPClientIT.ScriptedHttpServer(HTTP_201)) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            final HTTPResponse response = client.post(HTTPProtocol.HTTP, "localhost", "/orders", new byte[0]);
            assertTrue(response.isSuccess(), "201 should be treated as success");
            assertEquals(201, response.getStatusCode());
        }
    }

    @Test
    void largePostBodyExceeding2KB() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            byte[] largeBody = new byte[8192];
            for (int i = 0; i < largeBody.length; i++) {
                largeBody[i] = (byte) ('A' + (i % 26));
            }
            final HTTPResponse response = client.post(HTTPProtocol.HTTP, "localhost", "/api/data", largeBody);
            assertTrue(response.isSuccess());
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    void threeHeadersAreSentToServer() throws Exception {
        final AtomicReference<String> capturedHeaders = new AtomicReference<>();
        try (HeaderCapturingServer server = new HeaderCapturingServer(HTTP_200, capturedHeaders)) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            client.delete(HTTPProtocol.HTTP, "localhost", "/orders/123",
                    "x-api-key", "mykey",
                    "x-timestamp", "1700000000",
                    "x-signature", "deadbeef");

            String headers = capturedHeaders.get();
            assertNotNull(headers);
            assertTrue(headers.contains("x-api-key: mykey"), "Missing x-api-key header");
            assertTrue(headers.contains("x-timestamp: 1700000000"), "Missing x-timestamp header");
            assertTrue(headers.contains("x-signature: deadbeef"), "Missing x-signature header");
        }
    }

    @Test
    void preWarmingEstablishesConnectionBeforeRequest() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            client.connect(HTTPProtocol.HTTP, "localhost");

            // First request should succeed immediately without lazy connection setup
            final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", "/api/test");
            assertTrue(response.isSuccess());
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    void multipleDeleteRequestsOnSameConnection() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            for (int i = 0; i < 5; i++) {
                final HTTPResponse response = client.delete(HTTPProtocol.HTTP, "localhost", "/orders/" + i);
                assertTrue(response.isSuccess(), "Request " + i + " failed");
            }
        }
    }

    @Test
    void responseBodyIsAccessible() throws Exception {
        try (RetryableHTTPClientIT.ScriptedHttpServer server = new RetryableHTTPClientIT.ScriptedHttpServer(HTTP_200_WITH_BODY)) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", "/orders/123");
            assertTrue(response.isSuccess());
            final ByteBuffer body = response.getBody();
            assertNotNull(body);
            final byte[] actual = new byte[body.remaining()];
            body.get(actual);
            assertArrayEquals("{\"orderId\":\"123\"}".getBytes(StandardCharsets.UTF_8), actual);
        }
    }

    // -------------------------------------------------------------------------
    // 4xx / 5xx error status tests
    // -------------------------------------------------------------------------

    @Test
    void response400IsNotSuccess() throws Exception {
        try (RetryableHTTPClientIT.ScriptedHttpServer server = new RetryableHTTPClientIT.ScriptedHttpServer(HTTP_400)) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", "/orders/123");
            assertFalse(response.isSuccess());
            assertEquals(400, response.getStatusCode());
        }
    }

    @Test
    void response404IsNotSuccess() throws Exception {
        try (RetryableHTTPClientIT.ScriptedHttpServer server = new RetryableHTTPClientIT.ScriptedHttpServer(HTTP_404)) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", "/orders/unknown");
            assertFalse(response.isSuccess());
            assertEquals(404, response.getStatusCode());
        }
    }

    @Test
    void response429IsNotSuccess() throws Exception {
        try (RetryableHTTPClientIT.ScriptedHttpServer server = new RetryableHTTPClientIT.ScriptedHttpServer(HTTP_429)) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            final HTTPResponse response = client.post(HTTPProtocol.HTTP, "localhost", "/orders", new byte[0]);
            assertFalse(response.isSuccess());
            assertEquals(429, response.getStatusCode());
        }
    }

    @Test
    void response500IsNotSuccess() throws Exception {
        try (RetryableHTTPClientIT.ScriptedHttpServer server = new RetryableHTTPClientIT.ScriptedHttpServer(HTTP_500)) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", "/api/test");
            assertFalse(response.isSuccess());
            assertEquals(500, response.getStatusCode());
        }
    }

    // -------------------------------------------------------------------------
    // Connection failure / resilience tests
    // -------------------------------------------------------------------------

    @Test
    void serverImmediateCloseReturnsFailure() throws Exception {
        try (ImmediateCloseServer server = new ImmediateCloseServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            boolean failed = false;
            try {
                final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", "/test");
                failed = !response.isSuccess();
            } catch (IOException e) {
                failed = true;
            }
            assertTrue(failed, "Expected failure when server immediately closes connection");
        }
    }

    @Test
    void staleConnectionReconnectsTransparently() throws Exception {
        try (CloseAfterOneResponseServer server = new CloseAfterOneResponseServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());

            final HTTPResponse first = client.get(HTTPProtocol.HTTP, "localhost", "/orders");
            assertTrue(first.isSuccess(), "First request should succeed");

            server.awaitClosed(); // wait for server to have sent RST before writing again

            final HTTPResponse second = client.get(HTTPProtocol.HTTP, "localhost", "/orders");
            assertTrue(second.isSuccess(), "Second request should succeed after transparent reconnect");
        }
    }

    @Test
    void serverSendsPartialHeadersThenCloses() throws Exception {
        try (PartialResponseServer server = new PartialResponseServer("HTTP/1.1 200 OK\r\n")) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            boolean failed = false;
            try {
                final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", "/test");
                failed = !response.isSuccess();
            } catch (IOException e) {
                failed = true;
            }
            assertTrue(failed, "Expected failure for partial header response");
        }
    }

    @Test
    void serverSendsHeadersButBodyIsMissing() throws Exception {
        final String partialResponse = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n";
        try (PartialResponseServer server = new PartialResponseServer(partialResponse)) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            boolean failed = false;
            try {
                final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", "/test");
                failed = !response.isSuccess();
            } catch (IOException e) {
                failed = true;
            }
            assertTrue(failed, "Expected failure when response body is missing");
        }
    }

    @Test
    void malformedResponseThrowsIOException() throws Exception {
        try (PartialResponseServer server = new PartialResponseServer("GARBAGE\r\n\r\n")) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            assertThrows(IOException.class, () -> client.get(HTTPProtocol.HTTP, "localhost", "/test"));
        }
    }

    @Test
    void readTimeoutExhaustsRetriesOnHungServer() throws Exception {
        try (HangingServer server = new HangingServer()) {
            // 200ms × 3 retries = 600ms worst case, well within @Timeout(30)
            final HTTPClient client = new HTTPClient(true, server.getPort(), 200);
            final long start = System.currentTimeMillis();

            boolean failed = false;
            try {
                failed = !client.get(HTTPProtocol.HTTP, "localhost", "/test").isSuccess();
            } catch (IOException e) {
                failed = true;
            }

            assertTrue(failed, "Expected failure when server never responds");
            assertTrue(System.currentTimeMillis() - start >= 200, "Expected at least one timeout period to elapse");
        }
    }

    // -------------------------------------------------------------------------
    // Inner test server implementations
    // -------------------------------------------------------------------------

    static final class HeaderCapturingServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] response;
        private final AtomicReference<String> capturedHeaders;
        private final AtomicBoolean running = new AtomicBoolean(true);

        HeaderCapturingServer(final byte[] response, final AtomicReference<String> capturedHeaders) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.response = response;
            this.capturedHeaders = capturedHeaders;
            final Thread t = new Thread(this::acceptLoop, "header-capturing-server");
            t.setDaemon(true);
            t.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    final Socket client = serverSocket.accept();
                    final Thread handler = new Thread(() -> handle(client), "header-capturing-handler");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    if (running.get()) {
                        e.printStackTrace();
                    }
                }
            }
        }

        private void handle(final Socket socket) {
            try {
                socket.setSoTimeout(5000);
                final InputStream in = socket.getInputStream();
                final OutputStream out = socket.getOutputStream();
                final byte[] buf = new byte[8192];
                int pos = 0;

                while (pos < buf.length - 3) {
                    final int b = in.read();
                    if (b == -1) return;
                    buf[pos++] = (byte) b;
                    if (pos >= 4
                            && buf[pos - 4] == '\r' && buf[pos - 3] == '\n'
                            && buf[pos - 2] == '\r' && buf[pos - 1] == '\n') {
                        break;
                    }
                }

                capturedHeaders.set(new String(buf, 0, pos, StandardCharsets.UTF_8));
                out.write(response);
                out.flush();
            } catch (Exception e) {
                // ignore
            } finally {
                try { socket.close(); } catch (IOException ignore) {}
            }
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            serverSocket.close();
        }
    }

    /**
     * Accepts TCP connections and immediately closes them without sending any response.
     */
    static final class ImmediateCloseServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final AtomicBoolean running = new AtomicBoolean(true);

        ImmediateCloseServer() throws IOException {
            serverSocket = new ServerSocket(0);
            final Thread t = new Thread(this::acceptLoop, "immediate-close-server");
            t.setDaemon(true);
            t.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    final Socket s = serverSocket.accept();
                    try { s.close(); } catch (IOException ignore) {}
                } catch (IOException e) {
                    if (running.get()) e.printStackTrace();
                }
            }
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            serverSocket.close();
        }
    }

    /**
     * Serves a single 200 response per connection then closes with RST (SO_LINGER=0),
     * while continuing to accept new connections. Used to test the stale-connection
     * write-failure → reconnect path in HTTPClient.
     */
    static final class CloseAfterOneResponseServer implements AutoCloseable {

        private static final byte[] RESPONSE = (
                "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: 2\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n[]"
        ).getBytes();

        private final ServerSocket serverSocket;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final CountDownLatch firstConnectionClosed = new CountDownLatch(1);

        CloseAfterOneResponseServer() throws IOException {
            serverSocket = new ServerSocket(0);
            final Thread t = new Thread(this::acceptLoop, "close-after-one-server");
            t.setDaemon(true);
            t.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        void awaitClosed() throws InterruptedException {
            firstConnectionClosed.await(5, TimeUnit.SECONDS);
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    final Socket s = serverSocket.accept();
                    final Thread handler = new Thread(() -> handle(s), "close-after-one-handler");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    if (running.get()) e.printStackTrace();
                }
            }
        }

        private void handle(final Socket socket) {
            try {
                socket.setSoTimeout(5000);
                socket.setSoLinger(true, 0); // RST on close — makes write() to dead socket return -1
                final InputStream in = socket.getInputStream();
                final OutputStream out = socket.getOutputStream();
                final byte[] buf = new byte[4096];
                int pos = 0;
                while (pos < buf.length - 3) {
                    final int b = in.read();
                    if (b == -1) return;
                    buf[pos++] = (byte) b;
                    if (pos >= 4
                            && buf[pos - 4] == '\r' && buf[pos - 3] == '\n'
                            && buf[pos - 2] == '\r' && buf[pos - 1] == '\n') {
                        break;
                    }
                }
                out.write(RESPONSE);
                out.flush();
            } catch (Exception e) {
                // ignore
            } finally {
                try { socket.close(); } catch (IOException ignore) {}
                firstConnectionClosed.countDown();
            }
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            serverSocket.close();
        }
    }

    /**
     * Accepts connections, reads the request, then blocks forever without responding.
     * Used to verify that SO_RCVTIMEO causes read retries to exhaust and return failure.
     */
    static final class HangingServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final AtomicBoolean running = new AtomicBoolean(true);

        HangingServer() throws IOException {
            serverSocket = new ServerSocket(0);
            final Thread t = new Thread(this::acceptLoop, "hanging-server");
            t.setDaemon(true);
            t.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    final Socket s = serverSocket.accept();
                    final Thread handler = new Thread(() -> hang(s), "hanging-handler");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    if (running.get()) e.printStackTrace();
                }
            }
        }

        private void hang(final Socket socket) {
            try {
                final InputStream in = socket.getInputStream();
                final byte[] buf = new byte[4096];
                int pos = 0;
                while (pos < buf.length - 3) {
                    final int b = in.read();
                    if (b == -1) return;
                    buf[pos++] = (byte) b;
                    if (pos >= 4
                            && buf[pos - 4] == '\r' && buf[pos - 3] == '\n'
                            && buf[pos - 2] == '\r' && buf[pos - 1] == '\n') {
                        break;
                    }
                }
                Thread.sleep(Long.MAX_VALUE); // never respond
            } catch (Exception e) {
                // ignore
            } finally {
                try { socket.close(); } catch (IOException ignore) {}
            }
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            serverSocket.close();
        }
    }

    /**
     * Reads a request and responds with a configurable partial response, then closes.
     * Used to test client behavior when server sends incomplete or malformed data.
     */
    static final class PartialResponseServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] partialResponse;
        private final AtomicBoolean running = new AtomicBoolean(true);

        PartialResponseServer(final String partialResponse) throws IOException {
            this(partialResponse.getBytes(StandardCharsets.UTF_8));
        }

        PartialResponseServer(final byte[] partialResponse) throws IOException {
            this.partialResponse = partialResponse;
            serverSocket = new ServerSocket(0);
            final Thread t = new Thread(this::acceptLoop, "partial-response-server");
            t.setDaemon(true);
            t.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    final Socket s = serverSocket.accept();
                    final Thread handler = new Thread(() -> handle(s), "partial-response-handler");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    if (running.get()) e.printStackTrace();
                }
            }
        }

        private void handle(final Socket socket) {
            try {
                socket.setSoTimeout(5000);
                final InputStream in = socket.getInputStream();
                final OutputStream out = socket.getOutputStream();
                final byte[] buf = new byte[4096];
                int pos = 0;
                while (pos < buf.length - 3) {
                    final int b = in.read();
                    if (b == -1) return;
                    buf[pos++] = (byte) b;
                    if (pos >= 4
                            && buf[pos - 4] == '\r' && buf[pos - 3] == '\n'
                            && buf[pos - 2] == '\r' && buf[pos - 1] == '\n') {
                        break;
                    }
                }
                out.write(partialResponse);
                out.flush();
            } catch (Exception e) {
                // ignore
            } finally {
                try { socket.close(); } catch (IOException ignore) {}
            }
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            serverSocket.close();
        }
    }
}

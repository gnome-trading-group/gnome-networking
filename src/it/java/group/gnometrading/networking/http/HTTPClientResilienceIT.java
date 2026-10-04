package group.gnometrading.networking.http;

import group.gnometrading.strings.ViewString;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class HTTPClientResilienceIT {

    private static final String HOST = "localhost";

    @Test
    void chunkedResponseSplitAcrossWritesIsReassembled() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            final OutputStream out = socket.getOutputStream();
            while (readRequest(socket.getInputStream())) {
                out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n6\r\n".getBytes());
                out.flush();
                Thread.sleep(50);
                out.write("{\"a\":1\r\n4\r\n,\"b\"\r\n".getBytes());
                out.flush();
                Thread.sleep(50);
                out.write("3\r\n:2}\r\n0\r\n\r\n".getBytes());
                out.flush();
            }
        })) {
            final HTTPClient client = client(server, 1_000, 1 << 15, 1);
            for (int i = 0; i < 3; i++) {
                final HTTPResponse response = client.get(HTTPProtocol.HTTP, HOST, "/chunked");
                assertTrue(response.isSuccess());
                assertEquals("{\"a\":1,\"b\":2}", body(response));
            }
            assertEquals(1, server.connections(), "keep-alive connection should be reused");
        }
    }

    @Test
    void lateResponseIsNeverReturnedToTheNextRequest() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            final OutputStream out = socket.getOutputStream();
            while (readRequest(socket.getInputStream())) {
                if (connection == 0) {
                    Thread.sleep(600);
                    out.write(response("late"));
                } else {
                    out.write(response("fresh"));
                }
                out.flush();
            }
        })) {
            final HTTPClient client = client(server, 100, 1 << 15, 1);

            final HTTPResponse timedOut = client.get(HTTPProtocol.HTTP, HOST, "/slow");
            assertFalse(timedOut.isSuccess());

            Thread.sleep(700);
            final HTTPResponse next = client.get(HTTPProtocol.HTTP, HOST, "/fast");
            assertTrue(next.isSuccess());
            assertEquals("fresh", body(next));
        }
    }

    @Test
    void responseLargerThanTheReadBufferFailsLoudly() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            final OutputStream out = socket.getOutputStream();
            while (readRequest(socket.getInputStream())) {
                out.write(response(connection == 0 ? "x".repeat(4000) : "small"));
                out.flush();
            }
        })) {
            final HTTPClient client = client(server, 1_000, 1024, 1);

            final IOException e = assertThrows(IOException.class, () -> client.get(HTTPProtocol.HTTP, HOST, "/big"));
            assertTrue(e.getMessage().contains("full"), e.getMessage());

            final HTTPResponse next = client.get(HTTPProtocol.HTTP, HOST, "/small");
            assertEquals("small", body(next));
        }
    }

    @Test
    void connectionTheServerClosedIsReplacedBeforeSending() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            if (readRequest(socket.getInputStream())) {
                socket.getOutputStream().write(response("only-one"));
                socket.getOutputStream().flush();
            }
            // Mimics a server's idle timeout: the connection is closed after a single response.
        })) {
            final HTTPClient client = client(server, 1_000, 1 << 15, 1);
            assertTrue(client.get(HTTPProtocol.HTTP, HOST, "/first").isSuccess());
            Thread.sleep(200);

            final HTTPResponse second = client.get(HTTPProtocol.HTTP, HOST, "/second");
            assertTrue(second.isSuccess(), "a closed idle connection must not fail the next request");
            assertEquals(2, server.connections());
        }
    }

    @Test
    void connectionCloseResponseIsNotReused() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            final OutputStream out = socket.getOutputStream();
            while (readRequest(socket.getInputStream())) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").getBytes());
                out.flush();
            }
        })) {
            final HTTPClient client = client(server, 1_000, 1 << 15, 1);
            assertTrue(client.get(HTTPProtocol.HTTP, HOST, "/a").isSuccess());
            assertTrue(client.get(HTTPProtocol.HTTP, HOST, "/b").isSuccess());
            assertEquals(2, server.connections());
        }
    }

    @Test
    void asyncRequestsOverlapAcrossConnections() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            final OutputStream out = socket.getOutputStream();
            while (readRequest(socket.getInputStream())) {
                Thread.sleep(300);
                out.write(response("conn-" + connection));
                out.flush();
            }
        })) {
            final HTTPClient client = client(server, 1_000, 1 << 15, 2);
            client.connect(HTTPProtocol.HTTP, HOST);
            final ViewString path = new ViewString("/order");

            final long start = System.nanoTime();
            final int first = send(client, path);
            final int second = send(client, path);
            assertNotEquals(first, second);
            assertEquals(-1, send(client, path), "both connections are busy");

            final String firstBody = awaitBody(client, first);
            final String secondBody = awaitBody(client, second);
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertNotEquals(firstBody, secondBody);
            assertTrue(elapsedMillis < 550, "requests should overlap, took " + elapsedMillis + "ms");
        }
    }

    @Test
    void abandonedRequestClosesItsConnection() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            final OutputStream out = socket.getOutputStream();
            while (readRequest(socket.getInputStream())) {
                if (connection == 0) {
                    Thread.sleep(300);
                }
                out.write(response("conn-" + connection));
                out.flush();
            }
        })) {
            final HTTPClient client = client(server, 1_000, 1 << 15, 1);
            final int abandoned = send(client, new ViewString("/slow"));
            client.abandon(HOST, abandoned);

            final HTTPResponse next = client.get(HTTPProtocol.HTTP, HOST, "/next");
            assertEquals("conn-1", body(next));
        }
    }

    @Test
    void requestLargerThanTheDefaultWriteBufferIsSentWithALargerOne() throws Exception {
        final int bodySize = 100_000;
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            final InputStream in = socket.getInputStream();
            final StringBuilder headers = new StringBuilder();
            while (!headers.toString().endsWith("\r\n\r\n")) {
                headers.append((char) in.read());
            }
            final byte[] body = in.readNBytes(bodySize);
            socket.getOutputStream().write(response("got-" + body.length));
            socket.getOutputStream().flush();
        })) {
            final HTTPClient client = HTTPClient.builder()
                    .withPort(server.port())
                    .withWriteBufferSize(1 << 18)
                    .withKeepAlive(10, 5, 3)
                    .build();
            final HTTPResponse response = client.post(HTTPProtocol.HTTP, HOST, "/batch", new byte[bodySize]);
            assertEquals("got-" + bodySize, body(response));
        }
    }

    @Test
    void emptyBodyDoesNotExposeThePreviousResponse() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            final OutputStream out = socket.getOutputStream();
            int request = 0;
            while (readRequest(socket.getInputStream())) {
                out.write(request++ == 0 ? response("{\"order_id\":\"A\"}") : response(""));
                out.flush();
            }
        })) {
            final HTTPClient client = client(server, 1_000, 1 << 15, 1);
            assertEquals("{\"order_id\":\"A\"}", body(client.get(HTTPProtocol.HTTP, HOST, "/first")));

            final HTTPResponse empty = client.get(HTTPProtocol.HTTP, HOST, "/second");
            assertTrue(empty.isSuccess());
            assertFalse(empty.getBody().hasRemaining(), "an empty body must not carry the previous response");
        }
    }

    @Test
    void bytesBeyondTheResponseRetireTheConnection() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            final OutputStream out = socket.getOutputStream();
            while (readRequest(socket.getInputStream())) {
                // A buggy proxy: two extra bytes after the declared body.
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        })) {
            final HTTPClient client = client(server, 1_000, 1 << 15, 1);
            assertEquals("{}", body(client.get(HTTPProtocol.HTTP, HOST, "/a")));
            assertEquals("{}", body(client.get(HTTPProtocol.HTTP, HOST, "/b")));
            assertEquals(2, server.connections(), "the connection with stray bytes must not be reused");
        }
    }

    @Test
    void absurdChunkSizeFailsAndRetiresTheConnection() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            final OutputStream out = socket.getOutputStream();
            while (readRequest(socket.getInputStream())) {
                if (connection == 0) {
                    out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n7fffffff\r\nabc".getBytes(StandardCharsets.US_ASCII));
                } else {
                    out.write(response("fine"));
                }
                out.flush();
            }
        })) {
            final HTTPClient client = client(server, 300, 1 << 15, 1);
            assertFalse(client.get(HTTPProtocol.HTTP, HOST, "/bad").isSuccess());
            assertEquals("fine", body(client.get(HTTPProtocol.HTTP, HOST, "/good")));
        }
    }

    @Test
    void connectionsClosedByTheServerAreReconnectedCleanly() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            if (!readRequest(socket.getInputStream())) {
                return;
            }
            // Alternating sizes: a reconnected connection must not carry the previous response's bytes or length.
            final String body = connection % 2 == 0 ? "x".repeat(5_000) : "short-" + connection;
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
                    + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        })) {
            final HTTPClient client = client(server, 1_000, 1 << 15, 1);
            for (int i = 0; i < 20; i++) {
                final String expected = i % 2 == 0 ? "x".repeat(5_000) : "short-" + i;
                assertEquals(expected, body(client.get(HTTPProtocol.HTTP, HOST, "/" + i)), "request " + i);
            }
            assertEquals(20, server.connections());
        }
    }

    @Test
    void responseSpanningReadsSucceedsWhenReadsNeverTimeOut() throws Exception {
        try (ScriptedServer server = new ScriptedServer((connection, socket) -> {
            final OutputStream out = socket.getOutputStream();
            while (readRequest(socket.getInputStream())) {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nhello".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(300);
                out.write("world".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        })) {
            // A read timeout of 0 means reads wait indefinitely, so there is no response deadline to derive.
            final HTTPClient client = client(server, 0, 1 << 15, 1);
            assertEquals("helloworld", body(client.get(HTTPProtocol.HTTP, HOST, "/split")));
        }
    }

    private static HTTPClient client(ScriptedServer server, int readTimeoutMs, int readBufferSize, int connections) {
        return HTTPClient.builder()
                .withPort(server.port())
                .withReadTimeout(readTimeoutMs)
                .withConnectTimeout(2_000)
                .withReadBufferSize(readBufferSize)
                .withConnectionsPerHost(connections)
                .build();
    }

    private static int send(HTTPClient client, ViewString path) throws IOException {
        return client.send(HTTPProtocol.HTTP, HOST, HTTPMethod.GET, path, null, 0,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static String awaitBody(HTTPClient client, int connection) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final HTTPResponse response = client.poll(HOST, connection);
            if (response != null) {
                assertTrue(response.isSuccess());
                return body(response);
            }
            Thread.sleep(1);
        }
        throw new AssertionError("no response on connection " + connection);
    }

    private static String body(HTTPResponse response) {
        final byte[] bytes = new byte[response.getBody().remaining()];
        response.getBody().duplicate().get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static byte[] response(String body) {
        return ("HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\nConnection: keep-alive\r\n\r\n" + body)
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** Reads one bodiless request's headers; false once the client has closed the connection. */
    private static boolean readRequest(InputStream in) throws IOException {
        int matched = 0;
        while (matched < 4) {
            final int b = in.read();
            if (b < 0) {
                return false;
            }
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
                    ? matched + 1
                    : 0;
        }
        return true;
    }

    @FunctionalInterface
    interface ConnectionHandler {
        void handle(int connection, Socket socket) throws Exception;
    }

    /** Accepts connections and hands each, with its 0-based arrival index, to the handler on its own thread. */
    static final class ScriptedServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final AtomicInteger accepted = new AtomicInteger();

        ScriptedServer(ConnectionHandler handler) throws IOException {
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            final Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        final Socket socket = serverSocket.accept();
                        final int index = accepted.getAndIncrement();
                        final Thread worker = new Thread(() -> {
                            try (socket) {
                                handler.handle(index, socket);
                            } catch (Exception ignored) {
                                // The client closing mid-response is expected in several tests.
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

        int connections() {
            return accepted.get();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}

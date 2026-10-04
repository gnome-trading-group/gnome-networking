package group.gnometrading.networking.http;

import group.gnometrading.networking.sockets.SSLConfig;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HTTP client over real TLS: TLS 1.3 sends session tickets after the handshake and a close_notify before
 * closing, both of which sit unread on an idle connection, so the liveness probe has to see through them.
 */
@Timeout(30)
class HTTPSClientIT {

    private static final String HOST = "localhost";

    private static SSLConfig sslConfig;
    private static String caFile;

    @BeforeAll
    static void createCertificate() throws Exception {
        sslConfig = new SSLConfig();
        caFile = sslConfig.writeCaFile();
    }

    @Test
    void keepAliveConnectionIsReusedAcrossChunkedResponses() throws Exception {
        try (TlsServer server = new TlsServer((connection, socket, raw) -> {
            int request = 0;
            while (readRequest(socket.getInputStream())) {
                final String body = "{\"n\":" + request++ + ",\"pad\":\"" + "x".repeat(5_000) + "\"}";
                writeChunked(socket.getOutputStream(), body);
            }
        })) {
            final HTTPClient client = client(server, 1_000, 3_000);
            // Pre-warming leaves the server's session tickets unread on the idle connection until the first request.
            client.connect(HTTPProtocol.HTTPS, HOST);
            Thread.sleep(200);
            for (int i = 0; i < 5; i++) {
                final HTTPResponse response = client.get(HTTPProtocol.HTTPS, HOST, "/markets");
                assertTrue(response.isSuccess());
                assertTrue(body(response).startsWith("{\"n\":" + i + ","), body(response).substring(0, 20));
                assertTrue(body(response).endsWith("\"}"));
            }
            assertEquals(1, server.connections(), "unread session tickets must not make the pre-warmed connection look stale");
        }
    }

    @Test
    void connectionClosedWithCloseNotifyIsReplacedBeforeSending() throws Exception {
        try (TlsServer server = new TlsServer((connection, socket, raw) -> {
            if (readRequest(socket.getInputStream())) {
                socket.getOutputStream().write(response("first-" + connection));
                socket.getOutputStream().flush();
            }
            // SSLSocket.close() sends a close_notify alert before the TCP FIN, as a server's idle timeout would.
        })) {
            final HTTPClient client = client(server, 1_000, 3_000);
            assertEquals("first-0", body(client.get(HTTPProtocol.HTTPS, HOST, "/a")));
            Thread.sleep(200);

            final HTTPResponse second = client.get(HTTPProtocol.HTTPS, HOST, "/b");
            assertTrue(second.isSuccess(), "a closed idle connection must not fail the next request");
            assertEquals("first-1", body(second));
        }
    }

    @Test
    void connectionDroppedWithoutCloseNotifyIsReplacedBeforeSending() throws Exception {
        try (TlsServer server = new TlsServer((connection, socket, raw) -> {
            if (readRequest(socket.getInputStream())) {
                socket.getOutputStream().write(response("only-" + connection));
                socket.getOutputStream().flush();
            }
            // Later, once the connection sits idle, reset it underneath TLS with no close_notify, as a load
            // balancer would. Resetting straight away would race the response: Linux discards unread data on reset.
            Thread.sleep(200);
            raw.setSoLinger(true, 0);
            raw.close();
        })) {
            final HTTPClient client = client(server, 1_000, 3_000);
            assertEquals("only-0", body(client.get(HTTPProtocol.HTTPS, HOST, "/a")));
            Thread.sleep(500);

            final HTTPResponse second = client.get(HTTPProtocol.HTTPS, HOST, "/b");
            assertTrue(second.isSuccess());
            assertEquals("only-1", body(second));
        }
    }

    @Test
    void lateResponseOverTlsIsNeverReturnedToTheNextRequest() throws Exception {
        try (TlsServer server = new TlsServer((connection, socket, raw) -> {
            while (readRequest(socket.getInputStream())) {
                if (connection == 0) {
                    Thread.sleep(800);
                    socket.getOutputStream().write(response("late"));
                } else {
                    socket.getOutputStream().write(response("fresh"));
                }
                socket.getOutputStream().flush();
            }
        })) {
            final HTTPClient client = client(server, 100, 300);

            final long start = System.nanoTime();
            assertFalse(client.get(HTTPProtocol.HTTPS, HOST, "/slow").isSuccess());
            assertTrue(System.nanoTime() - start < 2_000_000_000L, "the response timeout should bound the wait");

            Thread.sleep(800);
            assertEquals("fresh", body(client.get(HTTPProtocol.HTTPS, HOST, "/fast")));
        }
    }

    private static HTTPClient client(TlsServer server, int readTimeoutMs, int responseTimeoutMs) {
        return HTTPClient.builder()
                .withPort(server.port())
                .withReadTimeout(readTimeoutMs)
                .withResponseTimeout(responseTimeoutMs)
                .withConnectTimeout(2_000)
                .withReadBufferSize(1 << 16)
                .withSocketFactory(new NativeSSLSocketFactory(caFile))
                .build();
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

    /** Writes the body as several chunks, flushing each so they arrive as separate TLS records. */
    private static void writeChunked(OutputStream out, String body) throws IOException {
        out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n".getBytes());
        final byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        for (int offset = 0; offset < bytes.length; offset += 1_500) {
            final int length = Math.min(1_500, bytes.length - offset);
            out.write((Integer.toHexString(length) + "\r\n").getBytes());
            out.write(bytes, offset, length);
            out.write("\r\n".getBytes());
            out.flush();
        }
        out.write("0\r\n\r\n".getBytes());
        out.flush();
    }

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
    interface Handler {
        void handle(int connection, SSLSocket socket, Socket raw) throws Exception;
    }

    /** A blocking TLS server: each accepted connection is handed to the handler on its own thread. */
    static final class TlsServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final AtomicInteger accepted = new AtomicInteger();

        TlsServer(Handler handler) throws IOException {
            // Plain sockets with TLS layered on top, so a handler can also drop the TCP connection directly.
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            final Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        final Socket raw = serverSocket.accept();
                        final int index = accepted.getAndIncrement();
                        final Thread worker = new Thread(() -> {
                            try (raw; SSLSocket socket = (SSLSocket) sslConfig.getSSLContext().getSocketFactory()
                                    .createSocket(raw, null, raw.getPort(), false)) {
                                socket.setUseClientMode(false);
                                handler.handle(index, socket, raw);
                            } catch (Exception ignored) {
                                // The client hanging up mid-response is expected in several tests.
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

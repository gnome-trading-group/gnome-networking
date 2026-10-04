package group.gnometrading.networking.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal HTTP/1.1 keep-alive server for integration tests.
 * Serves a canned 200 JSON response for valid GET/POST requests.
 * Closes the connection on garbled/unparseable input.
 */
class LocalTestHttpServer implements AutoCloseable {

    private static final String RESPONSE_BODY = "[]";
    private static final byte[] HTTP_200 = (
        "HTTP/1.1 200 OK\r\n"
        + "Content-Type: application/json\r\n"
        + "Content-Length: " + RESPONSE_BODY.length() + "\r\n"
        + "Connection: keep-alive\r\n"
        + "\r\n"
        + RESPONSE_BODY
    ).getBytes();

    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread acceptThread;

    LocalTestHttpServer() throws IOException {
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptThread = new Thread(this::acceptLoop, "test-http-server");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    int getPort() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket client = serverSocket.accept();
                final Thread handler = new Thread(() -> handleConnection(client), "test-http-handler");
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

                out.write(HTTP_200);
                out.flush();
            }
        } catch (Exception e) {
            // Timeout or closed connection — normal during concurrency tests
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

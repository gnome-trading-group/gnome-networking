package group.gnometrading.networking.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * A keep-alive HTTP/1.1 server (plain or TLS) whose behaviour each request picks by its path. The body of every
 * answer starts with the request's path, so a client can tell which request an answer belongs to.
 *
 * <ul>
 *   <li>{@code /delay/<ms>/...}: answers after that long
 *   <li>{@code /echo}: answers with the whole request
 *   <li>{@code /drop/...}: closes without answering
 *   <li>{@code /close/...}: answers with {@code Connection: close}, then closes
 *   <li>{@code /half/...}: sends part of the answer, then closes
 *   <li>{@code /stall/<ms>/...}: sends part of the answer, waits that long, then closes
 *   <li>{@code /extra/...}: answers, then sends a second answer nobody asked for
 *   <li>{@code /chunked/<bytes>/...}: answers with a chunked body that long (the path, padded)
 * </ul>
 */
final class ChaosServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final SSLContext tls;
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<Connection> connections = new CopyOnWriteArrayList<>();

    ChaosServer() throws IOException {
        this(null, 0);
    }

    /** @param tls null for plain HTTP; @param port 0 for any free port */
    ChaosServer(final SSLContext tls, final int port) throws IOException {
        this.tls = tls;
        this.serverSocket = new ServerSocket(port, 100, InetAddress.getLoopbackAddress());
        final Thread acceptor = new Thread(this::accept, "chaos-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return this.serverSocket.getLocalPort();
    }

    int accepted() {
        return this.accepted.get();
    }

    /** Closes one connection that is waiting for its next request, if any: a server dropping an idle connection. */
    boolean closeAnIdleConnection() {
        final Object[] open = this.connections.toArray();
        if (open.length == 0) {
            return false;
        }
        final Connection connection = (Connection) open[ThreadLocalRandom.current().nextInt(open.length)];
        if (!connection.idle) {
            return false;
        }
        connection.closeQuietly();
        return true;
    }

    /** Resets every connection (an RST, not a FIN), as a crashed server or a dropped NAT entry would. */
    void resetAll() {
        for (final Connection connection : this.connections) {
            connection.reset();
        }
    }

    private void accept() {
        while (this.running.get()) {
            try {
                final Socket raw = this.serverSocket.accept();
                this.accepted.incrementAndGet();
                final Connection connection = new Connection(raw);
                this.connections.add(connection);
                final Thread handler = new Thread(() -> {
                    try {
                        connection.serve();
                    } finally {
                        this.connections.remove(connection);
                        connection.closeQuietly();
                    }
                }, "chaos-handler");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        this.running.set(false);
        this.serverSocket.close();
        for (final Connection connection : this.connections) {
            connection.closeQuietly();
        }
    }

    private final class Connection {
        private final Socket raw;
        private volatile Socket socket;
        private volatile boolean idle;

        Connection(final Socket raw) {
            this.raw = raw;
        }

        void serve() {
            try {
                Socket socket = this.raw;
                if (tls != null) {
                    final SSLSocket ssl = (SSLSocket) tls.getSocketFactory().createSocket(this.raw, null, this.raw.getPort(), true);
                    ssl.setUseClientMode(false);
                    socket = ssl;
                }
                this.socket = socket;
                final InputStream in = socket.getInputStream();
                final OutputStream out = socket.getOutputStream();
                while (true) {
                    this.idle = true;
                    final String request = readRequest(in);
                    this.idle = false;
                    if (request == null || !answer(request, out)) {
                        return;
                    }
                }
            } catch (Exception e) {
                // Closed by the client, by closeAnIdleConnection, or by the server shutting down.
            }
        }

        void reset() {
            try {
                this.raw.setSoLinger(true, 0);
                this.raw.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }

        void closeQuietly() {
            try {
                final Socket socket = this.socket;
                if (socket != null) {
                    socket.close();
                }
                this.raw.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    /** @return false once the connection should be closed */
    private static boolean answer(final String request, final OutputStream out) throws Exception {
        final int pathStart = request.indexOf(' ') + 1;
        final String path = request.substring(pathStart, request.indexOf(' ', pathStart));
        final String[] parts = path.split("/");
        final String mode = parts.length > 1 ? parts[1] : "";
        switch (mode) {
            case "drop":
                return false;
            case "half": {
                out.write(partialAnswer(path));
                out.flush();
                return false;
            }
            case "stall": {
                out.write(partialAnswer(path));
                out.flush();
                Thread.sleep(Long.parseLong(parts[2]));
                return false;
            }
            case "delay":
                Thread.sleep(Long.parseLong(parts[2]));
                write(out, path, false);
                return true;
            case "echo":
                write(out, request, false);
                return true;
            case "close":
                write(out, path, true);
                return false;
            case "extra":
                // One write, so both answers arrive together: an answer nobody asked for that arrives later can't be
                // told apart from the answer to the next request, by any HTTP/1.1 client.
                out.write((answerText(path, false) + answerText("/unsolicited", false)).getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return true;
            case "chunked":
                writeChunked(out, pad(path, Integer.parseInt(parts[2])));
                return true;
            default:
                write(out, path, false);
                return true;
        }
    }

    private static String readRequest(final InputStream in) throws IOException {
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            final int b = in.read();
            if (b < 0) {
                return null;
            }
            request.write(b);
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
                    ? matched + 1
                    : (b == '\r' ? 1 : 0);
        }
        final String head = request.toString(StandardCharsets.US_ASCII);
        final int lengthAt = head.indexOf("Content-Length: ");
        if (lengthAt >= 0) {
            final int length = Integer.parseInt(head.substring(lengthAt + 16, head.indexOf("\r\n", lengthAt)));
            request.write(in.readNBytes(length));
        }
        return request.toString(StandardCharsets.US_ASCII);
    }

    private static void write(final OutputStream out, final String body, final boolean close) throws IOException {
        out.write(answerText(body, close).getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String answerText(final String body, final boolean close) {
        return "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n"
                + (close ? "Connection: close\r\n" : "") + "\r\n" + body;
    }

    private static byte[] partialAnswer(final String path) {
        return ("HTTP/1.1 200 OK\r\nContent-Length: " + (path.length() + 100) + "\r\n\r\n" + path)
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static void writeChunked(final OutputStream out, final String body) throws IOException {
        out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        final byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        for (int offset = 0; offset < bytes.length; offset += 1_500) {
            final int length = Math.min(1_500, bytes.length - offset);
            out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes, offset, length);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    static String pad(final String path, final int length) {
        return path + "x".repeat(Math.max(0, length - path.length()));
    }
}

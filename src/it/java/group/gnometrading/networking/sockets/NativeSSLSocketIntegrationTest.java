package group.gnometrading.networking.sockets;

import group.gnometrading.networking.BaseSocketServer;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;
import org.bouncycastle.asn1.x509.GeneralName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

public class NativeSSLSocketIntegrationTest extends BaseNativeSocketIntegrationTest {

    private SSLConfig sslConfig;

    @Override
    protected GnomeSocket createClient(int port) throws IOException {
        try {
            return new NativeSSLSocketFactory(sslConfig.writeCaFile()).createSocket(TEST_HOST, port);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected BaseSocketServer createServer() {
        try {
            sslConfig = new SSLConfig();
            return new SSLSocketServer(TEST_HOST, 0, sslConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Timeout(value = 20)
    void testUntrustedCertificateIsRejected() throws IOException {
        clientSocket = new NativeSSLSocketFactory(null).createSocket(TEST_HOST, server.getPort());

        SSLHandshakeException e = assertThrows(SSLHandshakeException.class, clientSocket::connect);
        assertTrue(e.getMessage().contains("Certificate verification failed"), e.getMessage());
        assertFalse(clientSocket.isConnected());
    }

    @Test
    @Timeout(value = 20)
    void testCertificateForAnotherHostIsRejected() throws Exception {
        server.stop();
        SSLConfig otherHost = new SSLConfig(new GeneralName(GeneralName.dNSName, "example.com"));
        server = new SSLSocketServer(TEST_HOST, 0, otherHost);
        serverExecutor.schedule(server, 0, TimeUnit.MILLISECONDS);
        server.awaitReady(5, TimeUnit.SECONDS);

        clientSocket = new NativeSSLSocketFactory(otherHost.writeCaFile()).createSocket(TEST_HOST, server.getPort());

        assertThrows(SSLHandshakeException.class, clientSocket::connect);
    }

    @Test
    @Timeout(value = 20)
    void testStalledHandshakeTimesOut() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            clientSocket = new NativeSSLSocketFactory(sslConfig.writeCaFile()).createSocket(TEST_HOST, silent.getLocalPort());
            clientSocket.setConnectTimeout(300);

            long start = System.nanoTime();
            assertThrows(SocketTimeoutException.class, clientSocket::connect);
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
            assertFalse(clientSocket.isConnected());
        }
    }

    @Test
    @Timeout(value = 20)
    void testReconnectResumesTheSession() throws Exception {
        // A blocking echo server: the selector-based test server can drop a TLS record split across reads.
        try (ServerSocket echoServer = sslConfig.getSSLContext().getServerSocketFactory()
                .createServerSocket(0, 2, InetAddress.getByName(TEST_HOST))) {
            Thread acceptor = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try (Socket accepted = echoServer.accept()) {
                        byte[] buffer = new byte[64];
                        int n = accepted.getInputStream().read(buffer);
                        accepted.getOutputStream().write(buffer, 0, Math.max(n, 0));
                        accepted.getOutputStream().flush();
                        accepted.getInputStream().read(); // hold the connection until the client closes it
                    } catch (IOException ignored) {
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            NativeSSLSocket socket = (NativeSSLSocket) new NativeSSLSocketFactory(sslConfig.writeCaFile())
                    .createSocket(TEST_HOST, echoServer.getLocalPort());
            clientSocket = socket;
            socket.connect();
            assertFalse(socket.isSessionReused());
            // TLS 1.3 sends its session ticket after the handshake, so it is only seen once something is read.
            echo(socket, "ticket please");
            socket.close();

            socket.connect();
            assertTrue(socket.isSessionReused());
            echo(socket, "resumed");
        }
    }

    private static void echo(GnomeSocket socket, String message) throws IOException {
        // Without a read timeout a missing reply would block the native read past any JUnit timeout.
        socket.setReadTimeout(1000);
        ByteBuffer out = ByteBuffer.allocateDirect(64);
        out.put(message.getBytes(StandardCharsets.US_ASCII)).flip();
        socket.write(out);
        ByteBuffer in = ByteBuffer.allocateDirect(64);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (in.position() < message.length() && System.nanoTime() < deadline) {
            if (socket.read(in) < 0) {
                break;
            }
        }
        in.flip();
        assertEquals(message, StandardCharsets.US_ASCII.decode(in).toString());
    }

    @Override
    protected ServerSocket createClosingServer() throws Exception {
        return sslConfig.getSSLContext().getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getByName(TEST_HOST));
    }

    @Test
    @Timeout(value = 30)
    void testConcurrentReadAndWriteOnOneConnectionStayIntact() throws Exception {
        // A gateway reads on one thread and writes on another; TLS state is not thread-safe, so without the
        // native lock this corrupts records or crashes.
        final int messages = 2_000;
        final int size = 256;
        try (ServerSocket echoServer = sslConfig.getSSLContext().getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread echo = new Thread(() -> {
                try (Socket accepted = echoServer.accept()) {
                    accepted.getInputStream().transferTo(accepted.getOutputStream());
                } catch (IOException ignored) {
                }
            });
            echo.setDaemon(true);
            echo.start();

            clientSocket = new NativeSSLSocketFactory(sslConfig.writeCaFile()).createSocket(TEST_HOST, echoServer.getLocalPort());
            clientSocket.connect();
            clientSocket.setReadTimeout(5_000);

            Thread writer = new Thread(() -> {
                ByteBuffer out = ByteBuffer.allocateDirect(size);
                try {
                    for (int i = 0; i < messages; i++) {
                        out.clear();
                        for (int b = 0; b < size; b++) {
                            out.put((byte) (i + b));
                        }
                        out.flip();
                        clientSocket.write(out);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            writer.start();

            ByteBuffer in = ByteBuffer.allocateDirect(1 << 16);
            long received = 0;
            final long expected = (long) messages * size;
            while (received < expected) {
                int n = clientSocket.read(in);
                assertTrue(n >= 0, "connection closed after " + received + " bytes");
                in.flip();
                while (in.hasRemaining()) {
                    final long index = received++;
                    final int message = (int) (index / size);
                    final int offset = (int) (index % size);
                    assertEquals((byte) (message + offset), in.get(), "byte " + index);
                }
                in.clear();
            }
            writer.join();
        }
    }

    @Test
    @Timeout(value = 20)
    void testANewSocketToTheSameHostResumesTheSession() throws Exception {
        final String caFile = sslConfig.writeCaFile();
        // Each test's server has its own certificate, so a context of its own keeps sessions from other tests out.
        final NativeSSLSocketFactory factory = new NativeSSLSocketFactory(caFile);
        try (ServerSocket echoServer = sslConfig.getSSLContext().getServerSocketFactory()
                .createServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try (Socket accepted = echoServer.accept()) {
                        byte[] buffer = new byte[64];
                        int n = accepted.getInputStream().read(buffer);
                        accepted.getOutputStream().write(buffer, 0, Math.max(n, 0));
                        accepted.getOutputStream().flush();
                        accepted.getInputStream().read();
                    } catch (IOException ignored) {
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            NativeSSLSocket first = (NativeSSLSocket) factory.createSocket(TEST_HOST, echoServer.getLocalPort());
            first.connect();
            echo(first, "ticket please");
            first.close();

            // A different socket object, as the HTTP pool creates on every reconnect.
            NativeSSLSocket second = (NativeSSLSocket) factory.createSocket(TEST_HOST, echoServer.getLocalPort());
            clientSocket = second;
            second.connect();
            assertTrue(second.isSessionReused());
        }
    }

}

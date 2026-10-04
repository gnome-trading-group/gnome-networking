package group.gnometrading.networking.sockets;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface GnomeSocket extends AutoCloseable {

    void connect() throws IOException;

    void close() throws IOException;

    boolean isConnected();

    default boolean isClosed() {
        return !isConnected();
    }

    int read(ByteBuffer directBuffer, int len) throws IOException;

    default int read(ByteBuffer directBuffer) throws IOException {
        return read(directBuffer, directBuffer.remaining());
    }

    int write(ByteBuffer directBuffer, int len) throws IOException;

    default int write(ByteBuffer directBuffer) throws IOException {
        return write(directBuffer, directBuffer.remaining());
    }

    default void configureBlocking(boolean blocking) throws IOException {
        throw new UnsupportedOperationException("Socket does not support non-blocking");
    }

    default void setKeepAlive(boolean on) throws IOException {
        throw new UnsupportedOperationException("Socket does not support keep-alive");
    }

    default void setReceiveBufferSize(int size) throws IOException {
        throw new UnsupportedOperationException("Socket does not support receive buffer size");
    }

    default void setSendBufferSize(int size) throws IOException {
        throw new UnsupportedOperationException("Socket does not support send buffer size");
    }

    default void setTcpNoDelay(boolean on) throws IOException {
        throw new UnsupportedOperationException("Socket does not support TCP no delay");
    }

    default void setReadTimeout(int millis) throws IOException {
        throw new UnsupportedOperationException("Socket does not support read timeout");
    }

    /**
     * Bounds how long {@link #connect()} may take, including any TLS handshake. Applies to later connects.
     */
    default void setConnectTimeout(int millis) {
        throw new UnsupportedOperationException("Socket does not support connect timeout");
    }

    /**
     * Bounds how long a write may wait for the socket to take its bytes before failing. Writes are all-or-nothing:
     * a write either sends every byte or throws.
     */
    default void setWriteTimeout(int millis) {
        throw new UnsupportedOperationException("Socket does not support write timeout");
    }

    /**
     * Enables TCP keep-alive probing: the first probe after {@code idleSeconds} of silence, then every
     * {@code intervalSeconds}, declaring the peer dead after {@code count} unanswered probes.
     */
    default void setKeepAlive(int idleSeconds, int intervalSeconds, int count) throws IOException {
        throw new UnsupportedOperationException("Socket does not support keep-alive tuning");
    }

    /**
     * Lets the kernel busy-poll the device queue for up to {@code micros} (Linux only). Native sockets wait for
     * data in poll(), which only busy-polls when the {@code net.core.busy_poll} sysctl is also set; without it,
     * this only affects the single non-blocking receive before each wait.
     */
    default void setBusyPoll(int micros) throws IOException {
        throw new UnsupportedOperationException("Socket does not support busy polling");
    }

    /**
     * Looks at a connected socket without blocking or consuming anything: whether the peer has closed it, and
     * whether bytes are waiting to be read.
     */
    default SocketProbe probe() {
        throw new UnsupportedOperationException("Socket does not support probing");
    }

    /**
     * Whether a connected socket is still open with no unread bytes waiting, so a request can be sent on it
     * knowing any reply belongs to that request.
     */
    default boolean isAlive() {
        return probe() == SocketProbe.IDLE;
    }
}

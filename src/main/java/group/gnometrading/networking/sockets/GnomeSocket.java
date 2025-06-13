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

    default void setSoTimeout(int timeout) throws IOException {
        throw new UnsupportedOperationException("Socket does not support timeout");
    }

    default void setReceiveBufferSize(int size) throws IOException {
        throw new UnsupportedOperationException("Socket does not support receive buffer size");
    }

    default void setSendBufferSize(int size) throws IOException {
        throw new UnsupportedOperationException("Socket does not support send buffer size");
    }

    default void setReuseAddress(boolean on) throws IOException {
        throw new UnsupportedOperationException("Socket does not support reuse address");
    }

    default void setReusePort(boolean on) throws IOException {
        throw new UnsupportedOperationException("Socket does not support reuse port");
    }

    default void setTcpNoDelay(boolean on) throws IOException {
        throw new UnsupportedOperationException("Socket does not support TCP no delay");
    }
}

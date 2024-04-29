package group.gnometrading.networking.sockets;

import java.io.IOException;
import java.net.SocketOption;
import java.nio.ByteBuffer;

public interface GnomeSocket extends AutoCloseable {

    default void connectNonBlocking(long timeoutInMillis) throws IOException {
        throw new UnsupportedOperationException("Non-blocking connection is not yet implemented");
    }

    void connectBlocking() throws IOException;

    void close() throws IOException;

    boolean isConnected();

    boolean isClosed();

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

    default <T> void setOption(SocketOption<T> socketOption, T t) throws IOException {
        throw new UnsupportedOperationException("Socket does not support configuring options");
    }
}

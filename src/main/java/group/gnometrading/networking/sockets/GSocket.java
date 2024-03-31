package group.gnometrading.networking.sockets;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketOption;
import java.net.SocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public interface GSocket extends AutoCloseable {

    void connect() throws IOException;

    void close() throws IOException;

    int read(ByteBuffer byteBuffer, int position, int len) throws IOException;

    int write(ByteBuffer byteBuffer, int position, int len) throws IOException;

    default void configureNonBlocking(boolean blocking) throws IOException {
        throw new UnsupportedOperationException("Socket does not support non-blocking");
    }

   default <T> void setOption(SocketOption<T> socketOption, T t) throws IOException {
        throw new UnsupportedOperationException("Socket does not support configuring options");
   }
}

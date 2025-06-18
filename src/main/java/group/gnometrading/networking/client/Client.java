package group.gnometrading.networking.client;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface Client extends AutoCloseable {

    void connect() throws IOException;

    default int write() throws IOException {
        return write(Integer.MAX_VALUE);
    }

    int write(int len) throws IOException;

    default int read() throws IOException {
        return read(Integer.MAX_VALUE);
    }

    int read(int len) throws IOException;

    void configureBlocking(boolean blocking) throws IOException;

    ByteBuffer getWriteBuffer();

    ByteBuffer getReadBuffer();

    void clearBuffers();

    void reconnect() throws Exception;

    boolean isConnected();

    void setKeepAlive(boolean on) throws IOException;

    void setTcpNoDelay(boolean on) throws IOException;
}

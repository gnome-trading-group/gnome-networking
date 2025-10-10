package group.gnometrading.networking.client;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface Client extends AutoCloseable {

    void connect() throws IOException;

    default int write() throws IOException {
        return write(Integer.MAX_VALUE);
    }

    default int write(int len) throws IOException {
        return write(getWriteBuffer(), len);
    }

    default int write(ByteBuffer directBuffer) throws IOException {
        return write(directBuffer, Integer.MAX_VALUE);
    }

    int write(ByteBuffer directBuffer, int len) throws IOException;

    default int read() throws IOException {
        return read(Integer.MAX_VALUE);
    }

    default int read(int len) throws IOException {
        return read(getReadBuffer(), len);
    }

    default int read(ByteBuffer directBuffer) throws IOException {
        return read(directBuffer, Integer.MAX_VALUE);
    }

    int read(ByteBuffer directBuffer, int len) throws IOException;

    void configureBlocking(boolean blocking) throws IOException;

    ByteBuffer getWriteBuffer();

    ByteBuffer getReadBuffer();

    int getReadBufferSize();

    int getWriteBufferSize();

    void clearBuffers();

    void reconnect() throws Exception;

    boolean isConnected();

    void setKeepAlive(boolean on) throws IOException;

    void setTcpNoDelay(boolean on) throws IOException;
}

package group.gnometrading.networking.client;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface Client extends AutoCloseable {

    void connect() throws IOException;

    default int write(ByteBuffer buffer) throws IOException {
        return write(buffer, buffer.remaining());
    }

    int write(ByteBuffer buffer, int len) throws IOException;

    ByteBuffer read() throws IOException;

    ByteBuffer read(int len) throws IOException;

    void configureBlocking(boolean blocking) throws IOException;
}

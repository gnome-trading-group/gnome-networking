package group.gnometrading.networking.client;

import group.gnometrading.networking.sockets.GnomeSocket;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class SocketClient implements Client {

    public static final int DEFAULT_READ_BUFFER_SIZE = 1 << 13; // 8kb
    public static final int DEFAULT_WRITE_BUFFER_SIZE = 1 << 11; // 2kb

    protected final GnomeSocket socket;
    protected final ByteBuffer readBuffer;
    protected final ByteBuffer writeBuffer;
    protected final int readBufferSize, writeBufferSize;

    protected SocketClient(
            InetSocketAddress remoteAddress,
            GnomeSocketFactory factory,
            int readBufferSize,
            int writeBufferSize
    ) throws IOException {
        this.socket = factory.createSocket(remoteAddress);
        this.readBuffer = ByteBuffer.allocateDirect(readBufferSize);
        this.writeBuffer = ByteBuffer.allocateDirect(writeBufferSize);
        this.readBufferSize = readBufferSize;
        this.writeBufferSize = writeBufferSize;
        this.clearBuffers();
    }

    @Override
    public void connect() throws IOException {
        this.clearBuffers();
        this.socket.connect();
    }

    @Override
    public int write(final ByteBuffer directBuffer, int len) throws IOException {
        directBuffer.flip();
        len = Math.min(len, directBuffer.remaining());

        int bytes = this.socket.write(directBuffer, len);

        if (bytes > 0) {
            if (directBuffer.remaining() == 0) {
                directBuffer.clear();
            } else {
                directBuffer.compact();
            }
        } else if (directBuffer.limit() < directBuffer.capacity()) {
            // There's unconsumed bytes, reverse flip
            directBuffer.position(directBuffer.limit());
            directBuffer.limit(directBuffer.capacity());
        }

        return normalize(bytes);
    }


    @Override
    public int read(final ByteBuffer directBuffer, int len) throws IOException {
        if (directBuffer.position() > 0) {
            if (directBuffer.remaining() == 0) {
                directBuffer.clear();
            } else {
                directBuffer.compact();
            }
        } else if (directBuffer.limit() < directBuffer.capacity()) {
            // There's unconsumed bytes, reverse flip
            directBuffer.position(directBuffer.limit());
            directBuffer.limit(directBuffer.capacity());
        }

        len = Math.min(len, directBuffer.remaining());
        int bytes = normalize(this.socket.read(directBuffer, len));
        if (bytes < 0) {
            return bytes;
        }

        if (directBuffer.position() > 0) {
            directBuffer.flip();
        } else { // If position == 0, we read nothing on the wire
            directBuffer.limit(bytes);
        }

        return directBuffer.remaining();
    }

    protected static int normalize(final int n) {
        return (n == -2 || n == -3) ? 0 : n;
    }


    @Override
    public void close() throws Exception {
        this.socket.close();
    }

    @Override
    public void configureBlocking(boolean blocking) throws IOException {
        this.socket.configureBlocking(blocking);
    }

    @Override
    public ByteBuffer getWriteBuffer() {
        return this.writeBuffer;
    }

    @Override
    public ByteBuffer getReadBuffer() {
        return this.readBuffer;
    }

    @Override
    public void clearBuffers() {
        this.readBuffer.clear();
        this.readBuffer.limit(0); // Read buffer should be "empty" to begin
        this.writeBuffer.clear();
    }

    @Override
    public void reconnect() throws Exception {
        this.close();
        this.connect();
    }

    @Override
    public boolean isConnected() {
        return this.socket.isConnected();
    }

    @Override
    public void setKeepAlive(boolean on) throws IOException {
        this.socket.setKeepAlive(on);
    }

    @Override
    public void setTcpNoDelay(boolean on) throws IOException {
        this.socket.setTcpNoDelay(on);
    }

    @Override
    public int getReadBufferSize() {
        return this.readBufferSize;
    }

    @Override
    public int getWriteBufferSize() {
        return this.writeBufferSize;
    }
}

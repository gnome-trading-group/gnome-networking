package group.gnometrading.networking.client;

import group.gnometrading.networking.sockets.GnomeSocket;
import group.gnometrading.networking.sockets.SocketProbe;
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

        final int bytes;
        try {
            bytes = this.socket.write(directBuffer, len);
        } catch (IOException | RuntimeException e) {
            // Back to fill mode: left flipped, the next write would flip again and drop the queued bytes.
            directBuffer.position(directBuffer.limit());
            directBuffer.limit(directBuffer.capacity());
            throw e;
        }

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
        } else {
            // Every byte is unconsumed, so reading now would overwrite them.
            throw new IOException("Read buffer of " + directBuffer.capacity()
                    + " bytes is full without a complete message; it needs to be larger");
        }

        len = Math.min(len, directBuffer.remaining());
        final int bytes;
        try {
            bytes = normalize(this.socket.read(directBuffer, len));
        } catch (IOException | RuntimeException e) {
            // Back to read mode: left in fill mode, the next read would parse already-consumed bytes as new data.
            directBuffer.flip();
            throw e;
        }
        if (bytes < 0) {
            directBuffer.flip();
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
    public void setReadTimeout(int millis) throws IOException {
        this.socket.setReadTimeout(millis);
    }

    @Override
    public void setConnectTimeout(int millis) {
        this.socket.setConnectTimeout(millis);
    }

    @Override
    public void setKeepAlive(int idleSeconds, int intervalSeconds, int count) throws IOException {
        this.socket.setKeepAlive(idleSeconds, intervalSeconds, count);
    }

    @Override
    public void setBusyPoll(int micros) throws IOException {
        this.socket.setBusyPoll(micros);
    }

    @Override
    public SocketProbe probe() {
        return this.socket.probe();
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

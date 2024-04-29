package group.gnometrading.networking.client;

import group.gnometrading.networking.sockets.GnomeSocket;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import sun.nio.ch.IOStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class SocketClient implements Client {

    public static final int DEFAULT_READ_BUFFER_SIZE = 1 << 13; // 8kb
    public static final int DEFAULT_WRITE_BUFFER_SIZE = 1 << 11; // 2kb
    protected static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocateDirect(0);

    protected final GnomeSocket socket;
    protected final ByteBuffer readBuffer;
    protected final ByteBuffer writeBuffer;

    protected SocketClient(
            InetSocketAddress remoteAddress,
            GnomeSocketFactory factory,
            int readBufferSize,
            int writeBufferSize,
            boolean backgroundReaderThread,
            boolean backgroundWriterThread
    ) throws IOException {
        this.socket = factory.createSocket(remoteAddress);
        // TODO: Configure the socket via a Builder pattern

        if (backgroundReaderThread) {
            throw new UnsupportedOperationException("Not yet implemented");
        }

        if (backgroundWriterThread) {
            throw new UnsupportedOperationException("Not yet implemented");
        }

        this.readBuffer = ByteBuffer.allocateDirect(readBufferSize);
        this.writeBuffer = ByteBuffer.allocateDirect(writeBufferSize);
    }

    @Override
    public void connect() throws IOException {
        this.socket.connectBlocking();
    }

    @Override
    public int write(ByteBuffer buffer, int len) throws IOException {
        int originalLimit = buffer.limit();
        buffer.limit(buffer.position() + len);
        int remaining = buffer.remaining();
        this.writeBuffer.put(buffer);
        this.writeBuffer.flip();
        int bytesWritten = this.socket.write(this.writeBuffer, len);

        if (bytesWritten > 0) {
            buffer.position(buffer.position() - (remaining - bytesWritten));
        }
        buffer.limit(originalLimit);
        this.writeBuffer.clear();
        return IOStatus.normalize(bytesWritten);
    }


    @Override
    public ByteBuffer read() throws IOException {
        return this.read(this.readBuffer.capacity());
    }

    @Override
    public ByteBuffer read(int len) throws IOException {
        if (this.readBuffer.position() > 0) {
            if (this.readBuffer.remaining() == 0) {
                this.readBuffer.clear();
            } else {
                this.readBuffer.compact();
            }
        } else if (this.readBuffer.limit() < this.readBuffer.capacity()) {
            // There's unconsumed bytes, reverse flip
            this.readBuffer.position(this.readBuffer.limit());
            this.readBuffer.limit(this.readBuffer.capacity());
        }

        len = Math.min(len, this.readBuffer.remaining());
        int bytes = this.socket.read(this.readBuffer, len);

        if (this.readBuffer.position() > 0) {
            this.readBuffer.flip();
        } else { // If position == 0, we read nothing on the wire
            this.readBuffer.limit(bytes < 0 ? 0 : bytes); // avoid Math.max stackframe?
        }

        return this.readBuffer;
    }


    @Override
    public void close() throws Exception {
        this.socket.close();
    }

    @Override
    public void configureBlocking(boolean blocking) throws IOException {
        this.socket.configureBlocking(blocking);
    }
}

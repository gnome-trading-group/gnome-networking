package group.gnometrading.networking.client;

import group.gnometrading.networking.sockets.SocketProbe;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public abstract class AbstractSocketMessageClient extends SocketClient implements MessageClient {


    protected AbstractSocketMessageClient(InetSocketAddress remoteAddress, GnomeSocketFactory factory, int readBufferSize, int writeBufferSize) throws IOException {
        super(remoteAddress, factory, readBufferSize, writeBufferSize);
    }

    @Override
    public int readMessage(final ByteBuffer byteBuffer) throws IOException {
        if (checkMessage(byteBuffer)) { // Early exit if there's already a message to be read
            return 1;
        }

        int bytes = this.read(byteBuffer);
        if (bytes < 0) {
            return bytes;
        }

        return checkMessage(byteBuffer) ? 1 : 0;
    }

    /**
     * Like {@link #readMessage()} but never blocks: reads from the socket only when it already has bytes waiting.
     *
     * @return an int < 0 if the socket is closed, 0 if there's no complete message yet, and 1 if there's a message
     */
    public int pollMessage() throws IOException {
        if (checkMessage(this.readBuffer)) {
            return 1;
        }
        final SocketProbe probe = probe();
        if (probe == SocketProbe.IDLE) {
            return 0;
        }
        if (probe == SocketProbe.DEAD) {
            return -1;
        }
        final int bytes = this.read(this.readBuffer);
        if (bytes < 0) {
            return bytes;
        }
        return checkMessage(this.readBuffer) ? 1 : 0;
    }

    private boolean checkMessage(final ByteBuffer byteBuffer) throws IOException {
        byteBuffer.mark();
        final boolean complete = isCompleteMessage(byteBuffer);
        if (!complete) {
            byteBuffer.reset();
        }
        return complete;
    }

    /**
     * Check whether the current read buffer has a complete message. The read buffer's position
     * should advance to the end of the message if it is present.
     *
     * @return true if there's a complete message
     */
    public abstract boolean isCompleteMessage(final ByteBuffer byteBuffer) throws IOException;
}

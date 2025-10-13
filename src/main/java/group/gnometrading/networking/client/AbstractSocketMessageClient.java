package group.gnometrading.networking.client;

import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;

import java.io.IOException;
import java.net.InetSocketAddress;

public abstract class AbstractSocketMessageClient extends SocketClient implements MessageClient {


    protected AbstractSocketMessageClient(InetSocketAddress remoteAddress, GnomeSocketFactory factory, int readBufferSize, int writeBufferSize) throws IOException {
        super(remoteAddress, factory, readBufferSize, writeBufferSize);
    }

    @Override
    public int readMessage() throws IOException {
        if (checkMessage()) { // Early exit if there's already a message to be read
            return 1;
        }

        int bytes = this.read();
        if (bytes < 0) {
            return bytes;
        }

        return checkMessage() ? 1 : 0;
    }

    private boolean checkMessage() {
        this.readBuffer.mark();
        final boolean complete = isCompleteMessage();
        if (!complete) {
            this.readBuffer.reset();
        }
        return complete;
    }

    /**
     * Check whether the current read buffer has a complete message. The read buffer's position
     * should advance to the end of the message if it is present.
     *
     * @return true if there's a complete message
     */
    public abstract boolean isCompleteMessage();
}

package group.gnometrading.networking.client;

import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;

import java.io.IOException;
import java.net.InetSocketAddress;

public abstract class AbstractSocketMessageClient extends SocketClient implements MessageClient {


    protected AbstractSocketMessageClient(InetSocketAddress remoteAddress, GnomeSocketFactory factory, int readBufferSize, int writeBufferSize, boolean backgroundReaderThread, boolean backgroundWriterThread) throws IOException {
        super(remoteAddress, factory, readBufferSize, writeBufferSize, backgroundReaderThread, backgroundWriterThread);
    }

    @Override
    public int readMessage() throws IOException {
        int bytes = this.read();
        if (bytes < 0) {
            return bytes;
        }
        this.readBuffer.mark();
        final boolean complete = isCompleteMessage();
        if (!complete) {
            this.readBuffer.reset();
            return 0;
        }
        return 1;
    }

    public abstract boolean isCompleteMessage();
}

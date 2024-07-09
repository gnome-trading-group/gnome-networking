package group.gnometrading.networking.client;

import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public abstract class AbstractSocketMessageClient extends SocketClient implements MessageClient {


    protected AbstractSocketMessageClient(InetSocketAddress remoteAddress, GnomeSocketFactory factory, int readBufferSize, int writeBufferSize, boolean backgroundReaderThread, boolean backgroundWriterThread) throws IOException {
        super(remoteAddress, factory, readBufferSize, writeBufferSize, backgroundReaderThread, backgroundWriterThread);
    }

    @Override
    public ByteBuffer readMessage() throws IOException {
        ByteBuffer direct = this.read();
        direct.mark();
        final boolean complete = isCompleteMessage(direct);
        direct.reset();
        return complete ? direct : EMPTY_BUFFER;
    }

    @Override
    public int writeMessage(ByteBuffer message) throws IOException {
        return this.write(message);
    }

    public abstract boolean isCompleteMessage(ByteBuffer directBuffer);
}

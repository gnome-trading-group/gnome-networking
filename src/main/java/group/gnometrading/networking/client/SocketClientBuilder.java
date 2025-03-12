package group.gnometrading.networking.client;

import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;

import java.io.IOException;
import java.net.InetSocketAddress;

// SocketClient constructor throws an IOException so cannot implement Builder
public class SocketClientBuilder /*implements Builder<SocketClient>*/ {

    private InetSocketAddress address = null;
    private GnomeSocketFactory socketFactory = GnomeSocketFactory.getDefault();
    private int readBufferSize = SocketClient.DEFAULT_READ_BUFFER_SIZE;
    private int writeBufferSize = SocketClient.DEFAULT_WRITE_BUFFER_SIZE;

    public SocketClientBuilder withAddress(InetSocketAddress address) {
        this.address = address;
        return this;
    }

    public SocketClientBuilder withSocketFactory(GnomeSocketFactory factory) {
        this.socketFactory = factory;
        return this;
    }

    public SocketClientBuilder withReadBufferSize(int readBufferSize) {
        this.readBufferSize = readBufferSize;
        return this;
    }

    public SocketClientBuilder withWriteBufferSize(int writeBufferSize) {
        this.writeBufferSize = writeBufferSize;
        return this;
    }

    public SocketClient build() throws IOException {
        if (address == null) {
            throw new IllegalArgumentException("address must not be null");
        }
        return new SocketClient(address, socketFactory, readBufferSize, writeBufferSize);
    }
}

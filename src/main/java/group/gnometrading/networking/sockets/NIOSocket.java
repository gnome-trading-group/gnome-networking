package group.gnometrading.networking.sockets;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class NIOSocket implements GSocket {

    private final SocketChannel socketChannel;
    private final InetSocketAddress remoteAddress;
    private final ByteBuffer[] holder;

    public NIOSocket(InetSocketAddress remoteAddress) throws IOException {
        this.remoteAddress = remoteAddress;
        this.socketChannel = SocketChannel.open();
        this.holder = new ByteBuffer[1];
    }
    @Override
    public void connect() throws IOException {
        this.socketChannel.connect(remoteAddress);
    }

    @Override
    public void close() throws IOException {
        this.socketChannel.close();
    }

    @Override
    public int read(ByteBuffer byteBuffer, int position, int len) throws IOException {
        this.holder[0] = byteBuffer;
        return (int) this.socketChannel.read(this.holder, position, len);
    }

    @Override
    public int write(ByteBuffer byteBuffer, int position, int len) throws IOException {
        this.holder[0] = byteBuffer;
        return (int) this.socketChannel.write(this.holder, position, len);
    }

    @Override
    public void configureNonBlocking(boolean blocking) throws IOException {
        this.socketChannel.configureBlocking(blocking);
    }

    @Override
    public <T> void setOption(SocketOption<T> socketOption, T t) throws IOException {
        socketChannel.setOption(socketOption, t);
    }
}

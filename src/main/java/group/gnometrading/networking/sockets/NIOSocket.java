package group.gnometrading.networking.sockets;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;


public class NIOSocket implements GnomeSocket {

    private final SocketChannel socketChannel;
    private final InetSocketAddress remoteAddress;

    public NIOSocket(InetSocketAddress remoteAddress) throws IOException {
        this.remoteAddress = remoteAddress;
        this.socketChannel = SocketChannel.open();
    }

    @Override
    public void connectBlocking() throws IOException {
        this.socketChannel.connect(remoteAddress);
    }

    @Override
    public void close() throws IOException {
        this.socketChannel.close();
    }

    @Override
    public boolean isConnected() {
        return this.socketChannel.isConnected();
    }

    @Override
    public boolean isClosed() {
        return !this.socketChannel.isOpen();
    }

    @Override
    public int read(ByteBuffer directBuffer, int len) throws IOException {
        return this.socketChannel.read(directBuffer);
    }

    @Override
    public int write(ByteBuffer directBuffer, int len) throws IOException {
        return this.socketChannel.write(directBuffer);
    }

    @Override
    public void configureBlocking(boolean blocking) throws IOException {
        this.socketChannel.configureBlocking(blocking);
    }

    @Override
    public <T> void setOption(SocketOption<T> socketOption, T t) throws IOException {
        socketChannel.setOption(socketOption, t);
    }
}

package group.gnometrading.networking.sockets;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;


public class NIOSocket implements GnomeSocket {

    private final SocketChannel socketChannel;
    private final InetSocketAddress remoteAddress;
    private final ByteBuffer[] holder;

    public NIOSocket(InetSocketAddress remoteAddress) throws IOException {
        this.remoteAddress = remoteAddress;
        this.socketChannel = SocketChannel.open();
        this.holder = new ByteBuffer[1];
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
//        this.holder[0] = directBuffer;
//        return (int) this.socketChannel.read(this.holder, directBuffer.position(), len);
    }

    @Override
    public int write(ByteBuffer directBuffer, int len) throws IOException {
        return this.socketChannel.write(directBuffer);
//        return (int) this.socketChannel.write(this.holder, directBuffer.position(), len);
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

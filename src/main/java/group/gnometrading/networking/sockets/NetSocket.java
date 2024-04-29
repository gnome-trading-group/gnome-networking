package group.gnometrading.networking.sockets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketOption;
import java.nio.ByteBuffer;

public class NetSocket implements GnomeSocket {

    private final Socket socket;
    private final InetSocketAddress remoteAddress;

    public NetSocket(InetSocketAddress remoteAddress) throws IOException {
        this.remoteAddress = remoteAddress;
        this.socket = new Socket(this.remoteAddress.getAddress(), this.remoteAddress.getPort());
    }

    @Override
    public void connectBlocking() throws IOException {
        this.socket.connect(remoteAddress);
    }

    @Override
    public void close() throws IOException {
        this.socket.close();
    }

    @Override
    public boolean isConnected() {
        return this.socket.isConnected();
    }

    @Override
    public boolean isClosed() {
        return this.socket.isClosed();
    }

    @Override
    public int read(ByteBuffer directBuffer, int len) throws IOException {
        return this.socket.getInputStream().read(directBuffer.array(), directBuffer.position(), len);
    }

    @Override
    public int write(ByteBuffer directBuffer, int len) throws IOException {
        this.socket.getOutputStream().write(directBuffer.array(), directBuffer.position(), len);
        return 0; // TODO: Anything better to return here?
    }

    @Override
    public <T> void setOption(SocketOption<T> socketOption, T t) throws IOException {
        this.socket.setOption(socketOption, t);
    }
}

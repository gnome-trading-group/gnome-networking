package group.gnometrading.networking.sockets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * GnomeSocket implementation for java.net Sockets. Note, this
 * implementation creates copies of read/write buffers on each invocation.
 */
public class NetSocket implements GnomeSocket {

    protected final Socket socket;
    private final InetSocketAddress remoteAddress;
    private final ReadableByteChannel inputChannel;
    private final WritableByteChannel outputChannel;

    public NetSocket(InetSocketAddress remoteAddress) throws IOException {
        this.remoteAddress = remoteAddress;
        this.socket = this.createSocket(remoteAddress);
        this.inputChannel = Channels.newChannel(this.socket.getInputStream());
        this.outputChannel = Channels.newChannel(this.socket.getOutputStream());
    }

    protected Socket createSocket(InetSocketAddress remoteAddress) throws IOException {
        return new Socket(this.remoteAddress.getAddress(), this.remoteAddress.getPort());
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
        return this.inputChannel.read(directBuffer);
    }

    @Override
    public int write(ByteBuffer directBuffer, int len) throws IOException {
        return this.outputChannel.write(directBuffer);
    }
}

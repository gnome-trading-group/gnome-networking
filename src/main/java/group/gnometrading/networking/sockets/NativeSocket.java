package group.gnometrading.networking.sockets;

import group.gnometrading.resources.LibraryLoader;
import sun.nio.ch.DirectBuffer;
import sun.nio.ch.IOStatus;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

public class NativeSocket implements GnomeSocket {

    static {
        try {
            LibraryLoader.loadLibrary("NativeSockets");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        NativeSocket.init();
    }

    private int fd;
    private final InetSocketAddress remoteAddress;
    private SocketState socketState;

    public NativeSocket(InetSocketAddress remoteAddress) throws IOException {
        if (remoteAddress.isUnresolved()) {
            throw new UnknownHostException("Unknown host: " + remoteAddress.getHostName());
        }
        this.remoteAddress = remoteAddress;
        this.socketState = SocketState.UNCONNECTED;
    }

    private static native void init();

    private native int socket(boolean stream, boolean reuse) throws IOException;

    @Override
    public void connect() throws IOException {
        InetAddress address = this.remoteAddress.getAddress();
        if (address.isAnyLocalAddress()) {
            address = InetAddress.getLocalHost();
        }
        this.fd = this.socket(true, true); // TODO: Support UDP
        if (this.connect0(this.fd, address, this.remoteAddress.getPort()) > 0) {
            this.socketState = SocketState.CONNECTED;
        } else {
            throw new SocketException("Unable to connect");
        }
    }

    private native int connect0(int fd, InetAddress remote, int remoteReport) throws IOException;

    @Override
    public void close() throws IOException {
        this.socketState = SocketState.UNCONNECTED;
        close0(this.fd);
    }

    private native void close0(int fd) throws IOException;

    @Override
    public boolean isConnected() {
        return this.socketState == SocketState.CONNECTED;
    }

    @Override
    public int read(ByteBuffer directBuffer, int len) throws IOException {
        ensureConnected();
        int pos = directBuffer.position();
        int n = this.read0(this.fd, ((DirectBuffer) directBuffer).address() + pos, len);
        n = IOStatus.normalize(n);
        if (n > 0)
            directBuffer.position(pos + n);
        return n;
    }

    @Override
    public int read(ByteBuffer directBuffer) throws IOException {
        return this.read(directBuffer, directBuffer.remaining());
    }

    private native int read0(int fd, long address, int len) throws IOException;

    @Override
    public int write(ByteBuffer directBuffer, int len) throws IOException {
        ensureConnected();
        int pos = directBuffer.position();
        int written = this.write0(fd, ((DirectBuffer) directBuffer).address() + pos, len);
        if (written > 0)
            directBuffer.position(pos + written);
        return written;
    }

    private native int write0(int fd, long address, int len) throws IOException;

    @Override
    public void configureBlocking(boolean blocking) throws IOException {
        ensureConnected();
        configureBlocking0(fd, blocking);
    }

    @Override
    public void reconnect() throws IOException {
        this.close();
        this.connect();
    }

    private native void configureBlocking0(int fd, boolean blocking) throws IOException;

    private void ensureConnected() throws SocketException {
        if (this.socketState != SocketState.CONNECTED) {
            throw new SocketException("Socket not connected");
        }
    }
}

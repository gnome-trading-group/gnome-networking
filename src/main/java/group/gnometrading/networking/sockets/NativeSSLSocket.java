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

public class NativeSSLSocket implements GnomeSocket {

    static {
        try {
            LibraryLoader.loadLibrary("NativeSockets");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        NativeSSLSocket.init();
    }

    private long handle;
    private final InetSocketAddress remoteAddress;
    private SocketState socketState;

    public NativeSSLSocket(InetSocketAddress remoteAddress) throws IOException {
        if (remoteAddress.isUnresolved()) {
            throw new UnknownHostException("Unknown host: " + remoteAddress.getHostName());
        }
        this.remoteAddress = remoteAddress;
        this.socketState = SocketState.UNCONNECTED;
    }

    private static native void init();

    private native long socket(boolean stream, boolean reuse) throws IOException;

    @Override
    public void connect() throws IOException {
        InetAddress address = this.remoteAddress.getAddress();
        if (address.isAnyLocalAddress()) {
            address = InetAddress.getLocalHost();
        }
        this.handle = this.socket(true, true); // TODO: Support UDP
        if (this.connect0(this.handle, address, this.remoteAddress.getPort(), address.getHostName()) > 0) {
            this.socketState = SocketState.CONNECTED;
        } else {
            throw new SocketException("Unable to connect");
        }
    }

    private native int connect0(long handle, InetAddress remote, int remoteReport, String hostname) throws IOException;

    @Override
    public void close() throws IOException {
        this.socketState = SocketState.UNCONNECTED;
        close0(this.handle);
    }

    private native void close0(long handle) throws IOException;

    @Override
    public boolean isConnected() {
        return this.socketState == SocketState.CONNECTED;
    }

    @Override
    public int read(ByteBuffer directBuffer, int len) throws IOException {
        ensureConnected();
        int pos = directBuffer.position();
        int n = this.read0(this.handle, ((DirectBuffer) directBuffer).address() + pos, len);
        n = IOStatus.normalize(n);
        if (n > 0)
            directBuffer.position(pos + n);
        return n;
    }

    @Override
    public int read(ByteBuffer directBuffer) throws IOException {
        return this.read(directBuffer, directBuffer.remaining());
    }

    private native int read0(long handle, long address, int len) throws IOException;

    @Override
    public int write(ByteBuffer directBuffer, int len) throws IOException {
        ensureConnected();
        int pos = directBuffer.position();
        int written = this.write0(this.handle, ((DirectBuffer) directBuffer).address() + pos, len);
        if (written > 0)
            directBuffer.position(pos + written);
        return written;
    }

    private native int write0(long handle, long address, int len) throws IOException;

    @Override
    public void configureBlocking(boolean blocking) throws IOException {
        ensureConnected();
        configureBlocking0(this.handle, blocking);
    }

    private native void configureBlocking0(long handle, boolean blocking) throws IOException;

    private void ensureConnected() throws SocketException {
        if (this.socketState != SocketState.CONNECTED) {
            throw new SocketException("Socket not connected");
        }
    }

    @Override
    public void setKeepAlive(boolean on) throws IOException {
        ensureConnected();
        setKeepAlive0(handle, on);
    }

    private native void setKeepAlive0(long handle, boolean on) throws IOException;

    @Override
    public void setReceiveBufferSize(int size) throws IOException {
        ensureConnected();
        setReceiveBufferSize0(handle, size);
    }

    private native void setReceiveBufferSize0(long handle, int size) throws IOException;

    @Override
    public void setSendBufferSize(int size) throws IOException {
        ensureConnected();
        setSendBufferSize0(handle, size);
    }

    private native void setSendBufferSize0(long handle, int size) throws IOException;

    @Override
    public void setTcpNoDelay(boolean on) throws IOException {
        ensureConnected();
        setTcpNoDelay0(handle, on);
    }

    private native void setTcpNoDelay0(long handle, boolean on) throws IOException;
}

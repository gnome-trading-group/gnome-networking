package group.gnometrading.networking.sockets;

import group.gnometrading.resources.LibraryLoader;
import sun.nio.ch.DirectBuffer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class NativeSocket implements GSocket {

    static {
        try {
            LibraryLoader.loadLibrary("Sockets");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        NativeSocket.init();
    }

    private final int fd;
    private final InetSocketAddress remoteAddress;

    public NativeSocket(InetSocketAddress remoteAddress) throws IOException {
        this.remoteAddress = remoteAddress;
        this.fd = this.socket(true, true); // TODO: Support UDP
    }

    private static native void init();

    private native int socket(boolean stream, boolean reuse) throws IOException;

    @Override
    public void connect() throws IOException {
        this.connect0(this.fd, this.remoteAddress.getAddress(), this.remoteAddress.getPort());
    }

    private native int connect0(int fd, InetAddress remote, int remoteReport) throws IOException;

    @Override
    public void close() throws IOException {
        close0(this.fd);
    }

    private native void close0(int fd) throws IOException;

    @Override
    public int read(ByteBuffer byteBuffer, int position, int len) throws IOException {
//        if (!byteBuffer.isDirect()) {
//            throw new IOException("ByteBuffer must be direct", new UnsupportedOperationException());
//        }
        return this.read0(this.fd, ((DirectBuffer) byteBuffer).address(), position, len);
    }

    private native int read0(int fd, long address, int position, int len) throws IOException;

    @Override
    public int write(ByteBuffer byteBuffer, int position, int len) throws IOException {
//        if (!byteBuffer.isDirect()) {
//            throw new IOException("ByteBuffer must be direct", new UnsupportedOperationException());
//        }
        return this.write0(fd, ((DirectBuffer) byteBuffer).address(), position, len);
    }

    private native int write0(int fd, long address, int position, int len) throws IOException;

}

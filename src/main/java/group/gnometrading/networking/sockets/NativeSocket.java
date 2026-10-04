package group.gnometrading.networking.sockets;

import group.gnometrading.networking.utils.IOUtil;
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
        NativeSockets.load();
        NativeSocket.init();
    }

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_WRITE_TIMEOUT_MS = 5_000;

    private static final SocketProbe[] PROBE_RESULTS = {SocketProbe.DEAD, SocketProbe.IDLE, SocketProbe.READABLE};

    private volatile int fd = -1;
    private final InetSocketAddress remoteAddress;
    private volatile SocketState socketState;
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private int writeTimeoutMs = DEFAULT_WRITE_TIMEOUT_MS;
    // Per connection, like the socket options they stand in for: the fd itself is always non-blocking.
    private volatile boolean blocking = true;
    private volatile int readTimeoutMs;

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
        // Until this connect succeeds the socket is not connected, even if the previous connection was.
        this.socketState = SocketState.UNCONNECTED;
        this.blocking = true;
        this.readTimeoutMs = 0;
        // Reconnecting reuses this object, so a previous connection must not be leaked or later double closed.
        closeFd();
        InetAddress address = resolve(this.remoteAddress);
        if (address.isAnyLocalAddress()) {
            address = InetAddress.getLocalHost();
        }
        this.fd = this.socket(true, true); // TODO: Support UDP
        try {
            if (this.connect0(this.fd, address, this.remoteAddress.getPort(), this.connectTimeoutMs) <= 0) {
                throw new SocketException("Unable to connect");
            }
        } catch (Throwable e) {
            closeFd();
            throw e;
        }
        this.socketState = SocketState.CONNECTED;
    }

    private native int connect0(int fd, InetAddress remote, int remoteReport, int timeoutMillis) throws IOException;

    @Override
    public void close() throws IOException {
        this.socketState = SocketState.UNCONNECTED;
        closeFd();
    }

    private void closeFd() throws IOException {
        if (this.fd < 0) {
            return;
        }
        // Cleared before closing so a second close cannot hit an fd the kernel has already reused.
        final int closing = this.fd;
        this.fd = -1;
        close0(closing);
    }

    private native void close0(int fd) throws IOException;

    @Override
    public boolean isConnected() {
        return this.socketState == SocketState.CONNECTED;
    }

    @Override
    public int read(ByteBuffer directBuffer, int len) throws IOException {
        ensureReadable();
        int pos = directBuffer.position();
        int n = this.read0(
                this.fd, ((DirectBuffer) directBuffer).address() + pos, len, this.blocking, this.readTimeoutMs);
        n = IOStatus.normalize(n);
        if (n > 0)
            directBuffer.position(pos + n);
        if (n == 0 && this.socketState == SocketState.BROKEN) {
            // Broken by a failed write: hand over what has already arrived (e.g. execution reports), then report
            // end of stream so the reader reconnects rather than waiting on a connection that cannot send.
            return -1;
        }
        return n;
    }

    @Override
    public int read(ByteBuffer directBuffer) throws IOException {
        return this.read(directBuffer, directBuffer.remaining());
    }

    /** Waits up to the timeout (0: indefinitely) for bytes when blocking, returning 0 if none arrive. */
    private native int read0(int fd, long address, int len, boolean blocking, int timeoutMillis) throws IOException;

    @Override
    public int write(ByteBuffer directBuffer, int len) throws IOException {
        ensureConnected();
        int pos = directBuffer.position();
        final int written;
        try {
            written = this.write0(fd, ((DirectBuffer) directBuffer).address() + pos, len, this.writeTimeoutMs);
        } catch (IOException e) {
            this.socketState = SocketState.BROKEN;
            throw e;
        }
        if (written > 0)
            directBuffer.position(pos + written);
        return IOUtil.normalize(written);
    }

    /** Writes all {@code len} bytes, waiting up to the timeout for the socket to take them, or throws. */
    private native int write0(int fd, long address, int len, int timeoutMillis) throws IOException;

    @Override
    public void configureBlocking(boolean blocking) throws IOException {
        ensureConnected();
        this.blocking = blocking;
    }

    private void ensureReadable() throws SocketException {
        if ((this.socketState != SocketState.CONNECTED && this.socketState != SocketState.BROKEN) || this.fd < 0) {
            throw new SocketException("Socket not connected");
        }
    }

    private void ensureConnected() throws SocketException {
        if (this.socketState == SocketState.BROKEN) {
            throw new SocketException("Socket broken by a failed write; it must be reconnected");
        }
        if (this.socketState != SocketState.CONNECTED || this.fd < 0) {
            throw new SocketException("Socket not connected");
        }
    }

    /**
     * Looks the host up again on every connect: exchange endpoints sit behind load balancers whose addresses
     * change, and an address fixed when the socket was created would leave every later reconnect failing.
     */
    static InetAddress resolve(final InetSocketAddress remoteAddress) throws UnknownHostException {
        return InetAddress.getByName(remoteAddress.getHostString());
    }

    @Override
    public void setKeepAlive(boolean on) throws IOException {
        ensureConnected();
        setKeepAlive0(fd, on);
    }

    private native void setKeepAlive0(int fd, boolean on) throws IOException;

    @Override
    public void setReceiveBufferSize(int size) throws IOException {
        ensureConnected();
        setReceiveBufferSize0(fd, size);
    }

    private native void setReceiveBufferSize0(int fd, int size) throws IOException;

    @Override
    public void setSendBufferSize(int size) throws IOException {
        ensureConnected();
        setSendBufferSize0(fd, size);
    }

    private native void setSendBufferSize0(int fd, int size) throws IOException;

    @Override
    public void setTcpNoDelay(boolean on) throws IOException {
        ensureConnected();
        setTcpNoDelay0(fd, on);
    }

    private native void setTcpNoDelay0(int fd, boolean on) throws IOException;

    @Override
    public void setReadTimeout(int millis) throws IOException {
        ensureConnected();
        this.readTimeoutMs = millis;
    }

    @Override
    public void setConnectTimeout(int millis) {
        this.connectTimeoutMs = millis;
    }

    @Override
    public void setWriteTimeout(int millis) {
        this.writeTimeoutMs = millis;
    }

    @Override
    public void setKeepAlive(int idleSeconds, int intervalSeconds, int count) throws IOException {
        ensureConnected();
        setKeepAliveOptions0(fd, idleSeconds, intervalSeconds, count);
    }

    private native void setKeepAliveOptions0(int fd, int idleSeconds, int intervalSeconds, int count)
            throws IOException;

    @Override
    public void setBusyPoll(int micros) throws IOException {
        ensureConnected();
        setBusyPoll0(fd, micros);
    }

    private native void setBusyPoll0(int fd, int micros) throws IOException;

    @Override
    public SocketProbe probe() {
        final int current = this.fd;
        if (this.socketState != SocketState.CONNECTED || current < 0) {
            return SocketProbe.DEAD;
        }
        return PROBE_RESULTS[probe0(current)];
    }

    private native int probe0(int fd);
}

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
import java.util.HashMap;
import java.util.Map;

/**
 * A TLS socket. Reading and writing may happen on different threads (the native side serializes every TLS
 * call), but {@link #connect()} and {@link #close()} must not race any other use of the same socket.
 */
public class NativeSSLSocket implements GnomeSocket {

    static {
        NativeSockets.load();
        NativeSSLSocket.init();
    }

    private static final SocketProbe[] PROBE_RESULTS = {SocketProbe.DEAD, SocketProbe.IDLE, SocketProbe.READABLE};

    private static volatile long defaultContext;

    // Resumable sessions by context and host, shared by every socket so that replacing a connection (as the
    // HTTP pool does) can resume rather than renegotiate. Each entry holds one native reference.
    private static final Map<String, Long> SESSIONS = new HashMap<>();

    private final long context;
    private final InetSocketAddress remoteAddress;
    private final String sessionKey;
    private volatile SocketState socketState;
    private volatile long handle;
    private int connectTimeoutMs = NativeSocket.DEFAULT_CONNECT_TIMEOUT_MS;
    private int writeTimeoutMs = NativeSocket.DEFAULT_WRITE_TIMEOUT_MS;

    public NativeSSLSocket(InetSocketAddress remoteAddress) throws IOException {
        this(remoteAddress, defaultContext());
    }

    /**
     * @param context a TLS context from {@link #createContext(String)}, shared by every socket that uses it
     */
    public NativeSSLSocket(InetSocketAddress remoteAddress, long context) throws IOException {
        if (remoteAddress.isUnresolved()) {
            throw new UnknownHostException("Unknown host: " + remoteAddress.getHostName());
        }
        this.remoteAddress = remoteAddress;
        this.context = context;
        this.sessionKey = context + "|" + remoteAddress.getHostString() + "|" + remoteAddress.getPort();
        this.socketState = SocketState.UNCONNECTED;
    }

    /** A reference to the cached session for this host, which the caller must free, or 0 if there is none. */
    private static long acquireSession(final String key) {
        synchronized (SESSIONS) {
            final Long session = SESSIONS.get(key);
            if (session == null) {
                return 0;
            }
            upRefSession0(session);
            return session;
        }
    }

    /** Caches {@code session}, taking over the caller's reference, and frees the one it replaces. */
    private static void storeSession(final String key, final long session) {
        final Long replaced;
        synchronized (SESSIONS) {
            replaced = SESSIONS.put(key, session);
        }
        if (replaced != null) {
            freeSession0(replaced);
        }
    }

    /**
     * Creates a TLS context that verifies servers against the CA certificates in {@code caFile} (PEM). Contexts
     * are meant to live for the whole process and are never freed.
     */
    public static long createContext(String caFile) throws IOException {
        return createContext0(caFile);
    }

    /** The process-wide context, which trusts the system CA bundle (or {@code SSL_CERT_FILE}). */
    private static long defaultContext() throws IOException {
        long context = defaultContext;
        if (context == 0) {
            synchronized (NativeSSLSocket.class) {
                context = defaultContext;
                if (context == 0) {
                    context = createContext0(null);
                    defaultContext = context;
                }
            }
        }
        return context;
    }

    private static native void init();

    private static native long createContext0(String caFile) throws IOException;

    private static native void freeSession0(long session);

    private static native void upRefSession0(long session);

    private native long socket(boolean stream, boolean reuse) throws IOException;

    @Override
    public void connect() throws IOException {
        // Until this connect succeeds the socket is not connected, even if the previous connection was: a failed
        // reconnect must never leave calls going to a freed native handle.
        this.socketState = SocketState.UNCONNECTED;
        closeHandle();

        InetAddress address = NativeSocket.resolve(this.remoteAddress);
        String verifiedHost = this.remoteAddress.getHostString();
        if (address.isAnyLocalAddress()) {
            address = InetAddress.getLocalHost();
            verifiedHost = address.getHostAddress();
        }

        this.handle = this.socket(true, true); // TODO: Support UDP
        final long session = acquireSession(this.sessionKey);
        try {
            final int connected = this.connect0(
                    this.handle,
                    this.context,
                    address,
                    this.remoteAddress.getPort(),
                    verificationHost(verifiedHost),
                    this.connectTimeoutMs,
                    session);
            if (connected <= 0) {
                throw new SocketException("Unable to connect");
            }
        } catch (Throwable e) {
            closeHandle();
            throw e;
        } finally {
            // The native connection holds its own reference to the session it resumed.
            freeSession0(session);
        }
        this.socketState = SocketState.CONNECTED;
    }

    /** The name certificates are checked against: an IPv6 literal without its brackets or scope. */
    static String verificationHost(final String host) {
        String name = host;
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        final int scope = name.indexOf('%');
        return scope >= 0 && name.indexOf(':') >= 0 ? name.substring(0, scope) : name;
    }

    private native int connect0(
            long handle,
            long context,
            InetAddress remote,
            int remoteReport,
            String hostname,
            int timeoutMillis,
            long session) throws IOException;

    @Override
    public void close() throws IOException {
        this.socketState = SocketState.UNCONNECTED;
        closeHandle();
    }

    private void closeHandle() throws IOException {
        if (this.handle == 0) {
            return;
        }
        // Cleared before closing so a second close cannot free the native state twice.
        final long closing = this.handle;
        this.handle = 0;
        final long newSession = close0(closing);
        if (newSession != 0) {
            storeSession(this.sessionKey, newSession);
        }
    }

    /** Frees the native state and returns the newest resumable session (one reference), or 0. */
    private native long close0(long handle) throws IOException;

    @Override
    public boolean isConnected() {
        return this.socketState == SocketState.CONNECTED;
    }

    @Override
    public int read(ByteBuffer directBuffer, int len) throws IOException {
        ensureReadable();
        int pos = directBuffer.position();
        int n = this.read0(this.handle, ((DirectBuffer) directBuffer).address() + pos, len);
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

    private native int read0(long handle, long address, int len) throws IOException;

    @Override
    public int write(ByteBuffer directBuffer, int len) throws IOException {
        ensureConnected();
        int pos = directBuffer.position();
        final int written;
        try {
            written = this.write0(this.handle, ((DirectBuffer) directBuffer).address() + pos, len, this.writeTimeoutMs);
        } catch (IOException e) {
            this.socketState = SocketState.BROKEN;
            throw e;
        }
        if (written > 0)
            directBuffer.position(pos + written);
        return IOUtil.normalize(written);
    }

    /** Writes all {@code len} bytes, waiting up to the timeout for the socket to take them, or throws. */
    private native int write0(long handle, long address, int len, int timeoutMillis) throws IOException;

    @Override
    public void configureBlocking(boolean blocking) throws IOException {
        ensureConnected();
        configureBlocking0(this.handle, blocking);
    }

    private native void configureBlocking0(long handle, boolean blocking) throws IOException;

    private void ensureReadable() throws SocketException {
        if ((this.socketState != SocketState.CONNECTED && this.socketState != SocketState.BROKEN) || this.handle == 0) {
            throw new SocketException("Socket not connected");
        }
    }

    private void ensureConnected() throws SocketException {
        if (this.socketState == SocketState.BROKEN) {
            throw new SocketException("Socket broken by a failed write; it must be reconnected");
        }
        if (this.socketState != SocketState.CONNECTED || this.handle == 0) {
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

    @Override
    public void setReadTimeout(int millis) throws IOException {
        ensureConnected();
        setReadTimeout0(handle, millis);
    }

    private native void setReadTimeout0(long handle, int millis) throws IOException;

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
        setKeepAliveOptions0(handle, idleSeconds, intervalSeconds, count);
    }

    private native void setKeepAliveOptions0(long handle, int idleSeconds, int intervalSeconds, int count)
            throws IOException;

    @Override
    public void setBusyPoll(int micros) throws IOException {
        ensureConnected();
        setBusyPoll0(handle, micros);
    }

    private native void setBusyPoll0(long handle, int micros) throws IOException;

    @Override
    public SocketProbe probe() {
        final long current = this.handle;
        if (this.socketState != SocketState.CONNECTED || current == 0) {
            return SocketProbe.DEAD;
        }
        return PROBE_RESULTS[probe0(current)];
    }

    private native int probe0(long handle);

    /**
     * Whether the current connection resumed an earlier TLS session instead of doing a full handshake. A session is
     * only available to resume once the previous connection has read the server's session ticket.
     */
    public boolean isSessionReused() throws SocketException {
        ensureConnected();
        return isSessionReused0(handle);
    }

    private native boolean isSessionReused0(long handle);
}

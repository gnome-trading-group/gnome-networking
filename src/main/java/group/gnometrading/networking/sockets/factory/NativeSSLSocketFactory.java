package group.gnometrading.networking.sockets.factory;

import group.gnometrading.networking.sockets.GnomeSocket;
import group.gnometrading.networking.sockets.NativeSSLSocket;

import java.io.IOException;
import java.net.InetSocketAddress;

public class NativeSSLSocketFactory extends GnomeSocketFactory {

    /** Overrides the trusted CA bundle (PEM) for every factory built with the no-argument constructor. */
    public static final String CA_FILE_PROPERTY = "gnome.ssl.caFile";

    private final String caFile;
    private volatile long context;

    public NativeSSLSocketFactory() {
        this(System.getProperty(CA_FILE_PROPERTY));
    }

    /**
     * @param caFile PEM file of the CA certificates to trust, or null for the system CA bundle
     */
    public NativeSSLSocketFactory(String caFile) {
        this.caFile = caFile;
    }

    @Override
    public GnomeSocket createSocket(InetSocketAddress remoteAddress) throws IOException {
        if (this.caFile == null) {
            return new NativeSSLSocket(remoteAddress);
        }
        return new NativeSSLSocket(remoteAddress, context());
    }

    private long context() throws IOException {
        long current = this.context;
        if (current == 0) {
            synchronized (this) {
                current = this.context;
                if (current == 0) {
                    current = NativeSSLSocket.createContext(this.caFile);
                    this.context = current;
                }
            }
        }
        return current;
    }
}

package group.gnometrading.networking.sockets.factory;

import group.gnometrading.networking.sockets.GSocket;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

public abstract class GSocketFactory {

    private static GSocketFactory DEFAULT;

    public static GSocketFactory getDefault() {
        synchronized(GSocketFactory.class) {
            if (DEFAULT == null) {
                DEFAULT = new NativeSocketFactory();
            }
        }

        return DEFAULT;
    }

    public GSocket createSocket() throws IOException {
        throw new UnsupportedOperationException("Unconnected sockets not implemented");
    }

    public abstract GSocket createSocket(String remoteHost, int remotePort) throws IOException;

    public GSocket createSocket(URI remoteUri) throws IOException {
        throw new UnsupportedOperationException("URI sockets not implemented");
    }

    public abstract GSocket createSocket(InetAddress remoteAddress, int remotePort) throws IOException;

    public abstract GSocket createSocket(InetSocketAddress remoteAddress) throws IOException;

    // TODO: Add constructors for binding
}

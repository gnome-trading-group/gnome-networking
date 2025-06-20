package group.gnometrading.networking.sockets.factory;

import group.gnometrading.networking.sockets.GnomeSocket;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

public abstract class GnomeSocketFactory {

    private static final GnomeSocketFactory DEFAULT = new NativeSocketFactory();

    public static GnomeSocketFactory getDefault() {
        return DEFAULT;
    }

    public GnomeSocket createSocket() throws IOException {
        throw new UnsupportedOperationException("Unconnected sockets not implemented");
    }

    public GnomeSocket createSocket(String remoteHost, int remotePort) throws IOException {
        return createSocket(new InetSocketAddress(remoteHost, remotePort));
    }

    public GnomeSocket createSocket(URI remoteUri) throws IOException {
        throw new UnsupportedOperationException("URI sockets not implemented");
    }

    public GnomeSocket createSocket(InetAddress remoteAddress, int remotePort) throws IOException {
        return createSocket(new InetSocketAddress(remoteAddress, remotePort));
    }

    public abstract GnomeSocket createSocket(InetSocketAddress remoteAddress) throws IOException;
}

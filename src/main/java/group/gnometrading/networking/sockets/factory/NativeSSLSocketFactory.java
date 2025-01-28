package group.gnometrading.networking.sockets.factory;

import group.gnometrading.networking.sockets.GnomeSocket;
import group.gnometrading.networking.sockets.NativeSSLSocket;

import java.io.IOException;
import java.net.InetSocketAddress;

public class NativeSSLSocketFactory extends GnomeSocketFactory {
    @Override
    public GnomeSocket createSocket(InetSocketAddress remoteAddress) throws IOException {
        return new NativeSSLSocket(remoteAddress);
    }
}

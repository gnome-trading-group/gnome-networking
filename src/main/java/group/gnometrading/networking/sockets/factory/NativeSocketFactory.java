package group.gnometrading.networking.sockets.factory;

import group.gnometrading.networking.sockets.GnomeSocket;
import group.gnometrading.networking.sockets.NativeSocket;

import java.io.IOException;
import java.net.InetSocketAddress;

public class NativeSocketFactory extends GnomeSocketFactory {
    @Override
    public GnomeSocket createSocket(InetSocketAddress remoteAddress) throws IOException {
        return new NativeSocket(remoteAddress);
    }
}

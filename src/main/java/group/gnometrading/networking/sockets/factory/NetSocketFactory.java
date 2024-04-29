package group.gnometrading.networking.sockets.factory;

import group.gnometrading.networking.sockets.GnomeSocket;
import group.gnometrading.networking.sockets.NetSocket;

import java.io.IOException;
import java.net.InetSocketAddress;

public class NetSocketFactory extends GnomeSocketFactory {
    @Override
    public GnomeSocket createSocket(InetSocketAddress remoteAddress) throws IOException {
        return new NetSocket(remoteAddress);
    }
}

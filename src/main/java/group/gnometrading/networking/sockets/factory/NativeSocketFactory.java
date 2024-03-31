package group.gnometrading.networking.sockets.factory;

import group.gnometrading.networking.sockets.GSocket;
import group.gnometrading.networking.sockets.NativeSocket;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public class NativeSocketFactory extends GSocketFactory {
    @Override
    public GSocket createSocket(String remoteHost, int remotePort) throws IOException {
        return createSocket(new InetSocketAddress(remoteHost, remotePort));
    }

    @Override
    public GSocket createSocket(InetAddress remoteAddress, int remotePort) throws IOException {
        return createSocket(new InetSocketAddress(remoteAddress, remotePort));
    }

    @Override
    public GSocket createSocket(InetSocketAddress remoteAddress) throws IOException {
        return new NativeSocket(remoteAddress);
    }
}

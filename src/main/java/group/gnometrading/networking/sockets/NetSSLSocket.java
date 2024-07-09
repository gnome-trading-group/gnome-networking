package group.gnometrading.networking.sockets;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class NetSSLSocket extends NetSocket {
    public NetSSLSocket(InetSocketAddress remoteAddress) throws IOException {
        super(remoteAddress);
    }

    @Override
    protected Socket createSocket(InetSocketAddress remoteAddress) throws IOException {
        return SSLSocketFactory.getDefault().createSocket(remoteAddress.getAddress(), remoteAddress.getPort());
    }

    public void startHandshake() throws IOException {
        var socket = (SSLSocket) this.socket;
        socket.startHandshake();
    }
}
package group.gnometrading.networking.sockets;

import group.gnometrading.networking.BaseSocketServer;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.InetAddress;

public class NativeSocketIntegrationTest extends BaseNativeSocketIntegrationTest {

    @Override
    protected GnomeSocket createClient(int port) throws IOException {
        return new NativeSocketFactory().createSocket(TEST_HOST, port);
    }

    @Override
    protected BaseSocketServer createServer() {
        return new SocketServer(TEST_HOST, 0);
    }

    @Override
    protected ServerSocket createClosingServer() throws Exception {
        return new ServerSocket(0, 1, InetAddress.getByName(TEST_HOST));
    }
}

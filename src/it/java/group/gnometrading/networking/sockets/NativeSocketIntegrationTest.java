package group.gnometrading.networking.sockets;

import group.gnometrading.networking.BaseSocketServer;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;

import java.io.IOException;

public class NativeSocketIntegrationTest extends BaseNativeSocketIntegrationTest {

    @Override
    protected GnomeSocket createClient(int port) throws IOException {
        return new NativeSocketFactory().createSocket(TEST_HOST, port);
    }

    @Override
    protected BaseSocketServer createServer() {
        return new SocketServer(TEST_HOST, 0);
    }
}
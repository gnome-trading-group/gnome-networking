package group.gnometrading.networking.sockets;

import group.gnometrading.networking.BaseSocketServer;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;

import java.io.IOException;

public class NativeSocketIntegrationTest extends BaseNativeSocketIntegrationTest {

    @Override
    protected GnomeSocket createClient() throws IOException {
        return new NativeSocketFactory().createSocket(TEST_HOST, 8081);
    }

    @Override
    protected BaseSocketServer createServer() {
        return new SocketServer(TEST_HOST, 8081);
    }
}
package group.gnometrading.networking.sockets;

import group.gnometrading.networking.BaseSocketServer;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;

import java.io.IOException;

public class NativeSSLSocketIntegrationTest extends BaseNativeSocketIntegrationTest {

    @Override
    protected GnomeSocket createClient(int port) throws IOException {
        return new NativeSSLSocketFactory().createSocket(TEST_HOST, port);
    }

    @Override
    protected BaseSocketServer createServer() {
        try {
            return new SSLSocketServer(TEST_HOST, 0, new SSLConfig());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
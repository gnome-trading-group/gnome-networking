package group.gnometrading.networking.sockets;

import group.gnometrading.networking.BaseSocketServer;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;

import java.io.IOException;

public class NativeSSLSocketIntegrationTest extends BaseNativeSocketIntegrationTest {

    @Override
    protected GnomeSocket createClient() throws IOException {
        return new NativeSSLSocketFactory().createSocket(TEST_HOST, 8443);
    }

    @Override
    protected BaseSocketServer createServer() {
        try {
            return new SSLSocketServer(TEST_HOST, 8443, new SSLConfig());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
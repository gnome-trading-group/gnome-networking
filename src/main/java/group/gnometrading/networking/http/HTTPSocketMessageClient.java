package group.gnometrading.networking.http;

import group.gnometrading.networking.client.AbstractSocketMessageClient;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.sockets.factory.NetSSLSocketFactory;
import sun.nio.ch.IOStatus;

import java.io.IOException;
import java.net.InetSocketAddress;

class HTTPSocketMessageClient extends AbstractSocketMessageClient {

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_HTTPS_PORT = 443;
    public static final int DEFAULT_HTTP_READ_BUFFER_SIZE = 1 << 15; // 32kb

    private final HTTPDecoder httpDecoder;
    private final HTTPEncoder httpEncoder;
    private final String host;

    public HTTPSocketMessageClient(final HTTPProtocol protocol, final String host) throws IOException {
        super(parseURL(host, parsePort(protocol)), parseSocketFactory(protocol), DEFAULT_HTTP_READ_BUFFER_SIZE, DEFAULT_WRITE_BUFFER_SIZE, false, false);
        this.host = host;
        this.httpDecoder = new HTTPDecoder();
        this.httpEncoder = new HTTPEncoder();
//        this.socket.configureBlocking(true);
    }

    private static int parsePort(final HTTPProtocol protocol) {
        return protocol == HTTPProtocol.HTTPS ? DEFAULT_HTTPS_PORT : DEFAULT_PORT;
    }

    private static InetSocketAddress parseURL(final String host, final int port) {
        return new InetSocketAddress(host, port);
    }

    private static GnomeSocketFactory parseSocketFactory(final HTTPProtocol protocol) {
        return protocol == HTTPProtocol.HTTPS ? new NetSSLSocketFactory() : GnomeSocketFactory.getDefault();
    }

    public int request(final HTTPMethod method, final String path, final byte[] body) throws IOException {
        this.writeBuffer.clear();
        httpEncoder.wrap(this.writeBuffer);
        httpEncoder.encode(method, path, this.host, body);
        this.writeBuffer.flip();

        return IOStatus.normalize(this.socket.write(this.writeBuffer));
    }

    @Override
    public boolean isCompleteMessage() {
        this.httpDecoder.wrap(this.readBuffer);
        return this.httpDecoder.isComplete();
    }

    public HTTPDecoder getHTTPDecoder() {
        return httpDecoder;
    }

    public boolean available() throws IOException {
        // TODO: Use native socket code to check if the socket is still open
        return true;
//        return request(HTTPMethod.HEAD, "/", null) >= 0;
    }
}

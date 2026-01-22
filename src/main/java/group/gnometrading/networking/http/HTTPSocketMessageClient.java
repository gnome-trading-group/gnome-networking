package group.gnometrading.networking.http;

import group.gnometrading.networking.client.AbstractSocketMessageClient;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.strings.GnomeString;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

class HTTPSocketMessageClient extends AbstractSocketMessageClient {

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_HTTPS_PORT = 443;
    public static final int DEFAULT_HTTP_READ_BUFFER_SIZE = 1 << 15; // 32kb

    private final HTTPDecoder httpDecoder;
    private final HTTPEncoder httpEncoder;
    private final String host;

    public HTTPSocketMessageClient(final HTTPProtocol protocol, final String host) throws IOException {
        this(protocol, host, parsePort(protocol));
    }

    public HTTPSocketMessageClient(final HTTPProtocol protocol, final String host, final int port) throws IOException {
        super(parseURL(host, port), parseSocketFactory(protocol), DEFAULT_HTTP_READ_BUFFER_SIZE, DEFAULT_WRITE_BUFFER_SIZE);
        this.host = host;
        this.httpDecoder = new HTTPDecoder();
        this.httpEncoder = new HTTPEncoder();
    }

    private static int parsePort(final HTTPProtocol protocol) {
        return protocol == HTTPProtocol.HTTPS ? DEFAULT_HTTPS_PORT : DEFAULT_PORT;
    }

    private static InetSocketAddress parseURL(final String host, final int port) {
        return new InetSocketAddress(host, port);
    }

    private static GnomeSocketFactory parseSocketFactory(final HTTPProtocol protocol) {
        return protocol == HTTPProtocol.HTTPS ? new NativeSSLSocketFactory() : new NativeSocketFactory();
    }

    public int request(
            final HTTPMethod method,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        this.writeBuffer.clear();
        httpEncoder.wrap(this.writeBuffer);
        httpEncoder.encode(method, path, this.host, body, headerKey1, headerValue1, headerKey2, headerValue2);
        this.writeBuffer.flip();

        return this.socket.write(this.writeBuffer);
    }

    public int request(
            final HTTPMethod method,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        this.writeBuffer.clear();
        httpEncoder.wrap(this.writeBuffer);
        httpEncoder.encode(method, path, this.host, body, headerKey1, headerValue1, headerKey2, headerValue2);
        this.writeBuffer.flip();

        return this.socket.write(this.writeBuffer);
    }

    @Override
    public boolean isCompleteMessage(final ByteBuffer byteBuffer) {
        this.httpDecoder.wrap(byteBuffer);
        return this.httpDecoder.isComplete();
    }

    public HTTPDecoder getHTTPDecoder() {
        return httpDecoder;
    }
}

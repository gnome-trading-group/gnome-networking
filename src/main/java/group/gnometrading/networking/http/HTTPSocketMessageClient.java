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
    public static final int DEFAULT_HTTP_READ_BUFFER_SIZE = 1 << 15; // 32KB
    public static final int DEFAULT_HTTP_WRITE_BUFFER_SIZE = 1 << 15; // 32KB

    // Shared so that every connection uses the same TLS context, rather than building one per connection.
    private static final GnomeSocketFactory SSL_SOCKET_FACTORY = new NativeSSLSocketFactory();
    private static final GnomeSocketFactory PLAIN_SOCKET_FACTORY = new NativeSocketFactory();

    private final HTTPDecoder httpDecoder;
    private final HTTPEncoder httpEncoder;
    private final String host;
    private final HTTPProtocol protocol;

    public HTTPSocketMessageClient(final HTTPProtocol protocol, final String host) throws IOException {
        this(protocol, host, parsePort(protocol), DEFAULT_HTTP_READ_BUFFER_SIZE);
    }

    public HTTPSocketMessageClient(final HTTPProtocol protocol, final String host, final int port) throws IOException {
        this(protocol, host, port, DEFAULT_HTTP_READ_BUFFER_SIZE);
    }

    public HTTPSocketMessageClient(
            final HTTPProtocol protocol, final String host, final int port, final int readBufferSize) throws IOException {
        this(protocol, host, port, readBufferSize, DEFAULT_HTTP_WRITE_BUFFER_SIZE, null);
    }

    /**
     * @param writeBufferSize the largest request (headers and body) the connection can send
     * @param socketFactory creates the connection's socket; null for the default plain or TLS socket for
     *     {@code protocol}
     */
    public HTTPSocketMessageClient(
            final HTTPProtocol protocol,
            final String host,
            final int port,
            final int readBufferSize,
            final int writeBufferSize,
            final GnomeSocketFactory socketFactory) throws IOException {
        super(
                parseURL(host, port),
                socketFactory != null ? socketFactory : parseSocketFactory(protocol),
                readBufferSize,
                writeBufferSize);
        this.host = host;
        this.protocol = protocol;
        this.httpDecoder = new HTTPDecoder();
        this.httpEncoder = new HTTPEncoder();
    }

    HTTPProtocol protocol() {
        return this.protocol;
    }

    static int parsePort(final HTTPProtocol protocol) {
        return protocol == HTTPProtocol.HTTPS ? DEFAULT_HTTPS_PORT : DEFAULT_PORT;
    }

    private static InetSocketAddress parseURL(final String host, final int port) {
        return new InetSocketAddress(host, port);
    }

    private static GnomeSocketFactory parseSocketFactory(final HTTPProtocol protocol) {
        return protocol == HTTPProtocol.HTTPS ? SSL_SOCKET_FACTORY : PLAIN_SOCKET_FACTORY;
    }

    public int request(
            final HTTPMethod method,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return request(method, path, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, null, null);
    }

    public int request(
            final HTTPMethod method,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return request(method, path, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, null, null);
    }

    public int request(
            final HTTPMethod method,
            final String path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, null, null);
    }

    public int request(
            final HTTPMethod method,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, null, null);
    }

    public int request(
            final HTTPMethod method,
            final String path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null);
    }

    public int request(
            final HTTPMethod method,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null);
    }

    public int request(
            final HTTPMethod method,
            final String path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public int request(
            final HTTPMethod method,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public int request(
            final HTTPMethod method,
            final String path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        this.writeBuffer.clear();
        httpEncoder.wrap(this.writeBuffer);
        httpEncoder.encode(method, path, this.host, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
        this.writeBuffer.flip();

        return writeRequest();
    }

    public int request(
            final HTTPMethod method,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        this.writeBuffer.clear();
        httpEncoder.wrap(this.writeBuffer);
        httpEncoder.encode(method, path, this.host, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
        this.writeBuffer.flip();

        return writeRequest();
    }

    /**
     * Writes the whole encoded request. A request cut off part way would leave the connection unusable, so a
     * partial write fails rather than returning.
     */
    private int writeRequest() throws IOException {
        int total = 0;
        while (this.writeBuffer.hasRemaining()) {
            final int written = this.socket.write(this.writeBuffer);
            if (written <= 0) {
                if (total == 0) {
                    return written;
                }
                throw new IOException("Request write stopped after " + total + " bytes");
            }
            total += written;
        }
        return total;
    }

    @Override
    public boolean isCompleteMessage(final ByteBuffer byteBuffer) throws IOException {
        this.httpDecoder.wrap(byteBuffer);
        return this.httpDecoder.isComplete();
    }

    public HTTPDecoder getHTTPDecoder() {
        return httpDecoder;
    }
}

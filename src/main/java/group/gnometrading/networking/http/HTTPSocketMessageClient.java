package group.gnometrading.networking.http;

import group.gnometrading.networking.client.AbstractSocketMessageClient;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.sockets.factory.NetSSLSocketFactory;
import sun.nio.ch.IOStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;

public class HTTPSocketMessageClient extends AbstractSocketMessageClient {

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_HTTPS_PORT = 443;
    public static final int DEFAULT_HTTP_READ_BUFFER_SIZE = 1 << 15; // 32kb

    private final URL url;
    private final HTTPDecoder httpDecoder;
    private final HTTPEncoder httpEncoder;
    private final ByteBuffer payloadBuffer;

    public HTTPSocketMessageClient(final URL url) throws IOException {
        super(parseURL(url), parseSocketFactory(url), DEFAULT_HTTP_READ_BUFFER_SIZE, DEFAULT_WRITE_BUFFER_SIZE, false, false);
        this.url = url;
        this.httpDecoder = new HTTPDecoder();
        this.httpEncoder = new HTTPEncoder();
        this.payloadBuffer = ByteBuffer.allocate(DEFAULT_READ_BUFFER_SIZE);
    }

    private static int parsePort(final URL url) {
        return url.getPort() == -1 ? (url.getProtocol().equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_PORT) : url.getPort();
    }

    private static boolean isSSL(final URL url) {
        return parsePort(url) == DEFAULT_HTTPS_PORT;
    }

    private static InetSocketAddress parseURL(final URL url) {
        return new InetSocketAddress(url.getHost(), parsePort(url));
    }

    private static GnomeSocketFactory parseSocketFactory(final URL url) {
        return isSSL(url) ? new NetSSLSocketFactory() : GnomeSocketFactory.getDefault();
    }

    public int request(final HTTPMethod method, final String path, final byte[] body) throws IOException {
        this.writeBuffer.clear();
        httpEncoder.wrap(this.writeBuffer);
        httpEncoder.encode(method, path, url, body);
        this.writeBuffer.flip();

        return IOStatus.normalize(this.socket.write(this.writeBuffer));
    }

    @Override
    public ByteBuffer readMessage() throws IOException {
        final ByteBuffer buffer = super.readMessage();
        if (buffer.remaining() == 0) {
            return buffer;
        }

        this.readBuffer.position(this.readBuffer.position() + this.httpDecoder.length());
        if (this.httpDecoder.contentLength() > 0) {
            this.payloadBuffer.clear();
            this.httpDecoder.copyBody(this.payloadBuffer);
            return this.payloadBuffer.flip();
        }
        return EMPTY_BUFFER;
    }

    @Override
    public boolean isCompleteMessage(final ByteBuffer directBuffer) {
        this.httpDecoder.wrap(directBuffer);
        return this.httpDecoder.isComplete();
    }
}

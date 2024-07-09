package group.gnometrading.networking.http;

import group.gnometrading.utils.ByteBufferUtils;

import java.net.URL;
import java.nio.ByteBuffer;

public class HTTPEncoder {
    static final String DEFAULT_PROTOCOL = "HTTP/1.1";
    static final String LINE_SEPARATOR = "\r\n";

    private final String protocol;

    private ByteBuffer buffer;

    public HTTPEncoder() {
        this(DEFAULT_PROTOCOL);
    }

    public HTTPEncoder(final String protocol) {
        this.protocol = protocol;
    }

    public void wrap(final ByteBuffer buffer) {
        this.buffer = buffer;
    }

    public void encode(final HTTPMethod method, final String path, final URL url, final byte[] body) {
        ByteBufferUtils.putString(this.buffer, method.name());
        this.buffer.put((byte) ' ');
        ByteBufferUtils.putString(this.buffer, path);
        this.buffer.put((byte) ' ');
        ByteBufferUtils.putString(this.buffer, this.protocol);
        ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);

        ByteBufferUtils.putString(this.buffer, "Host: ");
        ByteBufferUtils.putString(this.buffer, url.getHost());
        ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);

        if (body != null) {
            ByteBufferUtils.putString(this.buffer, "Content-Length: ");
            ByteBufferUtils.putNaturalIntAscii(buffer, body.length);
            ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);
        }

        ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);
        if (body != null) {
            this.buffer.put(body);
        }
    }
}

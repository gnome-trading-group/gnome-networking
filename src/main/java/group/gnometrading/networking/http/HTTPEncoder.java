package group.gnometrading.networking.http;

import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ByteBufferUtils;

import java.nio.ByteBuffer;

public class HTTPEncoder {
    static final String DEFAULT_PROTOCOL = "HTTP/1.1";
    static final String LINE_SEPARATOR = "\r\n";
    static final String HEADER_SEPARATOR = ": ";

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

    private void encodeBody(
            final String host,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) {
        ByteBufferUtils.putString(this.buffer, this.protocol);
        ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);

        ByteBufferUtils.putString(this.buffer, "Host: ");
        ByteBufferUtils.putString(this.buffer, host);
        ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);

        ByteBufferUtils.putString(this.buffer, "Connection: keep-alive");
        ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);

        if (headerKey1 != null) {
            ByteBufferUtils.putString(this.buffer, headerKey1);
            ByteBufferUtils.putString(this.buffer, HEADER_SEPARATOR);
            ByteBufferUtils.putString(this.buffer, headerValue1);
            ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);
        }

        if (headerKey2 != null) {
            ByteBufferUtils.putString(this.buffer, headerKey2);
            ByteBufferUtils.putString(this.buffer, HEADER_SEPARATOR);
            ByteBufferUtils.putString(this.buffer, headerValue2);
            ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);
        }

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

    public void encode(
            final HTTPMethod method,
            final GnomeString path,
            final String host,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) {
        ByteBufferUtils.putString(this.buffer, method.name());
        this.buffer.put((byte) ' ');
        ByteBufferUtils.putString(this.buffer, path);
        this.buffer.put((byte) ' ');

        encodeBody(host, body, headerKey1, headerValue1, headerKey2, headerValue2);
    }

    public void encode(
            final HTTPMethod method,
            final String path,
            final String host,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) {
        ByteBufferUtils.putString(this.buffer, method.name());
        this.buffer.put((byte) ' ');
        ByteBufferUtils.putString(this.buffer, path);
        this.buffer.put((byte) ' ');

        encodeBody(host, body, headerKey1, headerValue1, headerKey2, headerValue2);
    }
}

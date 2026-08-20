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
            final String headerValue5) {
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

        if (headerKey3 != null) {
            ByteBufferUtils.putString(this.buffer, headerKey3);
            ByteBufferUtils.putString(this.buffer, HEADER_SEPARATOR);
            ByteBufferUtils.putString(this.buffer, headerValue3);
            ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);
        }

        if (headerKey4 != null) {
            ByteBufferUtils.putString(this.buffer, headerKey4);
            ByteBufferUtils.putString(this.buffer, HEADER_SEPARATOR);
            ByteBufferUtils.putString(this.buffer, headerValue4);
            ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);
        }

        if (headerKey5 != null) {
            ByteBufferUtils.putString(this.buffer, headerKey5);
            ByteBufferUtils.putString(this.buffer, HEADER_SEPARATOR);
            ByteBufferUtils.putString(this.buffer, headerValue5);
            ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);
        }

        if (body != null) {
            ByteBufferUtils.putString(this.buffer, "Content-Length: ");
            ByteBufferUtils.putNaturalIntAscii(buffer, bodyLength);
            ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);
        }

        ByteBufferUtils.putString(this.buffer, LINE_SEPARATOR);
        if (body != null) {
            this.buffer.put(body, 0, bodyLength);
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
            final String headerValue2) {
        encode(method, path, host, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, null, null);
    }

    public void encode(
            final HTTPMethod method,
            final String path,
            final String host,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) {
        encode(method, path, host, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, null, null);
    }

    public void encode(
            final HTTPMethod method,
            final GnomeString path,
            final String host,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) {
        encode(method, path, host, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, null, null);
    }

    public void encode(
            final HTTPMethod method,
            final String path,
            final String host,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) {
        encode(method, path, host, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, null, null);
    }

    public void encode(
            final HTTPMethod method,
            final GnomeString path,
            final String host,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) {
        encode(method, path, host, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null);
    }

    public void encode(
            final HTTPMethod method,
            final String path,
            final String host,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) {
        encode(method, path, host, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null);
    }

    public void encode(
            final HTTPMethod method,
            final GnomeString path,
            final String host,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) {
        encode(method, path, host, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public void encode(
            final HTTPMethod method,
            final String path,
            final String host,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) {
        encode(method, path, host, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public void encode(
            final HTTPMethod method,
            final GnomeString path,
            final String host,
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
            final String headerValue5) {
        this.buffer.put(method.encoded());
        this.buffer.put((byte) ' ');
        ByteBufferUtils.putString(this.buffer, path);
        this.buffer.put((byte) ' ');

        encodeBody(host, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    public void encode(
            final HTTPMethod method,
            final String path,
            final String host,
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
            final String headerValue5) {
        this.buffer.put(method.encoded());
        this.buffer.put((byte) ' ');
        ByteBufferUtils.putString(this.buffer, path);
        this.buffer.put((byte) ' ');

        encodeBody(host, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }
}

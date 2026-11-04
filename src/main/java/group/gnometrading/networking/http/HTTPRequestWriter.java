package group.gnometrading.networking.http;

import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ByteBufferUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Encodes one HTTP/1.1 request straight into a connection's write buffer, without allocating: the path may be
 * written in pieces, header values are bytes, and there is no limit on the number of headers. Call in order:
 * {@code path} pieces, then {@code header}s, then {@link #body} or {@link #noBody}.
 */
public final class HTTPRequestWriter {

    private static final byte[] REQUEST_LINE_END = " HTTP/1.1\r\nHost: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEEP_ALIVE = "\r\nConnection: keep-alive\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEADER_SEPARATOR = ": ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTENT_LENGTH = "Content-Length: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte CR = '\r';
    private static final byte LF = '\n';

    private ByteBuffer buffer;
    private byte[] host;
    private boolean inPath;

    HTTPRequestWriter start(final ByteBuffer buffer, final byte[] host, final HTTPMethod method) {
        this.buffer = buffer;
        this.host = host;
        this.inPath = true;
        buffer.clear();
        buffer.put(method.encoded());
        buffer.put((byte) ' ');
        return this;
    }

    public HTTPRequestWriter path(final byte[] bytes, final int offset, final int length) {
        requirePath();
        this.buffer.put(bytes, offset, length);
        return this;
    }

    public HTTPRequestWriter path(final byte[] bytes) {
        return path(bytes, 0, bytes.length);
    }

    public HTTPRequestWriter path(final GnomeString text) {
        requirePath();
        ByteBufferUtils.putString(this.buffer, text);
        return this;
    }

    public HTTPRequestWriter header(final byte[] name, final byte[] value, final int offset, final int length) {
        startHeader(name);
        this.buffer.put(value, offset, length);
        return endLine();
    }

    public HTTPRequestWriter header(final byte[] name, final byte[] value) {
        return header(name, value, 0, value.length);
    }

    public HTTPRequestWriter header(final byte[] name, final GnomeString value) {
        startHeader(name);
        ByteBufferUtils.putString(this.buffer, value);
        return endLine();
    }

    public HTTPRequestWriter header(final byte[] name, final long value) {
        startHeader(name);
        ByteBufferUtils.putLongAscii(this.buffer, value);
        return endLine();
    }

    /** Ends the headers with a Content-Length and writes the body. */
    public void body(final byte[] body, final int offset, final int length) {
        startHeader(CONTENT_LENGTH, false);
        ByteBufferUtils.putNaturalIntAscii(this.buffer, length);
        endLine();
        endLine();
        this.buffer.put(body, offset, length);
    }

    /** Ends a request that carries no body. */
    public void noBody() {
        closeRequestLine();
        endLine();
    }

    private void startHeader(final byte[] name) {
        startHeader(name, true);
    }

    private void startHeader(final byte[] name, final boolean withSeparator) {
        closeRequestLine();
        this.buffer.put(name);
        if (withSeparator) {
            this.buffer.put(HEADER_SEPARATOR);
        }
    }

    private HTTPRequestWriter endLine() {
        this.buffer.put(CR);
        this.buffer.put(LF);
        return this;
    }

    private void closeRequestLine() {
        if (this.inPath) {
            this.inPath = false;
            this.buffer.put(REQUEST_LINE_END);
            this.buffer.put(this.host);
            this.buffer.put(KEEP_ALIVE);
        }
    }

    private void requirePath() {
        if (!this.inPath) {
            throw new IllegalStateException("The path is written before any header or body");
        }
    }
}

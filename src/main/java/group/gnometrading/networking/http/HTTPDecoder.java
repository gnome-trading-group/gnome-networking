package group.gnometrading.networking.http;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Parses one HTTP/1.1 response from the front of a buffer. A chunked body is reassembled in place, in the buffer
 * itself, once the whole response has arrived, so no bytes are copied elsewhere.
 */
public class HTTPDecoder {

    private static final int NO_LENGTH = -1;
    private static final int STATUS_DIGITS = 3;
    private static final int HEX_RADIX = 16;
    private static final int STATUS_NO_CONTENT = 204;
    private static final int STATUS_NOT_MODIFIED = 304;
    private static final int STATUS_CONTINUE_CLASS = 100;
    private static final int STATUS_SWITCHING_PROTOCOLS = 101;
    private static final int STATUS_SUCCESS_CLASS = 200;
    private static final byte LOWERCASE_BIT = 0x20;

    private ByteBuffer buffer;
    private boolean completed;
    private boolean closeConnection;
    private int bodyOffset, status, contentLength;

    public void wrap(final ByteBuffer buffer) throws IOException {
        this.buffer = buffer;

        this.completed = false;
        this.closeConnection = false;
        this.bodyOffset = -1;
        this.status = -1;
        this.contentLength = -1;
        parse();
    }

    private void parse() throws IOException {
        int start = this.buffer.position();
        // Interim 1xx responses (e.g. 103 Early Hints) come before the real one; treating one as the answer
        // would leave the real response to be read as the reply to the next request.
        while (true) {
            final int nextStart = parseResponse(start);
            if (nextStart < 0) {
                return;
            }
            start = nextStart;
        }
    }

    /** Parses the response at {@code start}; returns where the next one starts if it was an interim 1xx, else -1. */
    private int parseResponse(final int start) throws IOException {
        final int limit = this.buffer.limit();
        if (limit - start < 4) {
            return -1;
        }
        if (this.buffer.get(start) != 'H' || this.buffer.get(start + 1) != 'T'
                || this.buffer.get(start + 2) != 'T' || this.buffer.get(start + 3) != 'P') {
            throw new IOException("HTTP response is not compliant");
        }

        final int statusLineEnd = indexOf((byte) '\n', start, limit);
        if (statusLineEnd < 0) {
            return -1;
        }
        final int status = parseStatus(start, statusLineEnd);

        int length = NO_LENGTH;
        boolean chunked = false;
        // HTTP/1.0 closes after each response unless it asks to keep the connection alive.
        final boolean http10 = this.buffer.get(start + 5) == '1' && this.buffer.get(start + 6) == '.'
                && this.buffer.get(start + 7) == '0';
        boolean keepAlive = false;
        int lineStart = statusLineEnd + 1;
        while (true) {
            final int lineEnd = indexOf((byte) '\n', lineStart, limit);
            if (lineEnd < 0) {
                return -1;
            }
            final int contentEnd = lineEnd > lineStart && this.buffer.get(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
            if (contentEnd == lineStart) {
                lineStart = lineEnd + 1;
                break;
            }

            final int colon = indexOf((byte) ':', lineStart, contentEnd);
            if (colon > 0) {
                final int valueStart = skipSpaces(colon + 1, contentEnd);
                if (nameEquals(lineStart, colon, "content-length")) {
                    length = parseDecimal(valueStart, contentEnd);
                } else if (nameEquals(lineStart, colon, "transfer-encoding")) {
                    chunked = containsIgnoreCase(valueStart, contentEnd, "chunked");
                } else if (nameEquals(lineStart, colon, "connection")) {
                    this.closeConnection = containsIgnoreCase(valueStart, contentEnd, "close");
                    keepAlive = containsIgnoreCase(valueStart, contentEnd, "keep-alive");
                }
            }
            lineStart = lineEnd + 1;
        }

        if (isInterim(status)) {
            this.closeConnection = false;
            return lineStart;
        }

        if ((http10 && !keepAlive) || status == STATUS_SWITCHING_PROTOCOLS) {
            this.closeConnection = true;
        }
        this.status = status;
        this.bodyOffset = lineStart;
        if (chunked) {
            this.completed = decodeChunked(lineStart, limit);
        } else if (length == NO_LENGTH) {
            this.contentLength = 0;
            this.completed = true;
            this.buffer.position(lineStart);
            // Without a length, any body is delimited by the server closing the connection, which
            // this decoder does not support, so the connection cannot carry another request.
            if (hasBody(status)) {
                this.closeConnection = true;
            }
        } else {
            this.contentLength = length;
            this.completed = limit - lineStart >= length;
            if (this.completed) {
                this.buffer.position(lineStart + length);
            }
        }
        return -1;
    }

    /** 1xx responses other than 101 Switching Protocols are followed by the real response. */
    private static boolean isInterim(final int status) {
        return status >= STATUS_CONTINUE_CLASS && status < STATUS_SUCCESS_CLASS && status != STATUS_SWITCHING_PROTOCOLS;
    }

    private static boolean hasBody(final int status) {
        final boolean informational = status >= STATUS_CONTINUE_CLASS && status < STATUS_SUCCESS_CLASS;
        return !informational && status != STATUS_NO_CONTENT && status != STATUS_NOT_MODIFIED;
    }

    private int parseStatus(final int start, final int end) throws IOException {
        final int space = indexOf((byte) ' ', start, end);
        if (space < 0 || end - space <= STATUS_DIGITS) {
            throw new IOException("HTTP response has no status code");
        }
        int status = 0;
        for (int i = space + 1; i <= space + STATUS_DIGITS; i++) {
            final byte digit = this.buffer.get(i);
            if (digit < '0' || digit > '9') {
                throw new IOException("HTTP response has an invalid status code");
            }
            status = status * 10 + (digit - '0');
        }
        return status;
    }

    /**
     * Checks the whole chunked body has arrived and, if so, moves every chunk's data together so the body is
     * contiguous from {@link #bodyOffset}. Nothing is moved until the response is complete, since a partial
     * response is parsed again from scratch once more bytes arrive.
     */
    private boolean decodeChunked(final int start, final int limit) throws IOException {
        int position = start;
        int total = 0;
        while (true) {
            final int lineEnd = indexOf((byte) '\n', position, limit);
            if (lineEnd < 0) {
                return false;
            }
            final int size = parseChunkSize(position, lineEnd);
            final int dataStart = lineEnd + 1;
            if (size == 0) {
                final int end = skipTrailers(dataStart, limit);
                if (end < 0) {
                    return false;
                }
                compactChunks(start, total);
                this.contentLength = total;
                this.buffer.position(end);
                return true;
            }
            // Compared as remaining space so that a huge declared size cannot overflow the sum.
            if (size > limit - dataStart - 2) {
                return false;
            }
            if (this.buffer.get(dataStart + size) != '\r' || this.buffer.get(dataStart + size + 1) != '\n') {
                throw new IOException("HTTP response chunk is not followed by CRLF");
            }
            if (size > Integer.MAX_VALUE - total) {
                throw new IOException("HTTP response body is too large");
            }
            total += size;
            position = dataStart + size + 2;
        }
    }

    private void compactChunks(final int start, final int total) throws IOException {
        int position = start;
        int destination = start;
        final int end = start + total;
        while (destination < end) {
            final int lineEnd = indexOf((byte) '\n', position, this.buffer.limit());
            final int size = parseChunkSize(position, lineEnd);
            final int dataStart = lineEnd + 1;
            // The destination never passes the source, so a forward copy is safe within one buffer.
            for (int i = 0; i < size; i++) {
                this.buffer.put(destination + i, this.buffer.get(dataStart + i));
            }
            destination += size;
            position = dataStart + size + 2;
        }
    }

    /** Returns the index just past the empty line that ends the trailers, or -1 if it has not arrived. */
    private int skipTrailers(final int start, final int limit) {
        int lineStart = start;
        while (true) {
            final int lineEnd = indexOf((byte) '\n', lineStart, limit);
            if (lineEnd < 0) {
                return -1;
            }
            final boolean empty = lineEnd == lineStart || lineEnd == lineStart + 1 && this.buffer.get(lineStart) == '\r';
            lineStart = lineEnd + 1;
            if (empty) {
                return lineStart;
            }
        }
    }

    private int parseChunkSize(final int start, final int lineEnd) throws IOException {
        int size = 0;
        int digits = 0;
        for (int i = start; i < lineEnd; i++) {
            final int value = Character.digit(this.buffer.get(i), HEX_RADIX);
            if (value < 0) {
                break; // a chunk extension (';') or the line's '\r'
            }
            if (size > (Integer.MAX_VALUE - value) / HEX_RADIX) {
                throw new IOException("HTTP response has a chunk too large to hold");
            }
            size = size * HEX_RADIX + value;
            digits++;
        }
        if (digits == 0) {
            throw new IOException("HTTP response has an invalid chunk size");
        }
        return size;
    }

    private int parseDecimal(final int start, final int end) throws IOException {
        int value = 0;
        int digits = 0;
        for (int i = start; i < end; i++) {
            final byte at = this.buffer.get(i);
            if (at == ' ' || at == '\t') {
                break;
            }
            if (at < '0' || at > '9') {
                throw new IOException("HTTP response has an invalid Content-Length");
            }
            if (value > (Integer.MAX_VALUE - (at - '0')) / 10) {
                throw new IOException("HTTP response has a Content-Length too large to hold");
            }
            value = value * 10 + (at - '0');
            digits++;
        }
        if (digits == 0) {
            throw new IOException("HTTP response has an invalid Content-Length");
        }
        return value;
    }

    private int indexOf(final byte target, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (this.buffer.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private int skipSpaces(final int from, final int to) {
        int i = from;
        while (i < to && (this.buffer.get(i) == ' ' || this.buffer.get(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** Header names are case-insensitive; {@code lowercase} must be lowercase. */
    private boolean nameEquals(final int start, final int end, final String lowercase) {
        if (end - start != lowercase.length()) {
            return false;
        }
        for (int i = 0; i < lowercase.length(); i++) {
            if (toLower(this.buffer.get(start + i)) != lowercase.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsIgnoreCase(final int start, final int end, final String lowercase) {
        for (int i = start; i + lowercase.length() <= end; i++) {
            if (nameEquals(i, i + lowercase.length(), lowercase)) {
                return true;
            }
        }
        return false;
    }

    private static byte toLower(final byte value) {
        return value >= 'A' && value <= 'Z' ? (byte) (value | LOWERCASE_BIT) : value;
    }

    public boolean isComplete() {
        return this.completed;
    }

    /** Whether the server will not take another request on this connection after this response. */
    public boolean shouldClose() {
        return this.closeConnection;
    }

    public int length() {
        return this.bodyOffset + this.contentLength;
    }

    public int contentLength() {
        return this.contentLength;
    }

    public int getStatus() {
        return this.status;
    }

    public void copyBody(final ByteBuffer destination) {
        destination.put(destination.position(), this.buffer, this.bodyOffset, this.contentLength);
        destination.position(destination.position() + this.contentLength);
    }
}

package group.gnometrading.networking.http;

import group.gnometrading.strings.ExpandingMutableString;

import java.nio.ByteBuffer;

public class HTTPDecoder {

    private final ExpandingMutableString headerKey = new ExpandingMutableString();
    private final ExpandingMutableString headerValue = new ExpandingMutableString();

    private ByteBuffer buffer;
    private boolean completed;
    private int bodyOffset, status, contentLength;

    public void wrap(final ByteBuffer buffer) {
        this.buffer = buffer;

        this.completed = false;
        this.bodyOffset = -1;
        this.status = -1;
        this.contentLength = -1;
        parse();
    }

    private void parse() {
        if (!parseStatusLine()) {
            return;
        }

        if (!parseHeaders()) {
            return;
        }

        if (this.contentLength == -1) {
            return; // We did not have a Content-Length header
        }

        this.completed = this.buffer.remaining() >= this.contentLength;
    }

    private boolean parseStatusLine() {
        if (this.buffer.remaining() < 4) {
            return false;
        }

        if (this.buffer.get() != 'H' && this.buffer.get() != 'T' && this.buffer.get() != 'T' && this.buffer.get() != 'P') {
            throw new IllegalStateException("HTTP response is not compliant");
        }

        if (!continueThrough((byte) ' ')) return false;

        int status = 0;
        while (this.buffer.remaining() > 0) {
            byte at = this.buffer.get();
            if (at == ' ') {
                break;
            }

            status *= 10;
            status += (at - '0');
        }
        this.status = status;

        return continueThrough((byte) '\n');
    }

    private boolean parseHeaders() {
        while (parseHeader()) {
            if (headerKey.equals("Content-Length")) {
                this.contentLength = headerValue.toInt();
            } else if (headerKey.length() == 0) {
                // Consume the blank line before the body
                if (continueThrough((byte) '\n')) {
                    this.bodyOffset = this.buffer.position();
                    return true;
                }
            }
        }
        return false;
    }

    private boolean parseHeader() {
        headerKey.reset();
        headerValue.reset();

        while (this.buffer.remaining() > 0) {
            byte at = this.buffer.get();
            if (at == ':') {
                // Consume the space too
                if (this.buffer.remaining() > 0 && this.buffer.get(this.buffer.position()) == ' ') {
                    this.buffer.get();
                }
                break;
            } else if (at == '\r' && this.buffer.remaining() > 0 && this.buffer.get(this.buffer.position()) == '\n') {
                return true;
            }
            headerKey.append(at);
        }

        while (this.buffer.remaining() > 0) {
            byte at = this.buffer.get();
            if (at == '\r' && this.buffer.remaining() > 0 && this.buffer.get(this.buffer.position()) == '\n') {
                this.buffer.get();
                return true;
            }
            headerValue.append(at);
        }

        return false;
    }

    private boolean continueThrough(final byte b) {
        while (this.buffer.remaining() > 0) {
            if (this.buffer.get() == b) {
                return true;
            }
        }
        return false;
    }

    public boolean isComplete() {
        return this.completed;
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
        for (int i = 0; i < this.contentLength; i++) {
            destination.put(this.buffer.get(i + this.bodyOffset));
        }
    }

}

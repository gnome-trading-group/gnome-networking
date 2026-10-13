package group.gnometrading.networking.http;

import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.MutableString;
import group.gnometrading.utils.AsciiParsing;

import java.nio.ByteBuffer;

public class HTTPResponse {

    /** The status of a request a {@link RequestGate} refused: it was never sent. */
    public static final int STATUS_TOO_MANY_REQUESTS = 429;
    /** Captured header values longer than this are cut short. */
    public static final int MAX_HEADER_VALUE_LENGTH = 64;

    private boolean success;
    private int statusCode;
    private ByteBuffer body;
    private boolean refusedLocally;
    private final MutableString[] headers;
    private final boolean[] headerPresent;

    protected HTTPResponse() {
        this(0);
    }

    HTTPResponse(final int capturedHeaders) {
        this.headers = new MutableString[capturedHeaders];
        this.headerPresent = new boolean[capturedHeaders];
        for (int i = 0; i < capturedHeaders; i++) {
            this.headers[i] = new MutableString(MAX_HEADER_VALUE_LENGTH);
        }
    }

    public HTTPResponse update(final boolean success, final int statusCode, final ByteBuffer body) {
        this.success = success;
        this.statusCode = statusCode;
        this.body = body;
        this.refusedLocally = false;
        for (int i = 0; i < this.headerPresent.length; i++) {
            this.headerPresent[i] = false;
        }
        return this;
    }

    /** A request the client's {@link RequestGate} refused, answered as the venue would have: a 429. */
    HTTPResponse refuse() {
        update(false, STATUS_TOO_MANY_REQUESTS, null);
        this.refusedLocally = true;
        return this;
    }

    void captureHeaders(final HTTPDecoder decoder) {
        for (int i = 0; i < this.headers.length; i++) {
            this.headerPresent[i] = decoder.copyCapturedHeader(i, this.headers[i]);
        }
    }

    public boolean isSuccess() {
        return success;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public ByteBuffer getBody() {
        return body;
    }

    /** Whether the client's {@link RequestGate} refused the request, so it never reached the server. */
    public boolean isRefusedLocally() {
        return refusedLocally;
    }

    /** Whether the response had the header registered at {@code index} with {@link HTTPClientBuilder#captureHeaders}. */
    public boolean hasHeader(final int index) {
        return this.headerPresent[index];
    }

    /**
     * The value of the header registered at {@code index}, trimmed and cut to {@link #MAX_HEADER_VALUE_LENGTH}
     * bytes; null if the response didn't have it. Reused: valid until the client's next response.
     */
    public GnomeString header(final int index) {
        return this.headerPresent[index] ? this.headers[index] : null;
    }

    /** The header registered at {@code index} as a whole number, or {@code absent} if it is missing or isn't one. */
    public long headerAsLong(final int index, final long absent) {
        if (!this.headerPresent[index]) {
            return absent;
        }
        final MutableString value = this.headers[index];
        return AsciiParsing.parseNaturalLong(value.getBytes(), 0, value.length(), absent);
    }
}

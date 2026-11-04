package group.gnometrading.networking.http;

/** How one request sent through {@link AsyncHTTPConnections} ended. Reused: valid until the next poll. */
public final class HTTPCompletion {

    /** A whole response arrived; see {@link #response()}. */
    public static final int RESPONSE = 0;
    /** The connection failed or closed before a whole response arrived. The server may have acted on the request. */
    public static final int FAILED = 1;
    /** No whole response arrived by the request's deadline. The server may have acted on the request. */
    public static final int TIMED_OUT = 2;

    private final HTTPResponse response;
    private int outcome;
    private long tag;
    private int connection;

    HTTPCompletion(final int capturedHeaders) {
        this.response = new HTTPResponse(capturedHeaders);
    }

    HTTPCompletion set(final int outcome, final long tag, final int connection) {
        this.outcome = outcome;
        this.tag = tag;
        this.connection = connection;
        if (outcome != RESPONSE) {
            this.response.update(false, -1, null);
        }
        return this;
    }

    HTTPResponse mutableResponse() {
        return this.response;
    }

    public int outcome() {
        return this.outcome;
    }

    /** The tag the request was sent with. */
    public long tag() {
        return this.tag;
    }

    public int connection() {
        return this.connection;
    }

    /** The response when {@link #outcome()} is {@link #RESPONSE}; status -1 otherwise. */
    public HTTPResponse response() {
        return this.response;
    }
}

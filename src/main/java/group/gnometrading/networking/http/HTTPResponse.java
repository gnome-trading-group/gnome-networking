package group.gnometrading.networking.http;

import java.nio.ByteBuffer;

public class HTTPResponse {
    private boolean success;
    private int statusCode;
    private ByteBuffer body;

    protected HTTPResponse() {
    }

    public HTTPResponse update(final boolean success, final int statusCode, final ByteBuffer body) {
        this.success = success;
        this.statusCode = statusCode;
        this.body = body;
        return this;
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
}
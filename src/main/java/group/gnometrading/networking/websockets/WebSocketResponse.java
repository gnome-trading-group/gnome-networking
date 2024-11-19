package group.gnometrading.networking.websockets;

import group.gnometrading.networking.websockets.enums.Opcode;

import java.nio.ByteBuffer;

public class WebSocketResponse {

    private boolean success;
    private Opcode opcode;
    private ByteBuffer body;
    private boolean closed;

    protected WebSocketResponse() {}

    public WebSocketResponse update(final boolean success, final Opcode opcode, final ByteBuffer body, final boolean closed) {
        this.success = success;
        this.opcode = opcode;
        this.body = body;
        this.closed = closed;
        return this;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isSuccess() {
        return success;
    }

    public ByteBuffer getBody() {
        return body;
    }

    public Opcode getOpcode() {
        return opcode;
    }
}

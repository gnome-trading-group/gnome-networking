package group.gnometrading.networking.websockets;

import group.gnometrading.networking.websockets.enums.Opcode;

import java.nio.ByteBuffer;

public class WebSocketResponse {

    private boolean success;
    private Opcode opcode;
    private ByteBuffer body;

    protected WebSocketResponse() {}

    public WebSocketResponse update(final boolean success, final Opcode opcode, final ByteBuffer body) {
        this.success = success;
        this.opcode = opcode;
        this.body = body;
        return this;
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

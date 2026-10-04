package group.gnometrading.networking.websockets.exceptions;

import group.gnometrading.networking.websockets.enums.HandshakeState;

public class InvalidHandshakeException extends RuntimeException {
    private final HandshakeState handshakeState;

    public InvalidHandshakeException(HandshakeState handshakeState) {
        this(handshakeState, (Throwable) null);
    }

    public InvalidHandshakeException(HandshakeState handshakeState, Throwable cause) {
        super(handshakeState.description, cause);
        this.handshakeState = handshakeState;
    }

    /** @param detail what the server sent, e.g. its status line */
    public InvalidHandshakeException(HandshakeState handshakeState, String detail) {
        super(handshakeState.description + ": " + detail);
        this.handshakeState = handshakeState;
    }

    public HandshakeState getHandshakeState() {
        return handshakeState;
    }
}

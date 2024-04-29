package group.gnometrading.networking.websockets.exceptions;

import group.gnometrading.networking.websockets.enums.HandshakeState;

public class InvalidHandshakeException extends RuntimeException {
    public InvalidHandshakeException(HandshakeState handshakeState) {
        super(handshakeState.description);
    }
}

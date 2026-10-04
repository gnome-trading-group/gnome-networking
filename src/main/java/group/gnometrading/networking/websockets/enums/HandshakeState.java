package group.gnometrading.networking.websockets.enums;

public enum HandshakeState {
    MATCHED(null),
    INVALID_WRITE("IO error while sending handshake to server"),
    INVALID_READ("IO error while reading handshake from the server"),
    INCOMPLETE("Handshake is being sent in multiple packets"),
    INVALID_PROTOCOL("An invalid protocol was sent by the server"),
    TIMEOUT("The handshake attempt expired"),
    INVALID_HEADERS("The server's handshake response is missing a required header"),
    UNKNOWN("Unknown error occurred during the handshake");

    public final String description;

    HandshakeState(String description) {
        this.description = description;
    }
}

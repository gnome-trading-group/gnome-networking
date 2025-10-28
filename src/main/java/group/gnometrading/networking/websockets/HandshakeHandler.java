package group.gnometrading.networking.websockets;

import group.gnometrading.networking.client.Client;
import group.gnometrading.networking.websockets.drafts.Draft;
import group.gnometrading.networking.websockets.enums.HandshakeState;
import group.gnometrading.networking.websockets.exceptions.InvalidHandshakeException;

import java.io.IOException;
import java.util.concurrent.*;

public class HandshakeHandler {

    /**
     * Attempt a handshake as the client to whomever we are connected to over the socket.
     * <p />
     * TODO: Add the ability to request extensions and protocols
     *
     * @param client the connected client
     * @param draft the draft which to encode and decode the handshake
     * @throws InvalidHandshakeException on an unsuccessful handshake
     */
    public static void attemptHandshake(Client client, Draft draft, HandshakeInput input) throws InvalidHandshakeException {
        sendHandshake(client, draft, input);
        HandshakeState result = acceptHandshake(client, draft);

        if (result != HandshakeState.MATCHED) {
            throw new InvalidHandshakeException(result);
        }
    }

    private static void sendHandshake(Client client, Draft draft, HandshakeInput input) {
        try {
            byte[] write = draft.createHandshake(input);
            client.getWriteBuffer().put(write);
            client.write();
        } catch (IOException ignore) {
            throw new InvalidHandshakeException(HandshakeState.INVALID_WRITE);
        }
    }

    private static HandshakeState acceptHandshake(Client client, Draft draft) {
        try {
            while (true) {
                int bytes = client.read();
                if (bytes <= 0) continue;

                HandshakeState result = draft.parseHandshake(client.getReadBuffer());

                if (result != HandshakeState.INCOMPLETE) {
                    return result;
                }
            }
        } catch (IOException e) {
            throw new InvalidHandshakeException(HandshakeState.INVALID_READ);
        }
    }
}

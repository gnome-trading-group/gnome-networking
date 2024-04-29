package group.gnometrading.networking.websockets;

import group.gnometrading.networking.client.Client;
import group.gnometrading.networking.websockets.drafts.Draft;
import group.gnometrading.networking.websockets.enums.HandshakeState;
import group.gnometrading.networking.websockets.exceptions.InvalidHandshakeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

public class HandshakeHandler {

    private static final Logger logger = LoggerFactory.getLogger(HandshakeHandler.class);
    private static final int TIMEOUT_IN_SECONDS = 30; // This should be reasonable for everyone. Right? Guys?

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
        Future<HandshakeState> attempt = CompletableFuture.supplyAsync(() -> {
            logger.trace("Attempting to send handshake to server...");
            sendHandshake(client, draft, input);
            logger.trace("Handshake successfully sent. Waiting for response...");
            return acceptHandshake(client, draft);
        });

        try {
            HandshakeState result = attempt.get(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);

            if (result != HandshakeState.MATCHED) {
                throw new InvalidHandshakeException(result);
            }
        } catch (InterruptedException e) {
            // NO-OP if interrupted; what are we gonna do? :P
        } catch (TimeoutException e) {
            throw new InvalidHandshakeException(HandshakeState.TIMEOUT);
        } catch (ExecutionException e) {
            throw new InvalidHandshakeException(HandshakeState.UNKNOWN);
        }
    }

    private static void sendHandshake(Client client, Draft draft, HandshakeInput input) {
        try {
            byte[] write = draft.createHandshake(input);
            client.write(ByteBuffer.wrap(write));
        } catch (IOException ignore) {
            throw new InvalidHandshakeException(HandshakeState.INVALID_WRITE);
        }
    }

    private static HandshakeState acceptHandshake(Client client, Draft draft) {
        try {
            while (true) {
                ByteBuffer clientBuffer = client.read();
                HandshakeState result = draft.parseHandshake(clientBuffer);

                if (result != HandshakeState.INCOMPLETE) {
                    return result;
                }

                logger.trace("Partial handshake received. Continuing...");
            }
        } catch (IOException e) {
            throw new InvalidHandshakeException(HandshakeState.INVALID_READ);
        }
    }
}

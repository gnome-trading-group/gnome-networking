package group.gnometrading.networking.websockets;

import group.gnometrading.networking.client.Client;
import group.gnometrading.networking.websockets.drafts.Draft;
import group.gnometrading.networking.websockets.enums.HandshakeState;
import group.gnometrading.networking.websockets.exceptions.InvalidHandshakeException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

public class HandshakeHandler {

    private static final int MAX_STATUS_LINE_LENGTH = 120;

    public static final int DEFAULT_TIMEOUT_MS = 10_000;

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
        attemptHandshake(client, draft, input, DEFAULT_TIMEOUT_MS);
    }

    /**
     * @param timeoutMillis how long to wait for the server's response; reads should be bounded by a socket read
     *     timeout no longer than this, or a silent server blocks the first read indefinitely
     */
    public static void attemptHandshake(Client client, Draft draft, HandshakeInput input, int timeoutMillis)
            throws InvalidHandshakeException {
        sendHandshake(client, draft, input);
        HandshakeState result = acceptHandshake(client, draft, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));

        if (result == HandshakeState.INVALID_PROTOCOL || result == HandshakeState.INVALID_HEADERS) {
            // The status line says why, e.g. "HTTP/1.1 451" from a venue refusing the region.
            throw new InvalidHandshakeException(result, statusLine(client.getReadBuffer()));
        }
        if (result != HandshakeState.MATCHED) {
            throw new InvalidHandshakeException(result);
        }
    }

    private static String statusLine(final ByteBuffer response) {
        final StringBuilder line = new StringBuilder();
        for (int i = response.position(); i < response.limit() && line.length() < MAX_STATUS_LINE_LENGTH; i++) {
            final char c = (char) response.get(i);
            if (c == '\r' || c == '\n') {
                break;
            }
            line.append(c);
        }
        return line.toString();
    }

    private static void sendHandshake(Client client, Draft draft, HandshakeInput input) {
        try {
            byte[] write = draft.createHandshake(input);
            client.getWriteBuffer().put(write);
            client.write();
        } catch (IOException e) {
            throw new InvalidHandshakeException(HandshakeState.INVALID_WRITE, e);
        }
    }

    private static HandshakeState acceptHandshake(Client client, Draft draft, long deadlineNanos) {
        try {
            while (true) {
                int bytes = client.read();
                if (bytes < 0) {
                    return HandshakeState.INVALID_READ;
                }

                if (bytes > 0) {
                    HandshakeState result = draft.parseHandshake(client.getReadBuffer());
                    if (result != HandshakeState.INCOMPLETE) {
                        return result;
                    }
                }

                if (System.nanoTime() - deadlineNanos >= 0) {
                    return HandshakeState.TIMEOUT;
                }
            }
        } catch (IOException e) {
            throw new InvalidHandshakeException(HandshakeState.INVALID_READ, e);
        }
    }
}

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
    static final int MAX_BODY_BYTES = 512;
    // How long a rejected upgrade may take to deliver its body; the connect has already failed, so this is all it adds.
    private static final long REJECTION_BODY_WAIT_NANOS = TimeUnit.SECONDS.toNanos(1);

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
            // The status line says why, e.g. "HTTP/1.1 451" from a venue refusing the region, and the body often says
            // more, e.g. which key a 401 rejected.
            readRejectionBody(client, System.nanoTime() + REJECTION_BODY_WAIT_NANOS);
            throw new InvalidHandshakeException(result, rejection(client.getReadBuffer()));
        }
        if (result != HandshakeState.MATCHED) {
            throw new InvalidHandshakeException(result);
        }
    }

    /** The status line, and up to {@link #MAX_BODY_BYTES} of the body with anything unprintable as '.'. */
    static String rejection(final ByteBuffer response) {
        final String status = statusLine(response);
        final int bodyStart = bodyStart(response);
        if (bodyStart < 0 || bodyStart >= response.limit()) {
            return status;
        }
        final StringBuilder body = new StringBuilder(status).append(" body: ");
        final int end = Math.min(response.limit(), bodyStart + MAX_BODY_BYTES);
        for (int i = bodyStart; i < end; i++) {
            final char c = (char) (response.get(i) & 0xFF);
            body.append(c >= ' ' && c < 0x7F ? c : '.');
        }
        return body.toString();
    }

    /** Reads on until the body's first bytes are in, the server closes, or the deadline passes. */
    private static void readRejectionBody(final Client client, final long deadlineNanos) {
        try {
            while (System.nanoTime() - deadlineNanos < 0) {
                final ByteBuffer response = client.getReadBuffer();
                final int bodyStart = bodyStart(response);
                if (bodyStart >= 0) {
                    final int wanted = Math.min(MAX_BODY_BYTES, contentLength(response, bodyStart));
                    if (response.limit() - bodyStart >= wanted) {
                        return;
                    }
                }
                if (client.read() < 0) {
                    return;
                }
            }
        } catch (IOException e) {
            // Whatever was read already is still reported.
        }
    }

    /** Where the body starts, after the blank line ending the headers; -1 before the headers are all in. */
    private static int bodyStart(final ByteBuffer response) {
        for (int i = response.position(); i + 3 < response.limit(); i++) {
            if (response.get(i) == '\r' && response.get(i + 1) == '\n' && response.get(i + 2) == '\r'
                    && response.get(i + 3) == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    /** The Content-Length header's value; {@link #MAX_BODY_BYTES} if it is missing or unreadable. */
    private static int contentLength(final ByteBuffer response, final int bodyStart) {
        final StringBuilder headers = new StringBuilder();
        for (int i = response.position(); i < bodyStart; i++) {
            headers.append(Character.toLowerCase((char) (response.get(i) & 0xFF)));
        }
        final int at = headers.indexOf("\ncontent-length:");
        if (at < 0) {
            return MAX_BODY_BYTES;
        }
        int i = at + "\ncontent-length:".length();
        while (i < headers.length() && headers.charAt(i) == ' ') {
            i++;
        }
        long length = 0;
        boolean any = false;
        while (i < headers.length() && Character.isDigit(headers.charAt(i)) && length <= MAX_BODY_BYTES) {
            length = length * 10 + (headers.charAt(i++) - '0');
            any = true;
        }
        return any ? (int) Math.min(length, MAX_BODY_BYTES) : MAX_BODY_BYTES;
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

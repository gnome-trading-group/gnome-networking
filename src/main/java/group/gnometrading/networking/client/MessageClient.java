package group.gnometrading.networking.client;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface MessageClient extends Client {

    /**
     * Attempt to read an entire message from the socket.
     * @return an int < 0 if the socket is closed, 0 if there's no message, and 1 if there's a message
     * @throws IOException if the socket throws
     */
    default int readMessage() throws IOException {
        return readMessage(getReadBuffer());
    }

    /**
     * Attempt to read an entire message from the socket.
     * @return an int < 0 if the socket is closed, 0 if there's no message, and 1 if there's a message
     * @throws IOException if the socket throws
     */
    int readMessage(ByteBuffer directBuffer) throws IOException;
}

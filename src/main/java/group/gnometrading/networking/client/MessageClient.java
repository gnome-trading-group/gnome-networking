package group.gnometrading.networking.client;

import java.io.IOException;

public interface MessageClient extends Client {

    /**
     * Attempt to read an entire message from the socket.
     * @return an int < 0 if the socket is closed, 0 if there's no message, and 1 if there's a message
     * @throws IOException if the socket throws
     */
    int readMessage() throws IOException;
}

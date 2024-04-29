package group.gnometrading.networking.client;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface MessageClient extends Client {
    ByteBuffer readMessage() throws IOException;

    int writeMessage(ByteBuffer message) throws IOException;
}

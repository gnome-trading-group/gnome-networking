package group.gnometrading.networking.client;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface MessageClient extends Client {
    boolean readMessage() throws IOException;
}

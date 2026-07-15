package group.gnometrading.networking.websockets;

import java.net.URI;
import java.util.Map;

// Lombok would be nice
public class HandshakeInput {
    public URI uri;
    public Map<String, String> headers;
    // TODO: Add protocols, extensions here?

    public HandshakeInput(URI uri) {
        this(uri, null);
    }

    public HandshakeInput(URI uri, Map<String, String> headers) {
        this.uri = uri;
        this.headers = headers;
    }
}

package group.gnometrading.networking.websockets;

import group.gnometrading.networking.client.SocketClient;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.websockets.drafts.Draft;
import group.gnometrading.networking.websockets.drafts.RFC6455;

import java.io.IOException;
import java.net.URI;

public class WebSocketClientBuilder {

    private URI uri = null;
    private Draft draft = new RFC6455();
    private GnomeSocketFactory socketFactory = GnomeSocketFactory.getDefault();
    private int readBufferSize = SocketClient.DEFAULT_READ_BUFFER_SIZE;
    private int writeBufferSize = SocketClient.DEFAULT_WRITE_BUFFER_SIZE;

    public WebSocketClientBuilder withURI(URI uri) {
        this.uri = uri;
        return this;
    }

    public WebSocketClientBuilder withDraft(Draft draft) {
        this.draft = draft;
        return this;
    }

    public WebSocketClientBuilder withSocketFactory(GnomeSocketFactory factory) {
        this.socketFactory = factory;
        return this;
    }

    public WebSocketClientBuilder withReadBufferSize(int readBufferSize) {
        this.readBufferSize = readBufferSize;
        return this;
    }

    public WebSocketClientBuilder withWriteBufferSize(int writeBufferSize) {
        this.writeBufferSize = writeBufferSize;
        return this;
    }

    public WebSocketClient build() throws IOException {
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }

        return new WebSocketClient(uri, draft, socketFactory, readBufferSize, writeBufferSize);
    }

}

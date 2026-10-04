package group.gnometrading.networking.sockets;

import group.gnometrading.networking.BaseSSLSocketServer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

public class SSLSocketServer extends BaseSSLSocketServer {
    public SSLSocketServer(String host, int port, SSLConfig sslConfig) {
        super(host, port, sslConfig);
    }

    @Override
    protected void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        SSLEngine engine = (SSLEngine) key.attachment();

        // Appends to whatever is already buffered: bytes left over from the handshake, or the start of a record
        // whose remainder had not arrived yet. Clearing first would silently drop them.
        if (client.read(peerNetData) < 0) {
            client.close();
            return;
        }
        peerNetData.flip();
        try {
            while (peerNetData.hasRemaining()) {
                peerAppData.clear();
                SSLEngineResult result = engine.unwrap(peerNetData, peerAppData);
                switch (result.getStatus()) {
                    case OK:
                        peerAppData.flip();
                        if (peerAppData.hasRemaining()) {
                            write(client, engine, peerAppData);
                        }
                        break;
                    case BUFFER_OVERFLOW:
                        peerAppData = enlargeApplicationBuffer(engine, peerAppData);
                        break;
                    case BUFFER_UNDERFLOW:
                        return; // the rest of the record has not arrived yet; keep what we have
                    case CLOSED:
                        client.close();
                        return;
                    default:
                        throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
                }
            }
        } finally {
            peerNetData.compact();
        }
    }
}
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

        peerNetData.clear();
        int bytesRead = client.read(peerNetData);
        if (bytesRead > 0) {
            peerNetData.flip();
            while (peerNetData.hasRemaining()) {
                peerAppData.clear();
                SSLEngineResult result = engine.unwrap(peerNetData, peerAppData);
                switch (result.getStatus()) {
                    case OK:
                        peerAppData.flip();
                        write(client, engine, peerAppData);
                        break;
                    case BUFFER_OVERFLOW:
                        peerAppData = enlargeApplicationBuffer(engine, peerAppData);
                        break;
                    case BUFFER_UNDERFLOW:
                        peerNetData = handleBufferUnderflow(engine, peerNetData);
                        peerNetData.compact();
                        bytesRead = client.read(peerNetData);
                        if (bytesRead > 0) {
                            peerNetData.flip();
                            continue;
                        } else if (bytesRead < 0) {
                            client.close();
                            return;
                        }
                        return; // No more data available right now
                    case CLOSED:
                        client.close();
                        return;
                    default:
                        throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
                }
            }
        } else if (bytesRead < 0) {
            client.close();
        }
    }
}
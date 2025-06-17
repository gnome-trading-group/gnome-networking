package group.gnometrading.networking.sockets;

import group.gnometrading.networking.BaseSocketServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class SocketServer extends BaseSocketServer {

    public SocketServer(String host, int port) {
        super(host, port);
    }

    @Override
    protected void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int bytesRead = client.read(buffer);

        if (bytesRead == -1) {
            client.close();
            return;
        }

        buffer.flip();
        client.write(buffer);
    }
}

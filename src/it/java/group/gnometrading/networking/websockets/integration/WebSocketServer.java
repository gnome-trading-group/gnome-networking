package group.gnometrading.networking.websockets.integration;

import group.gnometrading.networking.websockets.enums.Opcode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebSocketServer implements Runnable {
    private final int port;
    private final AtomicBoolean running;
    private ServerSocketChannel serverChannel;
    private Selector selector;
    private static final Pattern SEC_WEBSOCKET_KEY_PATTERN = Pattern.compile("Sec-WebSocket-Key: (.*)");

    public WebSocketServer(int port) {
        this.port = port;
        this.running = new AtomicBoolean(true);
    }

    @Override
    public void run() {
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.socket().bind(new InetSocketAddress(port));
            serverChannel.configureBlocking(false);

            selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            while (running.get()) {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            stop();
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int bytesRead = client.read(buffer);

        if (bytesRead == -1) {
            client.close();
            return;
        }

        buffer.flip();
        String request = new String(buffer.array(), 0, bytesRead, StandardCharsets.UTF_8);

        if (request.contains("Sec-WebSocket-Key:")) {
            handleHandshake(client, request);
        } else {
            handleWebSocketFrame(client, buffer);
        }
    }

    private void handleHandshake(SocketChannel client, String request) throws IOException {
        Matcher matcher = SEC_WEBSOCKET_KEY_PATTERN.matcher(request);
        if (!matcher.find()) {
            client.close();
            return;
        }

        String key = matcher.group(1).trim();
        String acceptKey = generateAcceptKey(key);

        // Send the handshake response
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                         "Upgrade: websocket\r\n" +
                         "Connection: Upgrade\r\n" +
                         "Sec-WebSocket-Accept: " + acceptKey + "\r\n" +
                         "\r\n";

        ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
        client.write(responseBuffer);
    }

    private String generateAcceptKey(String key) {
        String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        String concat = key + magic;
        byte[] sha1;
        try {
            sha1 = java.security.MessageDigest.getInstance("SHA-1")
                .digest(concat.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        return Base64.getEncoder().encodeToString(sha1);
    }

    private void handleWebSocketFrame(SocketChannel client, ByteBuffer buffer) throws IOException {
        byte firstByte = buffer.get();
        Opcode opcode = Opcode.fromByte(firstByte);
        
        if (opcode == Opcode.PING) {
            ByteBuffer pongBuffer = ByteBuffer.allocate(2);
            pongBuffer.put((byte) (0b10000000 | Opcode.PONG.getCode()));
            pongBuffer.put((byte) 0);
            pongBuffer.flip();
            client.write(pongBuffer);
        } else {
            buffer.rewind();
            client.write(buffer);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        // Handle write operations if needed
    }

    public void stop() {
        running.set(false);
        try {
            if (selector != null) {
                selector.close();
            }
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
} 
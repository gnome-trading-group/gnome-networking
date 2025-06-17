package group.gnometrading.networking.websockets;

import group.gnometrading.networking.BaseSocketServer;
import group.gnometrading.networking.websockets.enums.Opcode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebSocketServer extends BaseSocketServer {
    private static final Pattern SEC_WEBSOCKET_KEY_PATTERN = Pattern.compile("Sec-WebSocket-Key: (.*)");

    public WebSocketServer(String host, int port) {
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
        boolean fin = (firstByte & 0x80) != 0;
        Opcode opcode = Opcode.fromByte(firstByte);

        byte secondByte = buffer.get();
        boolean masked = (secondByte & 0x80) != 0;
        int payloadLength = secondByte & 0x7F;

        if (payloadLength == 126) {
            payloadLength = (buffer.get() & 0xFF) << 8 | (buffer.get() & 0xFF);
        } else if (payloadLength == 127) {
            payloadLength = (int) (buffer.getLong() & 0x7FFFFFFFFFFFFFFFL);
        }

        byte[] maskingKey = new byte[4];
        if (masked) {
            buffer.get(maskingKey);
        }

        byte[] payload = new byte[payloadLength];
        buffer.get(payload);

        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ maskingKey[i % 4]);
            }
        }

        switch (opcode) {
            case TEXT:
                String message = new String(payload);
                if (message.equals("disconnect")) {
                    client.close();
                    break;
                }
            case BINARY:
                byte[] responseMaskingKey = new byte[4];
                new java.util.Random().nextBytes(responseMaskingKey);

                ByteBuffer responseBuffer = ByteBuffer.allocate(2 + payloadLength + (masked ? 4 : 0));
                responseBuffer.put((byte) (0x80 | opcode.getCode()));
                
                if (payloadLength <= 125) {
                    responseBuffer.put((byte) (0x80 | payloadLength));
                } else if (payloadLength <= 65535) {
                    responseBuffer.put((byte) (0x80 | 126));
                    responseBuffer.putShort((short) payloadLength);
                } else {
                    responseBuffer.put((byte) (0x80 | 127));
                    responseBuffer.putLong(payloadLength);
                }

                responseBuffer.put(responseMaskingKey);

                for (int i = 0; i < payload.length; i++) {
                    responseBuffer.put((byte) (payload[i] ^ responseMaskingKey[i % 4]));
                }

                responseBuffer.flip();
                client.write(responseBuffer);
                break;

            case PING:
                ByteBuffer pongBuffer = ByteBuffer.allocate(2 + payloadLength + (masked ? 4 : 0));
                pongBuffer.put((byte) (0x80 | Opcode.PONG.getCode()));
                pongBuffer.put((byte) payloadLength);
                if (masked) {
                    pongBuffer.put(maskingKey);
                }
                pongBuffer.put(payload);
                pongBuffer.flip();
                client.write(pongBuffer);
                break;

            case CLOSING:
                ByteBuffer closeBuffer = ByteBuffer.allocate(2 + payloadLength + (masked ? 4 : 0));
                closeBuffer.put((byte) (0x80 | Opcode.CLOSING.getCode()));
                closeBuffer.put((byte) payloadLength);
                if (masked) {
                    closeBuffer.put(maskingKey);
                }
                closeBuffer.put(payload);
                closeBuffer.flip();
                client.write(closeBuffer);
                client.close();
                break;

            default:
                buffer.rewind();
                client.write(buffer);
                break;
        }
    }
} 
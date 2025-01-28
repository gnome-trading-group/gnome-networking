package group.gnometrading.networking;

import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.sockets.factory.NetSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.networking.websockets.enums.Opcode;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class Main {
    public static void main(String[] args) throws IOException {
        System.out.println("hello");

        WebSocketClient client = new WebSocketClientBuilder()
                .withURI(URI.create("wss://api.hyperliquid.xyz/ws"))
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(1 << 14) // 16 kb
                .build();
        client.connect();
        client.configureBlocking(false);

        String message = "{ \"method\": \"subscribe\", \"subscription\": { \"type\": \"trades\", \"coin\": \"BTC\" } }";
        client.send(Opcode.TEXT, ByteBuffer.wrap(message.getBytes()));

        var result = client.read();
        while (!result.isClosed()) {
            if (result.isSuccess()) {
                System.out.println(StandardCharsets.UTF_8.decode(result.getBody()));
            }
            result = client.read();
        }
    }
}

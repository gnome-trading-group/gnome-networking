package group.gnometrading.networking.websockets;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import java.net.URI;
import org.junit.jupiter.api.Test;

class WebSocketClientBuilderTest {

    @Test
    void secureSchemeDefaultsToTls() {
        WebSocketClientBuilder builder = new WebSocketClientBuilder().withURI(URI.create("wss://example.com/ws"));
        assertInstanceOf(NativeSSLSocketFactory.class, builder.socketFactory());
    }

    @Test
    void plainSchemeDefaultsToPlainSockets() {
        WebSocketClientBuilder builder = new WebSocketClientBuilder().withURI(URI.create("ws://example.com/ws"));
        assertInstanceOf(NativeSocketFactory.class, builder.socketFactory());
    }

    @Test
    void explicitFactoryWins() {
        NativeSocketFactory factory = new NativeSocketFactory();
        WebSocketClientBuilder builder = new WebSocketClientBuilder()
                .withURI(URI.create("wss://example.com/ws"))
                .withSocketFactory(factory);
        assertSame(factory, builder.socketFactory());
    }

    @Test
    void keepAliveTimingsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new WebSocketClientBuilder().withKeepAlive(0, 5, 3));
    }
}

package group.gnometrading.networking.websockets;

import group.gnometrading.networking.websockets.drafts.RFC6455;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public abstract class WebSocketIntegrationTest {
    protected static final String TEST_HOST = "127.0.0.1";
    protected static final String TEST_PATH = "/ws";

    protected WebSocketServer server;
    protected WebSocketClient client;
    protected ExecutorService serverExecutor;

    @BeforeEach
    void setUp() throws Exception {
        server = new WebSocketServer(TEST_HOST, 0);
        serverExecutor = Executors.newSingleThreadExecutor();
        serverExecutor.submit(server);
        server.awaitReady(5, TimeUnit.SECONDS);

        client = new WebSocketClientBuilder()
            .withURI(new URI("ws://" + TEST_HOST + ":" + server.getPort() + TEST_PATH))
            .withDraft(new RFC6455())
            .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdown();
            serverExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
} 
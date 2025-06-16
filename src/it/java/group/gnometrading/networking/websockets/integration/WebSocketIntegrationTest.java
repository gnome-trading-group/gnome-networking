package group.gnometrading.networking.websockets.integration;

import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class WebSocketIntegrationTest {
    protected static final int TEST_PORT = 8080;
    protected static final String TEST_HOST = "localhost";
    protected static final String TEST_PATH = "/ws";
    
    protected WebSocketServer server;
    protected WebSocketClient client;
    protected ExecutorService serverExecutor;
    
    @BeforeEach
    void setUp() throws IOException, URISyntaxException {
        server = new WebSocketServer(TEST_PORT);
        serverExecutor = Executors.newSingleThreadExecutor();
        serverExecutor.submit(server);

        client = new WebSocketClientBuilder()
            .withURI(new URI("ws://" + TEST_HOST + ":" + TEST_PORT + TEST_PATH))
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
        }
    }
} 
package group.gnometrading.networking.sockets;

import group.gnometrading.networking.BaseSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public abstract class SocketIntegrationTest {
    protected static final String TEST_HOST = "127.0.0.1";

    protected BaseSocketServer server;
    protected GnomeSocket clientSocket;
    protected ScheduledExecutorService serverExecutor;

    protected abstract GnomeSocket createClient(int port) throws IOException;
    protected abstract BaseSocketServer createServer();

    @BeforeEach
    void setUp() throws InterruptedException {
        server = createServer();
        serverExecutor = Executors.newSingleThreadScheduledExecutor();
        serverExecutor.schedule(server, 0, TimeUnit.MILLISECONDS);
        server.awaitReady(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdown();
            serverExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (clientSocket != null && clientSocket.isConnected()) {
            clientSocket.close();
        }
    }

} 
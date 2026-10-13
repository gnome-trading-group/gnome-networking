package group.gnometrading.networking.http;

import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class KeepWarmIT {

    private static final long LONG_AGO = TimeUnit.SECONDS.toNanos(30);

    @Test
    void connectionIdleTooLongIsReplacedAndTheNextRequestUsesTheNewOne() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            assertTrue(client.get(HTTPProtocol.HTTP, "localhost", "/").isSuccess());
            Thread.sleep(30);

            client.keepWarm(HTTPProtocol.HTTP, "localhost", TimeUnit.MILLISECONDS.toNanos(10));
            awaitAccepted(server, 2);
            assertTrue(client.get(HTTPProtocol.HTTP, "localhost", "/").isSuccess());

            assertEquals(2, server.acceptedConnections(), "the request went on the replacement");
        }
    }

    @Test
    void recentlyUsedConnectionIsKept() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            assertTrue(client.get(HTTPProtocol.HTTP, "localhost", "/").isSuccess());

            client.keepWarm(HTTPProtocol.HTTP, "localhost", LONG_AGO);
            assertTrue(client.get(HTTPProtocol.HTTP, "localhost", "/").isSuccess());

            assertEquals(1, server.acceptedConnections());
        }
    }

    @Test
    void replacementCountsAsUse() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            assertTrue(client.get(HTTPProtocol.HTTP, "localhost", "/").isSuccess());
            Thread.sleep(30);

            client.keepWarm(HTTPProtocol.HTTP, "localhost", TimeUnit.MILLISECONDS.toNanos(10));
            client.keepWarm(HTTPProtocol.HTTP, "localhost", TimeUnit.MILLISECONDS.toNanos(10));
            awaitAccepted(server, 2);

            assertEquals(2, server.acceptedConnections(), "a fresh replacement isn't replaced again at once");
        }
    }

    @Test
    void connectionTheServerClosedIsReplacedHoweverRecent() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer(true)) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            assertTrue(client.get(HTTPProtocol.HTTP, "localhost", "/").isSuccess());
            Thread.sleep(50); // the server's close reaches the client

            client.keepWarm(HTTPProtocol.HTTP, "localhost", LONG_AGO);

            awaitAccepted(server, 2);
            assertEquals(2, server.acceptedConnections());
        }
    }

    @Test
    void missingConnectionsAreOpened() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client =
                    HTTPClient.builder().withPort(server.getPort()).withConnectionsPerHost(3).build();

            client.keepWarm(HTTPProtocol.HTTP, "localhost", LONG_AGO);

            awaitAccepted(server, 3);
            assertEquals(3, server.acceptedConnections());
        }
    }

    @Test
    void requestInFlightIsLeftAlone() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client = new HTTPClient(true, server.getPort());
            final GnomeString path = new ViewString("/");
            final int connection = client.send(HTTPProtocol.HTTP, "localhost", HTTPMethod.GET, path, null, 0,
                    null, null, null, null, null, null, null, null, null, null);

            client.keepWarm(HTTPProtocol.HTTP, "localhost", 0);

            HTTPResponse response;
            do {
                response = client.poll("localhost", connection);
            } while (response == null);
            assertTrue(response.isSuccess());
            assertEquals(1, server.acceptedConnections());
        }
    }

    @Test
    void keepingHealthyConnectionsWarmDoesNotAllocate() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPClient client =
                    HTTPClient.builder().withPort(server.getPort()).withConnectionsPerHost(2).build();
            client.connect(HTTPProtocol.HTTP, "localhost");
            final com.sun.management.ThreadMXBean threads =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            final long threadId = Thread.currentThread().getId();

            for (int i = 0; i < 2_000; i++) {
                client.keepWarm(HTTPProtocol.HTTP, "localhost", LONG_AGO);
            }
            final long before = threads.getThreadAllocatedBytes(threadId);
            for (int i = 0; i < 2_000; i++) {
                client.keepWarm(HTTPProtocol.HTTP, "localhost", LONG_AGO);
            }
            final long allocated = threads.getThreadAllocatedBytes(threadId) - before;

            assertEquals(2, server.acceptedConnections());
            // Measuring can itself allocate a little; anything per call would be far more.
            assertTrue(allocated < 1024, "allocated " + allocated + " bytes over 2,000 calls");
        }
    }

    private static void awaitAccepted(final LocalTestHttpServer server, final int count) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.acceptedConnections() < count && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
    }
}

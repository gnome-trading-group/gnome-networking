package group.gnometrading.networking.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class AsyncHTTPConnectionsIT {

    private static final String HOST = "localhost";
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final List<Exception> openFailures = new CopyOnWriteArrayList<>();
    private ConnectorThread connector;

    @AfterEach
    void tearDown() throws Exception {
        if (this.connector != null) {
            this.connector.stop();
        }
        for (int i = this.closeables.size() - 1; i >= 0; i--) {
            this.closeables.get(i).close();
        }
    }

    @Test
    void requestsOnSeveralConnectionsAreInFlightAtOnceAndEachCompletesWithItsOwnAnswer() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 4, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 4);

        // Far apart, so the answers' order doesn't hang on how fast a loaded machine schedules the server's threads.
        final int[] delays = {1_200, 800, 400, 0};
        final long start = System.nanoTime();
        for (int i = 0; i < delays.length; i++) {
            final int connection = connections.acquire();
            connections.request(connection, HTTPMethod.GET).path(ascii("/delay/" + delays[i])).noBody();
            assertEquals(AsyncHTTPConnections.SENT, connections.send(connection, 100 + i, System.nanoTime() + 10 * SECOND));
        }
        assertEquals(AsyncHTTPConnections.NONE, connections.acquire(), "every connection carries a request");
        assertEquals(4, connections.inFlight());

        final List<Long> tags = new ArrayList<>();
        while (tags.size() < 4) {
            if (connections.poll()) {
                final HTTPCompletion completion = connections.completion();
                assertEquals(HTTPCompletion.RESPONSE, completion.outcome());
                assertEquals(200, completion.response().getStatusCode());
                assertEquals("/delay/" + delays[(int) completion.tag() - 100], body(completion.response()));
                tags.add(completion.tag());
            }
        }
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(103L, tags.get(0), "the request answered at once completes first");
        assertTrue(elapsedMillis < 2_200, "in flight together, not one after another (2,400 ms): " + elapsedMillis + " ms");
        assertEquals(0, connections.inFlight());
        assertTrue(connections.acquire() >= 0, "connections are idle again");
        assertEquals(4, server.accepted(), "nothing was reconnected");
    }

    @Test
    void theRequestArrivesExactlyAsWritten() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 1, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 1);

        final int connection = connections.acquire();
        final HTTPRequestWriter request = connections.request(connection, HTTPMethod.POST).path(ascii("/echo"));
        for (int i = 0; i < 7; i++) {
            request.header(ascii("X-H" + i), ascii("v" + i));
        }
        final byte[] body = ascii("{\"order\":42}");
        request.body(body, 0, body.length);
        connections.send(connection, 1, System.nanoTime() + 5 * SECOND);

        final String echoed = body(awaitCompletion(connections).response());
        assertTrue(echoed.startsWith("POST /echo HTTP/1.1\r\nHost: localhost\r\n"), echoed);
        for (int i = 0; i < 7; i++) {
            assertTrue(echoed.contains("\r\nX-H" + i + ": v" + i + "\r\n"), echoed);
        }
        assertTrue(echoed.endsWith("Content-Length: 12\r\n\r\n{\"order\":42}"), echoed);
    }

    @Test
    void aResponseThatMissesItsDeadlineTimesOutAndTheConnectionIsReplaced() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 1, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 1);

        final int connection = connections.acquire();
        connections.request(connection, HTTPMethod.GET).path(ascii("/delay/2000")).noBody();
        connections.send(connection, 7, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));

        final HTTPCompletion completion = awaitCompletion(connections);
        assertEquals(HTTPCompletion.TIMED_OUT, completion.outcome());
        assertEquals(7, completion.tag());
        assertEquals(-1, completion.response().getStatusCode());

        awaitReady(connections, 1);
        assertEquals(2, server.accepted(), "the timed-out connection was replaced, so its late answer is never read");
        assertEquals("/delay/0", roundTrip(connections, "/delay/0"));
    }

    @Test
    void aConnectionDroppedMidRequestFails() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 1, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 1);

        final int connection = connections.acquire();
        connections.request(connection, HTTPMethod.GET).path(ascii("/drop")).noBody();
        connections.send(connection, 9, System.nanoTime() + 5 * SECOND);

        final HTTPCompletion completion = awaitCompletion(connections);
        assertEquals(HTTPCompletion.FAILED, completion.outcome());
        assertEquals(9, completion.tag());
        awaitReady(connections, 1);
        assertEquals("/delay/0", roundTrip(connections, "/delay/0"));
    }

    @Test
    void aConnectionTheServerClosesAfterAnsweringIsReplacedBeforeTheNextRequest() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 1, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 1);

        assertEquals("/close", roundTrip(connections, "/close"));
        awaitReady(connections, 1);
        assertEquals("/delay/0", roundTrip(connections, "/delay/0"));
        assertEquals(2, server.accepted());
    }

    @Test
    void aConnectionIdleTooLongIsHandedBackInsteadOfUsed() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 1, TimeUnit.MILLISECONDS.toNanos(50));
        awaitReady(connections, 1);
        Thread.sleep(100);

        assertEquals(AsyncHTTPConnections.NONE, connections.acquire(), "a stale connection is never written to");
        awaitAccepted(server, 2);
        awaitReady(connections, 1);
        assertEquals("/delay/0", roundTrip(connections, "/delay/0"));
    }

    @Test
    void anIdleConnectionRetiredAheadOfTimeIsReplacedBeforeTheNextRequestNeedsIt() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 1, TimeUnit.MILLISECONDS.toNanos(200));
        awaitReady(connections, 1);
        Thread.sleep(150);

        connections.retireIdle(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(100));
        awaitAccepted(server, 2);
        awaitReady(connections, 1);

        assertNotEquals(AsyncHTTPConnections.NONE, connections.acquire(), "a fresh connection waits for the request");
    }

    @Test
    void idleConnectionsAreRetiredOneAtATimeOldestFirstSoTheRestStayReady() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 3, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 3);
        // Connection 1 used last, so 0 and 2 are the older ones.
        Thread.sleep(20);
        connections.request(1, HTTPMethod.GET).path(ascii("/delay/0")).noBody();
        assertEquals(AsyncHTTPConnections.SENT, connections.send(1, 1, System.nanoTime() + 5 * SECOND));
        while (!connections.poll()) {
            Thread.onSpinWait();
        }
        this.connector.stop();
        this.connector = null;

        connections.retireIdle(System.nanoTime(), 0);

        final List<Integer> retired = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            if (connections.state(i) != AsyncHTTPConnections.READY) {
                retired.add(i);
            }
        }
        assertEquals(1, retired.size(), "one at a time");
        assertNotEquals(1, retired.get(0), "the one used most recently stays");
    }

    @Test
    void aConnectionTheServerClosedWhileIdleIsRetiredAheadOfTime() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 1, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 1);
        assertEquals("/close", roundTrip(connections, "/close"));
        // The server's close reaches this end before anything looks.
        Thread.sleep(100);

        connections.retireIdle(System.nanoTime(), TimeUnit.MINUTES.toNanos(1));
        awaitAccepted(server, 2);
        awaitReady(connections, 1);

        assertEquals("/delay/0", roundTrip(connections, "/delay/0"));
    }

    @Test
    void retiringNothingStaleLeavesEveryConnectionAsItIs() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 2, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 2);

        connections.retireIdle(System.nanoTime(), TimeUnit.SECONDS.toNanos(30));

        assertEquals(AsyncHTTPConnections.READY, connections.state(0));
        assertEquals(AsyncHTTPConnections.READY, connections.state(1));
        assertEquals(2, server.accepted());
    }

    @Test
    void aHostThatRefusesConnectionsIsRetriedWithBackoffAndReported() throws Exception {
        final int deadPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = socket.getLocalPort();
        }
        final AsyncHTTPConnections connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, HOST)
                .withPort(deadPort)
                .withConnectTimeout(500)
                .build();
        this.closeables.add(connections);
        startConnector(connections);

        final long deadline = System.nanoTime() + 5 * SECOND;
        while (this.openFailures.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(this.openFailures.size() >= 2, "failures are reported and retried");
        assertEquals(AsyncHTTPConnections.NONE, connections.acquire());
    }

    @Test
    void sendingAndPollingDoesNotAllocate() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 4, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 4);
        final byte[] path = ascii("/delay/0");
        final byte[] header = ascii("X-Signature");
        final byte[] value = ascii("abcdefghijklmnopqrstuvwxyz");
        final byte[] body = ascii("{\"order\":42}");

        final Runnable requests = () -> {
            int sent = 0;
            int done = 0;
            while (done < 1_000) {
                if (sent < 1_000) {
                    final int connection = connections.acquire();
                    if (connection >= 0) {
                        connections.request(connection, HTTPMethod.POST).path(path).header(header, value)
                                .header(header, sent).body(body, 0, body.length);
                        if (connections.send(connection, sent, System.nanoTime() + 5 * SECOND)
                                == AsyncHTTPConnections.SENT) {
                            sent++;
                        }
                    }
                }
                if (connections.poll()) {
                    assertEquals(HTTPCompletion.RESPONSE, connections.completion().outcome());
                    done++;
                }
                connections.retireIdle(System.nanoTime(), TimeUnit.SECONDS.toNanos(30));
            }
        };
        requests.run();

        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long before = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
        requests.run();
        final long allocated = threads.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
        assertTrue(allocated < 2_048, "allocated " + allocated + " bytes over 1,000 requests");
        assertEquals(4, server.accepted());
    }

    @Test
    void aWriteOntoAConnectionTheServerResetIsReportedAndTheConnectionReplaced() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 1, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 1);

        final int connection = connections.acquire();
        server.resetAll();
        Thread.sleep(50);
        connections.request(connection, HTTPMethod.GET).path(ascii("/ok/1")).noBody();
        final int result = connections.send(connection, 1, System.nanoTime() + 5 * SECOND);
        if (result == AsyncHTTPConnections.SENT) {
            // The reset can reach the socket only after the write: then the poll reports it.
            assertEquals(HTTPCompletion.FAILED, awaitCompletion(connections).outcome());
        } else {
            assertEquals(AsyncHTTPConnections.SEND_FAILED, result);
            assertEquals(0, connections.inFlight());
        }
        awaitReady(connections, 1);
        assertEquals("/ok/2", roundTrip(connections, "/ok/2"));
    }

    @Test
    void connectionsOpenOnceARefusingHostComesBack() throws Exception {
        final int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        final AsyncHTTPConnections connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, HOST)
                .withPort(port)
                .withConnections(2)
                .withConnectTimeout(500)
                .build();
        this.closeables.add(connections);
        startConnector(connections);
        final long deadline = System.nanoTime() + 5 * SECOND;
        while (this.openFailures.size() < 4 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(this.openFailures.size() >= 4, "both connections failed and were retried");

        final ChaosServer server = new ChaosServer(null, port);
        this.closeables.add(server);
        awaitReady(connections, 2);
        assertEquals("/ok/back", roundTrip(connections, "/ok/back"));
    }

    @Test
    void oneConnectorServesSeveralHosts() throws Exception {
        final ChaosServer first = server();
        final ChaosServer second = server();
        final AsyncHTTPConnections a = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, HOST)
                .withPort(first.port()).withConnections(2).build();
        final AsyncHTTPConnections b = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, HOST)
                .withPort(second.port()).withConnections(3).build();
        this.closeables.add(a);
        this.closeables.add(b);
        this.connector = new ConnectorThread(new HTTPConnector(List.of(a, b), HTTPConnector.Listener.NONE));

        awaitReady(a, 2);
        awaitReady(b, 3);
        assertEquals("/ok/a", roundTrip(a, "/ok/a"));
        assertEquals("/ok/b", roundTrip(b, "/ok/b"));
        assertEquals(2, first.accepted());
        assertEquals(3, second.accepted());
    }

    @Test
    void anAnswerCutOffOrStalledPartWayIsNeverDeliveredAsAnAnswer() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 2, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 2);

        int connection = connections.acquire();
        connections.request(connection, HTTPMethod.GET).path(ascii("/half/1")).noBody();
        connections.send(connection, 1, System.nanoTime() + 5 * SECOND);
        assertEquals(HTTPCompletion.FAILED, awaitCompletion(connections).outcome());

        connection = connections.acquire();
        connections.request(connection, HTTPMethod.GET).path(ascii("/stall/1000/2")).noBody();
        connections.send(connection, 2, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200));
        final HTTPCompletion stalled = awaitCompletion(connections);
        assertEquals(HTTPCompletion.TIMED_OUT, stalled.outcome());
        assertEquals(2, stalled.tag());
    }

    @Test
    void anAnswerNobodyAskedForRetiresItsConnection() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 1, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 1);

        assertEquals("/extra/1", roundTrip(connections, "/extra/1"));
        awaitReady(connections, 1);
        assertEquals("/ok/2", roundTrip(connections, "/ok/2"), "the unsolicited answer is never read as this one's");
        assertEquals(2, server.accepted());
    }

    @Test
    void aLargeChunkedAnswerArrivesWhole() throws Exception {
        final ChaosServer server = server();
        final AsyncHTTPConnections connections = connections(server, 1, TimeUnit.MINUTES.toNanos(1));
        awaitReady(connections, 1);

        assertEquals(ChaosServer.pad("/chunked/25000/1", 25_000), roundTrip(connections, "/chunked/25000/1"));
        assertEquals("/ok/2", roundTrip(connections, "/ok/2"));
        assertEquals(1, server.accepted());
    }

    private String roundTrip(final AsyncHTTPConnections connections, final String path) throws Exception {
        final int connection = connections.acquire();
        assertTrue(connection >= 0);
        connections.request(connection, HTTPMethod.GET).path(ascii(path)).noBody();
        assertEquals(AsyncHTTPConnections.SENT, connections.send(connection, 0, System.nanoTime() + 5 * SECOND));
        final HTTPCompletion completion = awaitCompletion(connections);
        assertEquals(HTTPCompletion.RESPONSE, completion.outcome());
        return body(completion.response());
    }

    private static HTTPCompletion awaitCompletion(final AsyncHTTPConnections connections) {
        final long deadline = System.nanoTime() + 10 * SECOND;
        while (System.nanoTime() < deadline) {
            if (connections.poll()) {
                return connections.completion();
            }
        }
        throw new AssertionError("no completion");
    }

    private static void awaitReady(final AsyncHTTPConnections connections, final int count) throws InterruptedException {
        final long deadline = System.nanoTime() + 10 * SECOND;
        while (System.nanoTime() < deadline) {
            int ready = 0;
            for (int i = 0; i < connections.size(); i++) {
                if (connections.state(i) == AsyncHTTPConnections.READY) {
                    ready++;
                }
            }
            if (ready >= count) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("connections never opened");
    }

    private static void awaitAccepted(final ChaosServer server, final int count) throws InterruptedException {
        final long deadline = System.nanoTime() + 10 * SECOND;
        while (server.accepted() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(count, server.accepted());
    }

    private ChaosServer server() throws IOException {
        final ChaosServer server = new ChaosServer();
        this.closeables.add(server);
        return server;
    }

    private AsyncHTTPConnections connections(final ChaosServer server, final int count, final long maxIdleNanos)
            throws IOException {
        final AsyncHTTPConnections connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, HOST)
                .withPort(server.port())
                .withConnections(count)
                .withMaxIdle(maxIdleNanos)
                .build();
        this.closeables.add(connections);
        startConnector(connections);
        return connections;
    }

    private void startConnector(final AsyncHTTPConnections connections) {
        final HTTPConnector agent = new HTTPConnector(
                List.of(connections),
                new HTTPConnector.Listener() {
                    @Override
                    public void onOpenFailed(final String host, final int connection, final Exception cause) {
                        AsyncHTTPConnectionsIT.this.openFailures.add(cause);
                    }
                },
                System::nanoTime,
                TimeUnit.MILLISECONDS.toNanos(20),
                TimeUnit.MILLISECONDS.toNanos(200));
        this.connector = new ConnectorThread(agent);
    }

    private static String body(final HTTPResponse response) {
        final ByteBuffer body = response.getBody().duplicate();
        final byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static final class ConnectorThread {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;

        ConnectorThread(final HTTPConnector connector) {
            this.thread = new Thread(() -> {
                while (this.running.get()) {
                    if (connector.doWork() == 0) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }, "test-http-connector");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        void stop() throws InterruptedException {
            this.running.set(false);
            this.thread.join(5_000);
        }
    }
}

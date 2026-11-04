package group.gnometrading.networking.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import group.gnometrading.networking.sockets.SSLConfig;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The sending thread and the connector racing against a server that answers late, drops, half-answers, stalls,
 * closes after answering, sends answers nobody asked for, chunks large bodies, and closes idle connections at random.
 * Whatever happens, every request sent completes exactly once, an answer is never handed to the wrong request, a
 * request the server never answered is never reported answered, and no call on the sending thread blocks.
 */
@Timeout(120)
class AsyncHTTPConnectionsStressIT {

    private static final long RUN_NANOS = TimeUnit.MILLISECONDS.toNanos(Long.getLong("stress.millis", 5_000));
    private static final int CONNECTIONS = 8;
    private static final int MAX_REQUESTS = 1_000_000;
    // Generous for a loaded CI machine; a call that blocked on the network would take a read or connect timeout.
    private static final long MAX_CALL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long ANSWERED_DEADLINE = TimeUnit.SECONDS.toNanos(3);
    private static final long STALL_DEADLINE = TimeUnit.MILLISECONDS.toNanos(150);

    private static final int OK = 0;
    private static final int DELAY = 1;
    private static final int CLOSE = 2;
    private static final int EXTRA = 3;
    private static final int CHUNKED = 4;
    private static final int DROP = 5;
    private static final int HALF = 6;
    private static final int STALL = 7;
    private static final String[] PATHS = {"/ok", "/delay/%d", "/close", "/extra", "/chunked/20000", "/drop", "/half", "/stall/400"};
    // Mostly answered requests, as in real traffic; failures often enough to race the connector constantly.
    private static final int[] WEIGHTS = {40, 20, 8, 6, 6, 8, 8, 4};

    @Test
    void plainHttp() throws Exception {
        try (ChaosServer server = new ChaosServer()) {
            run(server, AsyncHTTPConnections.builder(HTTPProtocol.HTTP, "localhost").withPort(server.port()));
        }
    }

    @Test
    void tls() throws Exception {
        final SSLConfig ssl = new SSLConfig();
        final String caFile = ssl.writeCaFile();
        final SSLContext context = ssl.getSSLContext();
        try (ChaosServer server = new ChaosServer(context, 0)) {
            run(server, AsyncHTTPConnections.builder(HTTPProtocol.HTTPS, "localhost")
                    .withPort(server.port())
                    .withSocketFactory(new NativeSSLSocketFactory(caFile)));
        }
    }

    private static void run(final ChaosServer server, final AsyncHTTPConnections.Builder builder) throws Exception {
        final AsyncHTTPConnections connections = builder
                .withConnections(CONNECTIONS)
                .withConnectTimeout(2_000)
                .withMaxIdle(TimeUnit.MILLISECONDS.toNanos(30))
                .build();
        final AtomicReference<Throwable> connectorFailure = new AtomicReference<>();
        final AtomicBoolean running = new AtomicBoolean(true);
        final HTTPConnector connector = new HTTPConnector(
                List.of(connections), HTTPConnector.Listener.NONE, System::nanoTime, 1_000_000, 20_000_000);
        final Thread connectorThread = loop("stress-connector", running, connectorFailure, () -> {
            if (connector.doWork() == 0) {
                Thread.sleep(0, 200_000);
            }
        });
        final Random killerRandom = new Random(1);
        final Thread killer = loop("stress-killer", running, connectorFailure, () -> {
            server.closeAnIdleConnection();
            Thread.sleep(2 + killerRandom.nextInt(10));
        });

        final int[] modes = new int[MAX_REQUESTS];
        final byte[] completions = new byte[MAX_REQUESTS];
        final boolean[] sent = new boolean[MAX_REQUESTS];
        final long[] outcomes = new long[3];
        final Random random = new Random(42);
        long maxCallNanos = 0;
        String slowest = "";
        int slowPolls = 0;
        int slowPollsDuringGc = 0;
        final long runStart = System.nanoTime();
        int next = 0;
        int sendFailures = 0;

        try {
            final long end = System.nanoTime() + RUN_NANOS;
            while (System.nanoTime() < end || connections.inFlight() > 0) {
                if (System.nanoTime() < end && connections.inFlight() < CONNECTIONS && next < MAX_REQUESTS) {
                    long start = System.nanoTime();
                    final int connection = connections.acquire();
                    if (System.nanoTime() - start > maxCallNanos) {
                        maxCallNanos = System.nanoTime() - start;
                        slowest = "acquire at " + (start - runStart) / 1_000_000 + " ms";
                    }
                    if (connection >= 0) {
                        final int tag = next++;
                        final int mode = pick(random);
                        modes[tag] = mode;
                        final String path = String.format(PATHS[mode], random.nextInt(5)) + "/" + tag;
                        connections.request(connection, HTTPMethod.POST)
                                .path(ascii(path))
                                .header(ascii("X-Tag"), tag)
                                .body(ascii("{\"tag\":" + tag + "}"), 0, String.valueOf(tag).length() + 8);
                        final long deadline = System.nanoTime() + (mode == STALL ? STALL_DEADLINE : ANSWERED_DEADLINE);
                        start = System.nanoTime();
                        final int result = connections.send(connection, tag, deadline);
                        if (System.nanoTime() - start > maxCallNanos) {
                            maxCallNanos = System.nanoTime() - start;
                            slowest = "send at " + (start - runStart) / 1_000_000 + " ms";
                        }
                        if (result == AsyncHTTPConnections.SENT) {
                            sent[tag] = true;
                        } else {
                            sendFailures++;
                        }
                    }
                }

                final long gcBefore = gcCount();
                final long start = System.nanoTime();
                final boolean completed = connections.poll();
                final long took = System.nanoTime() - start;
                if (took > TimeUnit.MILLISECONDS.toNanos(1)) {
                    slowPolls++;
                    if (gcCount() != gcBefore) {
                        slowPollsDuringGc++;
                    }
                }
                if (took > maxCallNanos) {
                    maxCallNanos = took;
                    slowest = "poll at " + (start - runStart) / 1_000_000 + " ms";
                }
                if (completed) {
                    check(connections.completion(), modes, sent, completions);
                    outcomes[connections.completion().outcome()]++;
                }
            }
        } finally {
            running.set(false);
            killer.join(5_000);
        }

        try {
            for (int tag = 0; tag < next; tag++) {
                assertEquals(sent[tag] ? 1 : 0, completions[tag], "request " + tag + " completed the wrong number of times");
            }
            assertEquals(0, connections.inFlight());
            assertTrue(outcomes[HTTPCompletion.RESPONSE] > 1_000, "too few answers to mean anything: " + outcomes[0]);
            assertTrue(outcomes[HTTPCompletion.FAILED] > 50, "failures weren't exercised: " + outcomes[1]);
            assertTrue(outcomes[HTTPCompletion.TIMED_OUT] > 5, "timeouts weren't exercised: " + outcomes[2]);
            assertTrue(maxCallNanos < MAX_CALL_NANOS, "a call on the sending thread took " + maxCallNanos / 1_000 + " µs");
            assertEquals(null, connectorFailure.get());

            // Once the chaos stops, the connections recover.
            assertEquals("/ok/final", roundTrip(connections, "/ok/final"));
            System.out.printf("%d requests: %d answered, %d failed, %d timed out, %d not sent; %d connections; "
                            + "slowest call %d µs (%s); polls over 1 ms: %d, during a GC: %d%n",
                    next, outcomes[0], outcomes[1], outcomes[2], sendFailures, server.accepted(), maxCallNanos / 1_000, slowest, slowPolls, slowPollsDuringGc);
        } finally {
            connectorThread.interrupt();
            connectorThread.join(5_000);
            connections.close();
        }
    }

    private static void check(
            final HTTPCompletion completion, final int[] modes, final boolean[] sent, final byte[] completions) {
        final int tag = (int) completion.tag();
        assertTrue(sent[tag], "a completion for request " + tag + ", which was never sent");
        completions[tag]++;
        final int mode = modes[tag];
        switch (completion.outcome()) {
            case HTTPCompletion.RESPONSE -> {
                if (mode >= DROP) {
                    fail("request " + tag + " (" + PATHS[mode] + ") was never answered, yet completed with an answer");
                }
                final String body = body(completion.response());
                final String suffix = "/" + tag;
                final String path = mode == CHUNKED ? body.substring(0, body.indexOf('x')) : body;
                assertTrue(path.startsWith(PATHS[mode].substring(0, PATHS[mode].indexOf('/', 1) < 0
                                ? PATHS[mode].length()
                                : PATHS[mode].indexOf('/', 1))) && path.endsWith(suffix),
                        "request " + tag + " got the answer " + abbreviate(body));
                if (mode == CHUNKED) {
                    assertEquals(20_000, body.length());
                }
            }
            case HTTPCompletion.FAILED -> {
                // Any request can fail when its connection is closed under it.
            }
            case HTTPCompletion.TIMED_OUT -> assertEquals(
                    STALL, mode, "request " + tag + " (" + PATHS[mode] + ") timed out though it is answered at once");
            default -> fail("unknown outcome " + completion.outcome());
        }
    }

    private static String roundTrip(final AsyncHTTPConnections connections, final String path) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int connection = AsyncHTTPConnections.NONE;
        while (connection < 0 && System.nanoTime() < deadline) {
            connection = connections.acquire();
            Thread.sleep(1);
        }
        assertTrue(connection >= 0, "no connection recovered");
        connections.request(connection, HTTPMethod.GET).path(ascii(path)).noBody();
        assertEquals(AsyncHTTPConnections.SENT, connections.send(connection, MAX_REQUESTS, deadline));
        while (!connections.poll()) {
            Thread.onSpinWait();
        }
        assertEquals(HTTPCompletion.RESPONSE, connections.completion().outcome());
        return body(connections.completion().response());
    }

    private static long gcCount() {
        long count = 0;
        for (final java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            count += gc.getCollectionCount();
        }
        return count;
    }

    private static int pick(final Random random) {
        int roll = random.nextInt(100);
        for (int mode = 0; mode < WEIGHTS.length; mode++) {
            roll -= WEIGHTS[mode];
            if (roll < 0) {
                return mode;
            }
        }
        return OK;
    }

    private interface Step {
        void run() throws Exception;
    }

    private static Thread loop(
            final String name, final AtomicBoolean running, final AtomicReference<Throwable> failure, final Step step) {
        final Thread thread = new Thread(() -> {
            try {
                while (running.get() || name.equals("stress-connector")) {
                    step.run();
                }
            } catch (InterruptedException e) {
                // Stopped.
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String body(final HTTPResponse response) {
        final ByteBuffer body = response.getBody().duplicate();
        final byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String abbreviate(final String text) {
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}

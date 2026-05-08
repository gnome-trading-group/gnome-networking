package group.gnometrading.networking.http;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that sharing one HTTPSocketMessageClient across threads causes failures,
 * and that giving each thread its own client eliminates those failures.
 *
 * The shared-client test may be non-deterministic on single-core machines or with
 * certain JIT timings, but should fail reliably on multi-core hardware. The
 * dedicated-client test must always pass: it validates the fix.
 */
class HTTPClientConcurrencyIT {

    private static final int ITERATIONS = 500;
    private static final String PATH = "/api/test";

    @Test
    @Timeout(30)
    void sharedClientFailsUnderConcurrentAccess() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPSocketMessageClient shared =
                new HTTPSocketMessageClient(HTTPProtocol.HTTP, "localhost", server.getPort());
            shared.connect();

            final int failures = runConcurrent(shared, shared, ITERATIONS);
            assertTrue(failures > 0,
                "Expected concurrency failures with a shared HTTPSocketMessageClient, got zero. "
                + "The race window may have been missed — try increasing ITERATIONS.");
        }
    }

    @Test
    @Timeout(30)
    void dedicatedClientsSucceedUnderConcurrentAccess() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final HTTPSocketMessageClient clientA =
                new HTTPSocketMessageClient(HTTPProtocol.HTTP, "localhost", server.getPort());
            final HTTPSocketMessageClient clientB =
                new HTTPSocketMessageClient(HTTPProtocol.HTTP, "localhost", server.getPort());
            clientA.connect();
            clientB.connect();

            final int failures = runConcurrent(clientA, clientB, ITERATIONS);
            assertEquals(0, failures,
                "Expected zero failures with per-thread HTTPSocketMessageClients");
        }
    }

    private int runConcurrent(
            final HTTPSocketMessageClient c1,
            final HTTPSocketMessageClient c2,
            final int iterations) throws InterruptedException {

        final AtomicInteger failures = new AtomicInteger(0);
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(2);

        spawn("thread-1", c1, iterations, failures, startGate, done);
        spawn("thread-2", c2, iterations, failures, startGate, done);

        startGate.countDown();
        done.await();
        return failures.get();
    }

    private void spawn(
            final String name,
            final HTTPSocketMessageClient client,
            final int iterations,
            final AtomicInteger failures,
            final CountDownLatch startGate,
            final CountDownLatch done) {

        final Thread t = new Thread(() -> {
            try {
                startGate.await();
                for (int i = 0; i < iterations; i++) {
                    try {
                        final int written = client.request(
                            HTTPMethod.GET, PATH, null, "x-api-key", "test-key", null, null);
                        if (written <= 0) {
                            failures.incrementAndGet();
                            continue;
                        }
                        final int msg = client.readMessage();
                        if (msg <= 0 || client.getHTTPDecoder().getStatus() != 200) {
                            failures.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        failures.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }
}

package group.gnometrading.networking.http;

import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class RequestGateIT {

    private static final byte[] HTTP_429 = (
            "HTTP/1.1 429 Too Many Requests\r\n"
            + "Retry-After: 3\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n"
    ).getBytes();

    private static final byte[] HTTP_200 = (
            "HTTP/1.1 200 OK\r\n"
            + "Content-Length: 2\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n[]"
    ).getBytes();

    @Test
    void refusedRequestIsNeverSent() throws Exception {
        try (RetryableHTTPClientIT.ScriptedHttpServer server = new RetryableHTTPClientIT.ScriptedHttpServer(HTTP_429)) {
            final RecordingGate gate = new RecordingGate();
            final HTTPClient client = client(server.getPort(), gate);

            gate.refuseNext = true;
            final HTTPResponse refused = client.post(HTTPProtocol.HTTP, "localhost", "/orders", new byte[] {'x'});
            assertTrue(refused.isRefusedLocally());
            assertEquals(429, refused.getStatusCode());
            assertTrue(gate.answered.isEmpty(), "a refused request has no response to report");

            // Had the refused request gone out, it would have taken the server's only response.
            final HTTPResponse sent = client.post(HTTPProtocol.HTTP, "localhost", "/orders", new byte[] {'x'});
            assertFalse(sent.isRefusedLocally());
            assertEquals(429, sent.getStatusCode());
            assertEquals(List.of("POST /orders", "POST /orders"), gate.asked);
            assertEquals(List.of("1:429:3"), gate.answered);
        }
    }

    @Test
    void gateSeesGnomeStringPathsAndTheirResponses() throws Exception {
        try (RetryableHTTPClientIT.ScriptedHttpServer server =
                new RetryableHTTPClientIT.ScriptedHttpServer(HTTP_200, HTTP_429)) {
            final RecordingGate gate = new RecordingGate();
            final HTTPClient client = client(server.getPort(), gate);
            final GnomeString path = new ViewString("/v1/order/1/cancel");

            assertTrue(client.post(HTTPProtocol.HTTP, "localhost", path, new byte[0]).isSuccess());
            final HTTPResponse limited = client.delete(HTTPProtocol.HTTP, "localhost", "/order");

            assertEquals(429, limited.getStatusCode());
            assertEquals(3, limited.headerAsLong(0, -1));
            assertEquals(List.of("POST /v1/order/1/cancel", "DELETE /order"), gate.asked);
            assertEquals(List.of("1:200:-", "2:429:3"), gate.answered);
        }
    }

    @Test
    void gateIsToldOfAResponseThatNeverCame() throws Exception {
        try (RetryableHTTPClientIT.ScriptedHttpServer server = new RetryableHTTPClientIT.ScriptedHttpServer()) {
            final RecordingGate gate = new RecordingGate();
            final HTTPClient client = client(server.getPort(), gate);

            final HTTPResponse response = client.get(HTTPProtocol.HTTP, "localhost", "/orders");

            assertFalse(response.isSuccess());
            assertEquals(1, gate.answered.size());
            assertFalse(gate.answered.get(0).contains(":429:"));
        }
    }

    @Test
    void sendAndPollHandTheTagBack() throws Exception {
        try (RetryableHTTPClientIT.ScriptedHttpServer server = new RetryableHTTPClientIT.ScriptedHttpServer(HTTP_429)) {
            final RecordingGate gate = new RecordingGate();
            final HTTPClient client = client(server.getPort(), gate);
            final GnomeString path = new ViewString("/orders");

            gate.refuseNext = true;
            assertEquals(
                    HTTPClient.SEND_REFUSED,
                    client.send(HTTPProtocol.HTTP, "localhost", HTTPMethod.POST, path, null, 0,
                            null, null, null, null, null, null, null, null, null, null));

            final int connection = client.send(HTTPProtocol.HTTP, "localhost", HTTPMethod.POST, path, null, 0,
                    null, null, null, null, null, null, null, null, null, null);
            HTTPResponse response;
            do {
                response = client.poll("localhost", connection);
            } while (response == null);

            assertEquals(429, response.getStatusCode());
            assertEquals(List.of("1:429:3"), gate.answered);
        }
    }

    @Test
    void gatedRequestsDoNotAllocate() throws Exception {
        try (LocalTestHttpServer server = new LocalTestHttpServer()) {
            final CountingGate gate = new CountingGate();
            final HTTPClient client = client(server.getPort(), gate);
            final GnomeString path = new ViewString("/orders");
            final com.sun.management.ThreadMXBean threads =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            final long threadId = Thread.currentThread().getId();

            getMany(client, path, 2_000);
            final long before = threads.getThreadAllocatedBytes(threadId);
            getMany(client, path, 2_000);
            final long allocated = threads.getThreadAllocatedBytes(threadId) - before;

            assertEquals(4_000, gate.answered);
            // Measuring can itself allocate a little; anything per request would be far more.
            assertTrue(allocated < 1024, "allocated " + allocated + " bytes over 2,000 requests");
        }
    }

    private static void getMany(final HTTPClient client, final GnomeString path, final int count) throws Exception {
        for (int i = 0; i < count; i++) {
            if (!client.get(HTTPProtocol.HTTP, "localhost", path).isSuccess()) {
                throw new AssertionError("request " + i + " failed");
            }
        }
    }

    private static HTTPClient client(final int port, final RequestGate gate) {
        return HTTPClient.builder()
                .withPort(port)
                .withReadTimeout(2_000)
                .withRequestGate(gate)
                .captureHeaders("Retry-After")
                .build();
    }

    private static final class CountingGate implements RequestGate {
        int answered;

        @Override
        public int beforeRequest(final HTTPMethod method, final String path) {
            return 0;
        }

        @Override
        public int beforeRequest(final HTTPMethod method, final GnomeString path) {
            return 0;
        }

        @Override
        public void afterResponse(final int tag, final HTTPResponse response) {
            if (response.hasHeader(0)) {
                throw new AssertionError("the test server sends no Retry-After");
            }
            this.answered++;
        }
    }

    /** Lets requests through with tags 1, 2, ... unless told to refuse the next one. */
    private static final class RecordingGate implements RequestGate {
        final List<String> asked = new ArrayList<>();
        final List<String> answered = new ArrayList<>();
        boolean refuseNext;
        private int nextTag = 1;

        @Override
        public int beforeRequest(final HTTPMethod method, final String path) {
            return admit(method + " " + path);
        }

        @Override
        public int beforeRequest(final HTTPMethod method, final GnomeString path) {
            return admit(method + " " + path);
        }

        private int admit(final String request) {
            this.asked.add(request);
            if (this.refuseNext) {
                this.refuseNext = false;
                return REFUSE;
            }
            return this.nextTag++;
        }

        @Override
        public void afterResponse(final int tag, final HTTPResponse response) {
            final long retryAfter = response.headerAsLong(0, -1);
            this.answered.add(tag + ":" + response.getStatusCode() + ":" + (retryAfter < 0 ? "-" : retryAfter));
        }
    }
}

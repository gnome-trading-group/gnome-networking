package group.gnometrading.networking.websockets;

import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.WebSocketResilienceIT.ScriptedServer;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.networking.websockets.exceptions.InvalidHandshakeException;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the zero-copy payload views {@link WebSocketClient#read()} hands out: frames that share a read, frames
 * split across reads, payloads the caller only partly consumes, and the copy paths for control and masked frames.
 */
@Timeout(60)
class WebSocketFramingIT {

    private static final int READ_BUFFER_SIZE = 1 << 18;
    private static final String UPGRADE = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"
            + "\r\n";

    @Test
    void randomFramesSplitAtRandomPointsArriveIntact() throws Exception {
        final long seed = System.nanoTime();
        final Random random = new Random(seed);
        final List<byte[]> payloads = new ArrayList<>();
        for (int size : new int[] {0, 1, 125, 126, 127, 65_535, 65_536, 65_537, 200_000}) {
            payloads.add(randomBytes(random, size));
        }
        for (int i = 0; i < 400; i++) {
            final int size = random.nextInt(10) == 0 ? random.nextInt(150_000) : random.nextInt(2_000);
            payloads.add(randomBytes(random, size));
        }
        Collections.shuffle(payloads, random);

        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.writeBytes(UPGRADE.getBytes(StandardCharsets.US_ASCII));
        for (byte[] payload : payloads) {
            stream.writeBytes(serverFrame(0x2, payload));
        }
        final byte[] wire = stream.toByteArray();
        final long writerSeed = random.nextLong();

        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            writeInRandomPieces(socket.getOutputStream(), wire, new Random(writerSeed));
            Thread.sleep(5_000);
        })) {
            final WebSocketClient client = client(server);
            client.connect();

            for (int i = 0; i < payloads.size(); i++) {
                final ByteBuffer body = nextData(client, Opcode.BINARY);
                final byte[] expected = payloads.get(i);
                assertArrayEquals(expected, peek(body), "payload " + i + " of " + expected.length + " bytes, seed " + seed);

                // Callers may drain the payload, consume part of it, or leave it untouched; none of it may
                // disturb the frames still waiting in the read buffer.
                switch (i % 3) {
                    case 0 -> body.position(body.limit());
                    case 1 -> body.position(body.position() + body.remaining() / 2);
                    default -> { }
                }
            }
        }
    }

    @Test
    void frameExactlyFillingTheReadBufferIsRead() throws Exception {
        final int bufferSize = 4096;
        final byte[] payload = randomBytes(new Random(1), bufferSize - 4); // 2-byte header + 2-byte length
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            final OutputStream out = socket.getOutputStream();
            out.write(UPGRADE.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            out.write(serverFrame(0x2, payload));
            out.flush();
            Thread.sleep(2_000);
        })) {
            final WebSocketClient client = client(server, bufferSize);
            client.connect();
            assertArrayEquals(payload, peek(nextData(client, Opcode.BINARY)));
        }
    }

    @Test
    void frameLargerThanTheReadBufferFailsLoudly() throws Exception {
        final int bufferSize = 4096;
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            final OutputStream out = socket.getOutputStream();
            out.write(UPGRADE.getBytes(StandardCharsets.US_ASCII));
            out.write(serverFrame(0x2, new byte[bufferSize]));
            out.flush();
            Thread.sleep(2_000);
        })) {
            final WebSocketClient client = client(server, bufferSize);
            client.connect();
            final Exception e = assertThrows(Exception.class, () -> {
                for (int i = 0; i < 100; i++) {
                    client.read();
                }
            });
            assertTrue(e.getMessage().contains("overflow") || e.getMessage().contains("full"), e.getMessage());
        }
    }

    @Test
    void pingPayloadIsEchoedTheWayTheOutboundGatewayPongs() throws Exception {
        final BlockingQueue<byte[]> pongs = new ArrayBlockingQueue<>(1);
        try (ScriptedServer server = new ScriptedServer(socket -> {
            final InputStream in = socket.getInputStream();
            readRequest(in);
            final OutputStream out = socket.getOutputStream();
            // A data frame first, so the ping's payload does not start at the front of the read buffer.
            out.write(UPGRADE.getBytes(StandardCharsets.US_ASCII));
            out.write(serverFrame(0x1, "tick".getBytes(StandardCharsets.US_ASCII)));
            out.write(serverFrame(0x9, "ping-data".getBytes(StandardCharsets.US_ASCII)));
            out.flush();
            final ClientFrame frame = readClientFrame(in);
            assertEquals(0xA, frame.opcode);
            pongs.add(frame.payload);
            Thread.sleep(2_000);
        })) {
            final WebSocketClient client = client(server);
            client.connect();
            assertEquals("tick", ascii(nextData(client, Opcode.TEXT)));

            final WebSocketResponse ping = client.read();
            assertEquals(Opcode.PING, ping.getOpcode());
            final ByteBuffer body = ping.getBody();
            body.rewind(); // what OutboundWebSocketReader.pong does before echoing
            assertTrue(client.writeMessage(Opcode.PONG, body));

            final byte[] echoed = pongs.poll(5, TimeUnit.SECONDS);
            assertNotNull(echoed);
            assertEquals("ping-data", new String(echoed, StandardCharsets.US_ASCII));
        }
    }

    @Test
    void maskedFrameIsUnmaskedIntoACopy() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            final OutputStream out = socket.getOutputStream();
            out.write(UPGRADE.getBytes(StandardCharsets.US_ASCII));
            out.write(maskedServerFrame("masked hello".getBytes(StandardCharsets.US_ASCII), new byte[] {9, 8, 7, 6}));
            out.write(serverFrame(0x1, "after".getBytes(StandardCharsets.US_ASCII)));
            out.flush();
            Thread.sleep(2_000);
        })) {
            final WebSocketClient client = client(server);
            client.connect();
            assertEquals("masked hello", ascii(nextData(client, Opcode.TEXT)));
            assertEquals("after", ascii(nextData(client, Opcode.TEXT)));
        }
    }

    @Test
    void resetWhileAViewIsOutstandingLeavesTheClientUsable() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            final InputStream in = socket.getInputStream();
            readRequest(in);
            final OutputStream out = socket.getOutputStream();
            out.write(UPGRADE.getBytes(StandardCharsets.US_ASCII));
            out.write(serverFrame(0x1, "first".getBytes(StandardCharsets.US_ASCII)));
            out.write(serverFrame(0x1, "dropped by reset".getBytes(StandardCharsets.US_ASCII)));
            out.flush();
            readClientFrame(in);
            out.write(serverFrame(0x1, "after reset".getBytes(StandardCharsets.US_ASCII)));
            out.flush();
            Thread.sleep(2_000);
        })) {
            final WebSocketClient client = client(server);
            client.connect();
            final ByteBuffer first = nextData(client, Opcode.TEXT);
            assertEquals("first", ascii(first));

            client.reset();
            assertTrue(client.writeMessage(Opcode.TEXT, ByteBuffer.wrap("next".getBytes(StandardCharsets.US_ASCII))));
            // reset() discards what was already read off the socket; whether the second frame got that far depends
            // on how the bytes were split across reads. Either way every frame after the reset must be intact.
            String next = ascii(nextData(client, Opcode.TEXT));
            if (next.equals("dropped by reset")) {
                next = ascii(nextData(client, Opcode.TEXT));
            }
            assertEquals("after reset", next);
        }
    }

    @Test
    void reconnectWhileAViewIsOutstandingStartsClean() throws Exception {
        final int[] connections = {0};
        try (ScriptedServer server = new ScriptedServer(socket -> {
            final int connection;
            synchronized (connections) {
                connection = connections[0]++;
            }
            readRequest(socket.getInputStream());
            final OutputStream out = socket.getOutputStream();
            out.write(UPGRADE.getBytes(StandardCharsets.US_ASCII));
            if (connection == 0) {
                out.write(serverFrame(0x1, "old-1".getBytes(StandardCharsets.US_ASCII)));
                out.write(serverFrame(0x1, "old-2".getBytes(StandardCharsets.US_ASCII)));
            } else {
                out.write(serverFrame(0x1, "new".getBytes(StandardCharsets.US_ASCII)));
            }
            out.flush();
            Thread.sleep(3_000);
        })) {
            final WebSocketClient client = client(server);
            client.connect();
            assertEquals("old-1", ascii(nextData(client, Opcode.TEXT)));

            client.reconnect();
            assertEquals("new", ascii(nextData(client, Opcode.TEXT)));
        }
    }

    @Test
    void consumedFramesAreNotReplayedAfterTheServerCloses() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            final OutputStream out = socket.getOutputStream();
            out.write(UPGRADE.getBytes(StandardCharsets.US_ASCII));
            out.write(serverFrame(0x1, "one".getBytes(StandardCharsets.US_ASCII)));
            out.write(serverFrame(0x1, "two".getBytes(StandardCharsets.US_ASCII)));
            out.flush();
            // Returning closes the connection: a FIN with no WebSocket close frame.
        })) {
            final WebSocketClient client = client(server);
            client.connect();
            assertEquals("one", ascii(nextData(client, Opcode.TEXT)));
            assertEquals("two", ascii(nextData(client, Opcode.TEXT)));

            for (int i = 0; i < 5; i++) {
                final WebSocketResponse response = client.read();
                assertFalse(response.isSuccess(), "an old frame was returned again after the close");
            }
        }
    }

    @Test
    void consumedFramesAreNotReplayedAfterAConnectionReset() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            final OutputStream out = socket.getOutputStream();
            out.write(UPGRADE.getBytes(StandardCharsets.US_ASCII));
            out.write(serverFrame(0x1, "only".getBytes(StandardCharsets.US_ASCII)));
            out.flush();
            Thread.sleep(300);
            socket.setSoLinger(true, 0); // closing now sends a reset, so the client's read fails
        })) {
            final WebSocketClient client = client(server);
            client.connect();
            assertEquals("only", ascii(nextData(client, Opcode.TEXT)));

            for (int i = 0; i < 5; i++) {
                try {
                    assertFalse(client.read().isSuccess(), "an old frame was returned again after the reset");
                } catch (IOException expected) {
                    // The reset surfaces as an error; what matters is that no stale frame comes back.
                }
            }
        }
    }

    @Test
    void handshakeSplitAfterTheAcceptHeaderIsNotMistakenForFrames() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            final OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(200);
            out.write("Date: Sat, 04 Oct 2026 12:00:00 GMT\r\nServer: test\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(serverFrame(0x1, "first".getBytes(StandardCharsets.US_ASCII)));
            out.flush();
            Thread.sleep(2_000);
        })) {
            final WebSocketClient client = client(server);
            client.connect();
            assertEquals("first", ascii(nextData(client, Opcode.TEXT)));
        }
    }

    @Test
    void largeMessageOnANonBlockingSocketArrivesWhole() throws Exception {
        final byte[] payload = randomBytes(new Random(7), 1 << 20);
        final java.util.concurrent.CompletableFuture<byte[]> received = new java.util.concurrent.CompletableFuture<>();
        try (ScriptedServer server = new ScriptedServer(socket -> {
            final InputStream in = socket.getInputStream();
            readRequest(in);
            socket.getOutputStream().write(UPGRADE.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            // Read slowly, so the client's send buffer fills and its non-blocking write has to wait.
            Thread.sleep(500);
            received.complete(readLargeClientFrame(in));
            Thread.sleep(1_000);
        })) {
            final WebSocketClient client = new WebSocketClientBuilder()
                    .withURI(URI.create("ws://127.0.0.1:" + server.port() + "/"))
                    .withWriteBufferSize((1 << 20) + 64)
                    .withBlocking(false)
                    .build();
            client.connect();
            assertTrue(client.writeMessage(Opcode.BINARY, ByteBuffer.wrap(payload)));
            assertArrayEquals(payload, received.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void framesEncodedOnTwoThreadsAtOnceStayIntact() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            socket.getOutputStream().write(UPGRADE.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            Thread.sleep(5_000);
        })) {
            final WebSocketClient client = client(server);
            client.connect();
            final java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
            final Runnable pinger = () -> encodeRepeatedly(client, Opcode.PING, failure);
            final Runnable ponger = () -> encodeRepeatedly(client, Opcode.PONG, failure);
            final Thread first = new Thread(pinger);
            final Thread second = new Thread(ponger);
            first.start();
            second.start();
            first.join();
            second.join();
            assertNull(failure.get(), failure.get());
        }
    }

    private static void encodeRepeatedly(
            WebSocketClient client, Opcode opcode, java.util.concurrent.atomic.AtomicReference<String> failure) {
        final ByteBuffer out = ByteBuffer.allocate(64);
        for (int i = 0; i < 100_000 && failure.get() == null; i++) {
            out.clear();
            if (opcode == Opcode.PING) {
                client.wrapPingMessage(out);
            } else {
                client.wrapPongMessage(out);
            }
            // An empty control frame from a client: opcode byte, mask bit with zero length, four mask bytes.
            if (out.position() != 6 || (out.get(0) & 0x0F) != opcode.code || out.get(1) != (byte) 0x80) {
                failure.set(opcode + " frame corrupted on iteration " + i + ": " + out.position() + " bytes");
            }
        }
    }

    /** Reads one masked client frame of any size and returns its unmasked payload. */
    private static byte[] readLargeClientFrame(InputStream stream) throws IOException {
        final DataInputStream in = new DataInputStream(stream);
        in.readUnsignedByte();
        long length = in.readUnsignedByte() & 0x7F;
        if (length == 126) {
            length = in.readUnsignedShort();
        } else if (length == 127) {
            length = in.readLong();
        }
        final byte[] mask = in.readNBytes(4);
        final byte[] payload = in.readNBytes((int) length);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % 4];
        }
        return payload;
    }

    @Test
    void refusedUpgradeNamesTheStatusAndClosesTheConnection() throws Exception {
        try (ScriptedServer server = new ScriptedServer(socket -> {
            readRequest(socket.getInputStream());
            socket.getOutputStream().write("HTTP/1.1 451 Unavailable For Legal Reasons\r\nContent-Length: 0\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            Thread.sleep(2_000);
        })) {
            final WebSocketClient client = client(server);
            final InvalidHandshakeException refused = assertThrows(InvalidHandshakeException.class, client::connect);

            assertTrue(refused.getMessage().contains("HTTP/1.1 451"), refused.getMessage());
            assertFalse(client.isConnected(), "a connection that never upgraded must not be left open");
        }
    }

    private static WebSocketClient client(ScriptedServer server) throws IOException {
        return client(server, READ_BUFFER_SIZE);
    }

    private static WebSocketClient client(ScriptedServer server, int readBufferSize) throws IOException {
        return new WebSocketClientBuilder()
                .withURI(URI.create("ws://127.0.0.1:" + server.port() + "/"))
                .withSocketFactory(new NativeSocketFactory())
                .withReadBufferSize(readBufferSize)
                .withReadTimeout(2_000)
                .build();
    }

    /** Reads until a complete frame arrives, failing if it is not the expected data opcode. */
    private static ByteBuffer nextData(WebSocketClient client, Opcode opcode) throws IOException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            final WebSocketResponse response = client.read();
            assertFalse(response.isClosed(), "connection closed");
            if (response.isSuccess()) {
                assertEquals(opcode, response.getOpcode());
                return response.getBody();
            }
        }
        throw new AssertionError("no frame arrived");
    }

    /** The payload's bytes, read without moving its position. */
    private static byte[] peek(ByteBuffer body) {
        final byte[] bytes = new byte[body.remaining()];
        body.get(body.position(), bytes);
        return bytes;
    }

    private static String ascii(ByteBuffer body) {
        return new String(peek(body), StandardCharsets.US_ASCII);
    }

    private static byte[] randomBytes(Random random, int size) {
        final byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    private static void writeInRandomPieces(OutputStream out, byte[] wire, Random random) throws Exception {
        int offset = 0;
        while (offset < wire.length) {
            final int piece = Math.min(wire.length - offset, 1 + random.nextInt(random.nextBoolean() ? 64 : 40_000));
            out.write(wire, offset, piece);
            out.flush();
            offset += piece;
            if (random.nextInt(20) == 0) {
                Thread.sleep(1);
            }
        }
    }

    private static byte[] serverFrame(int opcode, byte[] payload) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 | opcode);
        writeLength(out, 0, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] maskedServerFrame(byte[] payload, byte[] mask) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x81);
        writeLength(out, 0x80, payload.length);
        out.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i % 4]);
        }
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int maskBit, int length) {
        if (length <= 125) {
            out.write(maskBit | length);
        } else if (length <= 0xFFFF) {
            out.write(maskBit | 126);
            out.write(length >>> 8);
            out.write(length & 0xFF);
        } else {
            out.write(maskBit | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((long) length >>> shift) & 0xFF);
            }
        }
    }

    private record ClientFrame(int opcode, byte[] payload) {}

    /** Reads one masked client frame (payloads under 64KB). */
    private static ClientFrame readClientFrame(InputStream stream) throws IOException {
        final DataInputStream in = new DataInputStream(stream);
        final int opcode = in.readUnsignedByte() & 0x0F;
        int length = in.readUnsignedByte() & 0x7F;
        if (length == 126) {
            length = in.readUnsignedShort();
        }
        final byte[] mask = in.readNBytes(4);
        final byte[] payload = in.readNBytes(length);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % 4];
        }
        return new ClientFrame(opcode, payload);
    }

    private static void readRequest(InputStream in) throws IOException {
        int matched = 0;
        while (matched < 4) {
            final int b = in.read();
            if (b < 0) {
                return;
            }
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
                    ? matched + 1
                    : 0;
        }
    }
}

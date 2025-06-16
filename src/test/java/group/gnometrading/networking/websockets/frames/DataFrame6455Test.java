package group.gnometrading.networking.websockets.frames;

import group.gnometrading.networking.websockets.enums.Opcode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DataFrame6455Test {

    private static final int MASK_KEY = 0x12345678;

    private DataFrame6455 frame;
    private ByteBuffer buffer;

    @BeforeEach
    void setUp() {
        frame = new DataFrame6455(MASK_KEY);
        buffer = ByteBuffer.allocate(1 << 17);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 125, 126, 127, 1000, 65535, 65536, 70000})
    void testEncodeBinaryFrame(int payloadSize) {
        byte[] payload = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            payload[i] = (byte) i;
        }
        ByteBuffer payloadBuffer = ByteBuffer.wrap(payload);

        frame.wrap(buffer);
        frame.encode(Opcode.BINARY, payloadBuffer);

        buffer.flip();
        // Verify frame header
        assertEquals((byte) 0b10000010, buffer.get());
        byte secondByte = buffer.get();
        if (payloadSize <= 125) {
            assertEquals((byte) 0b10000000 | payloadSize, secondByte);
        } else if (payloadSize <= 65535) {
            assertEquals((byte) 0b11111110, secondByte);
            assertEquals(payloadSize, buffer.getShort() & 0xFFFF);
        } else {
            assertEquals((byte) 0b11111111, secondByte);
            assertEquals(payloadSize, buffer.getLong());
        }

        // Next 4 bytes: masking key
        int mask = buffer.getInt();
        assertEquals(0x12345678, mask);

        // Verify payload is masked
        byte[] maskedPayload = new byte[payloadSize];
        buffer.get(maskedPayload);
        for (int i = 0; i < payloadSize; i++) {
            byte maskByte = (byte) ((mask >> (8 * (3 - (i % 4)))) & 0xFF);
            assertEquals(payload[i], (byte) (maskedPayload[i] ^ maskByte));
        }
    }

    @Test
    void testEncodeControlFrame() {
        // Test encoding a control frame (PING)
        ByteBuffer emptyBuffer = ByteBuffer.allocate(0);

        frame.wrap(buffer);
        frame.encode(Opcode.PING, emptyBuffer);

        buffer.flip();
        // Verify frame header
        // First byte: 1000 1001
        // - 1: FIN bit
        // - 000: RSV1-3 bits
        // - 1001: PING opcode
        assertEquals((byte) 0b10001001, buffer.get());

        // Second byte: 1000 0000
        // - 1: MASK bit
        // - 0000000: payload length (0)
        assertEquals((byte) 0b10000000, buffer.get());

        // Next 4 bytes: masking key
        int mask = buffer.getInt();
        assertNotEquals(0, mask);

        // No payload for PING
        assertEquals(0, buffer.remaining());
    }

    @ParameterizedTest
    @MethodSource("provideFrameStates")
    void testIsIncomplete(byte[] frameData, int offset, int limit, boolean expectedIncomplete) {
        buffer.put(frameData);
        buffer.flip();
        frame.wrap(buffer, offset, limit);
        assertEquals(expectedIncomplete, frame.isIncomplete());
    }

    @ParameterizedTest
    @MethodSource("provideFragmentStates")
    void testIsFragment(byte[] frameData, int offset, int limit, boolean expectedFragment) {
        buffer.put(frameData);
        buffer.flip();
        frame.wrap(buffer, offset, limit);
        assertEquals(expectedFragment, frame.isFragment());
    }

    @ParameterizedTest
    @MethodSource("provideHeaderStates")
    void testHasCompleteHeader(byte[] frameData, int offset, int limit, boolean expectedComplete) {
        buffer.put(frameData);
        buffer.flip();
        frame.wrap(buffer, offset, limit);
        assertEquals(expectedComplete, frame.hasCompleteHeader());
    }

    @ParameterizedTest
    @MethodSource("provideFrameData")
    void testFrameData(byte[] frameData, int offset, int limit, int expectedLength, byte expectedOpcode, byte[] expectedPayload) {
        buffer.put(frameData);
        buffer.flip();
        frame.wrap(buffer, offset, limit);

        assertEquals(expectedLength, frame.length());
        assertEquals(expectedOpcode, frame.getOpcode().code);

        ByteBuffer payloadBuffer = ByteBuffer.allocate(expectedPayload.length);
        frame.copyPayloadData(payloadBuffer);
        payloadBuffer.flip();
        byte[] actualPayload = new byte[payloadBuffer.remaining()];
        payloadBuffer.get(actualPayload);
        assertArrayEquals(expectedPayload, actualPayload);
    }

    private static Stream<Arguments> provideFrameStates() {
        return Stream.of(
            // Empty buffer
            Arguments.of(new byte[0], 0, 0, true),

            // Just first byte (opcode)
            Arguments.of(new byte[] { (byte) 0b10000010 }, 0, 1, true),

            // Small frame (1 byte) - complete header
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x00  // payload
            }, 0, 7, false),

            // Small frame (1 byte) - incomplete payload
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78   // masking key
            }, 0, 6, true),

            // Medium frame (126 bytes) - complete header
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b11111110,  // MASK + extended length
                0x00, 0x00,  // length 0 in extended length
                0x12, 0x34, 0x56, 0x78   // masking key
            }, 0, 8, false),

            // Medium frame (126 bytes) - incomplete payload
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b11111110,  // MASK + extended length
                0x00, 0x7E,  // length 126
                0x12, 0x34, 0x56, 0x78   // masking key
            }, 0, 8, true),

            // Large frame (65536 bytes) - complete header
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b11111111,  // MASK + extended length
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // length 0
                0x12, 0x34, 0x56, 0x78   // masking key
            }, 0, 14, false),

            // Large frame (65536 bytes) - incomplete payload
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b11111111,  // MASK + extended length
                0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,  // length 65536
                0x12, 0x34, 0x56, 0x78   // masking key
            }, 0, 14, true),

            // Medium frame - incomplete extended length
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b11111110,  // MASK + extended length
                0x00  // only one byte of length
            }, 0, 3, true),

            // Large frame - incomplete extended length
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b11111111,  // MASK + extended length
                0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00  // only 7 bytes of length
            }, 0, 9, true),

            // Control frame (PING) - complete
            Arguments.of(new byte[] { 
                (byte) 0b10001001,  // FIN + PING opcode
                (byte) 0b10000000,  // MASK + length 0
                0x12, 0x34, 0x56, 0x78   // masking key
            }, 0, 6, false),

            // Control frame (PING) - incomplete
            Arguments.of(new byte[] { 
                (byte) 0b10001001,  // FIN + PING opcode
                (byte) 0b10000000,  // MASK + length 0
                0x12, 0x34, 0x56  // incomplete masking key
            }, 0, 5, true),

            // Frame with offset - complete
            Arguments.of(new byte[] {
                0x00, 0x00, 0x00,  // padding
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x00  // payload
            }, 3, 7, false),

            // Frame with offset - incomplete header
            Arguments.of(new byte[] {
                0x00, 0x00, 0x00,  // padding
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000001   // MASK + length 1
            }, 3, 2, true),

            // Frame with offset - incomplete extended length
            Arguments.of(new byte[] {
                0x00, 0x00, 0x00,  // padding
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b11111110,  // MASK + extended length
                0x00  // only one byte of length
            }, 3, 3, true),

            // Frame with offset - incomplete payload
            Arguments.of(new byte[] {
                0x00, 0x00, 0x00,  // padding
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78   // masking key
            }, 3, 6, true),

            // Frame with offset - complete large frame
            Arguments.of(new byte[] {
                0x00, 0x00, 0x00,  // padding
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b11111111,  // MASK + extended length
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // length 0
                0x12, 0x34, 0x56, 0x78   // masking key
            }, 3, 14, false),

            // Frame with different offsets
            Arguments.of(new byte[] {
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 10 bytes padding
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x00  // payload
            }, 10, 7, false),

            // Frame with offset and incomplete data
            Arguments.of(new byte[] {
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 10 bytes padding
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56   // incomplete masking key
            }, 10, 3, true)
        );
    }

    private static Stream<Arguments> provideFragmentStates() {
        return Stream.of(
            // Complete binary frame (not fragmented)
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x00  // payload
            }, 0, 7, false),

            // First fragment of binary frame
            Arguments.of(new byte[] { 
                (byte) 0b00000010,  // no FIN + BINARY opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x00  // payload
            }, 0, 7, true),

            // Middle fragment of binary frame
            Arguments.of(new byte[] { 
                (byte) 0b00000000,  // no FIN + CONTINUATION opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x00  // payload
            }, 0, 7, true),

            // Last fragment of binary frame
            Arguments.of(new byte[] { 
                (byte) 0b10000000,  // FIN + CONTINUATION opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x00  // payload
            }, 0, 7, false),

            // Complete text frame (not fragmented)
            Arguments.of(new byte[] { 
                (byte) 0b10000001,  // FIN + TEXT opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x00  // payload
            }, 0, 7, false),

            // First fragment of text frame
            Arguments.of(new byte[] { 
                (byte) 0b00000001,  // no FIN + TEXT opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x00  // payload
            }, 0, 7, true)
        );
    }

    private static Stream<Arguments> provideHeaderStates() {
        return Stream.of(
                // Empty buffer
                Arguments.of(new byte[0], 0, 0, false),

                // Just first byte (opcode)
                Arguments.of(new byte[] { (byte) 0b10000010 }, 0, 1, false),

                // Small frame (1 byte) - complete header
                Arguments.of(new byte[] {
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b10000001,  // MASK + length 1
                        0x12, 0x34, 0x56, 0x78,  // masking key
                        0x00  // payload
                }, 0, 6, true),

                // Small frame (1 byte) - incomplete header
                Arguments.of(new byte[] {
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b10000001,  // MASK + length 1
                        0x12, 0x34, 0x56   // incomplete masking key
                }, 0, 5, false),

                // Medium frame - complete header
                Arguments.of(new byte[] {
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b11111110,  // MASK + extended length
                        0x00, 0x01,  // length 1 in extended length
                        0x12, 0x34, 0x56, 0x78   // masking key
                }, 0, 8, true),

                // Medium frame - incomplete extended length
                Arguments.of(new byte[] {
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b11111110,  // MASK + extended length
                        0x00  // only one byte of length
                }, 0, 3, false),

                // Medium frame - incomplete masking key
                Arguments.of(new byte[] {
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b11111110,  // MASK + extended length
                        0x00, 0x00,  // length 0 in extended length
                        0x12, 0x34, 0x56   // incomplete masking key
                }, 0, 7, false),

                // Large frame - complete header
                Arguments.of(new byte[] {
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b11111111,  // MASK + extended length
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x08,  // length 8
                        0x12, 0x34, 0x56, 0x78   // masking key
                }, 0, 14, true),

                // Large frame - incomplete extended length
                Arguments.of(new byte[] {
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b11111111,  // MASK + extended length
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00  // only 7 bytes of length
                }, 0, 9, false),

                // Large frame - incomplete masking key
                Arguments.of(new byte[] {
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b11111111,  // MASK + extended length
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // length 0
                        0x12, 0x34, 0x56   // incomplete masking key
                }, 0, 13, false),

                // Control frame (PING) - complete header
                Arguments.of(new byte[] {
                        (byte) 0b10001001,  // FIN + PING opcode
                        (byte) 0b10000000,  // MASK + length 0
                        0x12, 0x34, 0x56, 0x78   // masking key
                }, 0, 6, true),

                // Control frame (PING) - incomplete header
                Arguments.of(new byte[] {
                        (byte) 0b10001001,  // FIN + PING opcode
                        (byte) 0b10000000,  // MASK + length 0
                        0x12, 0x34, 0x56  // incomplete masking key
                }, 0, 5, false),

                // Frame with offset - complete header
                Arguments.of(new byte[] {
                        0x00, 0x00, 0x00,  // padding
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b10000001,  // MASK + length 1
                        0x12, 0x34, 0x56, 0x78,  // masking key
                        0x00  // payload
                }, 3, 6, true),

                // Frame with offset - incomplete header
                Arguments.of(new byte[] {
                        0x00, 0x00, 0x00,  // padding
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b10000001,  // MASK + length 1
                        0x12, 0x34, 0x56   // incomplete masking key
                }, 3, 5, false),

                // Frame with offset - complete large frame header
                Arguments.of(new byte[] {
                        0x00, 0x00, 0x00,  // padding
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b11111111,  // MASK + extended length
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // length 0
                        0x12, 0x34, 0x56, 0x78   // masking key
                }, 3, 14, true),

                // Frame with offset - incomplete large frame header
                Arguments.of(new byte[] {
                        0x00, 0x00, 0x00,  // padding
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b11111111,  // MASK + extended length
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // length 0
                        0x12, 0x34, 0x56   // incomplete masking key
                }, 3, 13, false),

                // Frame with different offsets
                Arguments.of(new byte[] {
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 10 bytes padding
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b10000001,  // MASK + length 1
                        0x12, 0x34, 0x56, 0x78,  // masking key
                        0x00  // payload
                }, 10, 6, true),

                // Frame with offset and incomplete header
                Arguments.of(new byte[] {
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 10 bytes padding
                        (byte) 0b10000010,  // FIN + BINARY opcode
                        (byte) 0b10000001,  // MASK + length 1
                        0x12, 0x34, 0x56   // incomplete masking key
                }, 10, 5, false)
        );
    }

    private static Stream<Arguments> provideFrameData() {
        return Stream.of(
            // Empty binary frame
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000000,  // MASK + length 0
                0x12, 0x34, 0x56, 0x78   // masking key
            }, 0, 6, 6, (byte) 0x02, new byte[0]),

            // Single byte binary frame
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x50 // payload
            }, 0, 7, 7, (byte) 0x02, new byte[] { 0x50 ^ 0x12 }),

            // Small binary frame (3 bytes)
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000011,  // MASK + length 125
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x50, 0x77, 0x12  // payload
            }, 0, 9, 9, (byte) 0x02, new byte[] { 0x50 ^ 0x12, 0x77 ^ 0x34, 0x12 ^ 0x56 }),

            // Medium binary frame (126 bytes)
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b11111110,  // MASK + extended length
                0x00, 0x02,  // length 2
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x77, 0x12  // payload
            }, 0, 10, 10, (byte) 0x02, new byte[] { 0x77 ^ 0x12, 0x12 ^ 0x34 }),

            // Large binary frame (65536 bytes)
            Arguments.of(new byte[] { 
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b11111111,  // MASK + extended length
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,  // length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x12  // payload
            }, 0, 15, 15, (byte) 0x02, new byte[] { 0x12 ^ 0x12 }),

            // Text frame with UTF-8 data
            Arguments.of(new byte[] { 
                (byte) 0b10000001,  // FIN + TEXT opcode
                (byte) 0b10000100,  // MASK + length 4
                0x12, 0x34, 0x56, 0x78,  // masking key
                (byte) 0xE2, (byte) 0xAB, (byte) 0xCE, (byte) 0xF8  // 😀
            }, 0, 10, 10, (byte) 0x01, new byte[] { (byte) 0xE2 ^ 0x12, (byte) 0xAB ^ 0x34, (byte) 0xCE ^ 0x56, (byte) 0xF8 ^ 0x78 }),

            // PING frame
            Arguments.of(new byte[] { 
                (byte) 0b10001001,  // FIN + PING opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x42 // payload
            }, 0, 7, 7, (byte) 0x09, new byte[] { 0x42 ^ 0x12 }),

            // PONG frame
            Arguments.of(new byte[] { 
                (byte) 0b10001010,  // FIN + PONG opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x50  // payload
            }, 0, 7, 7, (byte) 0x0A, new byte[] { 0x50 ^ 0x12 }),

            // CLOSE frame
            Arguments.of(new byte[] { 
                (byte) 0b10001000,  // FIN + CLOSE opcode
                (byte) 0b10000010,  // MASK + length 2
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x11, (byte) 0xDC
            }, 0, 8, 8, (byte) 0x08, new byte[] { 0x11 ^ 0x12, (byte) 0xDC ^ 0x34 }),

            // Fragmented binary frame (first fragment)
            Arguments.of(new byte[] { 
                (byte) 0b00000010,  // no FIN + BINARY opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x50  // payload
            }, 0, 7, 7, (byte) 0x02, new byte[] { 0x50 ^ 0x12 }),

            // Fragmented binary frame (continuation)
            Arguments.of(new byte[] { 
                (byte) 0b00000000,  // no FIN + CONTINUATION opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x34  // payload
            }, 0, 7, 7, (byte) 0x00, new byte[] { 0x34 ^ 0x12 }),

            // Frame with offset
            Arguments.of(new byte[] {
                0x00, 0x00, 0x00,  // padding
                (byte) 0b10000010,  // FIN + BINARY opcode
                (byte) 0b10000001,  // MASK + length 1
                0x12, 0x34, 0x56, 0x78,  // masking key
                0x11  // payload
            }, 3, 7, 7, (byte) 0x02, new byte[] { 0x11 ^ 0x12 })
        );
    }
}
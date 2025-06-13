package group.gnometrading.networking.client;

import group.gnometrading.networking.sockets.GnomeSocket;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SocketClientTest {

    private GnomeSocket mockSocket;
    private SocketClient client;
    private static final int READ_BUFFER_SIZE = 1024;
    private static final int WRITE_BUFFER_SIZE = 512;

    @BeforeEach
    void setUp() throws IOException {
        mockSocket = mock(GnomeSocket.class);
        GnomeSocketFactory mockFactory = mock(GnomeSocketFactory.class);
        when(mockFactory.createSocket(any(InetSocketAddress.class))).thenReturn(mockSocket);
        
        client = new SocketClient(
            new InetSocketAddress("localhost", 8080),
            mockFactory,
            READ_BUFFER_SIZE,
            WRITE_BUFFER_SIZE
        );
    }

    @Test
    void testConnect() throws IOException {
        client.connect();
        verify(mockSocket).connect();
    }

    @Test
    void testClose() throws Exception {
        client.close();
        verify(mockSocket).close();
    }

    @Test
    void testConfigureBlocking() throws IOException {
        client.configureBlocking(true);
        verify(mockSocket).configureBlocking(true);
    }

    @Test
    void testGetBuffers() {
        ByteBuffer readBuffer = client.getReadBuffer();
        ByteBuffer writeBuffer = client.getWriteBuffer();

        assertNotNull(readBuffer);
        assertNotNull(writeBuffer);
        assertEquals(READ_BUFFER_SIZE, readBuffer.capacity());
        assertEquals(WRITE_BUFFER_SIZE, writeBuffer.capacity());
        assertTrue(readBuffer.isDirect());
        assertTrue(writeBuffer.isDirect());
    }

    @Test
    void testClearBuffers() {
        ByteBuffer readBuffer = client.getReadBuffer();
        ByteBuffer writeBuffer = client.getWriteBuffer();
        readBuffer.limit(50);
        readBuffer.put((byte) 1);
        writeBuffer.put((byte) 2);

        client.clearBuffers();

        assertEquals(0, readBuffer.position());
        assertEquals(0, readBuffer.limit());
        assertEquals(0, writeBuffer.position());
        assertEquals(WRITE_BUFFER_SIZE, writeBuffer.limit());
    }

    private static Stream<Arguments> testWriteArguments() {
        return Stream.of(
            Arguments.of(100, 100, 100),  // Full write from empty buffer
            Arguments.of(100, 50, 50),    // Partial write from empty buffer
            Arguments.of(100, 0, 0),      // No write from empty buffer
            Arguments.of(100, -1, -1),    // Error from empty buffer
            Arguments.of(0, 0, 0),        // Empty buffer, no write
            Arguments.of(WRITE_BUFFER_SIZE, WRITE_BUFFER_SIZE, WRITE_BUFFER_SIZE),  // Full buffer write
            Arguments.of(WRITE_BUFFER_SIZE, WRITE_BUFFER_SIZE/2, WRITE_BUFFER_SIZE/2)  // Half buffer write
        );
    }

    @ParameterizedTest
    @MethodSource("testWriteArguments")
    void testWrite(int len, int socketWriteResult, int expectedResult) throws IOException {
        ByteBuffer writeBuffer = client.getWriteBuffer();
        byte[] data = new byte[len];
        for (int i = 0; i < len; i++) {
            data[i] = (byte) i;
        }
        writeBuffer.put(data);

        when(mockSocket.write(any(), eq(len))).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            if (socketWriteResult > 0) buffer.position(buffer.position() + socketWriteResult);
            return socketWriteResult;
        });

        int result = client.write(len);
        assertEquals(expectedResult, result);

        if (socketWriteResult > 0) {
            if (socketWriteResult == len) {
                assertEquals(0, writeBuffer.position());
                assertEquals(WRITE_BUFFER_SIZE, writeBuffer.limit());
            } else {
                assertEquals(WRITE_BUFFER_SIZE - socketWriteResult, writeBuffer.remaining());
                assertEquals(socketWriteResult, writeBuffer.position());
            }
        } else if (socketWriteResult == 0) {
            assertEquals(len, writeBuffer.position());
            assertEquals(WRITE_BUFFER_SIZE, writeBuffer.limit());
        }
    }

    private static Stream<Arguments> testReadArguments() {
        return Stream.of(
            Arguments.of(100, 100, 100),  // Full read into empty buffer
            Arguments.of(100, 50, 50),    // Partial read into empty buffer
            Arguments.of(100, 0, 0),      // No read into empty buffer
            Arguments.of(100, -1, -1),    // Error reading into empty buffer
            Arguments.of(0, 0, 0),        // Empty read request
            Arguments.of(READ_BUFFER_SIZE, READ_BUFFER_SIZE, READ_BUFFER_SIZE),  // Full buffer read
            Arguments.of(READ_BUFFER_SIZE, READ_BUFFER_SIZE/2, READ_BUFFER_SIZE/2)  // Half buffer read
        );
    }

    @ParameterizedTest
    @MethodSource("testReadArguments")
    void testRead(int len, int socketReadResult, int expectedResult) throws IOException {
        ByteBuffer readBuffer = client.getReadBuffer();

        when(mockSocket.read(any(), eq(len))).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            if (socketReadResult > 0) {
                byte[] mockData = new byte[socketReadResult];
                for (int i = 0; i < socketReadResult; i++) {
                    mockData[i] = (byte) i;
                }
                buffer.put(mockData);
            }
            return socketReadResult;
        });

        int result = client.read(len);
        assertEquals(expectedResult, result);

        if (socketReadResult > 0) {
            assertEquals(socketReadResult, readBuffer.remaining());
            // Verify data integrity
            for (int i = 0; i < socketReadResult; i++) {
                assertEquals((byte) i, readBuffer.get(i));
            }
        }
    }

    @Test
    void testConsecutiveReads() throws IOException {
        ByteBuffer readBuffer = client.getReadBuffer();
        int firstRead = 50;
        int secondRead = 30;
        int thirdRead = 20;

        when(mockSocket.read(any(), eq(firstRead))).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            byte[] mockData = new byte[firstRead];
            for (int i = 0; i < firstRead; i++) {
                mockData[i] = (byte) i;
            }
            buffer.put(mockData);
            return firstRead;
        });

        when(mockSocket.read(any(), eq(secondRead))).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            byte[] mockData = new byte[secondRead];
            for (int i = 0; i < secondRead; i++) {
                mockData[i] = (byte) (i + firstRead);
            }
            buffer.put(mockData);
            return secondRead;
        });

        when(mockSocket.read(any(), eq(thirdRead))).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            byte[] mockData = new byte[thirdRead];
            for (int i = 0; i < thirdRead; i++) {
                mockData[i] = (byte) (i + firstRead + secondRead);
            }
            buffer.put(mockData);
            return thirdRead;
        });

        int result1 = client.read(firstRead);
        assertEquals(firstRead, result1);
        assertEquals(firstRead, readBuffer.remaining());

        int result2 = client.read(secondRead);
        assertEquals(firstRead + secondRead, result2);
        assertEquals(firstRead + secondRead, readBuffer.remaining());

        for (int i = 0; i < firstRead + secondRead; i++) {
            assertEquals((byte) i, readBuffer.get(i));
        }

        // Consume all the bytes
        readBuffer.position(readBuffer.limit());

        int result3 = client.read(thirdRead);
        assertEquals(thirdRead, result3);
        assertEquals(thirdRead, readBuffer.remaining());

        // Verify data integrity
        for (int i = 0; i < thirdRead; i++) {
            assertEquals((byte) i + firstRead + secondRead, readBuffer.get(i));
        }
    }

    @Test
    void testConsecutiveWrites() throws IOException {
        ByteBuffer writeBuffer = client.getWriteBuffer();
        int firstWrite = 50;
        int secondWrite = 30;
        int thirdWrite = 10;

        when(mockSocket.write(any(), eq(firstWrite))).thenAnswer(invocation -> {
            return 0;
        });

        when(mockSocket.write(any(), eq(secondWrite))).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            buffer.position(buffer.position() + firstWrite + secondWrite);
            return firstWrite + secondWrite;
        });

        when(mockSocket.write(any(), eq(thirdWrite))).thenAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            buffer.position(buffer.position() + thirdWrite);
            return thirdWrite;
        });

        byte[] data1 = new byte[firstWrite];
        byte[] data2 = new byte[secondWrite];
        byte[] data3 = new byte[thirdWrite];
        for (int i = 0; i < firstWrite; i++) data1[i] = (byte) i;
        for (int i = 0; i < secondWrite; i++) data2[i] = (byte) (i + firstWrite);
        for (int i = 0; i < thirdWrite; i++) data3[i] = (byte) (i + firstWrite + secondWrite);

        writeBuffer.put(data1);
        int result1 = client.write(firstWrite);
        assertEquals(0, result1);
        assertEquals(firstWrite, writeBuffer.position());
        for (int i = 0; i < firstWrite; i++) {
            assertEquals((byte) i, writeBuffer.get(i));
        }

        writeBuffer.put(data2);
        int result2 = client.write(secondWrite);
        assertEquals(firstWrite + secondWrite, result2);
        assertEquals(0, writeBuffer.position());

        writeBuffer.put(data3);
        int result3 = client.write(thirdWrite);
        assertEquals(thirdWrite, result3);
        assertEquals(0, writeBuffer.position());
    }

    @Test
    void testReconnect() throws Exception {
        ByteBuffer readBuffer = client.getReadBuffer();
        ByteBuffer writeBuffer = client.getWriteBuffer();
        readBuffer.limit(100);
        readBuffer.put((byte) 1);
        writeBuffer.put((byte) 2);

        client.reconnect();

        verify(mockSocket).close();
        verify(mockSocket).connect();

        assertEquals(0, readBuffer.position());
        assertEquals(0, readBuffer.limit());
        assertEquals(0, writeBuffer.position());
        assertEquals(WRITE_BUFFER_SIZE, writeBuffer.limit());
    }
} 
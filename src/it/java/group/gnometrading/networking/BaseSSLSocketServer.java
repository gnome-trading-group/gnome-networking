package group.gnometrading.networking;

import group.gnometrading.networking.sockets.SSLConfig;

import javax.net.ssl.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class BaseSSLSocketServer extends BaseSocketServer implements Runnable {

    protected final SSLContext sslContext;
    protected ExecutorService executor = Executors.newSingleThreadExecutor();

    protected ByteBuffer myAppData;
    protected ByteBuffer myNetData;
    protected ByteBuffer peerAppData;
    protected ByteBuffer peerNetData;

    public BaseSSLSocketServer(String host, int port, SSLConfig sslConfig) {
        super(host, port);
        this.sslContext = sslConfig.getSSLContext();

        SSLSession dummySession = sslContext.createSSLEngine().getSession();
        myAppData = ByteBuffer.allocate(dummySession.getApplicationBufferSize());
        myNetData = ByteBuffer.allocate(dummySession.getPacketBufferSize());
        peerAppData = ByteBuffer.allocate(dummySession.getApplicationBufferSize());
        peerNetData = ByteBuffer.allocate(dummySession.getPacketBufferSize());
        dummySession.invalidate();
    }

    protected void write(SocketChannel client, SSLEngine engine, ByteBuffer message) throws IOException {
        myAppData.clear();
        myAppData.put(message);
        myAppData.flip();
        while (myAppData.hasRemaining()) {
            // The loop has a meaning for (outgoing) messages larger than 16KB.
            // Every wrap call will remove 16KB from the original message and send it to the remote peer.
            myNetData.clear();
            SSLEngineResult result = engine.wrap(myAppData, myNetData);
            switch (result.getStatus()) {
                case OK:
                    myNetData.flip();
                    while (myNetData.hasRemaining()) {
                        client.write(myNetData);
                    }
                    break;
                case BUFFER_OVERFLOW:
                    myNetData = enlargePacketBuffer(engine, myNetData);
                    break;
                case BUFFER_UNDERFLOW:
                    throw new SSLException("Buffer underflow occured after a wrap. I don't think we should ever get here.");
                case CLOSED:
                    client.close();
                    return;
                default:
                    throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
            }
        }
    }

    @Override
    protected void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);

        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);
        engine.beginHandshake();

        if (doHandshake(client, engine)) {
            if (selector.isOpen()) {
                client.register(selector, SelectionKey.OP_READ, engine);
            }
        } else {
            client.close();
        }
    }

    private boolean doHandshake(SocketChannel client, SSLEngine engine) throws IOException {
        SSLEngineResult result;
        SSLEngineResult.HandshakeStatus handshakeStatus = engine.getHandshakeStatus();

        myNetData.clear();
        peerNetData.clear();

        while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED && handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            switch (handshakeStatus) {
                case NEED_UNWRAP:
                    if (client.read(peerNetData) < 0) {
                        if (engine.isInboundDone() && engine.isOutboundDone()) {
                            return false;
                        }
                        try {
                            engine.closeInbound();
                        } catch (SSLException e) {
                            System.err.println("This engine was forced to close inbound, without having received the proper SSL/TLS close notification message from the peer, due to end of stream.");
                            e.printStackTrace();
                        }
                        engine.closeOutbound();
                        // After closeOutbound the engine will be set to WRAP state, in order to try to send a close message to the client.
                        handshakeStatus = engine.getHandshakeStatus();
                        break;
                    }
                    peerNetData.flip();
                    try {
                        result = engine.unwrap(peerNetData, peerAppData);
                        peerNetData.compact();
                        handshakeStatus = result.getHandshakeStatus();
                    } catch (SSLException sslException) {
                        System.err.println("A problem was encountered while processing the data that caused the SSLEngine to abort. Will try to properly close connection...");
                        sslException.printStackTrace();
                        engine.closeOutbound();
                        handshakeStatus = engine.getHandshakeStatus();
                        break;
                    }
                    switch (result.getStatus()) {
                        case OK:
                            break;
                        case BUFFER_OVERFLOW:
                            // Will occur when peerAppData's capacity is smaller than the data derived from peerNetData's unwrap.
                            peerAppData = enlargeApplicationBuffer(engine, peerAppData);
                            break;
                        case BUFFER_UNDERFLOW:
                            // Will occur either when no data was read from the peer or when the peerNetData buffer was too small to hold all peer's data.
                            peerNetData = handleBufferUnderflow(engine, peerNetData);
                            break;
                        case CLOSED:
                            if (engine.isOutboundDone()) {
                                return false;
                            } else {
                                engine.closeOutbound();
                                handshakeStatus = engine.getHandshakeStatus();
                                break;
                            }
                        default:
                            throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
                    }
                    break;
                case NEED_WRAP:
                    myNetData.clear();
                    try {
                        result = engine.wrap(myAppData, myNetData);
                        handshakeStatus = result.getHandshakeStatus();
                    } catch (SSLException sslException) {
                        System.err.println("A problem was encountered while processing the data that caused the SSLEngine to abort. Will try to properly close connection...");
                        sslException.printStackTrace();
                        engine.closeOutbound();
                        handshakeStatus = engine.getHandshakeStatus();
                        break;
                    }
                    switch (result.getStatus()) {
                        case OK :
                            myNetData.flip();
                            while (myNetData.hasRemaining()) {
                                client.write(myNetData);
                            }
                            break;
                        case BUFFER_OVERFLOW:
                            // Will occur if there is not enough space in myNetData buffer to write all the data that would be generated by the method wrap.
                            // Since myNetData is set to session's packet size we should not get to this point because SSLEngine is supposed
                            // to produce messages smaller or equal to that, but a general handling would be the following:
                            myNetData = enlargePacketBuffer(engine, myNetData);
                            break;
                        case BUFFER_UNDERFLOW:
                            throw new SSLException("Buffer underflow occured after a wrap. I don't think we should ever get here.");
                        case CLOSED:
                            try {
                                myNetData.flip();
                                while (myNetData.hasRemaining()) {
                                    client.write(myNetData);
                                }
                                // At this point the handshake status will probably be NEED_UNWRAP so we make sure that peerNetData is clear to read.
                                peerNetData.clear();
                            } catch (Exception e) {
                                System.err.println("Failed to send server's CLOSE message due to socket channel's failure.");
                                handshakeStatus = engine.getHandshakeStatus();
                            }
                            break;
                        default:
                            throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
                    }
                    break;
                case NEED_TASK:
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        executor.execute(task);
                    }
                    handshakeStatus = engine.getHandshakeStatus();
                    break;
                default:
                    throw new IllegalStateException("Invalid SSL status: " + handshakeStatus);
            }
        }

        return true;
    }

    protected ByteBuffer enlargePacketBuffer(SSLEngine engine, ByteBuffer buffer) {
        return enlargeBuffer(buffer, engine.getSession().getPacketBufferSize());
    }

    protected ByteBuffer enlargeApplicationBuffer(SSLEngine engine, ByteBuffer buffer) {
        return enlargeBuffer(buffer, engine.getSession().getApplicationBufferSize());
    }

    protected ByteBuffer enlargeBuffer(ByteBuffer buffer, int sessionProposedCapacity) {
        if (sessionProposedCapacity > buffer.capacity()) {
            buffer = ByteBuffer.allocate(sessionProposedCapacity);
        } else {
            buffer = ByteBuffer.allocate(buffer.capacity() * 2);
        }
        return buffer;
    }

    protected ByteBuffer handleBufferUnderflow(SSLEngine engine, ByteBuffer buffer) {
        if (engine.getSession().getPacketBufferSize() < buffer.limit()) {
            return buffer;
        } else {
            ByteBuffer replaceBuffer = enlargePacketBuffer(engine, buffer);
            buffer.flip();
            replaceBuffer.put(buffer);
            return replaceBuffer;
        }
    }

    @Override
    public void stop() {
        super.stop();
        executor.shutdown();
    }
}
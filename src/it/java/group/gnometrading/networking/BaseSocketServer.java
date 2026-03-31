package group.gnometrading.networking;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class BaseSocketServer implements Runnable {
    protected final String host;
    protected final int port;
    protected final AtomicBoolean running;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile int actualPort;
    protected ServerSocketChannel serverChannel;
    protected Selector selector;

    public BaseSocketServer(String host, int port) {
        this.host = host;
        this.port = port;
        this.actualPort = port;
        this.running = new AtomicBoolean(true);
    }

    public void awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        ready.await(timeout, unit);
    }

    public int getPort() {
        return actualPort;
    }

    @Override
    public void run() {
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(new InetSocketAddress(host, port));
            actualPort = serverChannel.socket().getLocalPort();

            selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            ready.countDown();

            while (running.get()) {
                selector.select();
                if (!selector.isOpen()) {
                    continue;
                }
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (key.channel() != null) {
                            key.channel().close();
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            stop();
        }
    }

    protected void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
    }

    protected abstract void handleRead(SelectionKey key) throws IOException;

    public void stop() {
        running.set(false);
        try {
            if (selector != null) {
                selector.close();
            }
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
} 
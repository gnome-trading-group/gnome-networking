package group.gnometrading.networking.http;

import group.gnometrading.collections.GnomeMap;
import group.gnometrading.collections.PooledHashMap;
import group.gnometrading.strings.GnomeString;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * HTTPClient which produces garbage due to creating new sockets on connection close.
 * This client is not meant for multithreading use.
 */
public class HTTPClient implements AutoCloseable {

    private static final int MAX_READ_RETRIES = 3;
    static final int DEFAULT_READ_TIMEOUT_MS = 10_000;

    private final GnomeMap<String, HTTPSocketMessageClient> socketPool;
    private final HTTPResponse httpResponse;
    private final ByteBuffer body;
    private final boolean keepAlive;
    private final int portOverride;
    private final int readTimeoutMs;

    public HTTPClient() {
        this(true, 0);
    }

    public HTTPClient(final boolean keepAlive) {
        this(keepAlive, 0);
    }

    HTTPClient(final boolean keepAlive, final int portOverride) {
        this(keepAlive, portOverride, DEFAULT_READ_TIMEOUT_MS);
    }

    HTTPClient(final boolean keepAlive, final int portOverride, final int readTimeoutMs) {
        this.socketPool = new PooledHashMap<>();
        this.httpResponse = new HTTPResponse();
        this.body = ByteBuffer.allocate(HTTPSocketMessageClient.DEFAULT_HTTP_READ_BUFFER_SIZE);
        this.keepAlive = keepAlive;
        this.portOverride = portOverride;
        this.readTimeoutMs = readTimeoutMs;
    }

    private HTTPResponse request(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final HTTPMethod method,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5
    ) throws IOException {
        HTTPSocketMessageClient client = getSocketConnection(protocol, host);
        int bytesWritten = client.request(method, path, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);

        if (bytesWritten <= 0) {
            client = recreateConnection(protocol, host);
            bytesWritten = client.request(method, path, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
        }

        return parseResponse(client, bytesWritten);
    }

    private HTTPResponse request(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final HTTPMethod method,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        HTTPSocketMessageClient client = getSocketConnection(protocol, host);
        int bytesWritten = client.request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);

        if (bytesWritten <= 0) {
            client = recreateConnection(protocol, host);
            bytesWritten = client.request(method, path, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
        }

        return parseResponse(client, bytesWritten);
    }

    private HTTPResponse parseResponse(final HTTPSocketMessageClient client, final int bytesWritten) throws IOException {
        if (bytesWritten < 0) {
            return this.httpResponse.update(false, -1, null);
        }

        int message;
        int retries = 0;
        do {
            message = client.readMessage();
            retries++;
        } while (message == 0 && retries < MAX_READ_RETRIES);

        if (message <= 0) {
            return this.httpResponse.update(false, message, null);
        }

        this.body.clear();
        if (client.getHTTPDecoder().contentLength() > 0) {
            client.getHTTPDecoder().copyBody(this.body);
            this.body.flip();
        }

        int status = client.getHTTPDecoder().getStatus();
        return this.httpResponse.update(status >= 200 && status < 300, status, this.body);
    }

    public void connect(final HTTPProtocol protocol, final String host) throws IOException {
        getSocketConnection(protocol, host);
    }

    public void reconnect(final HTTPProtocol protocol, final String host) throws IOException {
        recreateConnection(protocol, host);
    }

    private HTTPSocketMessageClient getSocketConnection(final HTTPProtocol protocol, final String host) throws IOException {
        if (!socketPool.containsKey(host)) {
            final HTTPSocketMessageClient client = portOverride > 0
                    ? new HTTPSocketMessageClient(protocol, host, portOverride)
                    : new HTTPSocketMessageClient(protocol, host);
            client.connect();
            if (keepAlive) {
                client.setKeepAlive(true);
            }
            client.setTcpNoDelay(true);
            client.setReadTimeout(readTimeoutMs);
            socketPool.put(host, client);
        }
        return socketPool.get(host);
    }

    private HTTPSocketMessageClient recreateConnection(final HTTPProtocol protocol, final String host) throws IOException {
        final HTTPSocketMessageClient oldClient = socketPool.remove(host);
        if (oldClient != null) {
            try {
                oldClient.close();
            } catch (Exception e) {
                // Ignore - socket is already dead
            }
        }

        final HTTPSocketMessageClient newClient = portOverride > 0
                ? new HTTPSocketMessageClient(protocol, host, portOverride)
                : new HTTPSocketMessageClient(protocol, host);
        newClient.connect();
        if (keepAlive) {
            newClient.setKeepAlive(true);
        }
        newClient.setTcpNoDelay(true);
        newClient.setReadTimeout(readTimeoutMs);
        socketPool.put(host, newClient);

        return newClient;
    }

    // GET — String path

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // GET — GnomeString path

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // POST — String path

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // POST — GnomeString path

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // DELETE — String path

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // DELETE — GnomeString path

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, null, 0, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    public HTTPResponse delete(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.DELETE, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // PUT — String path

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    // PUT — GnomeString path

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, null, null, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, null, null, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, null, null, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, null, null, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, null, null);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, body == null ? 0 : body.length, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    public HTTPResponse put(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final int bodyLength,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2,
            final String headerKey3,
            final String headerValue3,
            final String headerKey4,
            final String headerValue4,
            final String headerKey5,
            final String headerValue5
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.PUT, body, bodyLength, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3, headerKey4, headerValue4, headerKey5, headerValue5);
    }

    @Override
    public void close() throws Exception {
        for (var key : this.socketPool.keys()) {
            try {
                this.socketPool.get(key).close();
            } catch (Exception ignored) {
            }
        }
    }
}

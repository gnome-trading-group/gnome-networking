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
public class HTTPClient {

    private final GnomeMap<String, HTTPSocketMessageClient> socketPool;
    private final HTTPResponse httpResponse;
    private final ByteBuffer body;

    public HTTPClient() {
        this.socketPool = new PooledHashMap<>();
        this.httpResponse = new HTTPResponse();
        this.body = ByteBuffer.allocate(HTTPSocketMessageClient.DEFAULT_HTTP_READ_BUFFER_SIZE);
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
            final String headerValue2
    ) throws IOException {
        final HTTPSocketMessageClient client = getSocketConnection(protocol, host);
        int bytesWritten = client.request(method, path, body, headerKey1, headerValue1, headerKey2, headerValue2);
        return parseResponse(client, bytesWritten);
    }

    private HTTPResponse request(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final HTTPMethod method,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        final HTTPSocketMessageClient client = getSocketConnection(protocol, host);
        int bytesWritten = client.request(method, path, body, headerKey1, headerValue1, headerKey2, headerValue2);
        return parseResponse(client, bytesWritten);
    }

    private HTTPResponse parseResponse(final HTTPSocketMessageClient client, final int bytesWritten) throws IOException {
        if (bytesWritten < 0) {
            return this.httpResponse.update(false, -1, null);
        }

        int message = client.readMessage();
        if (message <= 0) {
            return this.httpResponse.update(false, message, null);
        }

        this.body.clear();
        if (client.getHTTPDecoder().contentLength() > 0) {
            client.getHTTPDecoder().copyBody(this.body);
            this.body.flip();
        }

        return this.httpResponse.update(client.getHTTPDecoder().getStatus() == 200, client.getHTTPDecoder().getStatus(), this.body);
    }

    private HTTPSocketMessageClient getSocketConnection(final HTTPProtocol protocol, final String host) throws IOException {
        if (!socketPool.containsKey(host)) {
            socketPool.put(host, new HTTPSocketMessageClient(protocol, host));
        }

        final var client = socketPool.get(host);
        if (!client.available()) {
            this.socketPool.remove(host);
            return getSocketConnection(protocol, host);
        } else {
            return client;
        }
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, null, null);
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
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, null, null, null, null);
    }

    public HTTPResponse get(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, null, null);
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
        return request(protocol, host, path, HTTPMethod.GET, null, headerKey1, headerValue1, headerKey2, headerValue2);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final String path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, null, null);
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
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, null, null, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, null, null);
    }

    public HTTPResponse post(
            final HTTPProtocol protocol,
            final String host,
            final GnomeString path,
            final byte[] body,
            final String headerKey1,
            final String headerValue1,
            final String headerKey2,
            final String headerValue2
    ) throws IOException {
        return request(protocol, host, path, HTTPMethod.POST, body, headerKey1, headerValue1, headerKey2, headerValue2);
    }
}

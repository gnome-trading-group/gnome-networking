package group.gnometrading.networking.http;

import java.nio.charset.StandardCharsets;

public enum HTTPMethod {
    GET,
    POST,
    HEAD,
    DELETE,
    PUT;

    private final byte[] encoded;

    HTTPMethod() {
        this.encoded = name().getBytes(StandardCharsets.US_ASCII);
    }

    public byte[] encoded() {
        return encoded;
    }
}

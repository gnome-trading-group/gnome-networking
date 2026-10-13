package group.gnometrading.networking.http;

import group.gnometrading.strings.GnomeString;

/**
 * Sees every request an {@link HTTPClient} sends, before it is written, and every response it gets back, so that a
 * caller can hold requests to a budget, such as a venue's rate limit. Called on the thread making the request.
 */
public interface RequestGate {

    /** Returned by {@code beforeRequest} to refuse a request: nothing is sent. */
    int REFUSE = -1;

    /**
     * May block until the request can go.
     *
     * @return {@link #REFUSE}, or a non-negative tag the client passes back to {@link #afterResponse} with this
     *     request's response
     */
    int beforeRequest(HTTPMethod method, String path);

    /** As {@link #beforeRequest(HTTPMethod, String)}. */
    int beforeRequest(HTTPMethod method, GnomeString path);

    /**
     * The response to a request this gate let through, including a failed one (no response, timeout). Not called
     * when the request threw, or when a request from {@link HTTPClient#send} is abandoned. Captured headers are
     * readable here.
     */
    void afterResponse(int tag, HTTPResponse response);
}

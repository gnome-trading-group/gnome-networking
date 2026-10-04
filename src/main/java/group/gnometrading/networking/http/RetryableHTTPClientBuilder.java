package group.gnometrading.networking.http;

import java.util.function.Supplier;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.SystemEpochClock;

public final class RetryableHTTPClientBuilder {

    int maxRetries = 2;
    long idleReconnectThresholdMs = 25_000;
    long backoffMs = 100;
    EpochClock clock = SystemEpochClock.INSTANCE;
    Supplier<HTTPClient> clientFactory = HTTPClient::new;

    RetryableHTTPClientBuilder() {}

    /** Retries after the first attempt; see {@link RetryableHTTPClient} for which failures are retried. */
    public RetryableHTTPClientBuilder withMaxRetries(final int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    /** Reconnects before a request when nothing has been sent for this long, ahead of the server's idle timeout. */
    public RetryableHTTPClientBuilder withIdleReconnectThreshold(final long millis) {
        this.idleReconnectThresholdMs = millis;
        return this;
    }

    /** Wait before retry {@code n} is {@code n} times this. */
    public RetryableHTTPClientBuilder withBackoff(final long millis) {
        this.backoffMs = millis;
        return this;
    }

    public RetryableHTTPClientBuilder withClock(final EpochClock clock) {
        this.clock = clock;
        return this;
    }

    /** The settings of the underlying client, which is rebuilt from them whenever a connection fails. */
    public RetryableHTTPClientBuilder withHttpClient(final HTTPClientBuilder httpClient) {
        this.clientFactory = httpClient::build;
        return this;
    }

    public RetryableHTTPClient build() {
        return new RetryableHTTPClient(this);
    }
}

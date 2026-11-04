package group.gnometrading.networking.http;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Opens connections for {@link AsyncHTTPConnections} on its own thread, so the thread sending requests never waits
 * on a TCP or TLS handshake: every connection that is closed, or that its sender gave back, is (re)opened here.
 * Failures back off per connection, doubling up to a cap.
 */
public final class HTTPConnector implements GnomeAgent {

    /** Told about every open attempt's outcome, on the connector's thread. */
    public interface Listener {
        Listener NONE = new Listener() {};

        default void onOpened(final String host, final int connection) {}

        default void onOpenFailed(final String host, final int connection, final Exception cause) {}
    }

    private static final long DEFAULT_INITIAL_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long DEFAULT_MAX_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final AsyncHTTPConnections[] sets;
    private final long[][] retryAt;
    private final int[][] failures;
    private final Listener listener;
    private final LongSupplier nanoClock;
    private final long initialBackoffNanos;
    private final long maxBackoffNanos;

    public HTTPConnector(final List<AsyncHTTPConnections> sets, final Listener listener) {
        this(sets, listener, System::nanoTime, DEFAULT_INITIAL_BACKOFF_NANOS, DEFAULT_MAX_BACKOFF_NANOS);
    }

    public HTTPConnector(
            final List<AsyncHTTPConnections> sets,
            final Listener listener,
            final LongSupplier nanoClock,
            final long initialBackoffNanos,
            final long maxBackoffNanos) {
        this.sets = sets.toArray(new AsyncHTTPConnections[0]);
        this.retryAt = new long[this.sets.length][];
        this.failures = new int[this.sets.length][];
        for (int i = 0; i < this.sets.length; i++) {
            this.retryAt[i] = new long[this.sets[i].size()];
            this.failures[i] = new int[this.sets[i].size()];
        }
        this.listener = listener;
        this.nanoClock = nanoClock;
        this.initialBackoffNanos = initialBackoffNanos;
        this.maxBackoffNanos = maxBackoffNanos;
    }

    @Override
    public int doWork() {
        int work = 0;
        for (int s = 0; s < this.sets.length; s++) {
            final AsyncHTTPConnections set = this.sets[s];
            for (int c = 0; c < set.size(); c++) {
                if (!set.needsOpening(c) || this.nanoClock.getAsLong() - this.retryAt[s][c] < 0) {
                    continue;
                }
                work++;
                try {
                    set.open(c);
                    this.failures[s][c] = 0;
                    this.listener.onOpened(set.host(), c);
                } catch (Exception e) {
                    final int failed = Math.min(++this.failures[s][c], 30);
                    final long backoff = Math.min(this.maxBackoffNanos, this.initialBackoffNanos << (failed - 1));
                    this.retryAt[s][c] = this.nanoClock.getAsLong() + backoff;
                    this.listener.onOpenFailed(set.host(), c, e);
                }
            }
        }
        return work;
    }

    @Override
    public String roleName() {
        return "http-connector";
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.BACKGROUND;
    }
}

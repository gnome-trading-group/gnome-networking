package group.gnometrading.networking.crypto;

import java.util.Objects;

/**
 * HMAC-SHA256 under one key, fed in pieces, without allocating (the JDK's {@code Mac.doFinal} allocates its result
 * even when given an output array). One owner thread at a time.
 */
public final class HmacSha256 implements AutoCloseable {

    public static final int LENGTH = 32;

    private long handle;

    public HmacSha256(final byte[] key) {
        this.handle = NativeCrypto.hmacSha256Create(Objects.requireNonNull(key));
    }

    public void update(final byte[] data, final int offset, final int length) {
        Objects.checkFromIndexSize(offset, length, data.length);
        NativeCrypto.hmacSha256Update(requireOpen(), data, offset, length);
    }

    /** Writes the 32-byte MAC of everything fed since the last call, and starts the next message. */
    public void doFinal(final byte[] out, final int outOffset) {
        Objects.checkFromIndexSize(outOffset, LENGTH, out.length);
        NativeCrypto.hmacSha256Final(requireOpen(), out, outOffset);
    }

    private long requireOpen() {
        if (this.handle == 0) {
            throw new IllegalStateException("MAC is closed");
        }
        return this.handle;
    }

    @Override
    public void close() {
        if (this.handle != 0) {
            NativeCrypto.hmacSha256Free(this.handle);
            this.handle = 0;
        }
    }
}

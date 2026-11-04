package group.gnometrading.networking.crypto;

import java.util.Objects;

/** Ed25519 signatures without allocating. One owner thread at a time. */
public final class Ed25519Signer implements AutoCloseable {

    public static final int SEED_LENGTH = 32;
    public static final int SIGNATURE_LENGTH = 64;

    private long handle;

    /** @param seed the 32-byte private seed */
    public Ed25519Signer(final byte[] seed) {
        if (seed.length != SEED_LENGTH) {
            throw new IllegalArgumentException("An Ed25519 seed is " + SEED_LENGTH + " bytes, got " + seed.length);
        }
        this.handle = NativeCrypto.ed25519Create(seed);
    }

    /** Signs {@code message[offset, offset + length)} into 64 bytes of {@code out} at {@code outOffset}. */
    public void sign(final byte[] message, final int offset, final int length, final byte[] out, final int outOffset) {
        Objects.checkFromIndexSize(offset, length, message.length);
        Objects.checkFromIndexSize(outOffset, SIGNATURE_LENGTH, out.length);
        if (this.handle == 0) {
            throw new IllegalStateException("Signer is closed");
        }
        NativeCrypto.ed25519Sign(this.handle, message, offset, length, out, outOffset);
    }

    @Override
    public void close() {
        if (this.handle != 0) {
            NativeCrypto.ed25519Free(this.handle);
            this.handle = 0;
        }
    }
}

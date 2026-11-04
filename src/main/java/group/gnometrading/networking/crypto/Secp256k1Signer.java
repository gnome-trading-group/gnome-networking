package group.gnometrading.networking.crypto;

import java.util.Objects;

/**
 * ECDSA over secp256k1 (libsecp256k1) without allocating: RFC 6979 deterministic nonces, low-s signatures, and the
 * recovery id Ethereum signatures carry, so callers needn't recover the public key to find it. One owner thread at
 * a time.
 */
public final class Secp256k1Signer implements AutoCloseable {

    public static final int KEY_LENGTH = 32;
    public static final int DIGEST_LENGTH = 32;
    /** r then s, 32 bytes each, big-endian. */
    public static final int SIGNATURE_LENGTH = 64;

    private long handle;

    /** @param privateKey the 32-byte big-endian private scalar */
    public Secp256k1Signer(final byte[] privateKey) {
        if (privateKey.length != KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "A secp256k1 private key is " + KEY_LENGTH + " bytes, got " + privateKey.length);
        }
        this.handle = NativeCrypto.secp256k1Create(privateKey);
    }

    /**
     * Signs the 32-byte digest at {@code digest[offset]}, writing r and s into 64 bytes of {@code out} at
     * {@code outOffset}.
     *
     * @return the recovery id, 0 to 3
     */
    public int sign(final byte[] digest, final int offset, final byte[] out, final int outOffset) {
        Objects.checkFromIndexSize(offset, DIGEST_LENGTH, digest.length);
        Objects.checkFromIndexSize(outOffset, SIGNATURE_LENGTH, out.length);
        if (this.handle == 0) {
            throw new IllegalStateException("Signer is closed");
        }
        return NativeCrypto.secp256k1Sign(this.handle, digest, offset, out, outOffset);
    }

    @Override
    public void close() {
        if (this.handle != 0) {
            NativeCrypto.secp256k1Free(this.handle);
            this.handle = 0;
        }
    }
}

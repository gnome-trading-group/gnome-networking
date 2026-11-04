package group.gnometrading.networking.crypto;

import java.util.Objects;

/**
 * RSASSA-PSS with SHA-256, MGF1-SHA-256 and a 32-byte salt (what Kalshi requires), without allocating. One owner
 * thread at a time.
 */
public final class RsaPssSha256Signer implements AutoCloseable {

    private long handle;
    private final int signatureLength;

    /** @param pkcs8 the private key as PKCS#8 DER, e.g. {@code PrivateKey.getEncoded()} of a JDK RSA key */
    public RsaPssSha256Signer(final byte[] pkcs8) {
        this.handle = NativeCrypto.rsaCreate(Objects.requireNonNull(pkcs8));
        this.signatureLength = NativeCrypto.rsaSize(this.handle);
    }

    public int signatureLength() {
        return this.signatureLength;
    }

    /** Signs {@code message[offset, offset + length)} into {@code out} at {@code outOffset}; returns its length. */
    public int sign(final byte[] message, final int offset, final int length, final byte[] out, final int outOffset) {
        Objects.checkFromIndexSize(offset, length, message.length);
        Objects.checkFromIndexSize(outOffset, this.signatureLength, out.length);
        return NativeCrypto.rsaPssSha256Sign(requireOpen(), message, offset, length, out, outOffset);
    }

    private long requireOpen() {
        if (this.handle == 0) {
            throw new IllegalStateException("Signer is closed");
        }
        return this.handle;
    }

    @Override
    public void close() {
        if (this.handle != 0) {
            NativeCrypto.rsaFree(this.handle);
            this.handle = 0;
        }
    }
}

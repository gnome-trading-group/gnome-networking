package group.gnometrading.networking.crypto;

import group.gnometrading.networking.sockets.NativeSockets;

/**
 * Signing in native code (BoringSSL and libsecp256k1, linked into the socket library). The JDK's RSA and Ed25519
 * signers and its {@code Mac} allocate on every call; these write into the caller's arrays and allocate nothing on
 * the Java heap. Keys live in native memory behind a handle, freed by the owning signer's {@code close}.
 */
final class NativeCrypto {

    static {
        NativeSockets.load();
    }

    private NativeCrypto() {}

    static native long rsaCreate(byte[] pkcs8);

    static native int rsaSize(long handle);

    static native int rsaPssSha256Sign(long handle, byte[] message, int offset, int length, byte[] out, int outOffset);

    static native void rsaFree(long handle);

    static native long ed25519Create(byte[] seed);

    static native void ed25519Sign(long handle, byte[] message, int offset, int length, byte[] out, int outOffset);

    static native void ed25519Free(long handle);

    static native long hmacSha256Create(byte[] key);

    static native void hmacSha256Update(long handle, byte[] data, int offset, int length);

    static native void hmacSha256Final(long handle, byte[] out, int outOffset);

    static native void hmacSha256Free(long handle);

    static native long secp256k1Create(byte[] privateKey);

    static native int secp256k1Sign(long handle, byte[] digest, int offset, byte[] out, int outOffset);

    static native void secp256k1Free(long handle);
}

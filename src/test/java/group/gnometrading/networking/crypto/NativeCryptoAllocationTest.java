package group.gnometrading.networking.crypto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.Test;

class NativeCryptoAllocationTest {

    private static final long MAX_BYTES = 2_048;

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private final byte[] message =
            "1730700000000POST/trade-api/v2/portfolio/events/orders".getBytes(StandardCharsets.US_ASCII);
    private final byte[] out = new byte[512];

    @Test
    void rsaPssSigningDoesNotAllocate() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        try (RsaPssSha256Signer signer = new RsaPssSha256Signer(generator.generateKeyPair().getPrivate().getEncoded())) {
            assertNoAllocation("RSA-PSS", 1_000, 500, () -> signer.sign(message, 0, message.length, out, 0));
        }
    }

    @Test
    void ed25519SigningDoesNotAllocate() {
        try (Ed25519Signer signer = new Ed25519Signer(new byte[32])) {
            assertNoAllocation("Ed25519", 20_000, 10_000, () -> signer.sign(message, 0, message.length, out, 0));
        }
    }

    @Test
    void hmacDoesNotAllocate() {
        try (HmacSha256 mac = new HmacSha256("key".getBytes(StandardCharsets.US_ASCII))) {
            assertNoAllocation("HMAC-SHA256", 20_000, 10_000, () -> {
                mac.update(message, 0, 13);
                mac.update(message, 13, message.length - 13);
                mac.doFinal(out, 0);
            });
        }
    }

    @Test
    void secp256k1SigningDoesNotAllocate() {
        final byte[] key = new byte[32];
        key[31] = 9;
        final byte[] digest = new byte[32];
        try (Secp256k1Signer signer = new Secp256k1Signer(key)) {
            assertNoAllocation("secp256k1", 20_000, 10_000, () -> {
                digest[0]++;
                signer.sign(digest, 0, out, 0);
            });
        }
    }

    private static void assertNoAllocation(final String name, final int warmUp, final int measured, final Runnable work) {
        for (int i = 0; i < warmUp; i++) {
            work.run();
        }
        final long thread = Thread.currentThread().getId();
        final long before = THREADS.getThreadAllocatedBytes(thread);
        for (int i = 0; i < measured; i++) {
            work.run();
        }
        final long allocated = THREADS.getThreadAllocatedBytes(thread) - before;
        assertTrue(allocated < MAX_BYTES, name + " allocated " + allocated + " bytes over " + measured + " calls");
    }
}

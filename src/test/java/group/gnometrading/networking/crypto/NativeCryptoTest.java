package group.gnometrading.networking.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.Test;

class NativeCryptoTest {

    private static final X9ECParameters SECP256K1 = CustomNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters DOMAIN =
            new ECDomainParameters(SECP256K1.getCurve(), SECP256K1.getG(), SECP256K1.getN(), SECP256K1.getH());

    @Test
    void rsaPssSignaturesVerifyWithTheJdk() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair pair = generator.generateKeyPair();
        final byte[] message = "1730700000000GET/trade-api/v2/portfolio/orders".getBytes(StandardCharsets.US_ASCII);
        final byte[] padded = new byte[message.length + 7];
        System.arraycopy(message, 0, padded, 5, message.length);
        final byte[] out = new byte[300];

        try (RsaPssSha256Signer signer = new RsaPssSha256Signer(pair.getPrivate().getEncoded())) {
            assertEquals(256, signer.signatureLength());
            final int length = signer.sign(padded, 5, message.length, out, 3);
            assertEquals(256, length);

            final Signature verifier = Signature.getInstance("RSASSA-PSS");
            verifier.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            verifier.initVerify(pair.getPublic());
            verifier.update(message);
            assertTrue(verifier.verify(Arrays.copyOfRange(out, 3, 3 + length)));
        }
    }

    @Test
    void rsaRefusesAKeyThatIsntRsa() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        final byte[] ecKey = generator.generateKeyPair().getPrivate().getEncoded();
        assertThrows(IllegalArgumentException.class, () -> new RsaPssSha256Signer(ecKey));
        assertThrows(IllegalArgumentException.class, () -> new RsaPssSha256Signer(new byte[] {1, 2, 3}));
    }

    @Test
    void ed25519SignaturesMatchTheJdk() throws Exception {
        final byte[] seed = new byte[32];
        new Random(7).nextBytes(seed);
        final PrivateKey jdkKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        final byte[] message = "1730700000000GET/v1/orders/open".getBytes(StandardCharsets.US_ASCII);

        final Signature jdk = Signature.getInstance("Ed25519");
        jdk.initSign(jdkKey);
        jdk.update(message);
        final byte[] expected = jdk.sign();

        final byte[] out = new byte[70];
        try (Ed25519Signer signer = new Ed25519Signer(seed)) {
            signer.sign(message, 0, message.length, out, 6);
        }
        assertArrayEquals(expected, Arrays.copyOfRange(out, 6, 70));
    }

    @Test
    void hmacMatchesTheJdkAcrossPiecesAndMessages() throws Exception {
        final byte[] key = "a-shared-secret-of-some-length".getBytes(StandardCharsets.US_ASCII);
        final Mac jdk = Mac.getInstance("HmacSHA256");
        jdk.init(new SecretKeySpec(key, "HmacSHA256"));

        final byte[] out = new byte[40];
        try (HmacSha256 mac = new HmacSha256(key)) {
            for (final String text : new String[] {"1730700000POST/order{\"order\":1}", "", "second message"}) {
                final byte[] message = text.getBytes(StandardCharsets.US_ASCII);
                final int half = message.length / 2;
                mac.update(message, 0, half);
                mac.update(message, half, message.length - half);
                mac.doFinal(out, 8);
                assertArrayEquals(jdk.doFinal(message), Arrays.copyOfRange(out, 8, 40), text);
            }
        }
    }

    @Test
    void secp256k1SignaturesMatchBouncyCastleWithTheRightRecoveryId() {
        final Random random = new Random(11);
        for (int i = 0; i < 64; i++) {
            final byte[] key = new byte[32];
            final byte[] digest = new byte[32];
            random.nextBytes(key);
            random.nextBytes(digest);
            key[0] &= 0x7f;

            final byte[] out = new byte[64];
            final int recoveryId;
            try (Secp256k1Signer signer = new Secp256k1Signer(key)) {
                recoveryId = signer.sign(digest, 0, out, 0);
            }
            final BigInteger r = new BigInteger(1, Arrays.copyOfRange(out, 0, 32));
            final BigInteger s = new BigInteger(1, Arrays.copyOfRange(out, 32, 64));

            final BigInteger d = new BigInteger(1, key);
            final ECDSASigner reference = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
            reference.init(true, new ECPrivateKeyParameters(d, DOMAIN));
            final BigInteger[] expected = reference.generateSignature(digest);
            final BigInteger expectedS = expected[1].compareTo(DOMAIN.getN().shiftRight(1)) > 0
                    ? DOMAIN.getN().subtract(expected[1])
                    : expected[1];

            assertEquals(expected[0], r);
            assertEquals(expectedS, s);
            assertEquals(DOMAIN.getG().multiply(d).normalize(), recover(r, s, recoveryId, digest));
        }
    }

    @Test
    void secp256k1RefusesAnInvalidKey() {
        assertThrows(IllegalArgumentException.class, () -> new Secp256k1Signer(new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> new Secp256k1Signer(new byte[31]));
    }

    @Test
    void aClosedSignerRefusesToSign() {
        final byte[] key = new byte[32];
        key[31] = 1;
        final Secp256k1Signer signer = new Secp256k1Signer(key);
        signer.close();
        signer.close();
        assertThrows(IllegalStateException.class, () -> signer.sign(new byte[32], 0, new byte[64], 0));
    }

    @Test
    void outOfBoundsArgumentsAreRefusedBeforeNativeCode() {
        final byte[] key = new byte[32];
        key[31] = 1;
        try (Secp256k1Signer signer = new Secp256k1Signer(key);
                Ed25519Signer ed = new Ed25519Signer(key)) {
            assertThrows(IndexOutOfBoundsException.class, () -> signer.sign(new byte[32], 1, new byte[64], 0));
            assertThrows(IndexOutOfBoundsException.class, () -> signer.sign(new byte[32], 0, new byte[64], 1));
            assertThrows(IndexOutOfBoundsException.class, () -> ed.sign(new byte[4], 0, 5, new byte[64], 0));
        }
    }

    private static ECPoint recover(final BigInteger r, final BigInteger s, final int recoveryId, final byte[] digest) {
        final byte[] compressed = new byte[33];
        compressed[0] = (byte) ((recoveryId & 1) == 0 ? 0x02 : 0x03);
        final byte[] x = r.add(recoveryId >= 2 ? DOMAIN.getN() : BigInteger.ZERO).toByteArray();
        System.arraycopy(x, Math.max(0, x.length - 32), compressed, 33 - Math.min(32, x.length), Math.min(32, x.length));
        final ECPoint point = DOMAIN.getCurve().decodePoint(compressed);
        final BigInteger n = DOMAIN.getN();
        final BigInteger e = new BigInteger(1, digest);
        final BigInteger rInverse = r.modInverse(n);
        return ECAlgorithms.sumOfTwoMultiplies(
                        DOMAIN.getG(), rInverse.multiply(n.subtract(e)).mod(n), point, rInverse.multiply(s).mod(n))
                .normalize();
    }
}

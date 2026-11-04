package group.gnometrading.networking.crypto;

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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.junit.jupiter.api.Test;

/** JDK and BouncyCastle signing against the native signers. Run with {@code -Dtest=CryptoBenchmark}. */
class CryptoBenchmark {

    private static final int WARM_UP = 5_000;
    private static final int MEASURED = 5_000;

    private interface Work {
        void run() throws Exception;
    }

    @Test
    void report() throws Exception {
        final byte[] message =
                "1730700000000POST/trade-api/v2/portfolio/events/orders".getBytes(StandardCharsets.US_ASCII);
        final byte[] out = new byte[512];

        final KeyPairGenerator rsaGenerator = KeyPairGenerator.getInstance("RSA");
        rsaGenerator.initialize(2048);
        final KeyPair rsa = rsaGenerator.generateKeyPair();
        final Signature jdkRsa = Signature.getInstance("RSASSA-PSS");
        jdkRsa.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        jdkRsa.initSign(rsa.getPrivate());
        time("RSA-PSS jdk", () -> {
            jdkRsa.update(message);
            jdkRsa.sign();
        });
        try (RsaPssSha256Signer signer = new RsaPssSha256Signer(rsa.getPrivate().getEncoded())) {
            time("RSA-PSS native", () -> signer.sign(message, 0, message.length, out, 0));
        }

        final byte[] seed = new byte[32];
        seed[0] = 3;
        final PrivateKey edKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        final Signature jdkEd = Signature.getInstance("Ed25519");
        time("Ed25519 jdk", () -> {
            jdkEd.initSign(edKey);
            jdkEd.update(message);
            jdkEd.sign();
        });
        try (Ed25519Signer signer = new Ed25519Signer(seed)) {
            time("Ed25519 native", () -> signer.sign(message, 0, message.length, out, 0));
        }

        final byte[] key = "secret".getBytes(StandardCharsets.US_ASCII);
        final Mac jdkMac = Mac.getInstance("HmacSHA256");
        jdkMac.init(new SecretKeySpec(key, "HmacSHA256"));
        time("HMAC-SHA256 jdk", () -> jdkMac.doFinal(message));
        try (HmacSha256 mac = new HmacSha256(key)) {
            time("HMAC-SHA256 native", () -> {
                mac.update(message, 0, message.length);
                mac.doFinal(out, 0);
            });
        }

        final X9ECParameters curve = CustomNamedCurves.getByName("secp256k1");
        final ECDomainParameters domain =
                new ECDomainParameters(curve.getCurve(), curve.getG(), curve.getN(), curve.getH());
        final byte[] privateKey = new byte[32];
        privateKey[31] = 77;
        final byte[] digest = new byte[32];
        final ECDSASigner bouncy = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        final ECPrivateKeyParameters bouncyKey = new ECPrivateKeyParameters(new BigInteger(1, privateKey), domain);
        time("secp256k1 bouncycastle", () -> {
            digest[0]++;
            bouncy.init(true, bouncyKey);
            bouncy.generateSignature(digest);
        });
        try (Secp256k1Signer signer = new Secp256k1Signer(privateKey)) {
            time("secp256k1 native", () -> {
                digest[0]++;
                signer.sign(digest, 0, out, 0);
            });
        }
    }

    private static void time(final String name, final Work work) throws Exception {
        for (int i = 0; i < WARM_UP; i++) {
            work.run();
        }
        final long start = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) {
            work.run();
        }
        final long perCall = (System.nanoTime() - start) / MEASURED;
        System.out.printf("%-24s %,10d ns/op%n", name, perCall);
    }
}

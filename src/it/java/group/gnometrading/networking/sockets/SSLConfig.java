package group.gnometrading.networking.sockets;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class SSLConfig {
    private final SSLContext sslContext;
    private final X509Certificate certificate;

    /** A self-signed certificate valid for 127.0.0.1 and localhost. */
    public SSLConfig() throws Exception {
        this(new GeneralName(GeneralName.iPAddress, "127.0.0.1"), new GeneralName(GeneralName.dNSName, "localhost"));
    }

    public SSLConfig(GeneralName... subjectAlternativeNames) throws Exception {
        // Add BouncyCastle as a security provider
        Security.addProvider(new BouncyCastleProvider());

        // Generate key pair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        // Generate self-signed certificate
        X500Name issuer = new X500Name("CN=Test Certificate");
        X500Name subject = new X500Name("CN=Test Certificate");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000);
        Date notAfter = new Date(System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L);

        SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(
            keyPair.getPublic().getEncoded());

        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, subject, publicKeyInfo);
        certBuilder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(subjectAlternativeNames));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(keyPair.getPrivate());

        X509Certificate cert = new JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certBuilder.build(signer));
        this.certificate = cert;

        // Create keystore
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("test", keyPair.getPrivate(), "password".toCharArray(),
            new X509Certificate[]{cert});

        // Initialize KeyManagerFactory
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "password".toCharArray());

        // Initialize TrustManagerFactory
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        // Create and initialize SSLContext
        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
    }

    public SSLContext getSSLContext() {
        return sslContext;
    }

    /** Writes the certificate as a PEM file a client can trust as its CA. */
    public String writeCaFile() throws Exception {
        Path file = Files.createTempFile("gnome-test-ca", ".pem");
        file.toFile().deleteOnExit();
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        Files.writeString(file, pem, StandardCharsets.US_ASCII);
        return file.toString();
    }
} 